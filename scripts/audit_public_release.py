#!/usr/bin/env python3
"""Audit actual release API metadata and downloaded installer/provenance bytes; never publish or extract installers."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from publish_tested_installers import git_capture, source_capture


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


class Proof:
    def __init__(self, path, prefix):
        self.tar = tarfile.open(path, 'r:gz')
        self.members = {}
        try:
            for m in self.tar.getmembers():
                p = PurePosixPath(m.name)
                require(not p.is_absolute() and '\\' not in m.name and
                        all(x not in ('', '.', '..') for x in m.name.rstrip('/').split('/')) and
                        p.parts[0] == prefix and (m.isfile() or m.isdir()), 'Unsafe tar member')
                require(m.name not in self.members, 'Duplicate tar member')
                self.members[m.name] = m
            roots = [name.removesuffix('release.json') for name in self.members if name.endswith('/release.json')]
            require(len(roots) == 1, 'Ambiguous release cohort')
            self.root = roots[0]
        except BaseException:
            self.tar.close()
            raise

    def bytes(self, relative):
        m = self.members[self.root + relative]
        require(m.isfile() and m.size <= 32 * 1024 * 1024, 'Oversized selected proof')
        return self.tar.extractfile(m).read()

    def json(self, relative):
        return json.loads(self.bytes(relative).decode('utf-8-sig'))

    def tests(self):
        result = dict(reports=0, tests=0, failures=0, errors=0, skipped=0)
        identities = set()
        for name, m in self.members.items():
            if not (name.startswith(self.root + 'kotlin-test-results/TEST-') and name.endswith('.xml')):
                continue
            require(m.size <= 16 * 1024 * 1024, 'Oversized test XML')
            root = ET.fromstring(self.tar.extractfile(m).read())
            result['reports'] += 1
            for key in ('tests', 'failures', 'errors', 'skipped'):
                result[key] += int(root.attrib.get(key, 0))
            for case in root.findall('testcase'):
                identity = (case.attrib['classname'], case.attrib['name'])
                require(identity not in identities, 'Duplicate testcase identity')
                identities.add(identity)
                require(not any(case.find(x) is not None for x in ['failure', 'error', 'skipped']), 'Failed/skipped testcase')
        require(result['tests'] == len(identities) > 0 and
                result['failures'] == result['errors'] == result['skipped'] == 0, 'Incomplete test cohort')
        return result, identities

    def race(self):
        events = []; downloads = []
        for line in self.bytes('go-race.jsonl').decode('utf-8-sig').splitlines():
            if not line.strip(): continue
            if line.startswith('go: downloading '):
                require(re.fullmatch(r'go: downloading [^\s]+ v[^\s]+', line), 'Unknown Go download diagnostic')
                downloads.append(line)
            else:
                events.append(json.loads(line))
        require(not any(e['Action'] in ('fail', 'build-fail') or '(cached)' in e.get('Output', '') for e in events), 'Failed/cached race check')
        packages = {e['Package']: e['Action'] for e in events if 'Test' not in e and e['Action'] in ['pass', 'skip']}
        passed = [e for e in events if e['Action'] == 'pass' and 'Test' in e]
        require(passed and packages, 'Missing Go race results')
        owner = sorted(e['Test'] for e in passed if e['Package'].endswith('/internal/processowner'))
        return {'packages': packages, 'passEvents': len(passed), 'processownerTests': owner, 'moduleDownloadDiagnostics': len(downloads)}


def zero_exit(value, message):
    require(type(value) is int and value == 0, message)


def clean_native_text(value, message):
    require(isinstance(value, str) and not re.search(r'\b(?:ERROR|SEVERE)\b', value), message)


def check_windows_native(proof, smoke):
    require(smoke['status'] == 'pass' and smoke['installedFilesVerified'] > 0, 'Windows installed-file smoke incomplete')
    version = proof.json('installed-smoke/native-version.json')
    require(version == smoke['nativeVersion'], 'Windows native-version receipt differs from smoke')
    zero_exit(version['code'], 'Windows native version failed')
    version_text = version['stdout'] + '\n' + version['stderr']
    require('2026.1' in version_text, 'Wrong Windows native platform version')
    clean_native_text(version_text, 'Windows native version logged ERROR/SEVERE')
    clean_native_text(proof.bytes('installed-smoke/log/idea.log').decode('utf-8-sig'),
                      'Windows native startup idea.log contains ERROR/SEVERE')
    gui = smoke['nativeProjectStartup']
    zero_exit(gui['exitCode'], 'Windows native project startup failed')
    zero_exit(gui['exitCommand']['code'], 'Windows native project exit failed')
    require(gui['projectFrame']['class'] == 'SunAwtFrame' and gui['projectFrame']['title'],
            'Windows native project frame missing')
    require(len(smoke['toolVersions']) == 3, 'Windows bundled tool evidence missing')
    for record, expected in zip(smoke['toolVersions'], ('go1.26.10', 'v0.24.0', '1.27.2')):
        zero_exit(record['code'], 'Windows installed tool failed')
        require(expected in record['stdout'] + record['stderr'], 'Windows installed tool version mismatch')
    if proof.root + 'go-vet.log' in proof.members:
        require(not proof.bytes('go-vet.log'), 'Windows Go vet log is not empty')


def audit(directory, allow_draft=False, repo_root=None):
    directory = Path(directory).resolve()
    repo_root = Path(repo_root).resolve() if repo_root is not None else ROOT
    release = json.loads((directory / 'release.json').read_text())
    run = json.loads((directory / 'run.json').read_text())
    if allow_draft:
        require(release['draft'] is True and release.get('published_at') is None, 'Prepublication audit requires an unpublished private draft')
    else:
        require(release['draft'] is False and release['published_at'], 'Release is not public')
    require(run['status'] == 'completed' and run['conclusion'] == 'success' and run['path'] == '.github/workflows/release.yml', 'Workflow did not finish successfully')
    version = release['tag_name'].removeprefix('v')
    require(run['event'] in ('push', 'workflow_dispatch'), 'Unexpected producer event')
    if run['event'] == 'push':
        require(run['head_branch'] == release['tag_name'], 'Run tag mismatch')
    source = run['head_sha']
    local_tag = subprocess.check_output(['git', 'rev-parse', release['tag_name'] + '^{commit}'], cwd=repo_root).decode().strip()
    require(local_tag == source, 'Tag source mismatch')
    assets = {x['name']: x for x in release['assets']}
    expected = {'SHA256SUMS', 'windows-provenance.tar.gz', 'macos-provenance.tar.gz',
                f'open-Goland-{version}-windows-x64-setup.exe', f'open-Goland-{version}-macos-arm64.dmg'}
    require(set(assets) == expected and len(release['assets']) == 5, 'Expected exactly five assets')
    for name, a in assets.items():
        require(a['state'] == 'uploaded' and a['size'] > 0 and re.fullmatch(r'sha256:[0-9a-f]{64}', a['digest']), 'Incomplete asset')
        p = directory / name
        require(p.is_file() and not p.is_symlink(), 'Missing/linked actual release asset: ' + name)
        require(p.stat().st_size == a['size'] and 'sha256:' + sha(p) == a['digest'], 'Downloaded bytes mismatch')
    sums = {}
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split(maxsplit=1)
        name = name.removeprefix('./')
        require(re.fullmatch(r'[0-9a-f]{64}', digest) and '/' not in name and '\\' not in name and name not in sums, 'Invalid checksum path/digest')
        sums[name] = digest
    require(set(sums) == expected - {'SHA256SUMS'}, 'Incomplete checksum file')
    require(all(assets[name]['digest'] == 'sha256:' + value for name, value in sums.items()), 'Checksum/API digest mismatch')
    result = {'version': version, 'sourceCommit': source, 'runId': run['id'], 'attempt': run['run_attempt'],
              'producerEvent': run['event'], 'releaseState': 'private-draft' if allow_draft else 'public',
              'publishedAt': release['published_at'], 'assets': {n: {'bytes': a['size'], 'sha256': a['digest'][7:]} for n,a in assets.items()}}
    proofs = {}
    captures = {}; test_ids = {}
    try:
        proofs['windows'] = Proof(directory / 'windows-provenance.tar.gz', 'windows-provenance')
        proofs['macos'] = Proof(directory / 'macos-provenance.tar.gz', 'mac-provenance')
        for platform, p in proofs.items():
            r = p.json('release.json')
            require(r['sourceCommit'] == source and r['version'] == version, 'Producer source/version mismatch')
            require(assets[r['installer']]['size'] == r['installerBytes'] and assets[r['installer']]['digest'] == 'sha256:' + r['installerSha256'], 'Installer/provenance/API mismatch')
            stats, test_ids[platform] = p.tests(); race = p.race()
            if platform == 'windows':
                zero_exit(r['installExitCode'], 'Windows installation failed')
                zero_exit(r['uninstallExitCode'], 'Windows uninstallation failed')
                a = p.json('assembly.json')['manifest']; captures[platform] = a['buildInputs']
                require(a['sourceSdkUnchanged'] is True and f'vcs.revision={source}' in a['engineBuildInformation'] and 'vcs.modified=false' in a['engineBuildInformation'], 'Windows source binding failed')
                smoke = p.json(r['installedSmoke'])
                require(smoke['version'] == version, 'Windows installed smoke version mismatch')
                check_windows_native(p, smoke)
                engine = smoke['engine']; scope = smoke['scope']
                native = {'installExitCode': r['installExitCode'], 'uninstallExitCode': r['uninstallExitCode'], 'installedSmoke': smoke['status'], 'scope': scope}
            else:
                require(r['sourceDirty'] is False and all(r[k] is True for k in ['imageVerified','mountedAndCopied','signatureVerified']), 'Mac install/source failed')
                captures[platform] = p.json('source-inputs.json')
                require(r['sourceDigest'] == source_capture(captures[platform]), 'Mac release digest differs from source capture')
                checks = p.json('go-checks.json')
                require(checks == r['goChecks'] and checks['kind'] == 'fresh' and checks['sourceDigest'] == r['sourceDigest'], 'Mac fresh-Go receipt mismatch')
                require(all(c['exitCode'] == 0 for c in checks['commands']) and len(checks['commands']) == 2, 'Mac Go command failure')
                paths = [item['path'] for item in checks['logs']]
                require(len(paths) == len(set(paths)) and {'go-race.jsonl', 'go-vet.log'}.issubset(paths),
                        'Mac fresh Go race/vet log evidence missing or duplicated')
                for log in checks['logs']:
                    data = p.bytes(log['path'])
                    require(len(data) == log['bytes'] and hashlib.sha256(data).hexdigest() == log['sha256'], 'Mac fresh-Go log mismatch')
                require(checks['raceSummary']['packages'] == race['packages'] and
                        checks['raceSummary']['testPassEvents'] == race['passEvents'], 'Mac fresh-Go race summary differs from actual log')
                engine = r['installedEngineSmoke']; scope = 'Actual DMG verify, mount/install copy, signatures, native version, bundled tools and one uncached pure-Go test; not full interactive parity.'
                native_version = p.bytes('installed-native-version.log').decode('utf-8-sig')
                require('2026.1' in native_version, 'Mac native platform version evidence missing')
                clean_native_text(native_version, 'Mac native version logged ERROR/SEVERE')
                native = {k:r[k] for k in ['sourceDirty','sourceDigest','imageVerified','mountedAndCopied','signatureVerified','signing']}; native['scope'] = scope
            zero_exit(engine['exitCode'], 'Installed engine smoke failed')
            require(engine['cached'] is False and engine['testCount'] == 1 and engine['test'] == 'TestBundledInstalledRuntime', 'Uncached engine smoke failed')
            source_capture(captures[platform]); git_capture(repo_root, source, captures[platform])
            result[platform] = {'tests': stats, 'goRace': race, 'native': native}
        require(captures['windows'] == captures['macos'], 'Platform source sets differ')
        require(test_ids['windows'] == test_ids['macos'], 'Platform testcase sets differ')
        require(result['windows']['goRace']['packages'] == result['macos']['goRace']['packages'], 'Go package sets/results differ')
        result['sourceInputs'] = len(captures['macos']['files']); result['sourceDigest'] = captures['macos']['digest']
    finally:
        for p in proofs.values(): p.tar.close()
    (directory / ('independent-draft-audit.json' if allow_draft else 'independent-audit.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--allow-draft', action='store_true', help='Require a private unpublished draft; never publishes')
    parser.add_argument('--repo-root', type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.directory, args.allow_draft, args.repo_root), ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
