import importlib.util
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
