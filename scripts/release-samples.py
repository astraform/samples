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


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*arguments, **kwargs):
    return subprocess.run(arguments, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def prepare(output):
    configuration = json.loads((ROOT / "release.json").read_text())
    version = configuration["version"]
    require(isinstance(version, str) and VERSION.fullmatch(version), "Use one samples version: vMAJOR.MINOR.PATCH")
    require(configuration.get("publicationReady") is True,
            "release.json is not ready: select the verified image digests, public packaging revision and reviewed archive SHA-256 first")
    release_revision = run("git", "-C", str(ROOT), "rev-parse", "HEAD")
    notes = ROOT / "docs/releases" / f"{version}.md"
    require(notes.is_file(), f"Missing shared release notes: {notes.name}")
    require(not output.exists(), "Choose an unused output directory")
    starters = configuration["starters"]
    require(isinstance(starters, list) and starters, "Select at least one reviewed starter")
    # Staging never appears among the uploaded public files.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="astraform-samples-release-") as temporary:
        staging = Path(temporary)
        assets = staging / "assets"
        assets.mkdir()
        manifest = {"version": version, "publicationReady": True,
                    "releaseCommit": release_revision, "assets": {}, "starters": []}
        names = set()
        for index, starter in enumerate(starters):
            name = starter["name"]
            require(isinstance(name, str) and re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name)
                    and name not in names, "Starter names must be distinct, lowercase identifiers")
            names.add(name)
            for key in ("packagingRevision", "sourceRevision"):
                require(isinstance(starter[key], str) and REVISION.fullmatch(starter[key]),
                        f"{name}: {key} must be an immutable full commit")
            source = (staging / f"source-{index}").resolve()
            run("git", "init", "--quiet", str(source))
            run("git", "-C", str(source), "fetch", "--quiet", "--depth=1", "--no-tags",
                f"https://github.com/{REPOSITORY}.git", starter["packagingRevision"])
            run("git", "-C", str(source), "checkout", "--quiet", "--detach", "FETCH_HEAD")
            require(run("git", "-C", str(source), "rev-parse", "HEAD") == starter["packagingRevision"],
                    f"{name}: public packaging checkout differs from the release selection")
            packager = (source / starter["packager"]).resolve()
            require(packager.is_file() and packager.is_relative_to(source), "Packager must be inside public source")
            lock = staging / f"images-{index}.json"
            write_json(lock, starter["images"])
            packaged = staging / f"packaged-{index}"
            run(sys.executable, str(packager), "--version", version,
                "--source-revision", starter["sourceRevision"], "--image-lock", str(lock),
                "--output", str(packaged))
            archives = list(packaged.glob("*.tar.gz"))
            require(len(archives) == 1, f"{name}: expected one starter archive")
            archive = archives[0]
            require(digest(archive) == starter["archiveSha256"],
                    f"{name}: archive differs from the reviewed SHA-256; do not publish it")
            checksum = Path(str(archive) + ".sha256")
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
        "--target", manifest["releaseCommit"], "--title", f"Astraform Samples {version}",
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
