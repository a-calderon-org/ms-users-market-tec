"""All fixtures live in TemporaryDirectory; real Maven reports are never edited."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-mtec35-reports.py'
SPEC = importlib.util.spec_from_file_location('strict_reports', SCRIPT)
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)
BAD = ('failure', 'error', 'skipped', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError')


class StrictReportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.addCleanup(patch.stopall)
        patch.object(verifier, 'ROOT', self.root).start()

    def report(self, directory='surefire-reports', bad=None, style='plain', wrapped=False):
        namespace = 'urn:test-report' if style != 'plain' else ''
        if namespace:
            ET.register_namespace('r' if style == 'prefix' else '', namespace)
        def tag(name):
            return f'{{{namespace}}}{name}' if namespace else name
        suite = ET.Element(tag('testsuite'), tests='1', failures='0', errors='0', skipped='0')
        case = ET.SubElement(suite, tag('testcase'), classname='Example', name='passes')
        if bad:
            # Include nested stack/output structures used by Surefire rerun reports.
            result = ET.SubElement(case, tag(bad), type='example.Failure')
            ET.SubElement(result, tag('stackTrace')).text = 'synthetic failure'
            ET.SubElement(result, tag('system-out')).text = 'synthetic output'
        root = suite
        if wrapped:
            root = ET.Element(tag('testsuites'), tests='1', failures='0', errors='0', skipped='0')
            root.append(suite)
        path = self.root / 'target' / directory / 'TEST-example.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)
        return path

    def summary(self, style='plain', **changes):
        prefix = '{urn:summary}' if style != 'plain' else ''
        root = ET.Element(prefix + 'failsafe-summary', result='null', timeout='false')
        values = dict(completed='1', errors='0', failures='0', skipped='0', failureMessage=None)
        values.update(changes)
        for key, value in values.items():
            ET.SubElement(root, prefix + key).text = value
        path = self.root / 'target/failsafe-reports/failsafe-summary.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(root).write(path)
        return path

    def test_first_attempt_passes_in_both_report_directories_and_xml_formats(self):
        for directory in ('surefire-reports', 'failsafe-reports'):
            for style in ('plain', 'default', 'prefix'):
                for wrapped in (False, True):
                    with self.subTest(directory=directory, style=style, wrapped=wrapped):
                        self.report(directory, style=style, wrapped=wrapped)
                        self.assertEqual([('Example', 'passes')], verifier.verify(directory, 1))

    def test_every_non_pass_element_is_rejected_even_with_zero_suite_counters(self):
        for directory in ('surefire-reports', 'failsafe-reports'):
            for style in ('plain', 'default', 'prefix'):
                for bad in BAD:
                    for wrapped in (False, True):
                        with self.subTest(directory=directory, style=style, bad=bad, wrapped=wrapped):
                            self.report(directory, bad, style, wrapped)
                            with self.assertRaisesRegex(ValueError, bad):
                                verifier.verify(directory, 1)

    def test_namespaced_result_inside_unnamespaced_case_is_not_ignored(self):
        path = self.report()
        root = ET.parse(path).getroot()
        ET.SubElement(root.find('testcase'), '{urn:extension}flakyError')
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(ValueError, 'flakyError'):
            verifier.verify('surefire-reports', 1)

    def test_nonzero_or_missing_suite_counters_and_count_mismatches_are_rejected(self):
        for field, value in (('tests', '2'), ('tests', '-1'), ('failures', '1'),
                             ('errors', '1'), ('skipped', '1'), ('errors', None)):
            with self.subTest(field=field, value=value):
                path = self.report()
                root = ET.parse(path).getroot()
                if value is None:
                    del root.attrib[field]
                else:
                    root.set(field, value)
                ET.ElementTree(root).write(path)
                with self.assertRaises(ValueError):
                    verifier.verify('surefire-reports', 1)

    def test_missing_malformed_and_unexpected_report_roots_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'No XML'):
            verifier.verify('surefire-reports', 1)
        path = self.report()
        path.write_text('<testsuite')
        with self.assertRaises(ET.ParseError):
            verifier.verify('surefire-reports', 1)
        path.write_text('<anything/>')
        with self.assertRaises(ValueError):
            verifier.verify('surefire-reports', 1)

    def test_duplicate_or_missing_case_identity_and_wrong_expected_count_are_rejected(self):
        path = self.report()
        with self.assertRaisesRegex(ValueError, 'expected 2'):
            verifier.verify('surefire-reports', 2)
        duplicate = path.with_name('TEST-duplicate.xml')
        duplicate.write_bytes(path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            verifier.verify('surefire-reports', 2)
        duplicate.unlink()
        root = ET.parse(path).getroot()
        del root.find('testcase').attrib['name']
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(ValueError, 'identity'):
            verifier.verify('surefire-reports', 1)

    def test_passing_failsafe_summary_with_or_without_namespace(self):
        for style in ('plain', 'default'):
            with self.subTest(style=style):
                verifier.verify_summary(self.summary(style), 1)

    def test_failsafe_summary_failures_timeout_flakes_and_fork_errors_are_rejected(self):
        for field in ('completed', 'errors', 'failures', 'skipped', 'flakes', 'failureMessage'):
            with self.subTest(field=field):
                value = '2' if field == 'completed' else 'synthetic fork error' if field == 'failureMessage' else '1'
                with self.assertRaises(ValueError):
                    verifier.verify_summary(self.summary('default', **{field: value}), 1)
        for field, value in (('timeout', 'true'), ('result', '255'), ('result', '254')):
            path = self.summary()
            root = ET.parse(path).getroot()
            root.set(field, value)
            ET.ElementTree(root).write(path)
            with self.assertRaises(ValueError):
                verifier.verify_summary(path, 1)

    def test_failsafe_summary_rejects_retry_elements_and_duplicate_counts(self):
        for bad in ('flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'):
            path = self.summary()
            root = ET.parse(path).getroot()
            ET.SubElement(root, '{urn:retry}' + bad)
            ET.ElementTree(root).write(path)
            with self.assertRaisesRegex(ValueError, bad):
                verifier.verify_summary(path, 1)
        path = self.summary()
        root = ET.parse(path).getroot()
        ET.SubElement(root, 'errors').text = '0'
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            verifier.verify_summary(path, 1)

    def inventory(self):
        path = self.root / 'scripts/mtec35-test-inventory.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(dict(normal_count=1, required_normal_methods={'Example': ['passes']},
                                        sql_class='Example', sql_methods=['passes'])))

    def run_main(self, *args):
        with patch('sys.argv', ['verifier', *args]), contextlib.redirect_stdout(io.StringIO()):
            verifier.main()

    def test_complete_synthetic_inventory_passes_and_writes_results(self):
        self.inventory()
        self.report()
        self.report('failsafe-reports', style='prefix', wrapped=True)
        self.summary('default')
        self.run_main()
        result = json.loads((self.root / 'target/mtec35-qa-results.json').read_text())
        self.assertEqual(1, result['normal']['executed'])
        self.assertEqual(1, result['sql']['executed'])

    def test_normal_only_does_not_claim_sql_but_full_verification_requires_sql_reports(self):
        self.inventory()
        self.report()
        self.run_main('--normal-only')
        result = json.loads((self.root / 'target/mtec35-normal-results.json').read_text())
        self.assertNotIn('sql', result)
        with self.assertRaisesRegex(ValueError, 'No XML.*failsafe'):
            self.run_main()
        self.assertFalse((self.root / 'target/mtec35-qa-results.json').exists())

    def test_same_count_method_substitution_cannot_replace_mandatory_normal_or_sql_cases(self):
        self.inventory()
        path = self.report()
        root = ET.parse(path).getroot()
        root.find('testcase').set('name', 'substituted')
        ET.ElementTree(root).write(path)
        with self.assertRaisesRegex(ValueError, 'Mandatory normal'):
            self.run_main('--normal-only')
        self.report()
        path = self.report('failsafe-reports')
        root = ET.parse(path).getroot()
        root.find('testcase').set('name', 'substituted')
        ET.ElementTree(root).write(path)
        self.summary()
        with self.assertRaisesRegex(ValueError, 'SQL method inventory'):
            self.run_main()


if __name__ == '__main__':
    unittest.main()
