#!/usr/bin/env python3
"""Build a pinned, relocatable Go SDK/gopls/Delve bundle inside owned build paths."""
import argparse
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / 'product/go-tools.lock.json'
SCRIPT_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def progress(message):
    print(message, file=sys.stderr, flush=True)


def owned(path, *, cache=False):
    path = path.expanduser().absolute()
    resolved = path.resolve()
    if not any(resolved.is_relative_to((ROOT / part).resolve()) for part in ['build', '.tools']):
        raise ValueError('Tool outputs/cache must belong to this repository build or .tools directory')
    if resolved.is_relative_to((ROOT / '.tools/platform').resolve()):
        raise ValueError('The immutable platform SDK is not a tool output/cache')
    for parent in [path, *path.parents]:
        if parent.is_symlink():
            raise ValueError('Tool output/cache paths must not traverse symbolic links')
    return resolved


@contextmanager
def build_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as stream:
        stream.seek(0)
        if not stream.read(1):
            stream.write(b'0'); stream.flush()
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            deadline = time.monotonic() + 3600
            while True:
                try:
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                    break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(0.1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def atomic_json(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def files(root):
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Unexpected symbolic link in tool payload: ' + str(path))
        if path.is_file():
            result[path.relative_to(root).as_posix()] = {'bytes': path.stat().st_size, 'sha256': digest(path)}
    return result


def download(artifact, cache, offline):
    target = cache / 'downloads' / artifact['filename']
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and target.stat().st_size == artifact['size'] and digest(target) == artifact['sha256']:
        return target
    if offline:
        raise ValueError('Verified offline SDK archive is unavailable: ' + target.name)
    temporary = target.with_suffix(target.suffix + '.part')
    temporary.unlink(missing_ok=True)
    progress('Downloading verified SDK ' + artifact['filename'])
    parts = target.with_name(target.name + '.parts')
    parts.mkdir(exist_ok=True)
    chunk_size = 4 * 1024 * 1024
    ranges = [(start, min(start + chunk_size, artifact['size']) - 1) for start in range(0, artifact['size'], chunk_size)]
    def fetch_once(bounds):
        start, end = bounds
        part = parts / str(start)
        size = end - start + 1
        if part.is_file() and part.stat().st_size == size:
            return part
        stage = part.with_suffix('.part')
        stage.unlink(missing_ok=True)
        try:
            request = Request(artifact['url'], headers={'Range': f'bytes={start}-{end}'})
            with urlopen(request, timeout=30) as source, stage.open('xb') as output:
                if source.status != 206 or source.headers.get('Content-Range') != f'bytes {start}-{end}/{artifact["size"]}':
                    raise ValueError('Official SDK server did not honor the bounded byte range')
                copied = 0
                began = time.monotonic()
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    copied += len(chunk)
                    if copied > size or time.monotonic() - began > 900:
                        raise ValueError('SDK chunk exceeded its pinned size/time bound')
                    output.write(chunk)
            if stage.stat().st_size != size:
                raise ValueError('Incomplete SDK byte range')
            stage.replace(part)
            return part
        finally:
            stage.unlink(missing_ok=True)
    def fetch(bounds):
        for attempt in range(3):
            try:
                return fetch_once(bounds)
            except OSError:
                if attempt == 2:
                    raise
                progress(f'Retrying SDK byte range {bounds[0]} after network failure')
                time.sleep(attempt + 1)
    try:
        with ThreadPoolExecutor(max_workers=6) as pool:
            for completed, future in enumerate(as_completed([pool.submit(fetch, bounds) for bounds in ranges]), 1):
                future.result()
                progress(f'SDK {artifact["filename"]}: {completed}/{len(ranges)} verified-length chunks')
        with temporary.open('xb') as output:
            for start, unused in ranges:
                with (parts / str(start)).open('rb') as data:
                    shutil.copyfileobj(data, output)
        if temporary.stat().st_size != artifact['size'] or digest(temporary) != artifact['sha256']:
            shutil.rmtree(parts)
            raise ValueError('Official SDK archive SHA-256/size differs from lock: ' + target.name)
        temporary.replace(target)
        shutil.rmtree(parts)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def archive_member(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '\\' in name or any(part in {'.', '..'} for part in path.parts) or not path.parts or path.parts[0] != 'go':
        raise ValueError('Unsafe SDK archive path: ' + name)
    return path


def extract_sdk(archive, target):
    if archive.name.endswith('.zip'):
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                relative = archive_member(entry.filename)
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise ValueError('SDK archive links are not supported')
                path = target / relative
                if entry.is_dir():
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(entry) as data, path.open('xb') as output:
                        shutil.copyfileobj(data, output)
                    path.chmod(0o755 if path.suffix == '.exe' else 0o644)
    else:
        with tarfile.open(archive, 'r:gz') as source:
            for entry in source:
                relative = archive_member(entry.name)
                path = target / relative
                if entry.isdir():
                    path.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(entry) as data, path.open('xb') as output:
                        shutil.copyfileobj(data, output)
                    path.chmod(entry.mode & 0o777)
                else:
                    raise ValueError('SDK archive contains a non-regular payload: ' + entry.name)


def sdk(artifact, target_name, cache, offline):
    destination = cache / 'sdks' / artifact['version'] / target_name
    archive = download(artifact, cache, offline)
    marker = destination / 'sdk-files.json'
    if marker.is_file():
        try:
            known = json.loads(marker.read_text(encoding='utf-8'))
            if known['archiveSha256'] == artifact['sha256'] and known['files'] == files(destination / 'go'):
                return destination / 'go'
        except (OSError, ValueError, KeyError):
            pass
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='extract-', dir=destination.parent))
    try:
        extract_sdk(archive, temporary)
        root = temporary / 'go'
        if not (root / 'src/runtime').is_dir() or not (root / 'LICENSE').is_file() or not (root / 'VERSION').read_text().startswith(artifact['version'] + '\n'):
            raise ValueError('Extracted SDK is incomplete or has the wrong version')
        atomic_json(temporary / 'sdk-files.json', {'archiveSha256': artifact['sha256'], 'files': files(root)})
        if destination.exists():
            shutil.rmtree(destination)
        temporary.replace(destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return destination / 'go'


def execute(command, environment, cwd, timeout=900):
    options = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
    child = subprocess.Popen(list(map(str, command)), env=environment, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, **options)
    try:
        stdout, stderr = child.communicate(timeout=timeout)
    except BaseException:
        # Only this new tool-build process tree belongs to the builder. Never
        # kill arbitrary go/gopls/dlv instances from another IDE/project.
        if child.poll() is None:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(child.pid), '/T', '/F'], capture_output=True, timeout=10)
            else:
                os.killpg(child.pid, signal.SIGTERM)
            try:
                child.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                if os.name != 'nt':
                    os.killpg(child.pid, signal.SIGKILL)
                else:
                    child.kill()
                child.communicate(timeout=5)
        raise
    if child.returncode:
        raise RuntimeError('Tool build failed: ' + ' '.join(map(str, command)) + '\n' + stdout + stderr)
    return stdout


