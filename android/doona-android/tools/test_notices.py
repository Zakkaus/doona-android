import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import notices


class NoticeGateTest(unittest.TestCase):
    def setUp(self):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.out = root / 'licenses'
        self.enterContext(patch.multiple(notices, ROOT=root, OUT=self.out, components=[], documents={}, outputs={}))
        self.holder = 'Copyright (c) 2026 Léa'
        self.mit = notices.SPDX['MIT'].replace(b'Copyright (c) <year> <copyright holders>', self.holder.encode())

    def generate(self, text):
        notices.components.clear()
        notices.documents.clear()
        notices.outputs.clear()
        notices.add('cargo', 'example', '1', 'MIT OR Apache-2.0', '', [('LICENSE-MIT', text)])
        notices.render('AGPL-3.0-only')

    def test_missing_component(self):
        self.generate(self.mit)
        notices.check_or_write(None, True)
        path = self.out / 'index.json'
        saved = json.loads(path.read_bytes())
        saved['components'].clear()
        path.write_text(json.dumps(saved, ensure_ascii=False, indent=2) + '\n')
        with self.assertRaisesRegex(SystemExit, r'Stale notices: licenses/index\.json'):
            notices.check_or_write(None, False)

    def test_new_clause_requires_own_entry(self):
        self.generate(self.mit)
        notices.check_or_write(None, True)
        component, = notices.components
        self.assertEqual(component['bodies'], ['Apache-2.0', 'MIT'])
        self.assertEqual(component['attribution'], [self.holder])
        self.assertEqual(component['extras'], [])

        amended = self.mit + b'Additional condition: retain the project name.\r\n'
        self.generate(amended)
        with self.assertRaisesRegex(SystemExit, 'Stale notices:'):
            notices.check_or_write(None, False)
        index = json.loads(notices.outputs[self.out / 'index.json'])
        entry, = index['components'][0]['extras']
        start, length = index['ranges'][entry['text']]
        self.assertEqual(notices.outputs[self.out / 'notices.txt'][start:start + length], amended)

    def pin_geo(self, version):
        """Write geo-pins.json for `version` and return an APK that carries the pinned bytes."""
        data = {f'assets/geo/{name}': f'{name} {version}'.encode() for name in ('geoip.dat', 'geosite.dat')}
        files = {Path(path).name: {'version': version, 'source': f'https://example.org/{version}/{Path(path).name}',
                                   'sha256': hashlib.sha256(raw).hexdigest()} for path, raw in data.items()}
        pins = notices.CORE / 'tools/geo-pins.json'
        pins.parent.mkdir(parents=True, exist_ok=True)
        pins.write_text(json.dumps({'files': files}))
        return SimpleNamespace(namelist=lambda: list(data), read=data.__getitem__)

    def release_outputs(self, apk):
        """What the release check expects: every component built anew, the Geo ones from the APK."""
        notices.components.clear()
        notices.documents.clear()
        notices.outputs.clear()
        notices.add('project', 'example', '1', 'MIT', '', [('LICENSE', self.mit)])
        notices.add('cargo', 'library', '1', 'MIT', '', [('NOTICE', b'Keep this notice.\n')])
        notices.geo(apk)
        notices.render('MIT')
        return dict(notices.outputs)

    def test_new_pins_give_notices_with_the_new_version(self):
        self.enterContext(patch.object(notices, 'CORE', self.out.parent / 'core'))
        self.release_outputs(self.pin_geo('a' * 40))
        notices.check_or_write(None, True)
        bundle = (self.out / 'notices.txt').read_bytes()

        expected = self.release_outputs(self.pin_geo('b' * 40))
        notices.components.clear()
        notices.documents.clear()
        notices.outputs.clear()
        notices.update_geo()

        self.assertEqual({path: path.read_bytes() for path in expected}, expected)
        for path in (self.out / 'index.json', self.out.parent / 'THIRD_PARTY_NOTICES.md'):
            text = path.read_text()
            self.assertIn('b' * 40, text)
            self.assertNotIn('a' * 40, text)
        self.assertEqual((self.out / 'notices.txt').read_bytes(), bundle)

    def test_notice_is_not_a_substitute_for_a_licence(self):
        notices.add('maven', 'library', '1', 'Apache-2.0', '', [('META-INF/NOTICE', notices.SPDX['Apache-2.0'])])
        component, = notices.components
        entry, = component['extras']
        self.assertEqual(entry['file'], 'META-INF/NOTICE')
        self.assertEqual(notices.documents[entry['text']], notices.SPDX['Apache-2.0'])


class BrandMarkTest(unittest.TestCase):
    def setUp(self):
        self.main = Path(self.enterContext(tempfile.TemporaryDirectory()))
        (self.main / 'res/drawable').mkdir(parents=True)
        (self.main / 'kotlin').mkdir()
        self.enterContext(patch.multiple(notices, components=[], documents={}))
        for name in ('rv_brand_a', 'rv_brand_b'):
            (self.main / 'res/drawable' / f'{name}.xml').write_text('<vector/>')
        (self.main / 'kotlin/Page.kt').write_text('val glyph = R.drawable.rv_brand_a\n')

    def doc(self, *rows):
        (self.main / 'BRAND_ICONS.md').write_text('\n'.join(['## Simple Icons', '', '| drawable | slug |', '|---|---|', *rows, '']))

    def test_unlisted_mark_needs_a_source(self):
        self.doc('| rv_brand_a | a |')
        with self.assertRaisesRegex(SystemExit, 'Classify artwork or data: list rv_brand_b'):
            notices.brand_marks(self.main)

    def test_only_drawn_marks_are_credited(self):
        self.doc('| rv_brand_a | a |', '| rv_brand_b | b |')
        self.assertEqual({p.stem for p in notices.brand_marks(self.main)}, {'rv_brand_a', 'rv_brand_b'})
        component, = notices.components
        self.assertEqual((component['name'], component['license']), ('Simple Icons brand marks', 'CC0-1.0'))
        self.assertIn('Marks: rv_brand_a.', component['notice'])
