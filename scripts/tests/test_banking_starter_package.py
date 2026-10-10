import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "banking_starter_package", Path(__file__).resolve().parents[1] / "package-banking-starter.py")
packager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packager)


class BankingStarterPackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        for destination, relative in packager.FILES.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(destination + "\n")
        (self.root / "deploy/banking-starter/private.env").write_text("DO_NOT_DISTRIBUTE=secret\n")
        self.root_patch = patch.object(packager, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.source_revision = "1" * 40
        self.docker_configuration = Path(self.temporary.name) / "docker"
        self.docker_configuration.mkdir()
        self.enterContext(patch.dict(os.environ, {"DOCKER_CONFIG": str(self.docker_configuration)}))
        self.output = Path(self.temporary.name) / "output"
        self.images = {key: f"registry.example/{key}@sha256:" + "a" * 64
                       for key in ("banking", "advice", "postgres")}

    def registry(self, command, **kwargs):
        configuration = Path(kwargs["env"]["DOCKER_CONFIG"])
        self.assertTrue(configuration.is_dir())
        self.assertEqual({"config.json"}, {path.name for path in configuration.iterdir()})
        self.assertEqual({"cliPluginsExtraDirs"}, set(json.loads((configuration / "config.json").read_text())))
        if command[-1] == "{{json .Manifest}}":
            return json.dumps({"digest": "sha256:" + "a" * 64, "manifests": [
                {"platform": {"os": "linux", "architecture": arch}} for arch in ("amd64", "arm64")]})
        return json.dumps({f"linux/{arch}": {"os": "linux", "architecture": arch,
                           "config": {"Labels": {"org.opencontainers.image.revision": self.source_revision}}}
                           for arch in ("amd64", "arm64")})

    def test_public_archive_contains_only_starter_files_and_verifiable_metadata(self):
        with patch.object(packager.subprocess, "check_output", side_effect=self.registry):
            bundle = packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision)
        manifest = json.loads((bundle / "bundle-manifest.json").read_text())
        self.assertNotIn("localVerificationOnly", manifest)
        self.assertEqual(self.source_revision, manifest["sourceRevision"])
        self.assertEqual({"platform": "0.2.0", "pythonSdk": "0.3.1", "contracts": "1.2.0"},
                         manifest["compatibility"])
        self.assertEqual(self.images, json.loads((bundle / "image-lock.json").read_text()))
        self.assertEqual("scenario.json\n", (bundle / "scenario.json").read_text())
        self.assertEqual(set(packager.FILES) | {"image-lock.json", "release.env"}, set(manifest["files"]))
        for name, metadata in manifest["files"].items():
            self.assertEqual(hashlib.sha256((bundle / name).read_bytes()).hexdigest(), metadata["sha256"])
        self.assertIn("BANKING_IMAGE=" + self.images["banking"], (bundle / "release.env").read_text())
        archive = self.output / (bundle.name + ".tar.gz")
        self.assertEqual(f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}\n",
                         archive.with_name(archive.name + ".sha256").read_text())
        with tarfile.open(archive) as tar:
            files = {member.name.removeprefix(bundle.name + "/") for member in tar if member.isfile()}
            self.assertEqual(set(manifest["files"]) | {"bundle-manifest.json"}, files)
            self.assertEqual(0o755, tar.getmember(bundle.name + "/starter.sh").mode)
            self.assertEqual(0o644, tar.getmember(bundle.name + "/scenario.json").mode)

    def test_local_rehearsal_records_installed_image_ids_without_registry_calls(self):
        tags = {key: "astraform-test/" + key + ":review" for key in self.images}
        def inspect(command, **kwargs):
            self.assertEqual(["docker", "image", "inspect"], command[:3])
            return "sha256:" + "b" * 64 + "\n"
        with patch.object(packager.subprocess, "check_output", side_effect=inspect):
            bundle = packager.package("v0.1.0-local.1", tags, self.output, local_verification=True,
                                      source_revision=self.source_revision)
        manifest = json.loads((bundle / "bundle-manifest.json").read_text())
        self.assertTrue(manifest["localVerificationOnly"])
        self.assertEqual({key: "sha256:" + "b" * 64 for key in tags}, manifest["localImageIds"])
        self.assertEqual(tags, manifest["images"])

    def test_archive_is_reproducible_across_source_mtime_and_permission_changes(self):
        with patch.object(packager.subprocess, "check_output", side_effect=self.registry):
            bundle = packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision)
            for relative in packager.FILES.values():
                path = self.root / relative
                os.utime(path, (1800000000, 1800000000))
                path.chmod(0o600)
            other = self.output / "another"
            packager.package("v0.1.0", self.images, other, source_revision=self.source_revision)
        name = bundle.name + ".tar.gz"
        self.assertEqual((self.output / name).read_bytes(), (other / name).read_bytes())

    def test_invalid_lock_or_missing_source_fails_before_docker_and_output(self):
        cases = [
            {**self.images, "banking": "sample/bank:latest"},
            {**self.images, "unexpected": self.images["banking"]},
            {key: value for key, value in self.images.items() if key != "postgres"},
        ]
        with patch.object(packager.subprocess, "check_output") as docker:
            for revision in (None, "main", "123abc"):
                with self.subTest(revision=revision), self.assertRaisesRegex(ValueError, "full Git commit"):
                    packager.package("v0.1.0", self.images, self.output, source_revision=revision)
            for images in cases:
                with self.subTest(images=images), self.assertRaises(ValueError):
                    packager.package("v0.1.0", images, self.output, source_revision=self.source_revision)
            (self.root / packager.FILES["scenario.json"]).unlink()
            with self.assertRaisesRegex(ValueError, "Missing or unsafe"):
                packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision)
            docker.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_inaccessible_or_wrong_registry_images_do_not_create_an_archive(self):
        for fault in ("private", "wrong-digest", "missing-architecture", "missing-config", "wrong-source"):
            def inspect(command, **kwargs):
                if fault == "private":
                    raise subprocess.CalledProcessError(1, command, stderr="unauthorized")
                data = json.loads(self.registry(command, **kwargs))
                if command[-1] == "{{json .Manifest}}":
                    if fault == "wrong-digest":
                        data["digest"] = "sha256:" + "c" * 64
                    elif fault == "missing-architecture":
                        data["manifests"].pop()
                elif fault == "missing-config":
                    data.pop("linux/arm64")
                elif fault == "wrong-source":
                    data["linux/arm64"]["config"]["Labels"]["org.opencontainers.image.revision"] = "2" * 40
                return json.dumps(data)
            with self.subTest(fault=fault), patch.object(packager.subprocess, "check_output", side_effect=inspect):
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision)
                self.assertFalse(self.output.exists())

    def test_anonymous_registry_access_preserves_plugins_without_credentials(self):
        original = self.docker_configuration / "config.json"
        configured_plugin_directory = Path(self.temporary.name) / "extra-plugins"
        configured_plugin_directory.mkdir()
        original.write_text(json.dumps({
            "cliPluginsExtraDirs": [str(configured_plugin_directory)],
            "auths": {"registry.example": {"auth": "private-credential"}},
            "credsStore": "desktop", "credHelpers": {"ghcr.io": "private-helper"},
        }))
        before = original.read_bytes()
        def inspect(command, **kwargs):
            anonymous = Path(kwargs["env"]["DOCKER_CONFIG"])
            self.assertNotEqual(self.docker_configuration, anonymous)
            self.assertEqual({"cliPluginsExtraDirs": [str(configured_plugin_directory.resolve()),
                              str((self.docker_configuration / "cli-plugins").resolve())]},
                             json.loads((anonymous / "config.json").read_text()))
            return self.registry(command, **kwargs)
        with patch.object(packager.subprocess, "check_output", side_effect=inspect):
            packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision)
        self.assertEqual(before, original.read_bytes())


