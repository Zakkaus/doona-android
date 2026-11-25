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
SPDX = {p.stem: p.read_bytes() for p in (TOOLS / 'spdx').glob('*.txt')}
GPL = SOURCES / 'GPL-3.0-only.txt'
PROJECT_SOURCE = 'https://github.com/Zakkaus/doona-android'
LEGAL = re.compile(r'^(?:licen[sc]e|notice|copying|copyright|unlicense|third.party.notices)(?:$|[-._])', re.I)
components = []
outputs = {}
documents = {}


def run(cwd, *args):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


COPYRIGHT = re.compile(r'^\s*(?://\s*)?(?:Copyright:\s|Copyright\s+(?:\(c\)|©|\d{4}|Mozilla Foundation|Individual contributors|Google LLC)|©\s*\d{4})', re.I)
TITLES = {'MIT': {'MIT License', '# MIT License', 'The MIT License (MIT)'},
          'BSD-3-Clause': {'BSD 3-Clause License'}, 'ISC': {'ISC License:', 'ISC License (ISC)'},
          'Zlib': {'zlib License'}}


def body(text, identifier):
    lines = text.strip().splitlines()
    if lines and lines[0].strip() in TITLES.get(identifier, ()):
        lines = lines[1:]
    # Only substitute the holder slot, never matching lines inside the grant.
    if identifier in {'MIT', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', 'Zlib'}:
        while lines and (not lines[0].strip() or COPYRIGHT.match(lines[0]) or
                         lines[0].strip() == 'All rights reserved.'):
            lines.pop(0)
    elif identifier == 'Unicode-3.0' and len(lines) > 4 and COPYRIGHT.match(lines[4]):
        # The rest of this file can include ICU-specific conditions after the grant.
        del lines[4]
    elif identifier == 'Apache-2.0':
        # Only the example attribution in the appendix is a replaceable field.
        for i, line in enumerate(lines):
            if line.strip().startswith('Licensed under the Apache License, Version 2.0'):
                start = i
                while start and (not lines[start - 1].strip() or COPYRIGHT.match(lines[start - 1]) or
                                 lines[start - 1].strip() == 'Copyright [yyyy] [name of copyright owner]'):
                    start -= 1
                del lines[start:i]
                break
    return ' '.join(' '.join(lines).split())


STANDARD = {key: body(data.decode(), key) for key, data in SPDX.items()}


def retention_reason(label, text, bodies):
    if re.match(r'notice(?:$|[-._])', Path(label).name, re.I):
        return 'Upstream NOTICE; retain with this component.'
    reason = 'Upstream wording does not match a complete declared SPDX body.'
    for key in bodies:
        actual = body(text, key)
        expected = STANDARD[key]
        if actual == expected:
            return None
        if actual.startswith(expected + ' '):
            reason = f'Upstream includes additional text after the {key} body.'
        elif expected.startswith(actual + ' '):
            reason = f'Upstream omits the end of the SPDX {key} text.'
    return reason


def add(kind, name, version, license, source, texts, note=''):
    license = re.sub(r'\s*/\s*', ' OR ', license)
    ids = sorted(set(re.findall(r'[^\s()]+', license)) - {'OR', 'AND', 'WITH'})
    bodies = [key for key in ids if not key.startswith('LicenseRef-')]
    for key in bodies:
        if key not in SPDX:
            raise SystemExit(f'{name}: no SPDX text for {key}')
    attribution = {}
    extras = []
    for item in sorted(texts, key=lambda t: str(t) if isinstance(t, Path) else t[0]):
        label, data = (item.name, item.read_bytes()) if isinstance(item, Path) else item
        text = data.decode('utf-8-sig')
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if COPYRIGHT.match(line):
                attribution[line] = None
                j = i + 1
                while j < len(lines) and re.match(r'^\s+\d{4}\b', lines[j]):
                    attribution[lines[j]] = None
                    j += 1
            elif line.strip() in {'All rights reserved.', 'Google Inc.'}:
                attribution[line] = None
        reason = retention_reason(label, text, bodies)
        if reason is None:
            continue
        key = hashlib.sha256(data).hexdigest()
        documents[key] = data
        extras.append(dict(file=label, text=key, comment=reason))
    components.append(dict(kind=kind, name=name, version=version, license=license, bodies=bodies,
                           source=source, attribution=list(attribution), notice=note,
                           extras=sorted(extras, key=lambda e: (e['file'], e['text']))))


def cargo():
    target = 'aarch64-linux-android'
    meta = json.loads(run(CORE, 'cargo', 'metadata', '--locked', '--format-version', '1', '--filter-platform', target))
    root_id = meta['resolve']['root']
    project = next(p for p in meta['packages'] if p['id'] == root_id)
    add('project', project['name'], project['version'], project['license'], PROJECT_SOURCE, [ROOT / 'LICENSE'])
    # `metadata` includes dev, build and proc-macro edges. Use the target-normal tree for membership.
    tree = run(CORE, 'cargo', 'tree', '--locked', '--target', target, '-e', 'normal,no-proc-macro',
               '--prefix', 'none', '--format', '{p}')
    linked = {tuple(line.split()[:2]) for line in tree.splitlines()}
    external = json.loads((TOOLS / 'notice-sources.json').read_text())
    for p in meta['packages']:
        n, v = p['name'], p['version']
        if (n, 'v' + v) not in linked or p['id'] == root_id:
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
    add('native', 'Rust standard library', version, 'MIT OR Apache-2.0',
        f'https://github.com/rust-lang/rust/tree/{version}/library', [('COPYRIGHT-library.html (text)', text.encode())],
        'See the bundled library notice for vendored exceptions.')
    return project['license']


def archive(z, texts, native, prefix=''):
    for path in z.namelist():
        if path.endswith('/'):
            continue
        if LEGAL.match(Path(path).name):
            texts.append((prefix + path, z.read(path)))
        elif path.endswith('.jar'):
            with ZipFile(io.BytesIO(z.read(path))) as nested:
                archive(nested, texts, native, prefix + path + '!/')
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
        lic = ' AND '.join(dict.fromkeys(names))
        if not texts and lic != 'Apache-2.0':
            raise SystemExit(f'Review {name} ({lic}): archive has no licence text')
        note = 'Copyright 2008 Google Inc. All rights reserved.' if name.endswith(':datastore-preferences-external-protobuf') else ''
        if name == 'org.jetbrains.kotlin:kotlin-stdlib':
            if version != '2.4.21':
                raise SystemExit('Review license/README.md in the new Kotlin release for vendored JVM sources')
            lic += ' AND BSD-3-Clause AND BSL-1.0'
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
            lic = 'CC-BY-SA-4.0 AND GPL-3.0-only AND LicenseRef-MaxMind-GeoLite'
            note = ('This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com. '
                    'Loyalsoldier/geoip converts and augments the country data; MetaCubeX redistributes its DAT unchanged. '
                    'Under GeoLite EULA §6.1, disclosure requires prior notification and written consent from MaxMind '
                    'except as explicitly permitted by CC BY-SA 4.0. Permitted disclosures require the same or '
                    'substantially similar contractual duties downstream. Section 6.3 requires cessation of use and '
                    'destruction of old versions within 30 days after an updated database is released. '
                    'https://www.maxmind.com/en/geolite/eula')
            texts = [SOURCES / 'geo-cc.txt', SOURCES / 'maxmind-eula.txt', GPL]
        else:
            lic = 'GPL-3.0-only AND GPL-2.0-only AND LGPL-2.1-only AND MIT AND WTFPL AND LicenseRef-Unlicensed-rules'
            note = ('MetaCubeX compiles v2fly/domain-list-community with Loyalsoldier/domain-list-custom, its own '
                    'resouces/ directory and lists from mutable upstream branches: blackmatrix7/ios_rule_script, '
                    'gfwlist/gfwlist via cokebar/gfwlist2dnsmasq, felixonmars/dnsmasq-china-list, '
                    'crazy-max/WindowsSpyBlocker, https://gitea.com/XIU2/TrackersListCollection (GPL-3.0), and '
                    'biliintl.list and sharepoint.list from xishang0128/rules (no licence file). Build recipe: '
                    'https://github.com/MetaCubeX/meta-rules-dat/blob/4178770badecb1b349fbcd62c737e0d7a2079729/.github/workflows/run.yml.')
            texts = [f for f in SOURCES.glob('geo-*.txt') if f.name != 'geo-cc.txt'] + [GPL]
        add('data', name, p['version'], lic, p['source'], texts, note)


BRAND_SOURCES = {
    'Simple Icons': ('16.38.1', 'CC0-1.0', 'https://simpleicons.org', []),
    'Bootstrap Icons': ('1.13.2', 'MIT', 'https://icons.getbootstrap.com', [APP / 'app/src/debug/assets/licenses/bootstrap-icons-MIT.txt']),
    'Wikimedia Commons': ('PD-textlogo', 'LicenseRef-Public-domain', 'https://commons.wikimedia.org', []),
    'GitHub': ('github.com/logos', 'LicenseRef-GitHub-logos', 'https://github.com/logos', []),
}


def brand_marks(main):
    """Classify the brand marks by BRAND_ICONS.md's tables; credit the sources whose marks release code draws."""
    table, source = {}, None
    for line in (main / 'BRAND_ICONS.md').read_text().splitlines():
        if line.startswith('## '):
            source = line[3:].strip()
        elif m := re.match(r'^\| (rv_\w+) \|', line):
            if source not in BRAND_SOURCES or m[1] in table:
                raise SystemExit(f'BRAND_ICONS.md: {m[1]} under {source!r}')
            table[m[1]] = source
    marks = set((main / 'res/drawable').glob('rv_brand_*.xml')) | {p for p in (main / 'res/drawable').glob('rv_*.xml') if p.stem in table}
    unlisted = sorted(p.stem for p in marks if p.stem not in table)
    if unlisted:
        raise SystemExit(f'Classify artwork or data: list {", ".join(unlisted)} in BRAND_ICONS.md with its source')
    # Release resource shrinking keeps only the marks main code names; the rest serve debug pages.
    drawn = {m for p in (main / 'kotlin').rglob('*.kt') for m in re.findall(r'R\.drawable\.(rv_\w+)', p.read_text())}
    for name, (v, lic, src, texts) in BRAND_SOURCES.items():
        shipped = sorted(m for m in drawn if table.get(m) == name)
        if shipped:
            add('artwork', f'{name} brand marks', v, lic, src, texts,
                'The brand marks are trademarks of their owners, shown only to name a service or group; their use implies '
                "no affiliation with or endorsement by those owners, and doona's licence does not cover them. "
                'Marks: ' + ', '.join(shipped) + '. Provenance: app/src/main/BRAND_ICONS.md.')
    return marks


def first_comment(path):
    m = re.search(r'<!--(.*?)-->', path.read_text(), re.S)
    return ' '.join(m[1].split()) if m else ''


def assets():
    main = APP / 'app/src/main'
    # doona's own artwork: the launcher icon, the shortcut discs around its glyphs, and the analytics bars.
    owned = {'ic_duck.xml', 'ic_launcher_foreground.xml', 'ic_launcher_monochrome.xml', 'ic_launcher.xml',
             'rv_ic_analytics.xml', 'rv_shortcut_nodes.xml', 'rv_shortcut_power.xml'}
    paths = {p for module in ('app', 'icons', 'flags') for variant in ('main', 'release')
             for p in (APP / module / 'src' / variant / 'res').glob('*/*')
             if p.parent.name.split('-')[0] in {'drawable', 'font', 'mipmap', 'raw'}}
    sets = [
        ('app/src/main/res/font/*inter_*.ttf', 'Inter', '4.001', 'OFL-1.1', 'https://github.com/rsms/inter', [SOURCES / 'inter-OFL.txt'], 'Latin subset, fixed weights and optical sizes.'),
        ('app/src/main/res/font/noto_sans_tc_*.ttf', 'Noto Sans TC', '@fontsource-variable/noto-sans-tc 5.3.0', 'OFL-1.1', 'https://fontsource.org/fonts/noto-sans-tc', [SOURCES / 'noto-sans-tc-OFL.txt'], 'Subset and fixed-weight instances. Copyright 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name \'Source\'.'),
        ('icons/src/main/res/drawable/sym_*.xml', 'Material Symbols Rounded', '10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7', 'Apache-2.0', 'https://github.com/google/material-design-icons', [APP / 'icons/LICENSE', APP / 'icons/NOTICE'], ''),
        ('flags/src/main/res/drawable/flag_*.xml', 'circle-flags', '379588b5da95482d6bbf10bd45644a35b0609ea6', 'MIT', 'https://github.com/HatScripts/circle-flags', [APP / 'flags/LICENSE', APP / 'flags/NOTICE'], ''),
    ]
    # The app's own copies of set glyphs (rv_sym_*, rv_flag_*, ...) name their set in their first comment.
    headed = {'Material Symbols Rounded': 'Material Symbols Rounded "', 'circle-flags': 'HatScripts circle-flags '}
    for glob, name, v, lic, src, texts, note in sets:
        matches = set(APP.glob(glob))
        if name in headed:
            matches |= {p for p in (main / 'res/drawable').glob('rv_*.xml') if headed[name] in first_comment(p)}
        if matches:
            add('artwork', name, v, lic, src, texts, note)
            paths -= matches
    paths -= brand_marks(main)
    for p in sorted(paths):
        if p.name not in owned or main not in p.parents:
            raise SystemExit(f'Classify artwork or data: {p.relative_to(APP)}')
    maps = [p.stem for p in (main / 'kotlin').rglob('*.kt') if p.stem in ('LandMask', 'WorldShapes')]
    if sorted(maps) != ['LandMask', 'WorldShapes']:
        raise SystemExit('Review the Natural Earth inventory after moving or removing map geometry')
    add('data', 'Natural Earth 1:110m', '5.1.2', 'LicenseRef-Public-domain', 'https://www.naturalearthdata.com/about/terms-of-use/', [],
        'Made with Natural Earth. Derived map geometry: ' + ', '.join(sorted(maps)) + '.')


def native_and_data(apk, native):
    libs = {n[4:] for n in apk.namelist() if n.startswith('lib/')}
    cxx = 'arm64-v8a/libc++_shared.so'
    if cxx in libs:
        native.add(cxx)
        v = re.search(r'val coreNdk = "([^"]+)"', (APP / 'app/build.gradle.kts').read_text())[1]
        ndk = Path(os.environ['ANDROID_HOME']) / 'ndk' / v
        sections = re.split(r'(?=^={78}\nThe LLVM Project is under)', (ndk / 'NOTICE.toolchain').read_text(), flags=re.M)
        texts = [('NOTICE.toolchain', s.encode()) for s in sections if 'The libc++ library is dual licensed' in s or 'The libc++abi library is dual licensed' in s]
        if len(texts) != 2:
            raise SystemExit('Review libc++ and libc++abi notices for this NDK')
        add('native', 'LLVM libc++ and libc++abi', v, 'Apache-2.0 WITH LLVM-exception',
            'https://developer.android.com/ndk', texts, 'See the NDK notices for legacy licence options.')
    if libs != native | {'arm64-v8a/libdoonacore.so'}:
        raise SystemExit(f'Unaccounted APK library difference: {libs ^ (native | {"arm64-v8a/libdoonacore.so"})}')
    extra = {n for n in apk.namelist() if n.startswith('assets/') and not n.startswith('assets/licenses/')} - {
        'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm', 'assets/geo/pins.json', 'assets/geo/geoip.dat', 'assets/geo/geosite.dat'}
    if extra:
        raise SystemExit(f'Classify new packaged assets: {sorted(extra)}')
    geo(apk)


def render(project_license):
    components.sort(key=lambda c: (c['kind'], c['name'], c['version']))
    # The catalogue also covers BSD-2-Clause in the Rust distribution's composite notice.
    documents.update(SPDX)
    bundle = bytearray()
    locations = {}
    links = {}
    line = 1
    # Some exceptions contain other upstream files verbatim. Keep their separate index
    # entries, but reuse the exact byte range rather than printing those files twice.
    labels = {entry['text']: f"{c['name']} — {entry['file']}" for c in components for entry in c['extras']}
    for key in [*sorted(SPDX), *sorted(labels, key=lambda k: (-len(documents[k]), k))]:
        data = documents[key]
        offset = -1 if key in SPDX else bundle.find(data)
        if offset < 0:
            bundle.extend((labels.get(key, key) + '\n\n').encode())
            offset = len(bundle)
            text_line = line + 2
            bundle.extend(data + b'\n\n')
            line += data.count(b'\n') + 4
        else:
            text_line = 1 + bundle.count(b'\n', 0, offset)
        links[key] = f'notices.txt#L{text_line}'
        locations[key] = [offset, len(data)]
    outputs[OUT / 'notices.txt'] = bytes(bundle)
    index = dict(schema=2, components=components, ranges=locations)
    outputs[OUT / 'index.json'] = (json.dumps(index, ensure_ascii=False, indent=2) + '\n').encode()
    lines = ['# Third-party notices', '', f'Android client licence: [{project_license}](LICENSE).',
             f'Android client source: {PROJECT_SOURCE}', '']
    lines.extend(['## SPDX licence texts', ''])
    for key in sorted(SPDX):
        lines.append(f'- [{key}]({OUT.relative_to(ROOT)}/{links[key]})')
    lines.extend(['', '## Components', ''])
    for c in components:
        expression = re.sub(r'[^\s()]+', lambda m: f'[{m[0]}]({OUT.relative_to(ROOT)}/{links[m[0]]})'
                            if m[0] in c['bodies'] else m[0], c['license'])
        lines.extend([f"### {c['name']} {c['version']}", '', f"{c['kind']} · {expression} · [Source]({c['source']})", ''])
        for line in c['attribution']:
            lines.append('    ' + line)
        if c['notice']:
            lines.extend(['', c['notice']])
        for entry in c['extras']:
            lines.append(f"\n- [{entry['file']}]({OUT.relative_to(ROOT)}/{links[entry['text']]}) {entry['comment']}")
        lines.append('')
    outputs[ROOT / 'THIRD_PARTY_NOTICES.md'] = ('\n'.join(lines).rstrip() + '\n').encode()


def check_or_write(apk_path, write):
    stale = {p for p in OUT.rglob('*') if p.is_file()} - outputs.keys()
    changed = [p for p, data in outputs.items() if not p.exists() or p.read_bytes() != data]
    if write:
        if stale:
            raise SystemExit('Remove unreferenced notice files: ' + ', '.join(map(str, sorted(stale))))
        for p in changed:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(outputs[p])
    elif changed or stale:
        raise SystemExit('Stale notices: ' + ', '.join(str(p.relative_to(ROOT)) for p in sorted(stale | set(changed))))
    else:
        with ZipFile(apk_path) as apk:
            expected = {'assets/licenses/' + p.name: b for p, b in outputs.items() if p.parent == OUT}
            packed = {n for n in apk.namelist() if n.startswith('assets/licenses/')}
            if packed != expected.keys() or any(apk.read(n) != b for n, b in expected.items()):
                raise SystemExit('Packaged notices differ; rebuild the release APK')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='replace generated files instead of checking them')
    parser.add_argument('--apk', type=Path, default=APP / 'app/build/outputs/apk/release/app-release.apk')
    args = parser.parse_args()
    project_license = cargo()
    native = maven()
    assets()
    with ZipFile(args.apk) as apk:
        native_and_data(apk, native)
    render(project_license)
    check_or_write(args.apk, args.write)
    print(f'{len(components)} release components; {len(documents)} distinct bundled texts')


if __name__ == '__main__':
    main()
