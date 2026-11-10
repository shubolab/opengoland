"""Validate bundled tool receipts against reviewed repository source pins."""
import hashlib
import json
import re
from pathlib import Path, PurePosixPath


SCHEMA = 'open-goland-derived-go-tool-v1'


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def relative_file(root, relative):
    if (not isinstance(relative, str) or not relative or '\\' in relative or ':' in relative
            or PurePosixPath(relative).is_absolute() or any(p in ('', '.', '..') for p in relative.split('/'))):
        raise ValueError('Unsafe tool receipt path')
    root = Path(root).resolve()
    path = root.joinpath(*PurePosixPath(relative).parts)
    if not path.resolve().is_relative_to(root) or not path.is_file():
        raise ValueError('Missing tool receipt file: ' + relative)
    for parent in [path, *path.parents]:
        if parent == root: break
        if parent.is_symlink(): raise ValueError('Symbolic link in tool receipt path')
    return path


def tree_digest(records):
    # Exact authenticated module-file names; no platform-dependent Path sorting,
    # file modes or timestamps. Builds/VCS are outside this source copy, not
    # hidden by an ignore rule that could mask an extra source file.
    canonical = ''.join(record['sha256'] + '  ' + name + '\n' for name, record in sorted(records.items()))
    return hashlib.sha256(canonical.encode('utf-8')).hexdigest()


def source_patch_inputs(root, name, tool):
    selected = tool.get('sourcePatch')
    if selected is None: return None
    if name != 'dlv' or set(selected) != {'pin', 'pinSha256'}:
        raise ValueError('Unknown tool source derivation')
    pin_path = relative_file(root, selected['pin'])
    if not selected['pin'].startswith('product/patches/') or digest(pin_path) != selected['pinSha256']:
        raise ValueError('Reviewed tool source pin mismatch')
    pin = json.loads(pin_path.read_text(encoding='utf-8'))
    if (set(pin) != {'schemaVersion', 'id', 'upstream', 'patchFile', 'patchSha256', 'sources',
                     'architectureGuard', 'buildInfoModuleVersion', 'sourceTree'}
            or pin['schemaVersion'] != 1 or pin['id'] != 'open-goland-delve-arm64-cfi-v1'
            or pin['architectureGuard'] != 'arm64' or pin['buildInfoModuleVersion'] != '(devel)'
            or set(pin['sources']) != {'pkg/proc/stack.go'}):
        raise ValueError('Unknown derived Delve pin/schema/source set')
    expected = {key: tool[key] for key in ('module', 'version', 'package', 'sum', 'goModSum', 'goModSha256')}
    if (any(pin['upstream'].get(key) != value for key, value in expected.items())
            or set(pin['upstream']) != set(expected) | {'zipSha256', 'zipBytes'}):
        raise ValueError('Derived Delve upstream differs from reviewed lock')
    if pin['upstream']['module'] != 'github.com/go-delve/delve' or pin['upstream']['version'] != 'v1.27.2':
        raise ValueError('Unknown derived Delve upstream')
    for record in pin['sources'].values():
        if set(record) != {'upstreamSha256', 'patchedSha256'}:
            raise ValueError('Unknown tool source digest schema')
    if set(pin['sourceTree']) != {'upstreamSha256', 'patchedSha256', 'fileCount'}:
        raise ValueError('Unknown tool source tree schema')
    if '/' in pin['patchFile'] or '\\' in pin['patchFile']:
        raise ValueError('Tool source patch must be beside its pin')
    patch = relative_file(pin_path.parent, pin['patchFile'])
    if digest(patch) != pin['patchSha256']:
        raise ValueError('Reviewed tool source patch mismatch')
    return {'pin': pin, 'pinPath': pin_path, 'patchPath': patch,
            'pinRelativePath': selected['pin'], 'pinSha256': selected['pinSha256']}