class BankingStarterInitializationTest(unittest.TestCase):
    def setUp(self):
        repository = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location(
            "banking_starter_init", repository / "deploy/banking-starter/init.py")
        self.initializer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.initializer)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "starter"
        (self.root / "models").mkdir(parents=True)
        self.platform = Path(temporary.name) / "platform"
        (self.platform / "private").mkdir(parents=True)
        (self.platform / ".env").write_text("COMPOSE_PROJECT_NAME=astraform-trial\n")
        self.runtime = self.platform / "private/runtime.properties"
        self.original = "# Existing connections\nexisting.setting=preserved\n"
        self.runtime.write_text(self.original)
        for name in ("release.env", "image-lock.json"):
            (self.root / name).write_text("{}\n")
        schema = repository / "banking-advice-agent/bank-backend.input.schema.json"
        (self.root / schema.name).write_bytes(schema.read_bytes())
        for profile in ("gemini", "ollama"):
            template = repository / f"deploy/banking-starter/models/{profile}.env.example"
            (self.root / "models" / template.name).write_bytes(template.read_bytes())
        self.enterContext(patch.object(self.initializer, "ROOT", self.root))

    def test_initialization_preserves_runtime_file_and_refuses_duplicate_configuration(self):
        inode = self.runtime.stat().st_ino
        self.initializer.initialize(self.platform, "gemini")
        backup = self.root / "private/runtime.properties.before-starter"
        self.assertEqual(self.original, backup.read_text())
        self.assertEqual(inode, self.runtime.stat().st_ino)  # Existing bind mounts keep the same file.
        self.assertTrue(self.runtime.read_text().startswith(self.original))
        self.assertEqual(0o600, (self.root / "private/partner.env").stat().st_mode & 0o777)
        before = {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        runtime = self.runtime.read_bytes()
        with self.assertRaisesRegex(ValueError, "already initialized"):
            self.initializer.initialize(self.platform, "ollama")
        self.assertEqual(runtime, self.runtime.read_bytes())
        self.assertEqual(before, {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()})

    def test_compose_uses_saved_project_and_images_even_for_down(self):
        self.initializer.initialize(self.platform, "gemini")
        images = {key: f"registry.example/{key}@sha256:" + "a" * 64
                  for key in ("banking", "advice", "postgres")}
        (self.root / "image-lock.json").write_text(json.dumps(images))
        (self.root / "release.env").write_text(
            f"BANKING_IMAGE={images['banking']}\nADVICE_IMAGE={images['advice']}\nPOSTGRES_IMAGE={images['postgres']}\n")
        saved_project = self.initializer.env(self.root / ".env")["COMPOSE_PROJECT_NAME"]
        with patch.dict(os.environ, {"COMPOSE_PROJECT_NAME": "unrelated-production",
                                    "ADVICE_IMAGE": "unrelated/agent:latest"}):
            for arguments in (["up", "-d"], ["down"]):
                with self.subTest(arguments=arguments), patch.object(self.initializer.subprocess, "run") as run:
                    run.return_value.returncode = 0
                    self.assertEqual(0, self.initializer.compose(arguments))
                    command = run.call_args.args[0]
                    self.assertEqual(saved_project, command[command.index("--project-name") + 1])
                    self.assertEqual(saved_project, run.call_args.kwargs["env"]["COMPOSE_PROJECT_NAME"])
                    self.assertEqual(images["advice"], run.call_args.kwargs["env"]["ADVICE_IMAGE"])
                    self.assertEqual(arguments, command[-len(arguments):])

    def test_existing_bank_binding_is_not_overwritten(self):
        existing = self.original + "simulation.event.runtime.mcp.bindings.banking.endpoint=http://another-bank/mcp\n"
        self.runtime.write_text(existing)
        with self.assertRaisesRegex(ValueError, "already has banking bindings"):
            self.initializer.initialize(self.platform, "gemini")
        self.assertEqual(existing, self.runtime.read_text())
        self.assertFalse((self.root / ".env").exists())
        self.assertFalse((self.root / "private").exists())


if __name__ == "__main__":
    unittest.main()
