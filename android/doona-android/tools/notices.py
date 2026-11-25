"""Generate (or compare) the notices for a built Doona release APK."""
import argparse
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile

TOOLS = Path(__file__).resolve().parent
APP = TOOLS.parent
ROOT = APP.parents[1]
CORE = ROOT / 'crates/doona-core'
OUT = APP / 'app/src/main/assets/licenses'
SOURCES = TOOLS / 'notice-sources'
APACHE = OUT / 'Apache-2.0.txt'
LEGAL = re.compile(r'^(?:licen[sc]e|notice|copying|copyright|unlicense|third.party.notices)(?:$|[-._])', re.I)
components = []
outputs = {}
documents = {}


def run(cwd, *args):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def add(kind, name, version, license, source, texts, note=''):
    refs = set()
    for text in texts:
        if isinstance(text, Path) and text.parent == OUT:
            outputs[text] = text.read_bytes()
            refs.add(text.name)
            continue
        data = text.read_bytes() if isinstance(text, Path) else text
        data = data.decode('utf-8-sig').replace('\r\n', '\n').strip().encode() + b'\n'
        key = hashlib.sha256(data).hexdigest()
        documents[key] = data
        refs.add(key)
    components.append(dict(kind=kind, name=name, version=version, license=license,
                           source=source, texts=sorted(refs), notice=note))


def cargo():
    target = 'aarch64-linux-android'
    meta = json.loads(run(CORE, 'cargo', 'metadata', '--locked', '--format-version', '1', '--filter-platform', target))
    # `metadata` includes dev, build and proc-macro edges. Use the target-normal tree for membership.
    tree = run(CORE, 'cargo', 'tree', '--locked', '--target', target, '-e', 'normal,no-proc-macro',
               '--prefix', 'none', '--format', '{p}')
    linked = {tuple(line.split()[:2]) for line in tree.splitlines()}
    external = json.loads((TOOLS / 'notice-sources.json').read_text())
    for p in meta['packages']:
        n, v = p['name'], p['version']
        if (n, 'v' + v) not in linked or p['id'] == meta['resolve']['root']:
            continue
        # This reviewed version is a Cargo-to-Gradle version marker. Its lib.rs is entirely doc comments.
        if (n, v) == ('rustls-platform-verifier-android', '0.2.0'):
            continue
        d = Path(p['manifest_path']).parent
        src = p['source']; git = src.startswith('git+')
        url = src[4:].split('?')[0] + '/tree/' + src.split('#')[1] if git else f'https://crates.io/crates/{n}/{v}'
        files = [f for f in d.iterdir() if f.is_file() and LEGAL.match(f.name)]
        if not files and git:
            files = [Path(run(d, 'git', 'rev-parse', '--show-toplevel')) / 'LICENSE']
        if not files:
            rev = json.loads((d / '.cargo_vcs_info.json').read_text())['git']['sha1']
            if rev not in external.get(n, ''):
                raise SystemExit(f'Review missing licence for {n} {v} ({rev})')
            files = [SOURCES / (n + '.txt')]
        if n == 'ring':
            files.extend(f for f in d.rglob('LICENSE*') if f.parent != d)
        add('cargo', n, v, p['license'], url, files)
        if n == 'boring-sys':
            if hashlib.sha256((d / 'deps/boringssl/LICENSE').read_bytes()).hexdigest() != '827c8d8fc207c2392794eef9e00fe246f9f61fdcc132556c275be3dd8c3cd97f':
                raise SystemExit('Review the BoringSSL licence after the fork update')
            add('native', 'BoringSSL', src.split('#')[1], 'Apache-2.0 AND BSD-3-Clause',
                url + '/deps/boringssl', [d / 'deps/boringssl/LICENSE'])
        if n == 'unsafe-libyaml':
            add('native', 'LibYAML (Rust translation)', external['libyaml'].split('/')[2], 'MIT',
                'https://github.com/yaml/libyaml', [SOURCES / 'libyaml.txt'])
    rust = Path(run(CORE, 'rustc', '--print', 'sysroot')) / 'share/doc/rust'
    if not (rust / 'COPYRIGHT-library.html').is_file():
        raise SystemExit('Install the pinned toolchain notices: cd crates/doona-core && rustup component add rust-docs')
    # Keep the distribution's complete library notice, including its vendored code.
    body = (rust / 'COPYRIGHT-library.html').read_text().split('<body>', 1)[1].split('</body>', 1)[0]
    text = html.unescape(re.sub(r'<[^>]+>', '', body))
    version = run(CORE, 'rustc', '--version').split()[1]
    add('native', 'Rust standard library', version, 'MIT OR Apache-2.0; see bundled exceptions',
        f'https://github.com/rust-lang/rust/tree/{version}/library', [text.encode(), APACHE])


