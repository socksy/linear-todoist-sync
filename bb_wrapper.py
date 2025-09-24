#!/usr/bin/env python3
import os
import subprocess
import sys
import platform
import glob
import shutil

def get_default_task():
    """Find the first .bb file in the directory"""
    bb_files = glob.glob('*.bb')
    if bb_files:
        return os.path.splitext(bb_files[0])[0]
    return 'main'

def main():
    # Get the babashka task from environment variable or use smart default
    bb_task = os.environ.get('bb_task')
    if not bb_task:
        bb_task = get_default_task()
        print(f'No bb_task specified, using: {bb_task}')

    # Get any additional task arguments from environment
    bb_args = os.environ.get('bb_args', '')

    # Determine babashka binary based on platform and architecture
    system = platform.system().lower()
    arch = platform.machine().lower()

    if system == 'linux':
        if arch in ['x86_64', 'amd64']:
            bb_binary = 'bin/bb-linux-amd64'
        elif arch in ['aarch64', 'arm64']:
            bb_binary = 'bin/bb-linux-aarch64'
        else:
            raise RuntimeError(f'Unsupported Linux architecture: {arch}')
    else:
        # For local development on non-Linux systems, try to use system bb
        bb_binary = shutil.which('bb')
        if not bb_binary:
            raise RuntimeError(f'babashka not found in PATH. Install babashka or run on Linux with the bundled binaries.')

    # Run the babashka task
    try:
        cmd = [bb_binary, bb_task]
        if bb_args:
            cmd.extend(bb_args.split())
        result = subprocess.run(cmd, check=True)
        sys.exit(result.returncode)
    except subprocess.CalledProcessError as e:
        print(f'Error running babashka task: {e}')
        sys.exit(e.returncode)

if __name__ == '__main__':
    main()