"""Small synthetic receipt fixtures exercise audit rejection gates, not product acceptance."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'audit_public_release.py'
spec = importlib.util.spec_from_file_location('release_audit', SCRIPT)
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


class ReleaseAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.repo = self.root / 'repo'; self.repo.mkdir()
        (self.repo / 'engine').mkdir(); (self.repo / 'engine/go.mod').write_text('module example.invalid/fixture\n')
        self.hooks = self.root / 'hooks'; self.hooks.mkdir()
        self.git('init'); self.git('add', '.'); self.git('commit', '-m', 'Synthetic audit fixture')
        self.source = self.git('rev-parse', 'HEAD'); self.version = '0.1.0-preview.1'; self.tag = 'v' + self.version
        self.git('tag', self.tag)
        self.directory = self.root / 'assets'; self.directory.mkdir()
        self.names = {'windows': 'open-Goland-' + self.version + '-windows-x64-setup.exe',
                      'macos': 'open-Goland-' + self.version + '-macos-arm64.dmg'}
        for key, data in [('windows', b'MZ-synthetic-receipt-fixture'), ('macos', b'synthetic-dmg-receipt-fixture')]:
            (self.directory / self.names[key]).write_bytes(data)
        entries = [{'path': 'engine/go.mod', 'sha256': a.sha(self.repo / 'engine/go.mod')}]
        self.capture = {'schemaVersion': 1, 'algorithm': 'sha256', 'files': entries,
            'digest': hashlib.sha256(''.join(x['sha256'] + '  ' + x['path'] + '\n' for x in entries).encode()).hexdigest()}
        self.engine = {'exitCode': 0, 'cached': False, 'testCount': 1, 'test': 'TestBundledInstalledRuntime'}
        self.race = '\n'.join(json.dumps(x) for x in [
            {'Action': 'pass', 'Package': 'pkg', 'Test': 'TestOwned'},
            {'Action': 'pass', 'Package': 'pkg'}, {'Action': 'skip', 'Package': 'cmd'}]) + '\n'
        self.xml = '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="Fixture" name="first"/><testcase classname="Fixture" name="second"/></testsuite>'
        checks = {'kind': 'fresh', 'sourceDigest': self.capture['digest'],
            'commands': [{'exitCode': 0}, {'exitCode': 0}],
            'logs': [{'path': 'go-race.jsonl', 'bytes': len(self.race.encode()), 'sha256': hashlib.sha256(self.race.encode()).hexdigest()},
                     {'path': 'go-vet.log', 'bytes': 0, 'sha256': hashlib.sha256(b'').hexdigest()}],
            'raceSummary': {'packages': {'pkg': 'pass', 'cmd': 'skip'}, 'testPassEvents': 1}}
        self.mac = {'release.json': {**self.common_release('macos'), 'sourceDirty': False,
            'sourceDigest': self.capture['digest'], 'imageVerified': True, 'mountedAndCopied': True, 'signatureVerified': True,
            'signing': 'ad-hoc; not notarized', 'installedEngineSmoke': self.engine, 'goChecks': checks},
            'source-inputs.json': self.capture, 'go-checks.json': checks,
            'installed-native-version.log': '2026.1.4\n', 'go-race.jsonl': self.race, 'go-vet.log': '',
            'kotlin-test-results/TEST-Fixture.xml': self.xml}
        self.native_version = {'code': 0, 'stdout': '2026.1.4', 'stderr': ''}
        self.smoke = {'status': 'pass', 'version': self.version, 'installedFilesVerified': 19,
            'nativeVersion': self.native_version,
            'nativeProjectStartup': {'exitCode': 0, 'exitCommand': {'code': 0}, 'projectFrame': {'class': 'SunAwtFrame', 'title': 'fixture'}},
            'engine': self.engine, 'scope': 'Synthetic unit fixture, no product acceptance.',
            'toolVersions': [{'code': 0, 'stdout': text, 'stderr': ''} for text in ['go1.26.10', 'v0.24.0', '1.27.2']]}
        self.windows = {'release.json': {**self.common_release('windows'), 'installExitCode': 0, 'uninstallExitCode': 0,
            'installedSmoke': 'installed-smoke/smoke.json'},
            'assembly.json': {'manifest': {'sourceSdkUnchanged': True, 'buildInputs': self.capture,
                'engineBuildInformation': 'vcs.revision=' + self.source + '\nvcs.modified=false'}},
            'installed-smoke/smoke.json': self.smoke, 'installed-smoke/native-version.json': self.native_version,
            'installed-smoke/log/idea.log': 'INFO clean synthetic startup\n',
            'go-race.jsonl': self.race, 'go-vet.log': '', 'kotlin-test-results/TEST-Fixture.xml': self.xml}
        self.run = {'id': 41, 'run_attempt': 1, 'status': 'completed', 'conclusion': 'success',
            'path': '.github/workflows/release.yml', 'event': 'workflow_dispatch', 'head_branch': 'main', 'head_sha': self.source}
        self.release = {'id': 53, 'draft': True, 'published_at': None, 'tag_name': self.tag, 'assets': []}
        self.refresh()

    def git(self, *args):
        return subprocess.check_output(['git', '-c', 'core.hooksPath=' + str(self.hooks), '-c', 'commit.gpgsign=false',
            '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', *args], cwd=self.repo, stderr=subprocess.DEVNULL).decode().strip()

    def common_release(self, platform):
        path = self.directory / self.names[platform]
        return {'schemaVersion': 1, 'version': self.version, 'sourceCommit': self.source,
            'target': 'windows-x64' if platform == 'windows' else 'macos-arm64',
            'installer': path.name, 'installerBytes': path.stat().st_size, 'installerSha256': a.sha(path)}

    def write_proof(self, name, prefix, members):
        with tarfile.open(self.directory / name, 'w:gz') as archive:
            for relative, value in members.items():
                data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
                item = tarfile.TarInfo(prefix + relative); item.size = len(data); archive.addfile(item, io.BytesIO(data))

    def refresh(self):
        # CI's actual Mac layout contains a version-specific nested cohort, no native-observations.json.
        self.write_proof('macos-provenance.tar.gz', 'mac-provenance/' + self.version + '-synthetic/', self.mac)
        self.write_proof('windows-provenance.tar.gz', 'windows-provenance/', self.windows)
        files = sorted(p for p in self.directory.iterdir() if p.name in {*self.names.values(), 'macos-provenance.tar.gz', 'windows-provenance.tar.gz'})
        (self.directory / 'SHA256SUMS').write_text(''.join(a.sha(p) + '  ' + p.name + '\n' for p in files))
        files.append(self.directory / 'SHA256SUMS')
        self.release['assets'] = [{'name': p.name, 'state': 'uploaded', 'size': p.stat().st_size, 'digest': 'sha256:' + a.sha(p)} for p in files]
        (self.directory / 'release.json').write_text(json.dumps(self.release))
        (self.directory / 'run.json').write_text(json.dumps(self.run))

    def audit(self, allow_draft=True):
        return a.audit(self.directory, allow_draft=allow_draft, repo_root=self.repo)

    def test_nested_ci_proofs_and_actual_five_bytes_pass_without_manual_gui_evidence(self):
        result = self.audit()
        self.assertEqual(result['windows']['tests']['tests'], 2)
        self.assertEqual(result['macos']['tests']['skipped'], 0)
        self.assertEqual(result['sourceDigest'], self.capture['digest'])
        self.assertTrue((self.directory / 'independent-draft-audit.json').is_file())
        self.assertIn('not full interactive parity', result['macos']['native']['scope'])
        self.assertNotIn('native-observations.json', self.mac)

    def test_explicit_private_and_public_modes_cannot_accept_opposite_state(self):
        with self.assertRaises(ValueError): self.audit(False)
        self.release['draft'] = False; self.release['published_at'] = '2026-11-14T00:00:00Z'; self.refresh()
        with self.assertRaises(ValueError): self.audit(True)
        self.assertEqual(self.audit(False)['releaseState'], 'public')

    def test_wrong_source_tag_or_push_branch_is_rejected(self):
        for mutate in ('source', 'push_branch', 'producer_failure'):
            with self.subTest(mutate=mutate):
                previous = copy.deepcopy(self.run)
                if mutate == 'source': self.run['head_sha'] = 'f' * 40
                elif mutate == 'push_branch': self.run.update(event='push', head_branch='wrong-tag')
                else: self.run['conclusion'] = 'failure'
                self.refresh()
                with self.assertRaises(ValueError): self.audit()
                self.run = previous

    def test_missing_actual_asset_or_changed_installer_bytes_is_rejected(self):
        exe = self.directory / self.names['windows']; original = exe.read_bytes()
        exe.unlink()
        with self.assertRaises(ValueError): self.audit()
        exe.write_bytes(b'changed')
        with self.assertRaises(ValueError): self.audit()
        exe.write_bytes(original)

    def test_skipped_raw_case_and_different_identity_are_rejected(self):
        for xml in [self.xml.replace('skipped="0"', 'skipped="1"').replace('<testcase classname="Fixture" name="first"/>', '<testcase classname="Fixture" name="first"><skipped/></testcase>'), self.xml.replace('name="second"', 'name="different"')]:
            with self.subTest(xml=xml):
                self.windows['kotlin-test-results/TEST-Fixture.xml'] = xml; self.refresh()
                with self.assertRaises(ValueError): self.audit()

    def test_real_cold_version_severe_and_missing_idea_log_are_rejected(self):
        self.native_version['stderr'] = 'SEVERE: class initialization requested service'; self.refresh()
        with self.assertRaises(ValueError): self.audit()
        self.native_version['stderr'] = ''; self.windows['installed-smoke/log/idea.log'] = 'ERROR native startup failure'; self.refresh()
        with self.assertRaises(ValueError): self.audit()
        del self.windows['installed-smoke/log/idea.log']; self.refresh()
        with self.assertRaises(KeyError): self.audit()

    def test_missing_fresh_vet_log_and_wrong_mac_digest_or_race_summary_are_rejected(self):
        previous = copy.deepcopy(self.mac)
        for mutate in ('missing_vet', 'digest', 'race_summary'):
            with self.subTest(mutate=mutate):
                self.mac = copy.deepcopy(previous)
                if mutate == 'missing_vet':
                    self.mac['go-checks.json']['logs'] = self.mac['go-checks.json']['logs'][:1]
                    self.mac['release.json']['goChecks'] = self.mac['go-checks.json']
                elif mutate == 'digest': self.mac['release.json']['sourceDigest'] = 'f' * 64
                else:
                    self.mac['go-checks.json']['raceSummary']['testPassEvents'] = 3
                    self.mac['release.json']['goChecks'] = self.mac['go-checks.json']
                self.refresh()
                with self.assertRaises(ValueError): self.audit()

    def test_wrong_gui_frame_and_cached_race_are_rejected(self):
        self.smoke['nativeProjectStartup']['projectFrame']['class'] = 'unrelated'; self.refresh()
        with self.assertRaises(ValueError): self.audit()
        self.smoke['nativeProjectStartup']['projectFrame']['class'] = 'SunAwtFrame'; self.windows['go-race.jsonl'] = self.race + json.dumps({'Action': 'output', 'Package': 'pkg', 'Output': '(cached)'}) + '\n'; self.refresh()
        with self.assertRaises(ValueError): self.audit()

    def test_mac_missing_signature_or_modified_source_bytes_is_rejected(self):
        self.mac['release.json']['signatureVerified'] = False; self.refresh()
        with self.assertRaises(ValueError): self.audit()
        self.mac['release.json']['signatureVerified'] = True; self.refresh()
        (self.repo / 'engine/go.mod').write_text('changed worktree is not the captured commit\n')
        # Git capture intentionally binds immutable commit bytes, not an unrelated dirty working copy.
        self.assertEqual(self.audit()['sourceCommit'], self.source)
        self.mac['source-inputs.json'] = copy.deepcopy(self.capture); self.mac['source-inputs.json']['files'][0]['sha256'] = '0' * 64; self.refresh()
        with self.assertRaises(ValueError): self.audit()


if __name__ == '__main__': unittest.main()
