import json
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from xml.etree import ElementTree


SCRIPT = Path(__file__).resolve().parents[1] / 'generate_xml_from_google_services_json.py'
KEEP_FILENAME = 'com.defold.push.config.keep.xml'
TOOLS_KEEP = '{http://schemas.android.com/tools}keep'


class GenerateGoogleServicesTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.config = {
            'project_info': {
                'project_number': '123456',
                'firebase_url': 'https://example.invalid',
                'project_id': 'example',
                'storage_bucket': 'example.invalid',
            },
            'client': [{
                'client_info': {
                    'mobilesdk_app_id': 'example-app-id',
                    'android_client_info': {'package_name': 'com.example.first'},
                },
                'api_key': [{'current_key': 'example-api-key'}],
                'oauth_client': [{'client_type': 3, 'client_id': 'example-web-client'}],
            }, {
                'client_info': {
                    'mobilesdk_app_id': 'second-app-id',
                    'android_client_info': {'package_name': 'com.example.second'},
                },
            }],
        }
        self.input = self.root / 'app/google-services.json'
        self.input.parent.mkdir()
        self.write_input()

    def write_input(self):
        self.input.write_text(json.dumps(self.config))

    def run_generator(self, *args):
        result = subprocess.run([sys.executable, str(SCRIPT), *args],
                                cwd=self.root, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result

    def read_resources(self, values_path, keep_path):
        values = ElementTree.parse(values_path).getroot()
        self.assertNotIn(TOOLS_KEEP, values.attrib)
        strings = {child.get('name'): child.text for child in values.findall('string')}
        keep = ElementTree.parse(keep_path).getroot()
        self.assertEqual({'@string/' + name for name in strings},
                         set(keep.get(TOOLS_KEEP).split(',')))
        return strings

    # Verifies default Android output has effective raw keep rules, including OAuth strings.
    def test_default_android_output(self):
        self.run_generator()
        strings = self.read_resources(self.root / 'res/values/googleservices.xml',
                                      self.root / 'res/raw' / KEEP_FILENAME)
        self.assertEqual('example-app-id', strings['google_app_id'])
        self.assertEqual('example-web-client', strings['default_web_client_id'])
        self.assertEqual('example-api-key', strings['google_api_key'])

    # Verifies custom and qualified values paths keep the selected client's strings in the same res tree.
    def test_custom_values_output_and_package(self):
        for directory in ['values', 'values-v23']:
            with self.subTest(directory=directory):
                output = self.root / 'custom bundle/android/res' / directory / 'firebase.xml'
                self.run_generator('-o', str(output), '-p', 'com.example.second')
                strings = self.read_resources(output, output.parent.parent / 'raw' / KEEP_FILENAME)
                self.assertEqual('second-app-id', strings['google_app_id'])
                self.assertNotIn('default_web_client_id', strings)
                self.assertNotIn('google_api_key', strings)

    # Verifies legacy flat -o usage creates the raw directory beside the output file.
    def test_flat_output(self):
        self.run_generator('-o', 'google-services.xml')
        self.read_resources(self.root / 'google-services.xml', self.root / 'raw' / KEEP_FILENAME)

    # Verifies regeneration removes obsolete keeps when optional strings disappear from the input.
    def test_regeneration_replaces_keep_list(self):
        self.run_generator()
        self.config['client'][0]['oauth_client'] = []
        self.write_input()
        self.run_generator()
        strings = self.read_resources(self.root / 'res/values/googleservices.xml',
                                      self.root / 'res/raw' / KEEP_FILENAME)
        self.assertNotIn('default_web_client_id', strings)

    # Verifies inspection modes still work with Python 3 and never create either Android output.
    def test_inspection_modes_do_not_write_resources(self):
        for option in ['-l', '-f']:
            with self.subTest(option=option):
                result = self.run_generator(option, '-o', 'output/res/values/config.xml')
                self.assertIn('com.example.first' if option == '-l' else 'project_id=example',
                              result.stdout)
                self.assertFalse((self.root / 'output').exists())

    # Verifies desktop plist conversion keeps producing JSON without adding Android resource files.
    def test_plist_output_does_not_write_resource_keeps(self):
        config = {
            'GCM_SENDER_ID': '123456', 'DATABASE_URL': 'https://example.invalid',
            'PROJECT_ID': 'example', 'STORAGE_BUCKET': 'example.invalid',
            'GOOGLE_APP_ID': 'desktop-app-id', 'BUNDLE_ID': 'com.example.desktop',
            'CLIENT_ID': 'example-client', 'API_KEY': 'example-key',
            'IS_ANALYTICS_ENABLED': True, 'IS_APPINVITE_ENABLED': False,
        }
        plist = self.root / 'GoogleServices-Info.plist'
        plist.write_bytes(plistlib.dumps(config))
        self.run_generator('--plist', '-o', 'desktop/config.json')
        output = self.root / 'desktop/config.json'
        generated = json.loads(output.read_text())
        self.assertEqual('desktop-app-id', generated['client'][0]['client_info']['mobilesdk_app_id'])
        self.assertEqual([output], list(output.parent.iterdir()))
        self.assertFalse((self.root / 'raw').exists())


if __name__ == '__main__':
    unittest.main()
