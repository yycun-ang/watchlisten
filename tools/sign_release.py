"""Sign an unsigned APK with private environment configuration; no secret is logged."""
from pathlib import Path
import argparse
import hashlib
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def sdk_path():
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured)
    local = ROOT / "local.properties"
    if local.exists():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    raise RuntimeError("Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK")


def required(name):
    value = os.environ.get(name)
    if not value:
        raise RuntimeError("Missing environment variable: " + name)
    return value


def signer(prefix):
    key = Path(required(prefix + "_KEYSTORE")).resolve(strict=True)
    alias = required(prefix + "_ALIAS")
    required(prefix + "_STORE_PASSWORD")
    if not os.environ.get(prefix + "_KEY_PASSWORD"):
        os.environ[prefix + "_KEY_PASSWORD"] = os.environ[prefix + "_STORE_PASSWORD"]
    return ["--ks", str(key), "--ks-key-alias", alias, "--ks-pass", "env:" + prefix + "_STORE_PASSWORD",
            "--key-pass", "env:" + prefix + "_KEY_PASSWORD"]


def run(command):
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                               encoding="utf-8", errors="replace")
    if completed.returncode:
        # Do not echo signer arguments or environment to logs.
        raise RuntimeError(Path(command[0]).name + " failed; check SDK, key alias, passwords and lineage")
    return completed.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--lineage", type=Path, default=os.environ.get("WATCHLISTEN_RELEASE_LINEAGE"),
                        help="Optional public rotation proof; requires WATCHLISTEN_LEGACY_* for APK v2 compatibility")
    parser.add_argument("--build-tools", default="35.0.0")
    args = parser.parse_args()
    source = args.input.resolve(strict=True)
    target = args.output.resolve()
    if source == target:
        raise RuntimeError("Input must be unsigned and separate from output")
    suffix = ".bat" if os.name == "nt" else ""
    apksigner = sdk_path() / "build-tools" / args.build_tools / ("apksigner" + suffix)
    if not apksigner.is_file():
        raise RuntimeError("Requested Android build-tools are not installed")
    arguments = [str(apksigner), "sign", "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                 "--v3-signing-enabled", "true", "--v4-signing-enabled", "false"]
    if args.lineage:
        arguments += ["--lineage", str(args.lineage.resolve(strict=True)), "--rotation-min-sdk-version", "28"]
        arguments += signer("WATCHLISTEN_LEGACY") + ["--next-signer"]
    arguments += signer("WATCHLISTEN_RELEASE")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.stem + ".signing.apk")
    try:
        run(arguments + ["--out", str(temporary), str(source)])
        verified = run([str(apksigner), "verify", "--verbose", "--print-certs", str(temporary)])
        # The default verifier starts at manifest minSdk 30 and therefore reports
        # v2 as unused when v3 succeeds. Verify the compatibility block explicitly.
        compatibility = run([str(apksigner), "verify", "--verbose", "--min-sdk-version", "24",
                             "--max-sdk-version", "27", str(temporary)])
        if "v2): true" not in compatibility or "v3): true" not in verified:
            raise RuntimeError("APK v2/v3 signature verification failed")
        certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", verified)
        temporary.replace(target)
        print("Signed APK: " + str(target))
        print("SHA-256: " + hashlib.sha256(target.read_bytes()).hexdigest())
        if certificate:
            print("Certificate SHA-256: " + certificate.group(1))
        print("APK v2/v3 verified; private key material was not exported")
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print("Release signing failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
