#!/usr/bin/env python3
"""Fail-closed receipts for publishing already tested installers; never creates tags/releases."""
import argparse
import errno
import os
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def names(version):
    return {'mac': f'open-Goland-{version}-macos-arm64.dmg',
            'win': f'open-Goland-{version}-windows-x64-setup.exe',
            'macProof': 'macos-provenance.tar.gz', 'winProof': 'windows-provenance.tar.gz', 'sums': 'SHA256SUMS'}


def validate_inputs(repo, version, run_id, source):
    require(re.fullmatch(r'[^/\s]+/[^/\s]+', repo), 'Invalid repository')
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?', version), 'Invalid version')
    require(type(run_id) is int and run_id > 0, 'Invalid run ID')
    require(re.fullmatch(r'[0-9a-f]{40}', source), 'A full source SHA is required')


def asset_map(release):
    assets = release['assets']
    result = {item['name']: item for item in assets}
    require(len(result) == len(assets), 'Duplicate release asset names')
    return result


def body_sha(release):
    require(isinstance(release.get('body'), str) and release['body'].strip(), 'Reviewed release notes must already exist')
    return hashlib.sha256(release['body'].encode('utf-8')).hexdigest()


def uploaded(asset):
    require(asset.get('state') == 'uploaded' and type(asset.get('size')) is int and asset['size'] > 0, 'Incomplete asset')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', asset.get('digest', '')), 'GitHub asset SHA-256 digest is required')
    return {'bytes': asset['size'], 'sha256': asset['digest'][7:]}


def context(repo, version, run_id, source, run, jobs, artifacts, release):
    validate_inputs(repo, version, run_id, source)
    require(run['id'] == run_id and run['repository']['full_name'] == repo and run['head_repository']['full_name'] == repo,
            'Run repository/ID mismatch')
    require(run['path'] == '.github/workflows/release.yml' and run['head_sha'] == source, 'Run workflow/source mismatch')
    require(jobs['total_count'] == len(jobs['jobs']) and artifacts['total_count'] == len(artifacts['artifacts']), 'Truncated API page')
    windows = [j for j in jobs['jobs'] if j['name'] == 'windows']
    require(len(windows) == 1, 'Exactly one latest Windows job is required')
    job = windows[0]
    require(job['run_id'] == run_id and job['head_sha'] == source and job['status'] == 'completed' and job['conclusion'] == 'success',
            'The source Windows job must have succeeded')
    require(job.get('run_attempt', run['run_attempt']) == run['run_attempt'], 'Wrong job attempt')
    candidates = [a for a in artifacts['artifacts'] if a['name'] == 'windows-x64']
    require(len(candidates) == 1 and candidates[0]['expired'] is False, 'Exactly one unexpired Windows artifact is required')
    artifact = candidates[0]
    require(artifact['workflow_run']['id'] == run_id and artifact['workflow_run']['head_sha'] == source and
            artifact['workflow_run']['repository_id'] == run['repository']['id'] and artifact['workflow_run']['head_repository_id'] == run['head_repository']['id'], 'Artifact source mismatch')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', artifact.get('digest', '')), 'Artifact API digest is required')
    require(release['draft'] is True and release['tag_name'] == 'v' + version, 'An existing private version draft is required')
    expected = names(version)
    assets = asset_map(release)
    require(set(assets).issubset(expected.values()), 'Unexpected draft assets')
    has_ci_proofs = {expected[key] for key in ['macProof', 'winProof', 'sums']}.issubset(assets)
    if (repo, version, source) in CI_COHORTS:
        require(has_ci_proofs, 'Reviewed CI promotion requires its three original proof/checksum assets')
        return ci_context(repo, version, run_id, source, run, jobs, artifacts, release, assets)
    if has_ci_proofs and expected['mac'] not in assets:
        # An unknown CI-only draft cannot fall back to the legacy manual path.
        return ci_context(repo, version, run_id, source, run, jobs, artifacts, release, assets)
    require({expected[key] for key in ['mac', 'macProof']}.issubset(assets), 'Both uploaded Mac assets must exist before publication')
    mac = {expected[key]: uploaded(assets[expected[key]]) for key in ['mac', 'macProof']}
    return {'schema': 1, 'repository': repo, 'version': version, 'tag': 'v' + version, 'sourceSha': source,
            'runId': run_id, 'runAttempt': run['run_attempt'], 'artifactId': artifact['id'], 'artifactDigest': artifact['digest'], 'releaseId': release['id'],
            'bodySha256': body_sha(release), 'macAssets': mac}



