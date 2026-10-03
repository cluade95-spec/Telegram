"""Fingerprint native cache inputs; ccache also validates every compilation.

Only compiler objects are cached. Build directories, shared libraries, signing
outputs and Java/API credentials are never restored from this cache.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess


def fingerprint(root, ndk, cmake):
    digest = hashlib.sha256()
    contents = {}

    def add(label, path):
        stat = path.stat()
        identity = (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns)
        if identity not in contents:
            data = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    data.update(block)
            contents[identity] = data.digest()
        digest.update(label.encode() + b'\0' + contents[identity])

    # Includes sources, headers, CMake scripts, prebuilt archives, recursive
    # submodule contents, the complete NDK/sysroot/LLVM tools, and CMake/Ninja.
    # Hash file contents, not checkout timestamps. Git metadata is not an input.
    for label, directory in (
        ('native', root / 'TMessagesProj/jni'),
        ('ndk', ndk),
        ('cmake', cmake),
        ('buildSrc', root / 'buildSrc'),
    ):
        if not directory.is_dir():
            raise SystemExit(f'Missing cache input directory: {directory}')
        for current, dirs, files in os.walk(directory):
            dirs[:] = sorted(name for name in dirs if name != '.git')
            for name in sorted(files):
                if name != '.git':
                    path = Path(current) / name
                    add(f'{label}/{path.relative_to(directory)}', path)

    paths = subprocess.check_output(
        ['git', 'ls-files', '-z'], cwd=root
    ).decode().split('\0')
    for name in sorted(paths):
        if name and (name.endswith(('.gradle', '.gradle.kts', '.properties'))
                     or name.startswith('gradle/wrapper/')):
            add(name, root / name)
    for name in ('qa-build.gradle', 'qa-native-key.py'):
        add(f'ci/{name}', root / '.github/scripts' / name)
    for tool in ('ccache', 'nasm'):
        executable = shutil.which(tool)
        if not executable:
            if tool == 'ccache':
                raise SystemExit('Missing native tool: ccache')
            # ASM_NASM is declared but no NASM executable is needed by the
            # measured target. Preserve this fact in the key without installing one.
            digest.update(f'host-tool/{tool}=absent\0'.encode())
        else:
            add(f'host-tool/{tool}', Path(executable))

    # Ccache retains default header/argument/CWD checks, including time macros.
    # No sloppiness, ignored headers, or broad fallback cache keys are enabled.
    metadata = {
        'schema': 1,
        'system': platform.system(),
        'arch': platform.machine(),
        'workspace': str(root.resolve()),
        'image': [os.environ.get('ImageOS'), os.environ.get('ImageVersion')],
        'variant': 'afatStandalone/RelWithDebInfo/tmessages.49',
        'abis': ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'],
        'environment': {name: os.environ.get(name, '') for name in
                        ('CC', 'CXX', 'CFLAGS', 'CXXFLAGS', 'CPPFLAGS', 'LDFLAGS')},
        'ccache_config': Path(os.environ['CCACHE_CONFIGPATH']).read_text(),
    }
    digest.update(json.dumps(metadata, sort_keys=True).encode())
    return 'qa-native-v1-' + digest.hexdigest()


if __name__ == '__main__':
    sdk = Path(os.environ['ANDROID_HOME'])
    print('key=' + fingerprint(Path.cwd(), sdk / 'ndk/27.2.12479018',
                              sdk / 'cmake/3.22.1'))