def archive(z, texts, native):
    for path in z.namelist():
        if path.endswith('/'):
            continue
        if LEGAL.match(Path(path).name):
            texts.append(z.read(path))
        elif path.endswith('.jar'):
            with ZipFile(io.BytesIO(z.read(path))) as nested:
                archive(nested, texts, native)
        elif path.startswith('jni/arm64-v8a/'):
            native.add(path.removeprefix('jni/'))


def maven():
    native = set()
    lines = run(APP, './gradlew', '-q', '-I', 'tools/notices.gradle', ':app:releaseNoticeInputs')
    for line in lines.splitlines():
        if not line.startswith('NOTICE\t'):
            continue
        _, name, version, jar, pom, licence_pom = line.split('\t')
        xml = ET.parse(pom)
        licenses = ET.parse(licence_pom).findall('./{*}licenses/{*}license')
        names = [x.findtext('{*}name') for x in licenses]
        texts = []
        with ZipFile(jar) as z:
            archive(z, texts, native)
        for i, label in enumerate(names):
            if 'apache' in label.lower() and '2.0' in label:
                names[i] = 'Apache-2.0'
                texts.append(APACHE)
        lic = '; '.join(dict.fromkeys(names))
        if not texts:
            raise SystemExit(f'Review {name} ({lic}): archive has no licence text')
        note = 'Copyright 2008 Google Inc. All rights reserved.' if name.endswith(':datastore-preferences-external-protobuf') else ''
        if name == 'org.jetbrains.kotlin:kotlin-stdlib':
            if version != '2.4.21':
                raise SystemExit('Review license/README.md in the new Kotlin release for vendored JVM sources')
            lic += '; vendored BSD-3-Clause and BSL-1.0'
            texts.extend(SOURCES.glob('kotlin-*.txt'))
            note = ('Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors. '
                    'Collections: GWT, copyright 2007-08 Google Inc. UnsignedLongs: copyright 2011 The Guava Authors. '
                    'Time: copyright 2007-present Stephen Colebourne and Michael Nascimento Santos. '
                    'Special math functions: copyright Eric Ford and Hubert Holin 2001.')
        add('maven', name, version, lic, xml.findtext('{*}url') or licenses[0].findtext('{*}url'), texts, note)
    if not native:
        raise SystemExit('No native release Maven artifacts resolved')
    return native