# Explicitly reviewed CI cohorts. Future versions need their own reviewed inputs;
# this control plane must never infer a releasable cohort from equal partial counts.
CI_COHORTS = {
    ('shubolab/opengoland', '0.1.0-preview.20261114.2', '07ae69f3b2bbc36f3221ff08e8fe9046f199b624'): {
        'runId': 43543358207, 'runAttempt': 1,
        'jobs': {'windows': 132288803301, 'macos': 132288803355},
        'artifacts': {
            'windows-x64': {'id': 14095076153, 'digest': 'sha256:f436b31fddd041a75dcbe8c19fdc5791e7f4bac83bb3b1f8f1ca29dbf9621cc1', 'size_in_bytes': 877691674},
            'macos-arm64': {'id': 14094508173, 'digest': 'sha256:73fe37baa29066844d2e0c4c3c330a46152c6e3025363766bd61760ecfe41aef', 'size_in_bytes': 940021655},
        },
        'sourceInputs': 587, 'sourceDigest': '6a6111a516d49a65aee5292ac75d523bc96cc1b1b06500596f1a7a257b8d8655',
        'tests': {'reports': 174, 'tests': 969, 'failures': 0, 'errors': 0, 'skipped': 0},
        'assets': {
            'open-Goland-0.1.0-preview.20261114.2-macos-arm64.dmg': {'bytes': 1022010762, 'sha256': 'dad7cfb9e1c0b5bdd6d2fd1819de4f3e4eecb21aec0a59fc069462952e9d560e'},
            'open-Goland-0.1.0-preview.20261114.2-windows-x64-setup.exe': {'bytes': 871230954, 'sha256': '02dc6790866a6b541b5a71907eb570b488a219167d6742ea918ad8516be93515'},
            'macos-provenance.tar.gz': {'bytes': 4141351, 'sha256': '84dd1a2f06697bebba876911d4c0116a533ee2dc39c3891e38affc971ce94241'},
            'windows-provenance.tar.gz': {'bytes': 6469735, 'sha256': '7d3e18e1b7207842e90ff8396e2722fc7a60d08cf66f019469ef051b25df9a6e'},
            'SHA256SUMS': {'bytes': 426, 'sha256': 'c2bf5f49012d2e923dcfa4333c6767e28b46278eba7837091cdd925c5c563ac1'},
        },
    },
}


def reviewed_ci_cohort(repo, version, source):
    value = CI_COHORTS.get((repo, version, source))
    require(value is not None, 'This CI installer cohort has not been explicitly reviewed')
    return value


def link_or_copy(source, target):
    try:
        os.link(source, target)
    except OSError as error:
        if error.errno != errno.EXDEV: raise
        shutil.copyfile(source, target)


def ci_context(repo, version, run_id, source, run, jobs, artifacts, release, assets):
    """CI mode keeps uploaded original proofs/checksums; downloads both tested artifacts."""
    cohort = reviewed_ci_cohort(repo, version, source)
    require(run_id == cohort['runId'] and run['run_attempt'] == cohort['runAttempt'], 'Wrong reviewed CI producer attempt')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'The entire source producer must have succeeded')
    require(run.get('event') in {'push', 'workflow_dispatch'}, 'Unexpected source producer event')
    selected_jobs = {}; selected_artifacts = {}
    for platform, name in [('windows', 'windows-x64'), ('macos', 'macos-arm64')]:
        matches = [j for j in jobs['jobs'] if j['name'] == platform]
        require(len(matches) == 1, 'Exactly one latest successful ' + platform + ' job is required')
        job = matches[0]
        require(job['run_id'] == run_id and job['head_sha'] == source and job['status'] == 'completed' and job['conclusion'] == 'success' and job.get('run_attempt') == run['run_attempt'], 'Wrong or unsuccessful CI platform job')
        matches = [a for a in artifacts['artifacts'] if a['name'] == name]
        require(len(matches) == 1 and matches[0]['expired'] is False, 'Exactly one unexpired CI artifact per platform is required')
        artifact = matches[0]; producer = artifact['workflow_run']
        require(producer['id'] == run_id and producer['head_sha'] == source and producer['repository_id'] == run['repository']['id'] and producer['head_repository_id'] == run['head_repository']['id'], 'CI artifact source mismatch')
        require(re.fullmatch(r'sha256:[0-9a-f]{64}', artifact.get('digest', '')), 'CI artifact API digest is required')
        require(job['started_at'] <= artifact['created_at'] <= job['completed_at'], 'CI artifact was not created by the selected execution')
        require(job['id'] == cohort['jobs'][platform], 'Wrong reviewed actual CI job')
        require(all(artifact[key] == value for key, value in cohort['artifacts'][name].items()), 'Wrong reviewed actual CI artifact')
        selected_jobs[platform] = {key: job[key] for key in ['id', 'run_attempt', 'started_at', 'completed_at']}
        selected_artifacts[name] = {key: artifact[key] for key in ['id', 'digest', 'size_in_bytes', 'created_at']}
    expected = names(version)
    fixed = {expected[key]: uploaded(assets[expected[key]]) for key in ['macProof', 'winProof', 'sums']}
    require(fixed == {name: cohort['assets'][name] for name in fixed}, 'Original CI proofs/checksums differ from reviewed bytes')
    for name, asset in assets.items(): require(uploaded(asset) == cohort['assets'][name], 'Existing CI draft asset differs from reviewed bytes')
    # download-artifact v4 accepts a comma-separated list, with merge-multiple in the existing workflow.
    ids = ','.join(str(selected_artifacts[name]['id']) for name in ['windows-x64', 'macos-arm64'])
    return {'schema': 1, 'mode': 'ci-proofs', 'repository': repo, 'version': version, 'tag': 'v' + version, 'sourceSha': source,
            'runId': run_id, 'runAttempt': run['run_attempt'], 'artifactIds': ids,
            'artifactId': selected_artifacts['windows-x64']['id'], 'producerJobs': selected_jobs, 'producerArtifacts': selected_artifacts,
            'releaseId': release['id'], 'bodySha256': body_sha(release), 'fixedAssets': fixed}


