import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'publish_tested_installers.py'
spec = importlib.util.spec_from_file_location('tested_publish', SCRIPT)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class TestedInstallerPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.version = '0.1.0-preview.1'
        self.source = 'a' * 40
        self.repo = 'owner/repo'
        self.names = p.names(self.version)
        captured = {'schemaVersion': 1, 'algorithm': 'sha256', 'files': [{'path': 'engine/go.mod', 'sha256': 'b' * 64}]}
        captured['digest'] = hashlib.sha256(('b' * 64 + '  engine/go.mod\n').encode()).hexdigest()
        self.captured = captured
        xml = '<testsuite tests="2" failures="0" errors="0" skipped="0"/>'
        engine = {'exitCode': 0, 'testCount': 1, 'cached': False, 'test': 'TestBundledInstalledRuntime'}
        checks = {'kind': 'fresh', 'sourceDigest': captured['digest'], 'commands': [{'exitCode': 0}, {'exitCode': 0}], 'logs': [], 'raceSummary': {'packages': {'pkg': 'pass', 'cmd': 'skip'}}}
        self.mac_members = {
            'release.json': {'schemaVersion': 1, 'version': self.version, 'sourceCommit': self.source, 'sourceDirty': False,
                'sourceDigest': captured['digest'], 'target': 'macos-arm64', 'installer': self.names['mac'], 'installerBytes': 100,
                'installerSha256': 'c' * 64, 'imageVerified': True, 'mountedAndCopied': True, 'signatureVerified': True,
                'installedEngineSmoke': engine, 'goChecks': checks, 'installedApplication': '/owned/app'},
            'native-observations.json': {'status': 'pass-limited-installed-macos-scope', 'sourceCommit': self.source,
                'sourceDigest': captured['digest'], 'installerSha256': 'c' * 64, 'installedApplication': '/owned/app', 'knownOwnedPids': [{'pid': 12}], 'debugTargetPids': [], 'nativePid': 12, 'cleanup': {'knownPidsGone': {'12': True}, 'logErrors': []}},
            'source-inputs.json': captured, 'go-checks.json': checks, 'kotlin-test-results/TEST-one.xml': xml}
        self.mac = self.root / self.names['macProof']
        self.write_mac()
        self.win = self.root / 'download'
        proof = self.win / 'windows-provenance'
        proof.mkdir(parents=True)
        executable = self.win / self.names['win']; executable.write_bytes(b'MZowned-test-installer')
        self.win_report = {'schemaVersion': 1, 'version': self.version, 'sourceCommit': self.source, 'target': 'windows-x64',
            'installer': self.names['win'], 'installerSha256': p.sha(executable), 'installerBytes': executable.stat().st_size,
            'installExitCode': 0, 'uninstallExitCode': 0, 'installedSmoke': 'installed-smoke/smoke.json'}
        self.write_win('release.json', self.win_report)
        self.write_win('assembly.json', {'application': 'owned', 'manifest': {'version': self.version, 'target': 'windows-x64',
            'sourceSdkUnchanged': True, 'buildInputs': captured, 'engineBuildInformation': 'vcs.revision=' + self.source + '\nvcs.modified=false'}})
        self.smoke = {'status': 'pass', 'version': self.version, 'installedFilesVerified': 17,
            'nativeVersion': {'code': 0, 'stdout': '2026.1.4', 'stderr': 'WARN allowed'},
            'nativeProjectStartup': {'exitCode': 0, 'exitCommand': {'code': 0}, 'projectFrame': {'class': 'SunAwtFrame', 'title': 'owned project'}},
            'engine': engine, 'toolVersions': [{'code': 0, 'stdout': v, 'stderr': ''} for v in ['go1.26.10', 'v0.24.0', '1.27.2']]}
        self.write_win('installed-smoke/smoke.json', self.smoke)
        (proof / 'kotlin-test-results').mkdir(); (proof / 'kotlin-test-results/TEST-one.xml').write_text(xml)
        (proof / 'go-race.jsonl').write_text('\n'.join(json.dumps(x) for x in [
            {'Action': 'pass', 'Package': 'pkg', 'Test': 'TestOwned'}, {'Action': 'pass', 'Package': 'pkg'}, {'Action': 'skip', 'Package': 'cmd'}]) + '\n')
        # No empty go-vet.log is produced by PowerShell's empty Tee pipeline.
        self.run = {'id': 42, 'path': '.github/workflows/release.yml', 'head_sha': self.source, 'run_attempt': 1,
            'repository': {'full_name': self.repo, 'id': 7}, 'head_repository': {'full_name': self.repo, 'id': 7}, 'status': 'in_progress'}
        self.jobs = {'total_count': 1, 'jobs': [{'name': 'windows', 'run_id': 42, 'head_sha': self.source, 'status': 'completed', 'conclusion': 'success', 'run_attempt': 1}]}
        self.artifacts = {'total_count': 1, 'artifacts': [{'id': 8, 'name': 'windows-x64', 'expired': False, 'digest': 'sha256:' + 'd' * 64, 'workflow_run': {'id': 42, 'head_sha': self.source, 'repository_id': 7, 'head_repository_id': 7}}]}
        self.release = {'id': 5, 'draft': True, 'tag_name': 'v' + self.version, 'body': '审阅过的中文说明', 'assets': [
            self.asset(self.names['mac'], 100, 'c' * 64), self.asset(self.names['macProof'], self.mac.stat().st_size, p.sha(self.mac))]}

    def asset(self, name, size, digest):
        return {'name': name, 'size': size, 'digest': 'sha256:' + digest, 'state': 'uploaded'}

    def write_mac(self, extra=None):
        with tarfile.open(self.mac, 'w:gz') as archive:
            for name, value in self.mac_members.items():
                data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
                item = tarfile.TarInfo('mac-provenance/' + name); item.size = len(data)
                archive.addfile(item, io.BytesIO(data))
            if extra:
                archive.addfile(extra)

    def write_win(self, name, value):
        path = self.win / 'windows-provenance' / name; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding='utf-8')

    def context(self):
        return p.context(self.repo, self.version, 42, self.source, self.run, self.jobs, self.artifacts, self.release)

    def test_prepares_three_assets_and_publishes_only_exact_five_receipts(self):
        receipt = p.prepare(self.context(), self.win, self.mac, self.root / 'assets')
        self.assertEqual(set(x.name for x in (self.root / 'assets').iterdir()), {self.names[k] for k in ['win', 'winProof', 'sums']})
        self.assertEqual(len((self.root / 'assets/SHA256SUMS').read_text().splitlines()), 4)
        self.assertEqual(receipt['proofFacts']['windowsVetEvidence'], 'successful-driver-guard-no-output-file')
        final = copy.deepcopy(self.release)
        final['assets'] = [self.asset(name, value['bytes'], value['sha256']) for name, value in receipt['assets'].items()]
        p.verify_private(receipt, self.release)
        p.verify_release(receipt, final)
        for mutate in ['public', 'body', 'extra', 'digest', 'state']:
            with self.subTest(mutate=mutate):
                bad = copy.deepcopy(final)
                if mutate == 'public': bad['draft'] = False
                if mutate == 'body': bad['body'] = 'changed'
                if mutate == 'extra': bad['assets'].append(self.asset('extra', 1, 'f' * 64))
                if mutate == 'digest': bad['assets'][0]['digest'] = 'sha256:' + 'f' * 64
                if mutate == 'state': bad['assets'][0]['state'] = 'new'
                with self.assertRaises(ValueError): p.verify_release(receipt, bad)
                if mutate == 'public':
                    with self.assertRaises(ValueError): p.verify_private(receipt, bad)

    def test_rejects_wrong_run_repository_source_job_and_artifact(self):
        for mutate in ['repo', 'path', 'source', 'job', 'artifact', 'truncated']:
            with self.subTest(mutate=mutate):
                saved = copy.deepcopy((self.run, self.jobs, self.artifacts))
                if mutate == 'repo': self.run['head_repository']['full_name'] = 'fork/repo'
                if mutate == 'path': self.run['path'] = '.github/workflows/other.yml'
                if mutate == 'source': self.run['head_sha'] = 'f' * 40
                if mutate == 'job': self.jobs['jobs'][0]['conclusion'] = 'cancelled'
                if mutate == 'artifact': self.artifacts['artifacts'][0]['expired'] = True
                if mutate == 'truncated': self.jobs['total_count'] = 2
                with self.assertRaises(ValueError): self.context()
                self.run, self.jobs, self.artifacts = saved

    def test_requires_complete_mac_assets_and_native_cleanup(self):
        self.release['assets'][0]['state'] = 'new'
        with self.assertRaises(ValueError): self.context()
        self.release['assets'][0]['state'] = 'uploaded'
        self.mac_members['native-observations.json']['cleanup']['knownPidsGone']['12'] = False
        self.write_mac(); self.release['assets'][1] = self.asset(self.names['macProof'], self.mac.stat().st_size, p.sha(self.mac))
        with self.assertRaises(ValueError): p.validate_proofs(self.context(), self.win, self.mac)

    def test_rejects_unsafe_tar_without_extracting_anything(self):
        for name, kind in [('mac-provenance/../escape', tarfile.REGTYPE), ('mac-provenance/link', tarfile.SYMTYPE), ('mac-provenance/release.json', tarfile.REGTYPE)]:
            with self.subTest(name=name):
                item = tarfile.TarInfo(name); item.type = kind; item.linkname = '/outside'
                self.write_mac(item)
                with self.assertRaises(ValueError): p.MacProof(self.mac)
        self.assertFalse((self.root / 'escape').exists())

    def test_rejects_wrong_installer_bytes_uninstall_and_source_capture(self):
        ctx = self.context()
        (self.win / self.names['win']).write_bytes(b'wrong')
        with self.assertRaises(ValueError): p.prepare(ctx, self.win, self.mac, self.root / 'wrong-output')
        self.win_report['uninstallExitCode'] = 1; self.write_win('release.json', self.win_report)
        with self.assertRaises(ValueError): p.validate_proofs(ctx, self.win, self.mac)
        self.win_report['uninstallExitCode'] = 0; self.write_win('release.json', self.win_report)
        assembly = p.load(self.win / 'windows-provenance/assembly.json'); assembly['manifest']['buildInputs']['digest'] = 'f' * 64
        self.write_win('assembly.json', assembly)
        with self.assertRaises(ValueError): p.validate_proofs(ctx, self.win, self.mac)

    def test_rejects_bad_smoke_and_missing_go_results(self):
        ctx = self.context(); self.smoke['engine']['cached'] = True; self.write_win('installed-smoke/smoke.json', self.smoke)
        with self.assertRaises(ValueError): p.validate_proofs(ctx, self.win, self.mac)
        self.smoke['engine']['cached'] = False; self.write_win('installed-smoke/smoke.json', self.smoke)
        (self.win / 'windows-provenance/go-race.jsonl').write_text('{}\n')
        with self.assertRaises((ValueError, KeyError)): p.validate_proofs(ctx, self.win, self.mac)

    def test_legacy_complete_private_draft_still_routes_to_manual_validation(self):
        receipt = p.prepare(self.context(), self.win, self.mac, self.root / 'manual-first')
        final = copy.deepcopy(self.release)
        final['assets'] = [self.asset(name, value['bytes'], value['sha256']) for name, value in receipt['assets'].items()]
        self.release = final
        resumed = self.context()
        self.assertNotEqual(resumed.get('mode'), 'ci-proofs')
        self.assertEqual(resumed['artifactId'], 8)
        self.assertIn(self.names['mac'], resumed['macAssets'])
        p.validate_proofs(resumed, self.win, self.mac)

    def test_git_capture_covers_the_exact_release_commit_source_set(self):
        import subprocess
        repo = self.root / 'repo'; repo.mkdir(); (repo / 'engine').mkdir(); (repo / 'scripts').mkdir()
        (repo / 'product').mkdir()
        (repo / 'engine/go.mod').write_text('module owned\n')
        (repo / 'scripts/helper.py').write_text('owned = True\n')
        (repo / 'product/file-associations.json').write_text('{"extensions": ["go"]}\n')
        (repo / 'README.md').write_text('not a build input\n')
        hooks = self.root / 'empty-hooks'; hooks.mkdir()
        def git(*args):
            return subprocess.check_output(['git', '-c', 'core.hooksPath=' + str(hooks), '-c', 'commit.gpgsign=false', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', *args], cwd=repo, stderr=subprocess.DEVNULL).decode().strip()
        git('init'); git('add', '.'); git('commit', '-m', 'owned fixture'); source = git('rev-parse', 'HEAD')
        entries = [{'path': name, 'sha256': p.sha(repo / name)} for name in ['engine/go.mod', 'product/file-associations.json', 'scripts/helper.py']]
        p.git_capture(repo, source, {'files': entries})
        with self.assertRaises(ValueError):
            p.git_capture(repo, source, {'files': [entry for entry in entries if entry['path'] != 'product/file-associations.json']})
        altered = copy.deepcopy(entries)
        altered[1]['sha256'] = 'f' * 64
        with self.assertRaises(ValueError): p.git_capture(repo, source, {'files': altered})
        with self.assertRaises(ValueError): p.git_capture(repo, source, {'files': entries[:1]})
        entries[0]['sha256'] = 'f' * 64
        with self.assertRaises(ValueError): p.git_capture(repo, source, {'files': entries})


class CiInstallerPublicationTest(unittest.TestCase):
    """Synthetic CI-shaped fixtures test fail-closed gates, never real release acceptance."""
    def setUp(self):
        fixture_spec = importlib.util.spec_from_file_location('audit_fixtures', Path(__file__).with_name('test_audit_public_release.py'))
        fixture_module = importlib.util.module_from_spec(fixture_spec); fixture_spec.loader.exec_module(fixture_module)
        self.f = fixture_module.ReleaseAuditTest('test_nested_ci_proofs_and_actual_five_bytes_pass_without_manual_gui_evidence')
        self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.root = self.f.root; self.names = p.names(self.f.version); self.repo = 'owner/repo'
        self.f.windows['assembly.json']['manifest'].update(version=self.f.version, target='windows-x64')
        self.f.release['body'] = 'Reviewed bilingual fixture notes'
        self.run = {**self.f.run, 'repository': {'full_name': self.repo, 'id': 7}, 'head_repository': {'full_name': self.repo, 'id': 7}}
        self.jobs = {'total_count': 2, 'jobs': [{'id': identity, 'name': name, 'run_id': self.run['id'], 'head_sha': self.f.source, 'run_attempt': 1, 'status': 'completed', 'conclusion': 'success', 'started_at': '2026-11-14T00:00:00Z', 'completed_at': '2026-11-14T01:00:00Z'} for name, identity in [('windows', 8), ('macos', 9)]]}
        self.artifacts = {'total_count': 2, 'artifacts': [{'id': identity, 'name': name, 'expired': False, 'digest': 'sha256:' + digit * 64, 'size_in_bytes': 100, 'created_at': '2026-11-14T00:50:00Z', 'workflow_run': {'id': self.run['id'], 'head_sha': self.f.source, 'repository_id': 7, 'head_repository_id': 7}} for name, identity, digit in [('windows-x64', 80, 'd'), ('macos-arm64', 90, 'e')]]}
        self.merged = self.root / 'merged'; self.key = (self.repo, self.f.version, self.f.source)
        self.previous_cohorts = copy.deepcopy(p.CI_COHORTS); self.addCleanup(lambda: (p.CI_COHORTS.clear(), p.CI_COHORTS.update(self.previous_cohorts)))
        self.refresh()

    def refresh(self):
        import shutil
        self.f.refresh()
        if self.merged.exists(): shutil.rmtree(self.merged)
        self.merged.mkdir()
        for key in ['mac', 'win']: shutil.copyfile(self.f.directory / self.names[key], self.merged / self.names[key])
        for key in ['macProof', 'winProof']:
            with tarfile.open(self.f.directory / self.names[key], 'r:gz') as archive:
                for member in archive.getmembers():
                    path = self.merged / member.name; path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(archive.extractfile(member).read())
        self.initial = copy.deepcopy(self.f.release)
        self.initial['assets'] = [a for a in self.initial['assets'] if a['name'] in {self.names[k] for k in ['macProof', 'winProof', 'sums']}]
        assets = {a['name']: {'bytes': a['size'], 'sha256': a['digest'][7:]} for a in self.f.release['assets']}
        p.CI_COHORTS[self.key] = {'runId': self.run['id'], 'runAttempt': 1, 'jobs': {'windows': 8, 'macos': 9}, 'artifacts': {a['name']: {k: a[k] for k in ['id', 'digest', 'size_in_bytes']} for a in self.artifacts['artifacts']}, 'sourceInputs': 1, 'sourceDigest': self.f.capture['digest'], 'tests': {'reports': 1, 'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0}, 'assets': assets}

    def context(self):
        return p.context(self.repo, self.f.version, self.run['id'], self.f.source, self.run, self.jobs, self.artifacts, self.initial)

    def prepare(self, output='prepared'):
        return p.prepare_ci(self.context(), self.merged, self.f.directory, self.root / output, self.f.repo)

    def test_nested_ci_mode_uses_both_ids_and_preserves_exact_uploaded_small_bytes(self):
        ctx = self.context(); self.assertEqual(ctx['mode'], 'ci-proofs'); self.assertEqual(ctx['artifactIds'], '80,90')
        self.assertNotIn('native-observations.json', self.f.mac)
        receipt = self.prepare(); self.assertEqual(receipt['assets'], p.CI_COHORTS[self.key]['assets'])
        for key in ['macProof', 'winProof', 'sums']:
            self.assertEqual((Path(receipt['assetDirectory']) / self.names[key]).read_bytes(), (self.f.directory / self.names[key]).read_bytes())
        final = copy.deepcopy(self.f.release); p.verify_release(receipt, final)

    def test_whole_producer_same_attempt_and_exact_actual_jobs_artifacts_are_required(self):
        for mutate in ['producer', 'mac_job', 'attempt', 'job_id', 'artifact_id', 'artifact_digest', 'artifact_time', 'missing_artifact', 'unreviewed_source']:
            with self.subTest(mutate=mutate):
                saved = copy.deepcopy((self.run, self.jobs, self.artifacts, self.initial))
                if mutate == 'producer': self.run['conclusion'] = 'failure'
                elif mutate == 'mac_job': self.jobs['jobs'][1]['conclusion'] = 'failure'
                elif mutate == 'attempt': self.jobs['jobs'][1]['run_attempt'] = 2
                elif mutate == 'job_id': self.jobs['jobs'][1]['id'] = 99
                elif mutate == 'artifact_id': self.artifacts['artifacts'][1]['id'] = 99
                elif mutate == 'artifact_digest': self.artifacts['artifacts'][1]['digest'] = 'sha256:' + 'f' * 64
                elif mutate == 'artifact_time': self.artifacts['artifacts'][1]['created_at'] = '2026-11-14T02:00:00Z'
                elif mutate == 'missing_artifact': self.artifacts['artifacts'].pop(); self.artifacts['total_count'] = 1
                else: p.CI_COHORTS.pop(self.key)
                with self.assertRaises(ValueError): self.context()
                self.run, self.jobs, self.artifacts, self.initial = saved
                if mutate == 'unreviewed_source': self.refresh()

    def test_action_success_cannot_hide_missing_or_changed_merged_proof_or_installer(self):
        for mutate in ['missing_mac', 'changed_win', 'missing_proof_file', 'changed_proof_file']:
            with self.subTest(mutate=mutate):
                self.refresh()
                if mutate == 'missing_mac': (self.merged / self.names['mac']).unlink()
                elif mutate == 'changed_win': (self.merged / self.names['win']).write_bytes(b'changed')
                elif mutate == 'missing_proof_file': (self.merged / 'windows-provenance/go-race.jsonl').unlink()
                else: (self.merged / 'windows-provenance/go-race.jsonl').write_bytes(b'changed')
                with self.assertRaises(ValueError): self.prepare()

    def test_original_small_bytes_and_notes_cannot_change(self):
        self.initial['assets'][0]['digest'] = 'sha256:' + 'f' * 64
        with self.assertRaises(ValueError): self.context()
        self.refresh(); receipt = self.prepare(); final = copy.deepcopy(self.f.release)
        for mutate in ['notes', 'proof', 'public']:
            bad = copy.deepcopy(final)
            if mutate == 'notes': bad['body'] = 'unreviewed'
            elif mutate == 'proof': next(a for a in bad['assets'] if a['name'] == self.names['winProof'])['digest'] = 'sha256:' + 'f' * 64
            else: bad['draft'] = False
            with self.assertRaises(ValueError): p.verify_release(receipt, bad)

    def test_skips_missing_reports_identities_and_cold_errors_remain_blocking(self):
        for mutate in ['skip', 'missing_both_reports', 'wrong_identity', 'cold_severe', 'wrong_source']:
            with self.subTest(mutate=mutate):
                previous = copy.deepcopy((self.f.mac, self.f.windows, self.f.native_version))
                if mutate == 'skip': self.f.windows['kotlin-test-results/TEST-Fixture.xml'] = self.f.xml.replace('skipped="0"', 'skipped="1"').replace('name="first"/>', 'name="first"><skipped/></testcase>')
                elif mutate == 'missing_both_reports':
                    del self.f.mac['kotlin-test-results/TEST-Fixture.xml']; del self.f.windows['kotlin-test-results/TEST-Fixture.xml']
                elif mutate == 'wrong_identity': self.f.windows['kotlin-test-results/TEST-Fixture.xml'] = self.f.xml.replace('name="second"', 'name="changed"')
                elif mutate == 'cold_severe':
                    self.f.windows['installed-smoke/native-version.json']['stderr'] = 'SEVERE startup initialization'
                    self.f.windows['installed-smoke/smoke.json']['nativeVersion']['stderr'] = 'SEVERE startup initialization'
                else: self.f.mac['release.json']['sourceCommit'] = '0' * 40
                self.refresh()
                with self.assertRaises((ValueError, KeyError)): self.prepare()
                self.f.mac, self.f.windows, self.f.native_version = previous
                self.f.smoke = self.f.windows['installed-smoke/smoke.json']

    def test_final_gate_rechecks_real_api_data_and_runs_independent_private_audit(self):
        from unittest import mock
        receipt = self.prepare(); receipt_path = self.root / 'receipt.json'; receipt_path.write_text(json.dumps(receipt))
        final = copy.deepcopy(self.f.release); original = p.subprocess.check_output; calls = []
        def api_or_git(command, **kwargs):
            if command[:2] != ['gh', 'api']: return original(command, **kwargs)
            calls.append(command[2]); endpoint = command[2]
            if '/releases/' in endpoint: value = final
            elif '/jobs?' in endpoint: value = self.jobs
            elif '/artifacts?' in endpoint: value = self.artifacts
            else: value = self.run
            return json.dumps(value).encode()
        with mock.patch.object(p.subprocess, 'check_output', side_effect=api_or_git):
            result = p.independent_ci_audit(receipt, final, receipt_path, self.f.repo)
        self.assertEqual(result['releaseState'], 'private-draft'); self.assertEqual(len(calls), 4)
        self.assertTrue((self.root / 'independent-draft-audit.json').is_file())
        self.assertTrue((self.root / 'independent-release-api.json').is_file())


if __name__ == '__main__':
    unittest.main()