def geo(apk):
    shipped = sorted(n.removeprefix('assets/geo/') for n in apk.namelist() if n.startswith('assets/geo/') and n.endswith('.dat'))
    if not shipped:
        return
    pins = json.loads((CORE / 'tools/geo-pins.json').read_text())['files']
    for name in shipped:
        p = pins[name]
        if p['version'] != '5f22a3b3371d1a489fb3d0df25d00db049a6a603':
            raise SystemExit(f'Review upstream inputs and redistribution terms for new {name} pin')
        if hashlib.sha256(apk.read('assets/geo/' + name)).hexdigest() != p['sha256']:
            raise SystemExit(f'APK {name} differs from reviewed pin')
        if name == 'geoip.dat':
            lic = 'CC-BY-SA-4.0; GPL-3.0; MaxMind GeoLite terms'
            note = ('This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com. '
                    'Loyalsoldier/geoip converts and augments the country data; MetaCubeX redistributes its DAT unchanged. '
                    'Under GeoLite EULA §6.1, disclosure requires prior notification and written consent from MaxMind '
                    'except as explicitly permitted by CC BY-SA 4.0. Permitted disclosures require the same or '
                    'substantially similar contractual duties downstream. Section 6.3 requires cessation of use and '
                    'destruction of old versions within 30 days after an updated database is released. '
                    'https://www.maxmind.com/en/geolite/eula')
            texts = [SOURCES / 'geo-cc.txt', SOURCES / 'maxmind-eula.txt', ROOT / 'LICENSE']
        else:
            lic = 'Mixed: GPL-3.0, GPL-2.0, LGPL-2.1, MIT, WTFPL; xishang0128/rules has no licence file'
            note = ('MetaCubeX compiles v2fly/domain-list-community with Loyalsoldier/domain-list-custom, its own '
                    'resouces/ directory and lists from mutable upstream branches: blackmatrix7/ios_rule_script, '
                    'gfwlist/gfwlist via cokebar/gfwlist2dnsmasq, felixonmars/dnsmasq-china-list, '
                    'crazy-max/WindowsSpyBlocker, https://gitea.com/XIU2/TrackersListCollection (GPL-3.0), and '
                    'biliintl.list and sharepoint.list from xishang0128/rules. Build recipe: '
                    'https://github.com/MetaCubeX/meta-rules-dat/blob/4178770badecb1b349fbcd62c737e0d7a2079729/.github/workflows/run.yml.')
            texts = [f for f in SOURCES.glob('geo-*.txt') if f.name != 'geo-cc.txt'] + [ROOT / 'LICENSE']
        add('data', name, p['version'], lic, p['source'], texts, note)


def assets():
    main = APP / 'app/src/main'
    owned = {'ic_duck.xml', 'ic_launcher_foreground.xml', 'ic_launcher_monochrome.xml', 'ic_launcher.xml'}
    paths = {p for module in ('app', 'icons', 'flags') for variant in ('main', 'release')
             for p in (APP / module / 'src' / variant / 'res').glob('*/*')
             if p.parent.name.split('-')[0] in {'drawable', 'font', 'mipmap', 'raw'}}
    sets = [
        ('app/src/main/res/font/inter_*.ttf', 'Inter', '4.001', 'OFL-1.1', 'https://github.com/rsms/inter', 'inter-OFL.txt', 'Latin subset, fixed weights and optical sizes.'),
        ('app/src/main/res/font/noto_sans_tc_*.ttf', 'Noto Sans TC', '@fontsource-variable/noto-sans-tc 5.3.0', 'OFL-1.1', 'https://fontsource.org/fonts/noto-sans-tc', 'noto-sans-tc-OFL.txt', 'Subset and fixed-weight instances.'),
        ('icons/src/main/res/drawable/sym_*.xml', 'Material Symbols Rounded', '10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7', 'Apache-2.0', 'https://github.com/google/material-design-icons', APACHE.name, (APP / 'icons/NOTICE').read_text().strip()),
        ('flags/src/main/res/drawable/flag_*.xml', 'circle-flags', '379588b5da95482d6bbf10bd45644a35b0609ea6', 'MIT', 'https://github.com/HatScripts/circle-flags', 'circle-flags-MIT.txt', (APP / 'flags/NOTICE').read_text().strip()),
    ]
    for glob, name, v, lic, src, text, note in sets:
        matches = set(APP.glob(glob))
        if matches:
            add('artwork', name, v, lic, src, [OUT / text], note)
            paths -= matches
    for p in sorted(paths):
        if p.name not in owned or main not in p.parents:
            raise SystemExit(f'Classify artwork or data: {p.relative_to(APP)}')
    maps = [p.stem for p in (main / 'kotlin').rglob('*.kt') if p.stem in ('LandMask', 'WorldShapes')]
    if sorted(maps) != ['LandMask', 'WorldShapes']:
        raise SystemExit('Review the Natural Earth inventory after moving or removing map geometry')
    add('data', 'Natural Earth 1:110m', '5.1.2', 'Public domain', 'https://www.naturalearthdata.com/about/terms-of-use/', [],
        'Made with Natural Earth. Derived map geometry: ' + ', '.join(sorted(maps)) + '.')