def checksum_map(path, expected_names):
    result = {}
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        digest, name = line.split(maxsplit=1); name = name.removeprefix('./')
        require(re.fullmatch(r'[0-9a-f]{64}', digest) and '/' not in name and '\\' not in name and name not in result, 'Invalid checksum entry')
        result[name] = digest
    require(set(result) == set(expected_names), 'Incomplete original checksum file')
    return result


def compare_proof_tree(archive, directory, prefix):
    """Compare original uploaded tar members to actual action-downloaded proof bytes; never re-tar."""
    require(directory.is_dir() and not directory.is_symlink(), 'Missing actual CI provenance directory')
    expected = {name: member for name, member in archive.members.items() if member.isfile()}
    actual = {}
    for path in directory.rglob('*'):
        require(not path.is_symlink() and (path.is_file() or path.is_dir()), 'Unsafe downloaded provenance path')
        if path.is_file(): actual[prefix + '/' + path.relative_to(directory).as_posix()] = path
    require(set(actual) == set(expected), 'Downloaded and original uploaded provenance entry sets differ')
    for name, member in expected.items():
        path = actual[name]
        require(path.stat().st_size == member.size, 'Downloaded provenance byte count differs')
        with archive.tar.extractfile(member) as stream:
            digest = hashlib.sha256()
            for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
        require(sha(path) == digest.hexdigest(), 'Downloaded provenance differs from original uploaded bytes: ' + name)


def download_ci_references(ctx, mac_path):
    directory = mac_path.parent / 'ci-reference'
    directory.mkdir(exist_ok=False)
    expected = names(ctx['version'])
    require(mac_path.is_file() and not mac_path.is_symlink(), 'Missing original uploaded Mac proof')
    shutil.copyfile(mac_path, directory / expected['macProof'])
    subprocess.run(['gh', 'release', 'download', ctx['tag'], '--repo', ctx['repository'], '--pattern', expected['winProof'], '--pattern', expected['sums'], '--dir', str(directory)], check=True)
    return directory


