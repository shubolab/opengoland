#!/usr/bin/env python3
"""Fail-closed receipts for publishing already tested installers; never creates tags/releases."""
import argparse
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
    require({expected[key] for key in ['mac', 'macProof']}.issubset(assets), 'Both uploaded Mac assets must exist before publication')
    mac = {expected[key]: uploaded(assets[expected[key]]) for key in ['mac', 'macProof']}
    return {'schema': 1, 'repository': repo, 'version': version, 'tag': 'v' + version, 'sourceSha': source,
            'runId': run_id, 'runAttempt': run['run_attempt'], 'artifactId': artifact['id'], 'artifactDigest': artifact['digest'], 'releaseId': release['id'],
            'bodySha256': body_sha(release), 'macAssets': mac}


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
    for name, expected in receipt['macAssets'].items():
        require(uploaded(actual[name]) == expected, 'Mac assets changed before upload')


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
        print(result['artifactId'])
    elif args.mode == 'prepare':
        require(args.repo_root is not None, 'Release source Git verification is required')
        result = prepare(load(args.context), Path(args.windows), Path(args.mac_proof), Path(args.output), args.repo_root)
        Path(args.receipt).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    elif args.mode == 'verify-private':
        verify_private(load(args.receipt), load(args.release))
    else:
        verify_release(load(args.receipt), load(args.release))


if __name__ == '__main__':
    main()
