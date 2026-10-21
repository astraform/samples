#!/usr/bin/env python3
"""Prepare reviewed starter assets and publish one Astraform Samples release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "astraform/samples"
REVISION = re.compile(r"[0-9a-f]{40}")
VERSION = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
SHA256 = re.compile(r"[0-9a-f]{64}")
IMAGE = re.compile(r"[a-z0-9][a-z0-9._:/-]*@sha256:[0-9a-f]{64}")
BANKING_PACKAGER = "scripts/package-banking-starter.py"
MARKETING_PACKAGER = "scripts/package-marketing-starter.py"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*arguments, **kwargs):
    return subprocess.run(arguments, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def reviewed_starters(configuration):
    """Validate every public selection before fetching source or creating output."""
    require(isinstance(configuration, dict), "release.json must be an object")
    require(set(configuration) <= {"version", "publicationReady", "platformRelease", "starters"},
            "Unknown release setting; use the reviewed starter selectors")
    platform = configuration.get("platformRelease")
    if "platformRelease" in configuration:
        require(isinstance(platform, str) and VERSION.fullmatch(platform),
                "platformRelease must be one stable vMAJOR.MINOR.PATCH version")
        major, minor, patch = map(int, platform[1:].split("."))
        require(major == 0 and (major, minor, patch) >= (0, 3, 0),
                "platformRelease must be a supporting v0.3.0 or later v0 release")
    starters = configuration.get("starters")
    require(isinstance(starters, list) and starters, "Select at least one reviewed starter")
    names, selections, reviewed = set(), set(), []
    for starter in starters:
        require(isinstance(starter, dict), "Each starter must be an object")
        require(set(starter) <= {"name", "packagingRevision", "sourceRevision", "packager",
                                  "bank", "scenario", "images", "archiveSha256"},
                "Unknown starter setting; arbitrary packager arguments are not supported")
        name = starter.get("name")
        require(isinstance(name, str) and re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name)
                and name not in names, "Starter names must be distinct, lowercase identifiers")
        names.add(name)
        for key in ("packagingRevision", "sourceRevision"):
            require(isinstance(starter.get(key), str) and REVISION.fullmatch(starter[key]),
                    f"{name}: {key} must be an immutable full commit")
        require(isinstance(starter.get("archiveSha256"), str) and SHA256.fullmatch(starter["archiveSha256"]),
                f"{name}: archiveSha256 must be the reviewed SHA-256")
        packager = starter.get("packager")
        require(isinstance(packager, str) and packager in {BANKING_PACKAGER, MARKETING_PACKAGER},
                f"{name}: select a known starter packager")
        legacy = platform is None and packager == BANKING_PACKAGER and not ({"bank", "scenario"} & set(starter))
        if legacy:
            require(configuration["version"] == "v0.1.0",
                    "New releases require platformRelease and explicit starter selectors")
            # The immutable v0.1.0 public packager predates these CLI options.
            bank, scenario, arguments = "python", "issued", []
        elif packager == BANKING_PACKAGER:
            require(platform is not None, f"{name}: new public starters require platformRelease")
            bank, scenario = starter.get("bank"), starter.get("scenario")
            require(isinstance(bank, str) and isinstance(scenario, str)
                    and (bank, scenario) in {("python", "issued"), ("java", "issued"), ("java", "monthly")},
                    f"{name}: select reviewed Python issued, Java issued or Java monthly banking")
            arguments = ["--bank", bank, "--scenario", scenario, "--platform-release", platform]
        else:
            require(platform is not None, f"{name}: new public starters require platformRelease")
            require("bank" not in starter and starter.get("scenario") == "scheduled",
                    f"{name}: public marketing must select the reviewed scheduled scenario without bank")
            bank, scenario = None, "scheduled"
            arguments = ["--scenario", scenario, "--platform-release", platform]
        selection = (packager, bank, scenario)
        require(selection not in selections, "Starter selections must produce distinct archives")
        selections.add(selection)
        images = starter.get("images")
        expected = {"banking", "advice", "postgres"} if packager == BANKING_PACKAGER else {"domain", "agency", "tools"}
        require(isinstance(images, dict) and set(images) == expected,
                f"{name}: image lock must contain exactly {', '.join(sorted(expected))}")
        require(all(isinstance(image, str) and IMAGE.fullmatch(image) for image in images.values()),
                f"{name}: all public images must be digest-pinned")
        version = configuration["version"]
        archive = (f"astraform-banking-starter-{version}.tar.gz" if bank == "python"
                   else f"astraform-java-banking-{scenario}-starter-{version}.tar.gz" if bank == "java"
                   else f"astraform-marketing-scheduled-starter-{version}.tar.gz")
        reviewed.append((starter, arguments, archive))
    return reviewed


def prepare(output):
    configuration = json.loads((ROOT / "release.json").read_text())
    require(isinstance(configuration, dict), "release.json must be an object")
    version = configuration["version"]
    require(isinstance(version, str) and VERSION.fullmatch(version), "Use one samples version: vMAJOR.MINOR.PATCH")
    require(configuration.get("publicationReady") is True,
            "release.json is not ready: select the verified image digests, public packaging revision and reviewed archive SHA-256 first")
    starters = reviewed_starters(configuration)
    notes = ROOT / "docs/releases" / f"{version}.md"
    require(notes.is_file(), f"Missing shared release notes: {notes.name}")
    require(not output.exists() and not output.is_symlink(), "Choose an unused output directory")
    release_revision = run("git", "-C", str(ROOT), "rev-parse", "HEAD")
    require(REVISION.fullmatch(release_revision), "Release checkout must identify an immutable full commit")
    # Staging never appears among the uploaded public files.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="astraform-samples-release-") as temporary:
        staging = Path(temporary)
        assets = staging / "assets"
        assets.mkdir()
        manifest = {"version": version, "publicationReady": True,
                    "releaseCommit": release_revision, "assets": {}, "starters": []}
        if "platformRelease" in configuration:
            manifest["platformRelease"] = configuration["platformRelease"]
        for index, (starter, selectors, expected_archive) in enumerate(starters):
            name = starter["name"]
            source = (staging / f"source-{index}").resolve()
            run("git", "init", "--quiet", str(source))
            run("git", "-C", str(source), "fetch", "--quiet", "--depth=1", "--no-tags",
                f"https://github.com/{REPOSITORY}.git", starter["packagingRevision"])
            run("git", "-C", str(source), "checkout", "--quiet", "--detach", "FETCH_HEAD")
            require(run("git", "-C", str(source), "rev-parse", "HEAD") == starter["packagingRevision"],
                    f"{name}: public packaging checkout differs from the release selection")
            selected_packager = source / starter["packager"]
            packager = selected_packager.resolve()
            require(packager.is_file() and not selected_packager.is_symlink() and packager.is_relative_to(source),
                    "Packager must be inside public source")
            lock = staging / f"images-{index}.json"
            write_json(lock, starter["images"])
            packaged = staging / f"packaged-{index}"
            run(sys.executable, str(packager), "--version", version,
                "--source-revision", starter["sourceRevision"], "--image-lock", str(lock),
                "--output", str(packaged), *selectors)
            archives = list(packaged.glob("*.tar.gz"))
            require(len(archives) == 1, f"{name}: expected one starter archive")
            archive = archives[0]
            require(archive.is_file() and not archive.is_symlink(), f"{name}: archive must be a regular file")
            require(archive.name == expected_archive, f"{name}: archive does not match the reviewed starter selection")
            require(digest(archive) == starter["archiveSha256"],
                    f"{name}: archive differs from the reviewed SHA-256; do not publish it")
            checksum = Path(str(archive) + ".sha256")
            require(checksum.is_file() and not checksum.is_symlink(), f"{name}: checksum must be a regular file")
            require(checksum.read_text().strip() == f"{digest(archive)}  {archive.name}",
                    f"{name}: checksum file does not identify this archive")
            for path in (archive, checksum):
                require(path.name not in manifest["assets"], "Starter asset names must be unique")
                shutil.copyfile(path, assets / path.name)
                manifest["assets"][path.name] = digest(path)
            manifest["starters"].append({**starter, "archive": archive.name})
        shutil.copyfile(notes, assets / "RELEASE-NOTES.md")
        manifest["releaseNotesSha256"] = digest(assets / "RELEASE-NOTES.md")
        write_json(assets / "release-manifest.json", manifest)
        shutil.copytree(assets, output)
    print(f"Prepared Astraform Samples {version}; no images, tags or releases were published.")
    for name, checksum in manifest["assets"].items():
        print(f"{checksum}  {name}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(f"version={version}\n")
    return manifest


def github_exists(endpoint):
    result = subprocess.run(["gh", "api", endpoint], text=True, capture_output=True)
    if result.returncode == 0:
        return True
    require("HTTP 404" in result.stderr, f"Cannot check GitHub state: {result.stderr.strip()}")
    return False


def publish(directory):
    manifest_path = directory / "release-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    version = manifest["version"]
    require(isinstance(version, str) and VERSION.fullmatch(version), "Invalid samples release version")
    require(manifest.get("publicationReady") is True, "Candidate is not marked ready for publication")
    require(os.environ.get("GITHUB_REF") == "refs/heads/main", "Publish from the reviewed main branch")
    require(manifest["releaseCommit"] == os.environ.get("GITHUB_SHA")
            and REVISION.fullmatch(manifest["releaseCommit"]), "Candidate must come from this exact workflow commit")
    notes = directory / "RELEASE-NOTES.md"
    require(digest(notes) == manifest["releaseNotesSha256"], "Release notes changed after preparation")
    files = []
    for name, checksum in manifest["assets"].items():
        require(Path(name).name == name and name.endswith((".tar.gz", ".tar.gz.sha256")), "Invalid release asset name")
        path = directory / name
        require(path.is_file() and not path.is_symlink() and digest(path) == checksum,
                f"Prepared asset changed: {name}")
        files.append(path)
    require(files, "No starter assets to publish")
    require(not github_exists(f"repos/{REPOSITORY}/releases/tags/{version}"),
            "Release already exists; inspect it rather than overwriting it")
    require(not github_exists(f"repos/{REPOSITORY}/git/ref/tags/{version}"),
            "Tag already exists; never move or replace a release tag")
    files.append(manifest_path)
    run("gh", "release", "create", version, "--repo", REPOSITORY, "--draft",
        "--target", manifest["releaseCommit"], "--title", version,
        "--notes-file", str(notes), *(str(path) for path in files))
    # Verify the actual uploaded bytes before making the draft public.
    with tempfile.TemporaryDirectory(prefix="astraform-release-readback-") as temporary:
        run("gh", "release", "download", version, "--repo", REPOSITORY, "--dir", temporary)
        downloaded = Path(temporary)
        require({path.name for path in downloaded.iterdir()} == {path.name for path in files},
                "Draft assets differ from the prepared release; draft left for inspection")
        for path in files:
            require(digest(downloaded / path.name) == digest(path),
                    f"Uploaded bytes differ for {path.name}; draft left for inspection")
    run("gh", "release", "edit", version, "--repo", REPOSITORY, "--draft=false", "--latest")
    print(f"Published https://github.com/{REPOSITORY}/releases/tag/{version}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "publish"))
    parser.add_argument("--directory", type=Path, required=True)
    arguments = parser.parse_args()
    if arguments.operation == "prepare":
        prepare(arguments.directory.resolve())
    else:
        publish(arguments.directory.resolve())


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr.strip(), file=sys.stderr)
        sys.exit(1)