def prepare_ci(ctx, merged, references, output, repo_root=None):
    from audit_public_release import Proof
    expected = names(ctx['version']); source = ctx['sourceSha']; version = ctx['version']
    cohort = reviewed_ci_cohort(ctx['repository'], version, source)
    require(ctx['runId'] == cohort['runId'] and ctx['runAttempt'] == cohort['runAttempt'], 'Wrong reviewed CI prepare cohort')
    for name, record in ctx['fixedAssets'].items():
        path = references / name
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == record['bytes'] and sha(path) == record['sha256'], 'Original uploaded CI proof/checksum changed: ' + name)
    sums = checksum_map(references / expected['sums'], [expected[k] for k in ['mac', 'win', 'macProof', 'winProof']])
    for key in ['macProof', 'winProof']:
        require(sums[expected[key]] == ctx['fixedAssets'][expected[key]]['sha256'], 'Original proof/checksum binding mismatch')
    proofs = {}
    try:
        proofs['mac'] = Proof(references / expected['macProof'], 'mac-provenance')
        proofs['win'] = Proof(references / expected['winProof'], 'windows-provenance')
        for key, prefix in [('mac', 'mac-provenance'), ('win', 'windows-provenance')]:
            compare_proof_tree(proofs[key], merged / prefix, prefix)
        mac = proofs['mac'].json('release.json'); win = proofs['win'].json('release.json')
        for record, target, installer in [(mac, 'macos-arm64', expected['mac']), (win, 'windows-x64', expected['win'])]:
            require(record['schemaVersion'] == 1 and record['sourceCommit'] == source and record['version'] == version and record['target'] == target and record['installer'] == installer, 'CI release source/version mismatch')
            path = merged / installer
            require(path.is_file() and not path.is_symlink() and path.stat().st_size == record['installerBytes'] and sha(path) == record['installerSha256'] == sums[installer], 'Actual CI installer differs from original approved bytes')
        captured = proofs['mac'].json('source-inputs.json'); digest = source_capture(captured)
        require(len(captured['files']) == cohort['sourceInputs'] and digest == cohort['sourceDigest'], 'Incomplete reviewed source capture')
        require(mac['sourceDirty'] is False and mac['sourceDigest'] == digest and all(mac[key] is True for key in ['imageVerified', 'mountedAndCopied', 'signatureVerified']), 'Mac CI install/source checks incomplete')
        assembly = proofs['win'].json('assembly.json')['manifest']
        require(assembly['version'] == version and assembly['target'] == 'windows-x64' and assembly['sourceSdkUnchanged'] is True and source_capture(assembly['buildInputs']) == digest and assembly['buildInputs'] == captured, 'CI platform source captures differ')
        require('vcs.revision=' + source in assembly['engineBuildInformation'] and 'vcs.modified=false' in assembly['engineBuildInformation'], 'Windows CI engine source mismatch')
        if repo_root is not None: git_capture(repo_root, source, captured)
        require(type(win['installExitCode']) is int and win['installExitCode'] == 0 and type(win['uninstallExitCode']) is int and win['uninstallExitCode'] == 0, 'Windows CI install/uninstall failed')
        require(win['installedSmoke'] == 'installed-smoke/smoke.json', 'Unexpected installed smoke path')
        smoke = proofs['win'].json(win['installedSmoke']); native = smoke['nativeProjectStartup']
        require(smoke['status'] == 'pass' and smoke['version'] == version and smoke['installedFilesVerified'] > 0 and smoke['nativeVersion']['code'] == 0 and '2026.1' in smoke['nativeVersion']['stdout'] + smoke['nativeVersion']['stderr'], 'Windows CI installed/native version checks incomplete')
        require(native['exitCode'] == 0 and native['exitCommand']['code'] == 0 and native['projectFrame']['class'] == 'SunAwtFrame' and native['projectFrame']['title'], 'Windows CI actual project frame/exit incomplete')
        require(len(smoke['toolVersions']) == 3, 'Windows CI installed tools missing')
        for item, text in zip(smoke['toolVersions'], ['go1.26.10', 'v0.24.0', '1.27.2']):
            require(item['code'] == 0 and text in item['stdout'] + item['stderr'], 'Windows CI installed tool version mismatch')
        require(smoke['nativeVersion'] == proofs['win'].json('installed-smoke/native-version.json'), 'Windows CI native-version receipts differ')
        for text in [smoke['nativeVersion']['stdout'] + chr(10) + smoke['nativeVersion']['stderr'], proofs['win'].bytes('installed-smoke/log/idea.log').decode('utf-8-sig'), proofs['mac'].bytes('installed-native-version.log').decode('utf-8-sig')]:
            require(not re.search(r'\bERROR\b|\bSEVERE\b', text), 'CI cold startup logged ERROR/SEVERE')
        engine_smoke(smoke['engine']); engine_smoke(mac['installedEngineSmoke'])
        checks = proofs['mac'].json('go-checks.json')
        require(checks == mac['goChecks'] and checks['kind'] == 'fresh' and checks['sourceDigest'] == digest and len(checks['commands']) == 2 and all(x['exitCode'] == 0 for x in checks['commands']), 'Mac CI fresh Go checks incomplete')
        for item in checks['logs']:
            data = proofs['mac'].bytes(item['path'])
            require(len(data) == item['bytes'] and hashlib.sha256(data).hexdigest() == item['sha256'], 'Mac CI fresh Go log mismatch')
        require(not proofs['mac'].bytes('go-vet.log'), 'Mac CI vet output not empty')
        if proofs['win'].root + 'go-vet.log' in proofs['win'].members: require(not proofs['win'].bytes('go-vet.log'), 'Windows CI vet output not empty')
        mac_tests, mac_ids = proofs['mac'].tests(); win_tests, win_ids = proofs['win'].tests()
        require(mac_tests == cohort['tests'] and win_tests == cohort['tests'], 'Incomplete reviewed Kotlin report cohort')
        require(mac_tests == win_tests and mac_ids == win_ids, 'CI raw testcase identity sets differ')
        mac_race = proofs['mac'].race(); win_race = proofs['win'].race()
        require(mac_race['packages'] == win_race['packages'] == checks['raceSummary']['packages'], 'CI fresh race package results differ')
    finally:
        for archive in proofs.values(): archive.tar.close()
    output.mkdir(parents=True, exist_ok=False)
    for key in ['mac', 'win']:
        link_or_copy(merged / expected[key], output / expected[key])
    for key in ['macProof', 'winProof', 'sums']:
        shutil.copyfile(references / expected[key], output / expected[key])
    assets = {name: {'bytes': (output / name).stat().st_size, 'sha256': sha(output / name)} for name in expected.values()}
    require(assets == cohort['assets'], 'Final five CI assets differ from reviewed original bytes')
    return {**ctx, 'assets': assets, 'assetDirectory': str(output.resolve()), 'proofFacts': {'sourceDigest': digest, 'sourceInputs': len(captured['files']), 'kotlin': {'mac': mac_tests, 'windows': win_tests}, 'goRace': {'mac': mac_race, 'windows': win_race}, 'macScope': 'Actual CI DMG mount/copy/signature/native version and uncached installed engine test; independent local C7 native evidence is separate.'}}