def build_environment(builder, cache, target, offline):
    environment = os.environ.copy()
    for key in ['GOBIN', 'GOROOT', 'GOTOOLCHAIN', 'GOPATH', 'GOMODCACHE', 'GOCACHE', 'GOOS', 'GOARCH', 'GOFLAGS', 'GOENV', 'GOWORK', 'GONOSUMDB', 'GOPRIVATE', 'GONOPROXY', 'GOEXPERIMENT', 'GOAMD64', 'GOARM64', 'GOFIPS140']:
        environment.pop(key, None)
    goos, goarch = target.split('-')
    environment.update({'GOROOT': str(builder), 'GOTOOLCHAIN': 'local', 'GOENV': 'off', 'GOWORK': 'off', 'GOTELEMETRY': 'off',
                        'GOPATH': str(cache / 'gopath' / target), 'GOMODCACHE': str(cache / 'modules'), 'GOCACHE': str(cache / 'build-cache'),
                        'GOPROXY': 'off' if offline else 'https://proxy.golang.org', 'GOSUMDB': 'off' if offline else 'sum.golang.org',
                        'GOOS': goos, 'GOARCH': goarch, 'CGO_ENABLED': '0', 'GO111MODULE': 'on',
                        'GOAMD64': 'v1', 'GOARM64': 'v8.0', 'GOEXPERIMENT': ''})
    environment['PATH'] = str(builder / 'bin') + os.pathsep + environment.get('PATH', '')
    return environment