def validate_bundle(root, repository, manifest=None):
    """Read-only actual payload checks; installed copies use the reviewed repo lock.

    Legacy stock records remain supported only when the reviewed lock has no
    derivation for that tool. A new derived lock cannot accept a stock receipt.
    """
    root, repository = Path(root), Path(repository)
    lock_path = repository / 'product/go-tools.lock.json'
    lock = json.loads(lock_path.read_text(encoding='utf-8'))
    manifest = manifest or json.loads(relative_file(root, 'manifest.json').read_text(encoding='utf-8'))
    if (manifest.get('schemaVersion') != 1 or manifest.get('lockSha256') != digest(lock_path)
            or set(manifest.get('tools', {})) != set(lock['tools'])):
        raise ValueError('Tool manifest does not match the reviewed lock/schema')
    if manifest.get('target') not in lock['sdk'] or manifest.get('builderVersion') != lock['builderVersion']:
        raise ValueError('Unexpected tool target/builder')
    actual = {}
    for path in root.rglob('*'):
        if path.is_symlink(): raise ValueError('Symbolic link in bundled tool payload')
        if path.is_file() and path.relative_to(root).as_posix() != 'manifest.json':
            actual[path.relative_to(root).as_posix()] = {'bytes': path.stat().st_size, 'sha256': digest(path)}
    if actual != manifest.get('files'):
        raise ValueError('Bundled tool payload differs from its sealed inventory')
    if any('sourcePatch' in tool for tool in lock['tools'].values()):
        if manifest.get('receiptVerifierSha256') != digest(repository / 'scripts/go_tool_receipts.py'):
            raise ValueError('Tool receipt verifier source differs from the build')
    checks = {}
    for name, tool in lock['tools'].items():
        inputs = source_patch_inputs(repository, name, tool)
        record = manifest['tools'][name]
        binary = relative_file(root, record['executable'])
        if digest(binary) != record['sha256']:
            raise ValueError('Bundled tool artifact differs from its receipt: ' + name)
        info = record['buildInfo']
        if (info['goVersion'] != lock['builderVersion'] or
                info['build'].get('GOOS') + '-' + info['build'].get('GOARCH') != manifest['target']
                or info['build'].get('CGO_ENABLED') != '0'):
            raise ValueError('Bundled tool compiler/target differs: ' + name)
        if inputs is None:
            if record.get('distributionKind', 'upstream-source-built') != 'upstream-source-built' or 'derivation' in record:
                raise ValueError('Unexpected source-derived record under a stock lock')
            if info['module'] != {'module': tool['module'], 'version': tool['version'], 'sum': tool['sum']}:
                raise ValueError('Stock tool upstream module identity differs')
            checks[name] = {'kind': 'upstream-source-built', 'sha256': record['sha256']}
            continue
        if record.get('distributionKind') != 'source-derived' or record['version'] != tool['version']:
            raise ValueError('Derived Delve must be explicitly identified')
        if info['module'] != {'module': tool['module'], 'version': '(devel)'}:
            raise ValueError('Derived Delve must retain truthful devel build metadata')
        derivation = record['derivation']
        if set(derivation) != {'receipt', 'receiptSha256', 'pin', 'patch'}:
            raise ValueError('Unknown derived receipt references')
        receipt_path = relative_file(root, derivation['receipt'])
        if digest(receipt_path) != derivation['receiptSha256']:
            raise ValueError('Derived receipt bytes changed')
        receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
        pin = inputs['pin']
        if (set(receipt) != {'schema', 'pinSha256', 'upstream', 'patchSha256', 'changedSources',
                             'artifactSha256', 'buildInfo', 'sourceFileCount', 'sourceTreeDigests',
                             'builderVersion', 'builder', 'command', 'sourcePatchRunnerSha256'}
                or receipt.get('schema') != SCHEMA or receipt.get('pinSha256') != inputs['pinSha256']
                or receipt.get('upstream') != pin['upstream'] or receipt.get('patchSha256') != pin['patchSha256']
                or receipt.get('changedSources') != pin['sources']
                or receipt.get('artifactSha256') != record['sha256'] or receipt.get('buildInfo') != info
                or receipt.get('sourceFileCount') != pin['sourceTree']['fileCount']
                or receipt.get('sourceTreeDigests') != {'upstream': pin['sourceTree']['upstreamSha256'],
                                                      'derived': pin['sourceTree']['patchedSha256']}
                or receipt.get('builderVersion') != lock['builderVersion']):
            raise ValueError('Derived receipt differs from reviewed source/artifact identity')
        builder = receipt['builder']
        if (set(builder) != {'host', 'archiveSha256', 'executableSha256'} or builder['host'] not in lock['builders']
                or builder['archiveSha256'] != lock['builders'][builder['host']]['sha256']
                or not re.fullmatch('[0-9a-f]{64}', builder['executableSha256'])):
            raise ValueError('Derived compiler archive differs from reviewed lock')
        if (receipt['command'] != ['go', 'build', '-mod=readonly', '-trimpath', '-buildvcs=false', '-o', '<staged-dlv>', './cmd/dlv']
                or receipt['sourcePatchRunnerSha256'] != digest(repository / 'scripts/source_patch.py')):
            raise ValueError('Derived tool compiler/patch command differs from reviewed source')
        if (digest(relative_file(root, derivation['pin'])) != inputs['pinSha256']
                or digest(relative_file(root, derivation['patch'])) != pin['patchSha256']):
            raise ValueError('Bundled source pin/patch differs from reviewed inputs')
        checks[name] = {'kind': 'source-derived', 'upstreamVersion': tool['version'],
                        'sha256': record['sha256'], 'patchSha256': pin['patchSha256'],
                        'pinSha256': inputs['pinSha256'], 'receiptSha256': derivation['receiptSha256']}
    return {'target': manifest['target'], 'tools': checks, 'binding': 'reviewed repository lock/pin/patch and actual installed tool bytes'}