def independent_ci_audit(receipt, release, receipt_path, repo_root):
    """Last gate before the existing workflow publishes: genuine fresh APIs and actual five files."""
    from audit_public_release import audit
    def api(path):
        return json.loads(subprocess.check_output(['gh', 'api', 'repos/' + receipt['repository'] + '/' + path]))
    live_release = api('releases/' + str(receipt['releaseId']))
    verify_release(receipt, live_release)
    run = api('actions/runs/' + str(receipt['runId']))
    jobs = api('actions/runs/' + str(receipt['runId']) + '/jobs?filter=latest&per_page=100')
    artifacts = api('actions/runs/' + str(receipt['runId']) + '/artifacts?per_page=100')
    current = context(receipt['repository'], receipt['version'], receipt['runId'], receipt['sourceSha'], run, jobs, artifacts, live_release)
    require(current['mode'] == 'ci-proofs' and current['producerJobs'] == receipt['producerJobs'] and current['producerArtifacts'] == receipt['producerArtifacts'], 'CI producer identity changed before publication')
    work = receipt_path.parent; directory = work / 'independent-audit-input'; directory.mkdir(exist_ok=False)
    (directory / 'release.json').write_text(json.dumps(live_release, indent=2) + '\n')
    (directory / 'run.json').write_text(json.dumps(run, indent=2) + '\n')
    for name, expected in receipt['assets'].items():
        path = Path(receipt['assetDirectory']) / name
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == expected['bytes'] and sha(path) == expected['sha256'], 'Prepared asset bytes changed before final audit')
        link_or_copy(path, directory / name)
    result = audit(directory, allow_draft=True, repo_root=repo_root)
    shutil.copyfile(directory / 'independent-draft-audit.json', work / 'independent-draft-audit.json')
    for name, value in [('independent-release-api.json', live_release), ('independent-run-api.json', run), ('independent-jobs-api.json', jobs), ('independent-artifacts-api.json', artifacts)]:
        (work / name).write_text(json.dumps(value, indent=2) + '\n')
    return result


def safe_name(name, prefix):
    path = PurePosixPath(name)
    require(not path.is_absolute() and '\\' not in name and not any(p in ('', '.', '..') for p in name.rstrip('/').split('/')),
            'Unsafe provenance path')
    require(path.parts and path.parts[0] == prefix, 'Unexpected provenance prefix')
    return path


class MacProof:
    def __init__(self, path):
        self.archive = tarfile.open(path, 'r:gz')
        self.members = {}
        try:
            for member in self.archive.getmembers():
                safe_name(member.name, 'mac-provenance')
                require(member.name not in self.members, 'Duplicate provenance member')
                require(member.isfile() or member.isdir(), 'Links/devices are not accepted in provenance')
                self.members[member.name] = member
        except Exception:
            self.archive.close()
            raise

    def close(self):
        self.archive.close()

    def read(self, relative):
        member = self.members['mac-provenance/' + relative]
        require(member.isfile() and member.size <= 32 * 1024 * 1024, 'Invalid provenance JSON member')
        with self.archive.extractfile(member) as stream:
            return json.load(stream)

    def xml(self):
        return [ET.fromstring(self.archive.extractfile(m).read()) for n, m in self.members.items()
                if n.startswith('mac-provenance/kotlin-test-results/TEST-') and n.endswith('.xml')]