def module_info(go, tool, environment, cwd):
    record = json.loads(execute([go, 'mod', 'download', '-json', tool['module'] + '@' + tool['version']], environment, cwd))
    if record.get('Sum') != tool['sum'] or record.get('GoModSum') != tool['goModSum'] or digest(Path(record['GoMod'])) != tool['goModSha256']:
        raise ValueError('Pinned module/go.mod checksums changed: ' + tool['module'])
    return record


def build_info(go, binary, environment, cwd):
    lines = execute([go, 'version', '-m', binary], environment, cwd).splitlines()
    info = {'goVersion': lines[0].split()[-1], 'dependencies': [], 'build': {}}
    for line in lines[1:]:
        parts = line.split()
        if not parts:
            continue
        if parts[0] in {'mod', 'dep'}:
            record = dict(zip(['module', 'version', 'sum'], parts[1:]))
            if parts[0] == 'mod':
                info['module'] = record
            else:
                info['dependencies'].append(record)
        elif parts[0] == 'build' and '=' in parts[1]:
            key, value = parts[1].split('=', 1); info['build'][key] = value
    return info


def copy_licenses(go, info, environment, cwd, target):
    records = []
    modules = [info['module'], *info['dependencies']]
    for module in modules:
        spec = module['module'] + '@' + module['version']
        data = json.loads(execute([go, 'mod', 'download', '-json', spec], environment, cwd))
        if data.get('Sum') != module['sum']:
            raise ValueError('Build dependency module checksum differs: ' + spec)
        source = Path(data['Dir'])
        directory = re.sub(r'[^A-Za-z0-9._-]', '_', spec)
        selected = []
        for path in sorted(source.rglob('*')):
            if path.is_file() and re.match(r'(?i)^(license|copying|notice|copyright|authors)([._-].*)?$', path.name):
                relative = path.relative_to(source)
                output = target / directory / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, output)
                selected.append({'path': output.relative_to(target).as_posix(), 'sha256': digest(output)})
        if not selected:
            raise ValueError('No license/notice found for bundled build module: ' + spec)
        records.append({**module, 'files': selected})
    return records


def host_target():
    os_name = {'Darwin': 'darwin', 'Windows': 'windows', 'Linux': 'linux'}.get(platform.system())
    arch = {'arm64': 'arm64', 'aarch64': 'arm64', 'x86_64': 'amd64', 'amd64': 'amd64'}.get(platform.machine().lower())
    return str(os_name) + '-' + str(arch)