def native_and_data(apk, native):
    libs = {n[4:] for n in apk.namelist() if n.startswith('lib/')}
    cxx = 'arm64-v8a/libc++_shared.so'
    if cxx in libs:
        native.add(cxx)
        v = re.search(r'val coreNdk = "([^"]+)"', (APP / 'app/build.gradle.kts').read_text())[1]
        ndk = Path(os.environ['ANDROID_HOME']) / 'ndk' / v
        sections = re.split(r'(?=^={78}\nThe LLVM Project is under)', (ndk / 'NOTICE.toolchain').read_text(), flags=re.M)
        texts = [s.encode() for s in sections if 'The libc++ library is dual licensed' in s or 'The libc++abi library is dual licensed' in s]
        if len(texts) != 2:
            raise SystemExit('Review libc++ and libc++abi notices for this NDK')
        add('native', 'LLVM libc++ and libc++abi', v, 'Apache-2.0 WITH LLVM-exception; see legacy notices',
            'https://developer.android.com/ndk', texts)
    if libs != native | {'arm64-v8a/libdoonacore.so'}:
        raise SystemExit(f'Unaccounted APK library difference: {libs ^ (native | {"arm64-v8a/libdoonacore.so"})}')
    extra = {n for n in apk.namelist() if n.startswith('assets/') and not n.startswith('assets/licenses/')} - {
        'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm', 'assets/geo/pins.json', 'assets/geo/geoip.dat', 'assets/geo/geosite.dat'}
    if extra:
        raise SystemExit(f'Classify new packaged assets: {sorted(extra)}')
    geo(apk)


def render():
    components.sort(key=lambda c: (c['kind'], c['name'], c['version']))
    bundle = bytearray()
    locations = {}
    links = {}
    line = 1
    for key, data in sorted(documents.items(), key=lambda item: item[1]):
        locations[key] = [len(bundle), len(data)]
        links[key] = f'notices.txt#L{line}'
        bundle.extend(data + b'\n')
        line += data.count(b'\n') + 1
    outputs[OUT / 'notices.txt'] = bytes(bundle)
    index = dict(schema=1, components=components, ranges=locations)
    outputs[OUT / 'index.json'] = (json.dumps(index, ensure_ascii=False, indent=2) + '\n').encode()
    lines = ['# Third-party notices', '', 'Android client source: https://github.com/Zakkaus/doona-android', '']
    for c in components:
        lines.extend([f"### {c['name']} {c['version']}", '', f"{c['kind']} · {c['license']} · [Source]({c['source']})", ''])
        if c['notice']:
            lines.extend([c['notice'], ''])
        for i, ref in enumerate(c['texts'], 1):
            lines.append(f"- [Licence or notice {i}]({OUT.relative_to(ROOT)}/{links.get(ref, ref)})")
        lines.append('')
    outputs[ROOT / 'THIRD_PARTY_NOTICES.md'] = ('\n'.join(lines).rstrip() + '\n').encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='replace generated files instead of checking them')
    parser.add_argument('--apk', type=Path, default=APP / 'app/build/outputs/apk/release/app-release.apk')
    args = parser.parse_args()
    cargo()
    native = maven()
    assets()
    with ZipFile(args.apk) as apk:
        native_and_data(apk, native)
    render()
    stale = {p for p in OUT.rglob('*') if p.is_file()} - outputs.keys()
    changed = [p for p, data in outputs.items() if not p.exists() or p.read_bytes() != data]
    if args.write:
        if stale:
            raise SystemExit('Remove unreferenced notice files: ' + ', '.join(map(str, sorted(stale))))
        for p in changed:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(outputs[p])
    elif changed or stale:
        raise SystemExit('Stale notices: ' + ', '.join(str(p.relative_to(ROOT)) for p in sorted(stale | set(changed))))
    else:
        with ZipFile(args.apk) as apk:
            expected = {'assets/licenses/' + p.name: b for p, b in outputs.items() if p.parent == OUT}
            packed = {n for n in apk.namelist() if n.startswith('assets/licenses/')}
            if packed != expected.keys() or any(apk.read(n) != b for n, b in expected.items()):
                raise SystemExit('Packaged notices differ; rebuild the release APK')
    print(f'{len(components)} release components; {len(documents)} distinct bundled texts')


if __name__ == '__main__':
    main()