def source_capture(capture):
    require(capture['schemaVersion'] == 1 and capture['algorithm'] == 'sha256', 'Unknown source capture schema')
    entries = capture['files']
    require(entries and len({x['path'] for x in entries}) == len(entries), 'Missing/duplicate source capture')
    for entry in entries:
        safe_name('source/' + entry['path'], 'source')
        require(re.fullmatch(r'[0-9a-f]{64}', entry['sha256']), 'Invalid captured source SHA')
    digest = hashlib.sha256(''.join(f"{x['sha256']}  {x['path']}\n" for x in entries).encode()).hexdigest()
    require(digest == capture['digest'], 'Source capture digest mismatch')
    return digest


def git_capture(repo_root, source, capture):
    paths = set(subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', source], cwd=repo_root).decode().splitlines())
    def included(name):
        path = PurePosixPath(name)
        return (name.startswith(('src/main/', 'src/test/')) or
                (name.startswith('engine/') and (path.suffix == '.go' or path.name in {'go.mod', 'go.sum'} or 'licenses' in path.parts)) or
                (name.startswith('scripts/') and path.suffix in {'.py', '.sh', '.ps1', '.iss'}) or
                (name.startswith('.github/workflows/') and path.suffix in {'.yml', '.yaml'}) or
                (name.startswith('product/') and 'windows' in path.name and path.suffix == '.json') or
                name.startswith('product/patches/') or name in {
                    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', '.gitattributes',
                    'product/platform.lock.json', 'product/go-tools.lock.json',
                    'product/file-associations.json'})
    require({x['path'] for x in capture['files']} == {name for name in paths if included(name)}, 'Source capture omits/adds release build inputs')
    requests = ''.join(source + ':' + x['path'] + '\n' for x in capture['files'])
    require('\x00' not in requests and all('\n' not in x['path'] and '\r' not in x['path'] for x in capture['files']), 'Invalid source path')
    output = subprocess.check_output(['git', 'cat-file', '--batch'], cwd=repo_root, input=requests.encode())
    from io import BytesIO
    stream = BytesIO(output)
    for entry in capture['files']:
        header = stream.readline().split()
        require(len(header) == 3 and header[1] == b'blob', 'Captured source is missing from release commit')
        data = stream.read(int(header[2]))
        require(stream.read(1) == b'\n' and hashlib.sha256(data).hexdigest() == entry['sha256'], 'Captured source differs from release commit')
    require(not stream.read(), 'Unexpected Git source data')


def xml_summary(roots):
    require(roots, 'Kotlin XML evidence is required')
    result = {'reports': len(roots), 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for root in roots:
        for key in ['tests', 'failures', 'errors', 'skipped']:
            result[key] += int(root.attrib.get(key, 0))
    require(result['tests'] > 0 and result['failures'] == result['errors'] == 0, 'Kotlin test failures or empty evidence')
    return result


def engine_smoke(value):
    require(type(value['exitCode']) is int and value['exitCode'] == 0 and value['testCount'] == 1 and value['cached'] is False,
            'Installed engine test did not pass uncached')
    require(value['test'] == 'TestBundledInstalledRuntime', 'Wrong installed engine smoke')


def validate_proofs(ctx, windows, mac_path, repo_root=None):
    version, source = ctx['version'], ctx['sourceSha']
    expected = names(version)
    require(mac_path.stat().st_size == ctx['macAssets'][expected['macProof']]['bytes'] and sha(mac_path) == ctx['macAssets'][expected['macProof']]['sha256'],
            'Mac provenance differs from uploaded bytes')
    archive = MacProof(mac_path)
    try:
        mac = archive.read('release.json'); native = archive.read('native-observations.json'); captured = archive.read('source-inputs.json')
        mac_digest = source_capture(captured)
        require(mac['schemaVersion'] == 1 and mac['version'] == version and mac['sourceCommit'] == source and mac['sourceDirty'] is False and mac['sourceDigest'] == mac_digest,
                'Mac release source/version mismatch')
        require(mac['target'] == 'macos-arm64' and mac['installer'] == expected['mac'], 'Wrong Mac installer')
        require(all(mac[k] is True for k in ['imageVerified', 'mountedAndCopied', 'signatureVerified']), 'Mac installation/signature evidence incomplete')
        require(ctx['macAssets'][expected['mac']] == {'bytes': mac['installerBytes'], 'sha256': mac['installerSha256']}, 'Uploaded Mac installer digest/size mismatch')
        require(native['status'] == 'pass-limited-installed-macos-scope' and native['sourceCommit'] == source and native['sourceDigest'] == mac_digest and native['installerSha256'] == mac['installerSha256'],
                'Native Mac observation cohort mismatch')
        require(native['installedApplication'] == mac['installedApplication'], 'Mac native installed-copy mismatch')
        gone = native['cleanup']['knownPidsGone']
        known = {str(item['pid']) for item in native['knownOwnedPids']} | {str(pid) for pid in native['debugTargetPids']} | {str(native['nativePid'])}
        require(known.issubset(gone), 'Mac cleanup omits a known owned PID')
        require(isinstance(gone, dict) and gone and all(x is True for x in gone.values()) and native['cleanup']['logErrors'] == [], 'Mac native cleanup/errors failed')
        engine_smoke(mac['installedEngineSmoke'])
        mac_checks = archive.read('go-checks.json')
        require(mac_checks == mac['goChecks'] and mac_checks['kind'] == 'fresh' and mac_checks['sourceDigest'] == mac_digest,
                'Mac fresh Go evidence differs from driver receipt')
        require(len(mac_checks['commands']) == 2 and all(x['exitCode'] == 0 for x in mac_checks['commands']), 'Mac Go checks did not succeed')
        for log in mac_checks['logs']:
            safe_name('mac-provenance/' + log['path'], 'mac-provenance')
            member = archive.members['mac-provenance/' + log['path']]
            with archive.archive.extractfile(member) as stream:
                data = stream.read()
            require(len(data) == log['bytes'] and hashlib.sha256(data).hexdigest() == log['sha256'], 'Mac Go log hash mismatch')
        mac_xml = xml_summary(archive.xml())
    finally:
        archive.close()
    proof = windows / 'windows-provenance'
    win = load(proof / 'release.json'); assembly = load(proof / 'assembly.json'); manifest = assembly['manifest']
    require(win['schemaVersion'] == 1 and win['version'] == version and win['sourceCommit'] == source and win['target'] == 'windows-x64' and win['installer'] == expected['win'],
            'Windows release source/version mismatch')
    require(type(win['installExitCode']) is int and win['installExitCode'] == 0 and type(win['uninstallExitCode']) is int and win['uninstallExitCode'] == 0,
            'Windows install/uninstall did not pass')
    require(manifest['version'] == version and manifest['target'] == 'windows-x64' and manifest['sourceSdkUnchanged'] is True, 'Windows assembly mismatch')
    require(source_capture(manifest['buildInputs']) == mac_digest and manifest['buildInputs'] == captured, 'Windows/Mac source capture mismatch')
    require(f'vcs.revision={source}' in manifest['engineBuildInformation'] and 'vcs.modified=false' in manifest['engineBuildInformation'], 'Wrong installed Windows engine source')
    if repo_root is not None:
        git_capture(repo_root, source, captured)
    require(win['installedSmoke'] == 'installed-smoke/smoke.json', 'Unexpected smoke path')
    smoke = load(proof / win['installedSmoke'])
    require(smoke['status'] == 'pass' and smoke['version'] == version and smoke['installedFilesVerified'] > 0 and smoke['nativeVersion']['code'] == 0,
            'Installed Windows smoke incomplete')
    require('2026.1' in smoke['nativeVersion']['stdout'] + smoke['nativeVersion']['stderr'], 'Wrong native platform version')
    gui = smoke['nativeProjectStartup']
    require(gui['exitCode'] == 0 and gui['exitCommand']['code'] == 0 and gui['projectFrame']['class'] == 'SunAwtFrame' and gui['projectFrame']['title'],
            'Windows native frame/exit evidence incomplete')
    engine_smoke(smoke['engine'])
    require(len(smoke['toolVersions']) == 3, 'Tool version evidence missing')
    for item, text in zip(smoke['toolVersions'], ['go1.26.10', 'v0.24.0', '1.27.2']):
        require(item['code'] == 0 and text in item['stdout'] + item['stderr'], 'Installed tool version mismatch')
    win_xml = xml_summary([ET.parse(p).getroot() for p in (proof / 'kotlin-test-results').glob('TEST-*.xml')])
    require(win_xml['tests'] == mac_xml['tests'] and win_xml['reports'] == mac_xml['reports'], 'Incomplete cross-platform Kotlin reports')
    packages = {}; failures = 0; passes = 0
    for line in (proof / 'go-race.jsonl').read_text(encoding='utf-8-sig').splitlines():
        event = json.loads(line)
        require('(cached)' not in event.get('Output', ''), 'Go race evidence must be uncached')
        failures += event['Action'] in ['fail', 'build-fail']
        passes += event['Action'] == 'pass' and 'Test' in event
        if 'Test' not in event and event['Action'] in ['pass', 'fail', 'skip']:
            packages[event['Package']] = event['Action']
    require(passes > 0 and failures == 0 and packages == mac_checks['raceSummary']['packages'], 'Go race failures/missing package results')
    vet = proof / 'go-vet.log'
    # PowerShell Tee-Object creates no file when successful vet has no output.
    # release.json is emitted only after build-release.ps1's zero-exit guard.
    require(not vet.exists() or vet.stat().st_size == 0, 'Go vet log is not clean')
    return {'sourceDigest': mac_digest, 'windows': win, 'kotlin': {'windows': win_xml, 'mac': mac_xml},
            'windowsGoPassEvents': passes, 'windowsVetEvidence': 'empty-log' if vet.exists() else 'successful-driver-guard-no-output-file', 'macInstaller': ctx['macAssets'][expected['mac']]}


def prepare(ctx, windows, mac_path, output, repo_root=None):
    facts = validate_proofs(ctx, windows, mac_path, repo_root)
    expected = names(ctx['version']); executable = windows / expected['win']; record = facts['windows']
    require(executable.is_file() and not executable.is_symlink() and executable.stat().st_size == record['installerBytes'] and sha(executable) == record['installerSha256'], 'Windows installer byte mismatch')
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(executable, output / expected['win'])
    with tarfile.open(output / expected['winProof'], 'w:gz') as archive:
        proof = windows / 'windows-provenance'
        for path in [proof] + sorted(proof.rglob('*')):
            require(not path.is_symlink() and (path.is_file() or path.is_dir()), 'Unsafe Windows provenance file')
            archive.add(path, arcname=path.relative_to(windows).as_posix(), recursive=False)
    assets = dict(ctx['macAssets'])
    for key in ['win', 'winProof']:
        path = output / expected[key]; assets[path.name] = {'bytes': path.stat().st_size, 'sha256': sha(path)}
    sums = output / expected['sums']; sums.write_text(''.join(f"{x['sha256']}  {name}\n" for name, x in sorted(assets.items())), encoding='utf-8')
    assets[sums.name] = {'bytes': sums.stat().st_size, 'sha256': sha(sums)}
    return {**ctx, 'assets': assets, 'proofFacts': facts}


def verify_private(receipt, release):
    require(release['id'] == receipt['releaseId'] and release['draft'] is True and release['tag_name'] == receipt['tag'], 'Release is no longer the expected private draft')
    require(body_sha(release) == receipt['bodySha256'], 'Reviewed release notes changed')
    actual = asset_map(release)
    require(set(actual).issubset(receipt['assets']), 'Unexpected draft assets')
    fixed = receipt['fixedAssets'] if receipt.get('mode') == 'ci-proofs' else receipt['macAssets']
    for name, expected in fixed.items():
        require(uploaded(actual[name]) == expected, 'Previously approved assets changed before upload')


def verify_release(receipt, release):
    verify_private(receipt, release)
    actual = asset_map(release)
    require(set(actual) == set(receipt['assets']), 'Release must contain exactly five expected assets')
    for name, expected in receipt['assets'].items():
        require(uploaded(actual[name]) == expected, 'Release asset mismatch: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['context', 'prepare', 'verify-private', 'verify'])
    for key in ['repo', 'version', 'source', 'run', 'jobs', 'artifacts', 'release', 'context', 'windows', 'mac-proof', 'output', 'receipt', 'repo-root']:
        parser.add_argument('--' + key)
    parser.add_argument('--run-id', type=int)
    args = parser.parse_args()
    if args.mode == 'context':
        result = context(args.repo, args.version, args.run_id, args.source, load(args.run), load(args.jobs), load(args.artifacts), load(args.release))
        Path(args.output).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(result.get('artifactIds', result['artifactId']))
    elif args.mode == 'prepare':
        require(args.repo_root is not None, 'Release source Git verification is required')
        ctx = load(args.context)
        if ctx.get('mode') == 'ci-proofs':
            references = download_ci_references(ctx, Path(args.mac_proof))
            result = prepare_ci(ctx, Path(args.windows), references, Path(args.output), args.repo_root)
        else:
            result = prepare(ctx, Path(args.windows), Path(args.mac_proof), Path(args.output), args.repo_root)
        Path(args.receipt).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    elif args.mode == 'verify-private':
        verify_private(load(args.receipt), load(args.release))
    else:
        receipt = load(args.receipt); release = load(args.release)
        verify_release(receipt, release)
        if receipt.get('mode') == 'ci-proofs':
            independent_ci_audit(receipt, release, Path(args.receipt), Path(__file__).resolve().parents[1])


if __name__ == '__main__':
    main()
