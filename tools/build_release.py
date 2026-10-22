#!/usr/bin/env python3
"""Build and verify GitHub release assets; keep signing material outside the repository."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def initialize_signing(config):
    if config.exists():
        raise RuntimeError("Signing configuration already exists; reuse it without --init-signing.")
    config.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    keystore = config.parent / "release-signing.p12"
    if keystore.exists():
        raise RuntimeError("Keystore already exists. Restore its configuration instead of replacing it.")
    password = secrets.token_hex(32)
    java_home = os.environ.get("JAVA_HOME")
    keytool = str(Path(java_home) / "bin/keytool") if java_home else shutil.which("keytool")
    if not keytool:
        raise RuntimeError("Set JAVA_HOME to JDK 21.")
    env = os.environ.copy()
    env["UNLEASHED_KEYSTORE_PASSWORD"] = password
    subprocess.run([
        keytool, "-genkeypair", "-alias", "unleashed", "-keyalg", "RSA",
        "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "10000",
        "-dname", "CN=unleashed", "-storetype", "PKCS12",
        "-keystore", str(keystore), "-storepass:env", "UNLEASHED_KEYSTORE_PASSWORD",
        "-keypass:env", "UNLEASHED_KEYSTORE_PASSWORD",
    ], env=env, check=True)
    keystore.chmod(0o600)
    with open(config, "x", opener=lambda p, f: os.open(p, f, 0o600)) as stream:
        # Java properties escaping; the password itself is plain hexadecimal.
        escaped_path = str(keystore).replace("\\", "\\\\").replace(":", "\\:")
        stream.write(f"storeFile={escaped_path}\nstorePassword={password}\n"
                     f"keyAlias=unleashed\nkeyPassword={password}\n")
    print(f"Created signing material in {config.parent}. Back up both private files securely.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--init-signing", action="store_true",
                        help="Create the signing key once, then build. Never use for an existing release key.")
    args = parser.parse_args()
    config = Path(os.environ.get("UNLEASHED_SIGNING_PROPERTIES",
                  str(Path.home() / ".config/unleashed/release-signing.properties"))).resolve()
    if config.is_relative_to(ROOT):
        raise RuntimeError("Keep signing material outside the repository.")
    if args.init_signing:
        initialize_signing(config)
    if not config.is_file():
        raise RuntimeError("Release signing is missing. Restore the existing signing configuration or set UNLEASHED_SIGNING_PROPERTIES.")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise RuntimeError("Set ANDROID_HOME to your Android SDK.")
    build_tools = Path(sdk) / "build-tools/36.0.0"
    for name in ("apksigner", "aapt2"):
        if not (build_tools / name).is_file():
            raise RuntimeError("Install Android SDK Build-tools 36.0.0.")
    env = os.environ.copy()
    env["UNLEASHED_SIGNING_PROPERTIES"] = str(config)
    subprocess.run([
        str(ROOT / "gradlew"), ":app:assembleRelease", ":app:testDebugUnitTest",
        ":app:lintRelease",
    ], cwd=ROOT, env=env, check=True)
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    certificate = subprocess.check_output([
        str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)
    ], text=True, env=env)
    manifest = subprocess.check_output([
        str(build_tools / "aapt2"), "dump", "badging", str(apk)
    ], text=True, env=env)
    if "application-debuggable" in manifest:
        raise RuntimeError("Refusing to distribute a debuggable APK.")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text())[1]
    output = ROOT / "artifacts/release"
    output.mkdir(parents=True, exist_ok=True)
    destination = output / "unleashed.apk"
    shutil.copy2(apk, destination)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  {destination.name}\n")
    (output / "signing-certificate.txt").write_text(certificate)
    print(f"Verified signed release v{version}: {destination}")
    print(f"SHA-256: {digest}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
