#!/usr/bin/env python3
import os
import platform
import subprocess
import sys
import tarfile
import urllib.request

BB_VERSION = "1.3.191"


def get_bb_binary():
    system = platform.system().lower()
    arch = platform.machine().lower()

    match (system, arch):
        case ("linux", "x86_64" | "amd64"):
            variant, suffix = "linux-amd64", "-static"
        case ("linux", "aarch64" | "arm64"):
            variant, suffix = "linux-aarch64", "-static"
        case ("darwin", "x86_64" | "amd64"):
            variant, suffix = "macos-amd64", ""
        case ("darwin", "aarch64" | "arm64"):
            variant, suffix = "macos-aarch64", ""
        case _:
            raise RuntimeError(f"Unsupported platform: {system}/{arch}")

    cached = f"/tmp/bb-{variant}-{BB_VERSION}"
    if not os.path.exists(cached):
        url = f"https://github.com/babashka/babashka/releases/download/v{BB_VERSION}/babashka-{BB_VERSION}-{variant}{suffix}.tar.gz"
        print(f"Downloading babashka v{BB_VERSION}...")
        tarball = cached + ".tar.gz"
        urllib.request.urlretrieve(url, tarball)
        with tarfile.open(tarball, "r:gz") as tar:
            member = tar.getmember("bb")
            with tar.extractfile(member) as src, open(cached, "wb") as out:
                out.write(src.read())
        os.unlink(tarball)
        os.chmod(cached, 0o755)

    return cached


def main():
    bb_task = os.environ.get("bb_task", "sync")
    bb_args = os.environ.get("bb_args", "")

    bb_binary = get_bb_binary()

    cmd = [bb_binary, bb_task]
    if bb_args:
        cmd.extend(bb_args.split())

    result = subprocess.run(cmd, check=False)
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()