def bundle(args):
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    lock_sha = digest(LOCK)
    if args.target not in lock['sdk'] or host_target() not in lock['builders']:
        raise ValueError('Unsupported target or builder host')
    output = owned(args.output)
    cache = owned(args.cache, cache=True)
    if output == cache or output.is_relative_to(cache) or cache.is_relative_to(output):
        raise ValueError('Output and shared compiler cache must not overlap')
    cache.mkdir(parents=True, exist_ok=True)
    with build_lock(cache / 'build.lock'):
        builder = sdk(lock['builders'][host_target()], host_target(), cache, args.offline)
        go = builder / 'bin' / ('go.exe' if os.name == 'nt' else 'go')
        marker = output / 'manifest.json'
        if marker.is_file():
            try:
                previous = json.loads(marker.read_text(encoding='utf-8'))
                expected = previous['files']
                current = files(output); current.pop('manifest.json', None)
                if (previous['lockSha256'] == lock_sha and previous.get('builderScriptSha256') == SCRIPT_SHA256
                        and previous['target'] == args.target and expected == current):
                    return {**previous, 'cacheHit': True, 'output': str(output), 'builderGoExecutable': str(go)}
            except (OSError, ValueError, KeyError):
                pass
        runtime = sdk(lock['sdk'][args.target], args.target, cache, args.offline)
        environment = build_environment(builder, cache, args.target, args.offline)
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix='go-tools-', dir=output.parent))
        try:
            progress('Copying complete SDK ' + lock['runtimeSdkVersion'] + ' for ' + args.target)
            shutil.copytree(runtime, temporary / 'go')
            binary_dir = temporary / 'bin'; binary_dir.mkdir()
            tools = {}
            for name, tool in lock['tools'].items():
                progress('Verifying/building ' + name + ' ' + tool['version'] + ' for ' + args.target)
                record = module_info(go, tool, environment, temporary)
                execute([go, 'mod', 'verify'], environment, Path(record['Dir']))
                execute([go, 'install', '-trimpath', '-buildvcs=false', tool['package'] + '@' + tool['version']], environment, temporary)
                suffix = '.exe' if args.target.startswith('windows-') else ''
                directory = Path(environment['GOPATH']) / 'bin'
                if host_target() != args.target:
                    directory /= args.target.replace('-', '_')
                source = directory / (name + suffix)
                destination = binary_dir / source.name
                shutil.copy2(source, destination)
                info = build_info(go, destination, environment, temporary)
                if info['module'] != {'module': tool['module'], 'version': tool['version'], 'sum': tool['sum']} or info['build'].get('GOOS') + '-' + info['build'].get('GOARCH') != args.target:
                    raise ValueError('Built tool identity differs from lock/target: ' + name)
                notices = copy_licenses(go, info, environment, temporary, temporary / 'licenses')
                tools[name] = {'version': tool['version'], 'executable': destination.relative_to(temporary).as_posix(), 'sha256': digest(destination), 'buildInfo': info, 'licenses': notices}
            payload = files(temporary)
            if lock_sha != digest(LOCK) or SCRIPT_SHA256 != digest(Path(__file__)):
                raise ValueError('Tool builder source or lock changed during the build')
            manifest = {'schemaVersion': 1, 'lockSha256': lock_sha, 'builderScriptSha256': SCRIPT_SHA256, 'target': args.target,
                        'runtimeSdkVersion': lock['runtimeSdkVersion'], 'builderVersion': lock['builderVersion'],
                        'tools': tools, 'files': payload,
                        'scope': 'Complete official SDK and checksum-verified source-built language/debug tools; runtime tests recorded separately.'}
            atomic_json(temporary / 'manifest.json', manifest)
            backup = None
            if output.exists():
                backup = Path(tempfile.mkdtemp(prefix='previous-tools-', dir=output.parent))
                backup.rmdir()
                output.replace(backup)
            try:
                temporary.replace(output)
            except BaseException:
                if backup is not None:
                    backup.replace(output)
                raise
            if backup is not None:
                shutil.rmtree(backup)
            return {**manifest, 'output': str(output), 'cacheHit': False, 'builderGoExecutable': str(go)}
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True, choices=['darwin-arm64', 'windows-amd64'])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cache', type=Path, default=ROOT / '.tools/go-tools-cache')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--report', type=Path)
    try:
        args = parser.parse_args()
        result = bundle(args)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            atomic_json(args.report, result)
        print(json.dumps({
            'status': 'pass', 'target': result['target'], 'output': result['output'],
            'builderGoExecutable': result['builderGoExecutable'],
            'runtimeSdkVersion': result['runtimeSdkVersion'], 'builderVersion': result['builderVersion'],
            'tools': {name: {key: data[key] for key in ['version', 'executable', 'sha256']}
                      for name, data in result['tools'].items()},
            'manifest': str(Path(result['output']) / 'manifest.json'), 'cacheHit': result['cacheHit'],
        }, ensure_ascii=False))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
