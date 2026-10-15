#!/usr/bin/env python3
"""Package the image-only banking starter; never build images or publish artifacts."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    **{name: f"deploy/banking-starter/{name}" for name in (
        "compose.yaml", "init.py", "starter.sh", "README.md", "LICENSE", "NOTICE",
        "models/gemini.env.example", "models/ollama.env.example",
    )},
    "bank-backend.input.schema.json": "banking-advice-agent/bank-backend.input.schema.json",
    "scenario.json": "banking-advice-agent/issued-statement-configuration.json",
}
COMPATIBILITY = {"platform": "0.2.0", "pythonSdk": "0.3.2", "contracts": "1.2.0"}
LOCAL_COMPATIBILITY = {"platform": "0.2.0", "pythonSdk": "0.3.3", "contracts": "1.2.1"}
IMAGE_VARIABLES = {"banking": "BANKING_IMAGE", "advice": "ADVICE_IMAGE", "postgres": "POSTGRES_IMAGE"}
DIGEST_IMAGE = re.compile(r"[a-z0-9][a-z0-9._:/-]*@sha256:[0-9a-f]{64}")
TAGGED_IMAGE = re.compile(r"[a-z0-9][a-z0-9._:/-]*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def anonymous_docker_environment(directory):
    original = Path(os.environ.get("DOCKER_CONFIG", Path.home() / ".docker"))
    config_file = original / "config.json"
    configuration = json.loads(config_file.read_text()) if config_file.is_file() else {}
    plugins = configuration.get("cliPluginsExtraDirs", [])
    require(isinstance(plugins, list) and all(isinstance(path, str) for path in plugins),
            "Docker cliPluginsExtraDirs must contain directory paths")
    # Keep plugin discovery, never copy registry credentials or credential helpers.
    plugin_paths = [str(Path(path).expanduser().resolve()) for path in plugins]
    plugin_paths.append(str((original / "cli-plugins").resolve()))
    write_json(Path(directory) / "config.json", {"cliPluginsExtraDirs": list(dict.fromkeys(plugin_paths))})
    return {**os.environ, "DOCKER_CONFIG": str(directory)}


def verify_images(images, local_verification, source_revision):
    local_ids = {}
    with tempfile.TemporaryDirectory(prefix="astraform-anonymous-registry-") as configuration:
        environment = None if local_verification else anonymous_docker_environment(configuration)
        for name, image in images.items():
            if local_verification:
                identity = subprocess.check_output([
                    "docker", "image", "inspect", image, "--format", "{{.Id}}",
                ], text=True, timeout=30).strip()
                require(re.fullmatch(r"sha256:[0-9a-f]{64}", identity),
                        f"{name} is not an installed local image")
                local_ids[name] = identity
                continue
            manifest = json.loads(subprocess.check_output([
                "docker", "buildx", "imagetools", "inspect", image,
                "--format", "{{json .Manifest}}",
            ], text=True, timeout=120, env=environment))
            require(manifest.get("digest") == image.split("@", 1)[1],
                    f"{name} registry digest does not match the lock")
            required = {("linux", "amd64"), ("linux", "arm64")}
            platforms = {(entry.get("platform", {}).get("os"),
                          entry.get("platform", {}).get("architecture"))
                         for entry in manifest.get("manifests", [])}
            require(required.issubset(platforms), f"{name} must expose Linux AMD64 and ARM64 images")
            # Reading the pinned configuration blobs also requires anonymous access.
            configs = json.loads(subprocess.check_output([
                "docker", "buildx", "imagetools", "inspect", image,
                "--format", "{{json .Image}}",
            ], text=True, timeout=120, env=environment))
            configured = set()
            for entry in configs.values():
                platform = (entry.get("os"), entry.get("architecture"))
                if platform not in required:
                    continue
                if name != "postgres":
                    labels = entry.get("config", {}).get("Labels", {})
                    require(labels.get("org.opencontainers.image.revision") == source_revision,
                            f"{name} source revision does not match for {platform}")
                configured.add(platform)
            require(required.issubset(configured), f"{name} has incomplete platform configuration metadata")
    return local_ids


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def package_bundle(version, images, output, source_revision, files, bundle_name,
                   compatibility, schema_version, local_verification=False,
                   configuration=None, required_platform=None, compatibility_meaning=None):
    """One deterministic archive writer shared by the existing sample starters."""
    require(re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version),
            "version must be an explicit release version such as v0.2.0 or v0.2.0-local.1")
    require(isinstance(source_revision, str) and re.fullmatch(r"[0-9a-f]{40}", source_revision),
            "source revision must be a full Git commit")
    require(isinstance(images, dict) and images, "image lock must not be empty")
    for name, image in images.items():
        pinned = isinstance(image, str) and DIGEST_IMAGE.fullmatch(image)
        tagged = (isinstance(image, str) and TAGGED_IMAGE.fullmatch(image)
                  and not image.startswith("sha256:"))
        require(pinned or (local_verification and tagged),
                f"{name} must be digest-pinned; installed tags require --local-verification")
    for filename, relative in files.items():
        require(not Path(filename).is_absolute() and ".." not in Path(filename).parts,
                "Starter destination must stay inside the archive")
        source = ROOT / relative
        require(source.is_file() and not source.is_symlink() and source.resolve().is_relative_to(ROOT.resolve()),
                f"Missing or unsafe starter source: {relative}")
    output = Path(output).resolve()
    names = (bundle_name, bundle_name + ".tar.gz", bundle_name + ".tar.gz.sha256")
    require(not any((output / name).exists() or (output / name).is_symlink() for name in names),
            "Output already exists; choose a fresh output directory or version")
    local_ids = verify_images(images, local_verification, source_revision)
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".astraform-starter-", dir=output) as directory:
        staging = Path(directory)
        bundle = staging / bundle_name
        bundle.mkdir()
        for filename, relative in files.items():
            target = bundle / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        write_json(bundle / "image-lock.json", images)
        variables = {name: name.upper() + "_IMAGE" for name in images}
        if schema_version == "astraform.banking-starter.bundle.v1":
            variables = IMAGE_VARIABLES
        (bundle / "release.env").write_text("".join(
            f"{variables[key]}={images[key]}\n" for key in sorted(images)), encoding="utf-8")
        if configuration is not None:
            write_json(bundle / "starter-config.json", configuration)
        manifest = {
            "schemaVersion": schema_version,
            "version": version,
            "sourceRevision": source_revision,
            "compatibility": compatibility,
            "images": images,
            "files": {path.relative_to(bundle).as_posix(): {
                "sha256": file_hash(path), "sizeBytes": path.stat().st_size,
            } for path in sorted(bundle.rglob("*")) if path.is_file()},
        }
        if required_platform:
            manifest["requiredPlatform"] = required_platform
        if compatibility_meaning:
            manifest["compatibilityMeaning"] = compatibility_meaning
        if local_verification:
            manifest.update(localVerificationOnly=True, localImageIds=local_ids,
                sourceRevisionMeaning="Baseline checkout commit only; uncommitted operator-file hashes and installed image IDs identify this local candidate.")
        write_json(bundle / "bundle-manifest.json", manifest)
        for path in [bundle, *bundle.rglob("*")]:
            path.chmod(0o755 if path.is_dir() or path.name in ("init.py", "starter.sh") else 0o644)

        def metadata(member):
            member.uid = member.gid = member.mtime = 0
            member.uname = member.gname = ""
            return member

        archive = staging / names[1]
        with archive.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w") as tar:
                    tar.add(bundle, arcname=bundle_name, filter=metadata)
        (staging / names[2]).write_text(f"{file_hash(archive)}  {archive.name}\n", encoding="utf-8")
        archive.chmod(0o644)
        (staging / names[2]).chmod(0o644)
        for name in names:
            (staging / name).rename(output / name)
    return output / bundle_name


def package(version, images, output, local_verification=False, source_revision=None,
            bank="python", scenario="issued"):
    require(bank in {"python", "java"}, "bank must be python or java")
    require(scenario in {"issued", "monthly"}, "scenario must be issued or monthly")
    require(isinstance(images, dict) and set(images) == set(IMAGE_VARIABLES),
            "image lock must contain exactly banking, advice and postgres references")
    files = {**FILES, "RELEASE-NOTES.md": f"docs/releases/{version}.md"}
    files["scenario.json"] = "banking-advice-agent/" + (
        "issued-statement-configuration.json" if scenario == "issued" else "three-month-configuration.json")
    source = ROOT / files["scenario.json"]
    require(source.is_file() and not source.is_symlink(), f"Missing or unsafe starter source: {files['scenario.json']}")
    selected = json.loads(source.read_text())
    policy = selected.get("launchRequestTemplate", {}).get("completionPolicy", {})
    finish_present = "requireCustomerFinish" in policy
    finish = policy.get("requireCustomerFinish")
    require(not finish_present or type(finish) is bool,
            "completionPolicy.requireCustomerFinish must be a Boolean when present")
    require(local_verification or (bank == "python" and scenario == "issued" and not finish_present),
            "New starter variants and customer-FINISH criteria require --local-verification; publication is not authorized")
    configuration = {
        "schemaVersion": "astraform.banking-starter.configuration.v1",
        "bankImplementation": bank, "scenario": scenario,
        "bankPort": 8092 if bank == "python" else 8094,
        "domainId": "banking-python" if bank == "python" else "banking",
        "nativeDomainEnabled": scenario == "monthly",
        "requiresUnreleasedPlatform": finish_present,
        "requiredPlatformCapabilities": (["retainedParticipationSupported"] if scenario == "monthly" else [])
            + (["completionPolicy.requireCustomerFinish"] if finish_present else []),
        "requiresUnreleasedCustomerSdk": False,
        "requiredCustomerSdkCapabilities": ["completionPolicy.requireCustomerFinish"] if finish_present else [],
    }
    compatibility = {**(LOCAL_COMPATIBILITY if local_verification else COMPATIBILITY),
                     **({"javaSdk": "0.4.3"} if bank == "java" else {})}
    if finish_present:
        compatibility["platform"] = "unreleased-local-customer-finish"
    bundle_name = (f"astraform-banking-starter-{version}" if bank == "python" and scenario == "issued"
                   else f"astraform-{bank}-banking-{scenario}-starter-{version}")
    return package_bundle(version, images, output, source_revision, files, bundle_name,
                          compatibility, "astraform.banking-starter.bundle.v1", local_verification,
                          configuration, {"completionPolicy.requireCustomerFinish": finish} if finish_present else None,
                          "Local compatibility records current source dependency targets: Python SDK 0.3.3, contracts 1.2.1 and Java SDK 0.4.3 when selected. Actual image contents require separate installed-package verification against the recorded image IDs. Native customer completion is available in published Python SDK 0.3.3; a supporting local platform is still required when completionPolicy.requireCustomerFinish is present. See starter-config.json requiredCustomerSdkCapabilities."
                          if local_verification else None)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision",
                        help="Full samples source commit used for both service images; defaults to HEAD only for local rehearsal")
    parser.add_argument("--image-lock", required=True, type=Path,
                        help="JSON object with banking, advice and postgres image references")
    parser.add_argument("--output", required=True, type=Path,
                        help="Parent directory for the versioned folder, archive and checksum")
    parser.add_argument("--bank", choices=("python", "java"), default="python", help="Bank service implementation")
    parser.add_argument("--scenario", choices=("issued", "monthly"), default="issued", help="Issued statement or native three-month statement scenario")
    parser.add_argument("--local-verification", action="store_true",
                        help="Rehearsal only: allow installed image tags and record their local IDs")
    args = parser.parse_args()
    source_revision = args.source_revision
    if source_revision is None and args.local_verification:
        source_revision = subprocess.check_output(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True, timeout=30).strip()
    bundle = package(args.version, json.loads(args.image_lock.read_text()), args.output,
                     args.local_verification, source_revision, args.bank, args.scenario)
    print(f"Prepared {bundle}; no images, release or public download were published.")
    if args.local_verification:
        print("Local verification only; this archive requires the recorded images already installed.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
