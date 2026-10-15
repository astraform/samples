import importlib.util
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "samples_release", Path(__file__).resolve().parents[1] / "release-samples.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class SamplesPrepareTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "checkout"
        self.root.mkdir()
        self.output = self.root.parent / "output"
        notes = self.root / "docs/releases/v0.2.0.md"
        notes.parent.mkdir(parents=True)
        notes.write_text("Reviewed four-starter release\n")
        self.commands = []
        self.packaging_revision = "b" * 40
        self.source_revision = "a" * 40
        self.release_revision = "c" * 40
        self.configuration = {"version": "v0.2.0", "publicationReady": True,
                              "platformRelease": "v0.3.0", "starters": [
            self.starter("python-issued", release.BANKING_PACKAGER, "issued", "python"),
            self.starter("java-issued", release.BANKING_PACKAGER, "issued", "java"),
            self.starter("java-monthly", release.BANKING_PACKAGER, "monthly", "java"),
            self.starter("marketing-scheduled", release.MARKETING_PACKAGER, "scheduled"),
        ]}
        self.enterContext(patch.object(release, "ROOT", self.root))
        self.enterContext(patch.dict(os.environ, {}, clear=True))
        self.wrong_archive_name = False
        self.corrupt_archive = False
        self.corrupt_checksum = False
        self.wrong_checkout = False

    def starter(self, name, packager, scenario, bank=None):
        roles = ("banking", "advice", "postgres") if bank else ("domain", "agency", "tools")
        selection = {"name": name, "packagingRevision": self.packaging_revision,
                     "sourceRevision": self.source_revision, "packager": packager,
                     "scenario": scenario,
                     "images": {role: f"ghcr.io/astraform/{role}@sha256:" + "d" * 64 for role in roles},
                     "archiveSha256": hashlib.sha256(name.encode()).hexdigest()}
        if bank:
            selection["bank"] = bank
        return selection

    def command(self, *arguments, **kwargs):
        self.commands.append(arguments)
        if arguments[0] == "git":
            if arguments[1] == "init":
                source = Path(arguments[-1])
                (source / "scripts").mkdir(parents=True)
                for name in ("package-banking-starter.py", "package-marketing-starter.py"):
                    (source / "scripts" / name).write_text("# public packager\n")
            elif arguments[-2:] == ("rev-parse", "HEAD"):
                if arguments[2] == str(self.root):
                    return self.release_revision
                return "e" * 40 if self.wrong_checkout else self.packaging_revision
            return ""
        self.assertEqual(arguments[0], release.sys.executable)
        source_index = int(Path(arguments[1]).parents[1].name.split("-")[-1])
        selected = self.configuration["starters"][source_index]
        destination = Path(arguments[arguments.index("--output") + 1])
        destination.mkdir()
        bank = selected.get("bank", "python" if selected["packager"] == release.BANKING_PACKAGER else None)
        scenario = selected.get("scenario", "issued")
        version = self.configuration["version"]
        name = (f"astraform-banking-starter-{version}.tar.gz" if bank == "python"
                else f"astraform-java-banking-{scenario}-starter-{version}.tar.gz" if bank == "java"
                else f"astraform-marketing-scheduled-starter-{version}.tar.gz")
        if self.wrong_archive_name:
            name = f"unexpected-{version}.tar.gz"
        archive = destination / name
        archive.write_bytes(b"unreviewed" if self.corrupt_archive else selected["name"].encode())
        checksum = Path(str(archive) + ".sha256")
        checksum.write_text(f"{release.digest(archive)}  {'different.tar.gz' if self.corrupt_checksum else name}\n")
        return ""

    def prepare(self):
        release.write_json(self.root / "release.json", self.configuration)
        with patch.object(release, "run", side_effect=self.command):
            return release.prepare(self.output)

    def assert_invalid_before_effects(self, expected):
        release.write_json(self.root / "release.json", self.configuration)
        with patch.object(release, "run") as command, self.assertRaisesRegex(ValueError, expected):
            release.prepare(self.output)
        command.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_prepares_four_unique_archives_with_exact_reviewed_selectors(self):
        manifest = self.prepare()
        self.assertEqual(manifest["platformRelease"], "v0.3.0")
        self.assertEqual(manifest["releaseCommit"], self.release_revision)
        self.assertEqual(len(manifest["assets"]), 8)
        self.assertEqual(len(manifest["starters"]), 4)
        self.assertEqual(set(path.name for path in self.output.iterdir()),
                         {*manifest["assets"], "RELEASE-NOTES.md", "release-manifest.json"})
        packaged = [args for args in self.commands if args[0] == release.sys.executable]
        self.assertEqual([args[args.index("--scenario") + 1] for args in packaged],
                         ["issued", "issued", "monthly", "scheduled"])
        self.assertEqual([args[args.index("--bank") + 1] for args in packaged[:3]], ["python", "java", "java"])
        self.assertNotIn("--bank", packaged[-1])
        for args in packaged:
            self.assertEqual(args[args.index("--platform-release") + 1], "v0.3.0")
            self.assertEqual(args[args.index("--source-revision") + 1], self.source_revision)
            self.assertNotIn("--local-verification", args)
        fetched = [args for args in self.commands if "fetch" in args]
        self.assertEqual(len(fetched), 4)
        for args in fetched:
            self.assertEqual(args[-2:], ("https://github.com/astraform/samples.git", self.packaging_revision))
            self.assertIn("--no-tags", args)
        for asset, checksum in manifest["assets"].items():
            self.assertEqual(release.digest(self.output / asset), checksum)

    def test_historical_v010_selection_omits_new_packager_flags(self):
        self.configuration = {"version": "v0.1.0", "publicationReady": True,
                              "starters": [self.configuration["starters"][0]]}
        for key in ("bank", "scenario"):
            self.configuration["starters"][0].pop(key)
        (self.root / "docs/releases/v0.1.0.md").write_text("Historical release\n")
        manifest = self.prepare()
        command = next(args for args in self.commands if args[0] == release.sys.executable)
        for flag in ("--bank", "--scenario", "--platform-release", "--local-verification"):
            self.assertNotIn(flag, command)
        self.assertNotIn("platformRelease", manifest)
        self.assertIn("astraform-banking-starter-v0.1.0.tar.gz", manifest["assets"])

    def test_invalid_final_selection_rejected_before_any_git_or_packaging(self):
        invalid = [
            ({"packager": "scripts/unreviewed.py"}, "known starter packager"),
            ({"packager": "../package-banking-starter.py"}, "known starter packager"),
            ({"scenario": "current"}, "reviewed scheduled"),
            ({"bank": "java"}, "without bank"),
            ({"extraArgs": ["--local-verification"]}, "arbitrary packager arguments"),
            ({"sourceRevision": "main"}, "immutable full commit"),
            ({"packagingRevision": "HEAD"}, "immutable full commit"),
            ({"archiveSha256": "REPLACE_AFTER_REVIEW"}, "reviewed SHA-256"),
            ({"images": {"domain": "ghcr.io/astraform/domain:latest"}}, "image lock must contain exactly"),
        ]
        original = copy.deepcopy(self.configuration)
        for changes, message in invalid:
            with self.subTest(changes=changes):
                self.configuration = copy.deepcopy(original)
                self.configuration["starters"][-1].update(changes)
                self.assert_invalid_before_effects(message)

    def test_unreviewed_banking_combinations_and_duplicate_archives_rejected(self):
        for changes in ({"bank": "python", "scenario": "monthly"}, {"bank": "java", "scenario": "current"},
                        {"bank": ["java"]}, {"scenario": None}):
            with self.subTest(changes=changes):
                original = copy.deepcopy(self.configuration)
                self.configuration["starters"][0].update(changes)
                self.assert_invalid_before_effects("select reviewed")
                self.configuration = original
        self.configuration["starters"][1]["bank"] = "python"
        self.assert_invalid_before_effects("distinct archives")

    def test_missing_invalid_or_unsupported_platform_release_rejected_before_effects(self):
        for platform in (None, "v0.2.0", "v0.3.0-rc.1", "0.3.0", "v1.0.0", []):
            with self.subTest(platform=platform):
                original = copy.deepcopy(self.configuration)
                if platform is None:
                    self.configuration.pop("platformRelease")
                else:
                    self.configuration["platformRelease"] = platform
                self.assert_invalid_before_effects("platformRelease")
                self.configuration = original

    def test_public_tagged_image_rejected_before_effects(self):
        self.configuration["starters"][-1]["images"]["agency"] = "ghcr.io/astraform/agency:latest"
        self.assert_invalid_before_effects("digest-pinned")

    def test_draft_placeholder_selection_never_reaches_git(self):
        self.configuration["publicationReady"] = False
        self.configuration["starters"][0]["archiveSha256"] = "REPLACE_AFTER_REVIEW"
        self.assert_invalid_before_effects("release.json is not ready")

    def test_existing_or_dangling_output_is_refused_before_git(self):
        self.output.mkdir()
        self.assert_invalid_before_effects_without_output_check("unused output directory")
        self.output.rmdir()
        self.output.symlink_to(self.root.parent / "missing-directory")
        self.assert_invalid_before_effects_without_output_check("unused output directory")

    def assert_invalid_before_effects_without_output_check(self, expected):
        release.write_json(self.root / "release.json", self.configuration)
        with patch.object(release, "run") as command, self.assertRaisesRegex(ValueError, expected):
            release.prepare(self.output)
        command.assert_not_called()

    def test_checkout_and_archive_must_match_the_reviewed_selection(self):
        for flag, message in (("wrong_checkout", "public packaging checkout differs"),
                              ("wrong_archive_name", "archive does not match"),
                              ("corrupt_archive", "archive differs from the reviewed SHA-256"),
                              ("corrupt_checksum", "checksum file does not identify")):
            with self.subTest(flag=flag):
                setattr(self, flag, True)
                with self.assertRaisesRegex(ValueError, message):
                    self.prepare()
                self.assertFalse(self.output.exists())
                setattr(self, flag, False)


class SamplesReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.notes = self.directory / "RELEASE-NOTES.md"
        self.notes.write_text("Shared samples release\n")
        self.revision = "a" * 40
        self.enterContext(patch.dict(os.environ, {
            "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": self.revision,
        }))
        assets = {}
        # Two distinct starters exercise one release with multiple assets.
        for name in ("banking", "another-domain"):
            archive = self.directory / f"astraform-{name}-v0.1.0.tar.gz"
            archive.write_bytes(name.encode())
            checksum = Path(str(archive) + ".sha256")
            checksum.write_text(f"{release.digest(archive)}  {archive.name}\n")
            for path in (archive, checksum):
                assets[path.name] = release.digest(path)
        self.manifest = {"version": "v0.1.0", "publicationReady": True, "releaseCommit": self.revision,
                         "assets": assets, "releaseNotesSha256": release.digest(self.notes)}
        self.manifest_path = self.directory / "release-manifest.json"
        release.write_json(self.manifest_path, self.manifest)
        self.commands = []
        self.corrupt_download = False

    def github(self, *arguments, **kwargs):
        self.commands.append(arguments)
        if arguments[1:3] == ("release", "download"):
            destination = Path(arguments[arguments.index("--dir") + 1])
            for name in [*self.manifest["assets"], "release-manifest.json"]:
                shutil.copyfile(self.directory / name, destination / name)
            if self.corrupt_download:
                (destination / next(iter(self.manifest["assets"]))).write_bytes(b"wrong uploaded bytes")
        return ""

    def test_publishes_all_starters_only_after_draft_bytes_match(self):
        with patch.object(release, "github_exists", return_value=False), \
                patch.object(release, "run", side_effect=self.github):
            release.publish(self.directory)
        self.assertEqual([call[2] for call in self.commands], ["create", "download", "edit"])
        created = self.commands[0]
        self.assertEqual(created[created.index("--target") + 1], self.revision)
        self.assertIn("--draft", created)
        for name in self.manifest["assets"]:
            self.assertIn(str(self.directory / name), created)
        self.assertIn("--draft=false", self.commands[-1])

    def test_changed_uploaded_bytes_leave_draft_unpublished(self):
        self.corrupt_download = True
        with patch.object(release, "github_exists", return_value=False), \
                patch.object(release, "run", side_effect=self.github), \
                self.assertRaisesRegex(ValueError, "Uploaded bytes differ"):
            release.publish(self.directory)
        self.assertNotIn("edit", [call[2] for call in self.commands])

    def test_changed_candidate_never_reaches_github(self):
        (self.directory / next(iter(self.manifest["assets"]))).write_bytes(b"changed locally")
        with patch.object(release, "github_exists") as github, self.assertRaisesRegex(ValueError, "Prepared asset changed"):
            release.publish(self.directory)
        github.assert_not_called()

    def test_unready_release_selection_never_fetches_or_packages(self):
        release.write_json(self.directory / "release.json", {
            "version": "v0.1.0", "publicationReady": False, "starters": [],
        })
        with patch.object(release, "ROOT", self.directory), \
                patch.object(release, "run") as command, \
                self.assertRaisesRegex(ValueError, "release.json is not ready"):
            release.prepare(self.directory / "output")
        command.assert_not_called()
        self.assertFalse((self.directory / "output").exists())

    def test_unready_candidate_never_reaches_github(self):
        self.manifest["publicationReady"] = False
        release.write_json(self.manifest_path, self.manifest)
        with patch.object(release, "github_exists") as github, \
                patch.object(release, "run") as command, \
                self.assertRaisesRegex(ValueError, "not marked ready"):
            release.publish(self.directory)
        github.assert_not_called()
        command.assert_not_called()

    def test_existing_release_or_tag_is_never_overwritten(self):
        for existing in ([True], [False, True]):
            with self.subTest(existing=existing), \
                    patch.object(release, "github_exists", side_effect=existing), \
                    patch.object(release, "run") as command, self.assertRaisesRegex(ValueError, "already exists"):
                release.publish(self.directory)
            command.assert_not_called()

    def test_lookup_failure_is_not_treated_as_an_unused_release(self):
        for error, missing in (("HTTP 404: Not Found", True), ("HTTP 403: Forbidden", False)):
            with self.subTest(error=error), patch.object(release.subprocess, "run", return_value=
                    subprocess.CompletedProcess([], 1, stdout="", stderr=error)):
                if missing:
                    self.assertFalse(release.github_exists("repos/astraform/samples/releases/tags/v0.1.0"))
                else:
                    with self.assertRaisesRegex(ValueError, "Cannot check GitHub state"):
                        release.github_exists("repos/astraform/samples/releases/tags/v0.1.0")


if __name__ == "__main__":
    unittest.main()
