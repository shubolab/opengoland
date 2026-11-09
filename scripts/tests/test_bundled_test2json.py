"""Guard the derived tool's source identity and owned staging boundary."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'bundle-go-tools.py'
spec = importlib.util.spec_from_file_location('derived_test2json_bundle', SCRIPT)
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class DerivedTest2JsonTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.sdk = self.root / 'sdk/go'
        for name, data in {'bin/go' + ('.exe' if os.name == 'nt' else ''): b'fixture, never executed',
                           'src/cmd/test2json/main.go': b'package main', 'src/runtime/runtime.go': b'package runtime',
                           'VERSION': b'go1.26.10\n', 'LICENSE': b'Go license fixture'}.items():
            p = self.sdk / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        self.marker = self.sdk.parent / 'sdk-files.json'
        self.marker.write_text(json.dumps({'archiveSha256': '1' * 64, 'files': bundle.files(self.sdk)}), encoding='utf-8')
        self.staging = self.root / 'staging'
        self.staging.mkdir()
        self.lock = {'runtimeSdkVersion': 'go1.26.10', 'sdk': {
            'darwin-arm64': {'version': 'go1.26.10', 'sha256': '1' * 64},
            'windows-amd64': {'version': 'go1.26.10', 'sha256': '2' * 64}}}
        self.info = {'goVersion': 'go1.26.10', 'dependencies': [],
                     'build': {'GOOS': 'windows', 'GOARCH': 'amd64', 'CGO_ENABLED': '0'}}
        self.calls = []

    def compile_fixture(self, command, environment, cwd):
        self.calls.append((command, environment.copy(), cwd))
        output = Path(command[command.index('-o') + 1])
        output.write_bytes(b'derived PE fixture, never executed')
        return ''

    def derive(self, compiler=None, info=None):
        with patch.object(bundle, 'host_target', return_value='darwin-arm64'), \
                patch.object(bundle, 'sdk', return_value=self.sdk), \
                patch.object(bundle, 'execute', side_effect=compiler or self.compile_fixture), \
                patch.object(bundle, 'build_info', return_value=info or self.info):
            return bundle.build_runtime_test2json(self.lock, self.root / 'cache', self.staging, 'windows-amd64', True)

    def test_cross_tool_uses_runtime_source_compiler_and_only_staging_output(self):
        original = bundle.files(self.sdk)
        marker = self.marker.read_bytes()
        record = self.derive()
        command, env, cwd = self.calls[0]
        self.assertEqual(self.sdk / 'bin' / ('go.exe' if os.name == 'nt' else 'go'), command[0])
        self.assertEqual('cmd/test2json', command[-1])
        self.assertEqual(self.staging / 'go/pkg/tool/windows_amd64/test2json.exe', Path(command[-2]))
        self.assertEqual('off', env['GOPROXY'])
        self.assertEqual('off', env['GOSUMDB'])
        self.assertEqual('off', env['GO111MODULE'])
        self.assertEqual('0', env['CGO_ENABLED'])
        self.assertEqual(str(self.sdk), env['GOROOT'])
        self.assertFalse(Path(env['GOCACHE']).exists())
        self.assertEqual(original, bundle.files(self.sdk))
        self.assertEqual(marker, self.marker.read_bytes())
        self.assertEqual('1' * 64, record['hostRuntimeArchiveSha256'])
        self.assertEqual('2' * 64, record['targetRuntimeArchiveSha256'])
        self.assertEqual(hashlib.sha256((self.staging / record['executable']).read_bytes()).hexdigest(), record['sha256'])

    def test_wrong_compiler_version_or_target_cannot_produce_valid_manifest(self):
        for key, value in (('goVersion', 'go1.27.3'), ('GOARCH', 'arm64'), ('CGO_ENABLED', '1')):
            info = copy.deepcopy(self.info)
            if key == 'goVersion': info[key] = value
            else: info['build'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'identity differs'):
                self.derive(info=info)
            (self.staging / 'go/pkg/tool/windows_amd64/test2json.exe').unlink()

    def test_mutation_of_authenticated_source_during_build_is_rejected(self):
        def mutate(command, environment, cwd):
            self.compile_fixture(command, environment, cwd)
            (self.sdk / 'src/runtime/runtime.go').write_bytes(b'changed while compiling')
        with self.assertRaisesRegex(ValueError, 'SDK changed'):
            self.derive(compiler=mutate)

    def test_existing_sdk_tool_is_not_overwritten(self):
        destination = self.staging / 'go/pkg/tool/windows_amd64/test2json.exe'
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b'original official tool')
        with self.assertRaisesRegex(ValueError, 'already contains'):
            self.derive()
        self.assertEqual(b'original official tool', destination.read_bytes())
        self.assertEqual([], self.calls)

    def test_utf8_go_output_does_not_use_windows_ansi_default(self):
        # A real process writes UTF-8 bytes, as Go does for JSON and build-info
        # paths. Force Popen's omitted-encoding choice to Windows CP1252.
        popen = bundle.subprocess.Popen
        def ansi_default(*arguments, **keywords):
            keywords.setdefault('encoding', 'cp1252')
            return popen(*arguments, **keywords)
        with patch.object(bundle.subprocess, 'Popen', side_effect=ansi_default):
            output = bundle.execute([sys.executable, '-c',
                                     "import sys; sys.stdout.buffer.write('前😀'.encode('utf-8'))"],
                                    os.environ.copy(), self.root)
        self.assertEqual('前😀', output)


if __name__ == '__main__':
    unittest.main()
