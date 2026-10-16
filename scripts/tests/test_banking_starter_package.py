import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
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
        self.files = {**packager.FILES, "RELEASE-NOTES.md": "docs/releases/v0.1.0.md"}
        for destination, relative in self.files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(destination + "\n")
        self.scenario = {"launchRequestTemplate": {"completionPolicy": {"mode": "GOAL"}}}
        (self.root / packager.FILES["scenario.json"]).write_text(json.dumps(self.scenario))
        (self.root / "banking-advice-agent/three-month-configuration.json").write_text(json.dumps(self.scenario))
        (self.root / "docs/releases/v0.1.0-local.1.md").write_text("Local rehearsal notes\n")
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
            bundle = packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision, platform_release="v0.3.0")
        manifest = json.loads((bundle / "bundle-manifest.json").read_text())
        self.assertNotIn("localVerificationOnly", manifest)
        self.assertEqual(self.source_revision, manifest["sourceRevision"])
        self.assertEqual(self.images, json.loads((bundle / "image-lock.json").read_text()))
        self.assertEqual(self.scenario, json.loads((bundle / "scenario.json").read_text()))
        self.assertEqual(set(self.files) | {"image-lock.json", "release.env", "starter-config.json"}, set(manifest["files"]))
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
            bundle = packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision, platform_release="v0.3.0")
            for relative in self.files.values():
                path = self.root / relative
                os.utime(path, (1800000000, 1800000000))
                path.chmod(0o600)
            other = self.output / "another"
            packager.package("v0.1.0", self.images, other, source_revision=self.source_revision, platform_release="v0.3.0")
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
                    packager.package("v0.1.0", self.images, self.output, source_revision=revision,
                                     platform_release="v0.3.0")
            for images in cases:
                with self.subTest(images=images), self.assertRaises(ValueError):
                    packager.package("v0.1.0", images, self.output, source_revision=self.source_revision, platform_release="v0.3.0")
            (self.root / packager.FILES["scenario.json"]).unlink()
            with self.assertRaisesRegex(ValueError, "Missing or unsafe"):
                packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision, platform_release="v0.3.0")
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
                    packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision, platform_release="v0.3.0")
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
            packager.package("v0.1.0", self.images, self.output, source_revision=self.source_revision, platform_release="v0.3.0")
        self.assertEqual(before, original.read_bytes())

    def test_all_bank_scenario_variants_preserve_implementation_and_platform_requirements(self):
        tags = {key: "astraform-test/" + key + ":review" for key in self.images}
        for bank in ("python", "java"):
            for scenario in ("issued", "monthly"):
                with self.subTest(bank=bank, scenario=scenario), patch.object(packager.subprocess, "check_output", return_value="sha256:" + "b" * 64 + "\n"):
                    bundle = packager.package("v0.1.0", tags, self.output / (bank + scenario), True, self.source_revision, bank, scenario)
                manifest = json.loads((bundle / "bundle-manifest.json").read_text())
                configuration = json.loads((bundle / "starter-config.json").read_text())
                self.assertTrue(manifest["localVerificationOnly"])
                self.assertEqual(8092 if bank == "python" else 8094, configuration["bankPort"])
                self.assertEqual("banking-python" if bank == "python" else "banking", configuration["domainId"])
                self.assertEqual(scenario == "monthly", configuration["nativeDomainEnabled"])

    def test_public_variants_require_an_explicit_supporting_platform_release(self):
        with patch.object(packager.subprocess, "check_output") as docker:
            for bank, scenario in (("java", "issued"), ("python", "monthly"), ("java", "monthly")):
                with self.subTest(bank=bank, scenario=scenario), self.assertRaisesRegex(ValueError, "platform-release"):
                    packager.package("v0.1.0", self.images, self.output, False, self.source_revision, bank, scenario)
            self.scenario["launchRequestTemplate"]["completionPolicy"]["requireCustomerFinish"] = True
            (self.root / packager.FILES["scenario.json"]).write_text(json.dumps(self.scenario))
            with self.assertRaisesRegex(ValueError, "platform-release"):
                packager.package("v0.1.0", self.images, self.output, False, self.source_revision)
            docker.assert_not_called()
        self.assertFalse(self.output.exists())
        with patch.object(packager.subprocess, "check_output", return_value="sha256:" + "b" * 64 + "\n"):
            bundle = packager.package("v0.1.0", self.images, self.output, True, self.source_revision)
        manifest = json.loads((bundle / "bundle-manifest.json").read_text())
        self.assertTrue(manifest["requiredPlatform"]["completionPolicy.requireCustomerFinish"])
        self.assertTrue(json.loads((bundle / "starter-config.json").read_text())["requiresUnreleasedPlatform"])

    def test_any_explicit_finish_boolean_records_native_customer_sdk_requirement(self):
        for value in (False, True):
            with self.subTest(value=value):
                self.scenario["launchRequestTemplate"]["completionPolicy"]["requireCustomerFinish"] = value
                (self.root / packager.FILES["scenario.json"]).write_text(json.dumps(self.scenario))
                with patch.object(packager.subprocess, "check_output") as docker:
                    with self.assertRaisesRegex(ValueError, "platform-release"):
                        packager.package("v0.1.0", self.images, self.output / f"public-{value}", False, self.source_revision)
                    docker.assert_not_called()
                with patch.object(packager.subprocess, "check_output", return_value="sha256:" + "b" * 64 + "\n"):
                    bundle = packager.package("v0.1.0", self.images, self.output / str(value), True, self.source_revision)
                configuration = json.loads((bundle / "starter-config.json").read_text())
                manifest = json.loads((bundle / "bundle-manifest.json").read_text())
                self.assertFalse(configuration["requiresUnreleasedCustomerSdk"])
                self.assertEqual(["completionPolicy.requireCustomerFinish"], configuration["requiredCustomerSdkCapabilities"])
                self.assertTrue(configuration["requiresUnreleasedPlatform"])
                self.assertIs(value, manifest["requiredPlatform"]["completionPolicy.requireCustomerFinish"])
                self.assertEqual(self.scenario, json.loads((bundle / "scenario.json").read_text()))
                self.assertNotIn("customer-run-evidence.py", manifest["files"])

    def test_public_bank_variants_preserve_completion_and_native_scheduling_requirements(self):
        for bank in ("python", "java"):
            for scenario in ("issued", "monthly"):
                for finish in (False, True):
                    selected = {"launchRequestTemplate": {"completionPolicy": {
                        "mode": "GOAL", "requireCustomerFinish": finish}}}
                    scenario_file = "issued-statement-configuration.json" if scenario == "issued" else "three-month-configuration.json"
                    (self.root / "banking-advice-agent" / scenario_file).write_text(json.dumps(selected))
                    with self.subTest(bank=bank, scenario=scenario, finish=finish), \
                         patch.object(packager.subprocess, "check_output", side_effect=self.registry):
                        bundle = packager.package("v0.1.0", self.images,
                                                  self.output / f"{bank}-{scenario}-{finish}",
                                                  False, self.source_revision, bank, scenario, "v0.3.0")
                    manifest = json.loads((bundle / "bundle-manifest.json").read_text())
                    configuration = json.loads((bundle / "starter-config.json").read_text())
                    self.assertNotIn("localVerificationOnly", manifest)
                    self.assertNotIn("localImageIds", manifest)
                    self.assertFalse(configuration["requiresUnreleasedPlatform"])
                    self.assertFalse(configuration["requiresUnreleasedCustomerSdk"])
                    self.assertEqual("v0.3.0", manifest["requiredPlatform"]["release"])
                    self.assertIs(finish, manifest["requiredPlatform"]["completionPolicy.requireCustomerFinish"])
                    self.assertEqual(["completionPolicy.requireCustomerFinish"], configuration["requiredCustomerSdkCapabilities"])
                    capabilities = (["retainedParticipationSupported"] if scenario == "monthly" else []) + ["completionPolicy.requireCustomerFinish"]
                    self.assertEqual(capabilities, configuration["requiredPlatformCapabilities"])
                    self.assertEqual(True if scenario == "monthly" else None,
                                     manifest["requiredPlatform"].get("retainedParticipationSupported"))
                    self.assertEqual(selected, json.loads((bundle / "scenario.json").read_text()))

    def test_public_platform_release_validation_and_local_mode_are_unambiguous(self):
        with patch.object(packager.subprocess, "check_output") as docker:
            for release in (None, "v0.2.0", "v0.2.99", "v1.0.0", "v0.3.0-SNAPSHOT", "0.3.0", "v00.3.0", "latest", True):
                with self.subTest(release=release), self.assertRaises(ValueError):
                    packager.package("v0.1.0", self.images, self.output,
                                     source_revision=self.source_revision, platform_release=release)
            with self.assertRaisesRegex(ValueError, "cannot be combined"):
                packager.package("v0.1.0", self.images, self.output, True, self.source_revision,
                                 platform_release="v0.3.0")
            docker.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_public_packaging_never_overwrites_a_prepared_archive(self):
        with patch.object(packager.subprocess, "check_output", side_effect=self.registry):
            bundle = packager.package("v0.1.0", self.images, self.output,
                                      source_revision=self.source_revision, platform_release="v0.3.0")
        archive = Path(str(bundle) + ".tar.gz")
        original = archive.read_bytes()
        with patch.object(packager.subprocess, "check_output") as docker:
            with self.assertRaisesRegex(ValueError, "already exists"):
                packager.package("v0.1.0", self.images, self.output,
                                 source_revision=self.source_revision, platform_release="v0.3.0")
            docker.assert_not_called()
        self.assertEqual(original, archive.read_bytes())

    def test_invalid_finish_value_fails_before_images_or_archive(self):
        for value in (None, "true", 0):
            with self.subTest(value=value):
                self.scenario["launchRequestTemplate"]["completionPolicy"]["requireCustomerFinish"] = value
                (self.root / packager.FILES["scenario.json"]).write_text(json.dumps(self.scenario))
                with patch.object(packager.subprocess, "check_output") as docker:
                    with self.assertRaisesRegex(ValueError, "must be a Boolean"):
                        packager.package("v0.1.0", self.images, self.output, True, self.source_revision)
                    docker.assert_not_called()
                self.assertFalse(self.output.exists())

    def test_image_owned_customer_imports_with_only_shared_evidence_neighbor(self):
        repository = Path(__file__).resolve().parents[2]
        standalone = Path(self.temporary.name) / "standalone-image-files"
        standalone.mkdir()
        for source, name in (("banking-advice-agent/run-customer.py", "run-customer.py"),
                             ("integration/customer-run-evidence.py", "customer-run-evidence.py")):
            shutil.copyfile(repository / source, standalone / name)
        result = subprocess.run([sys.executable, "-B", "run-customer.py", "--help"],
                                cwd=standalone, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--inspect", result.stdout)


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

    def test_ollama_initialization_keeps_partner_call_within_a2a_budget(self):
        self.initializer.initialize(self.platform, "ollama")
        partner = self.initializer.env(self.root / "private/partner.env")
        bindings = (self.root / "private/runtime-bindings.properties").read_text()
        timeout = next(line.split("=", 1)[1] for line in bindings.splitlines()
                       if line.startswith("simulation.event.runtime.a2a.request-timeout="))
        self.assertEqual("900s", timeout)
        self.assertIn("simulation.event.runtime.a2a.poll-timeout=900s\n", bindings)
        self.assertEqual("840", partner["BANKING_LLM_TIMEOUT_SECONDS"])
        self.assertLess(10 + float(partner["BANKING_LLM_TIMEOUT_SECONDS"]), float(timeout[:-1]))
        self.assertEqual("banking-starter-qwen35-4b-thinking-v8", partner["BANKING_LLM_BINDING_REVISION"])
        self.assertEqual("32768", partner["BANKING_LLM_MAX_OUTPUT_TOKENS"])
        self.assertEqual("high", partner["BANKING_LLM_REASONING_EFFORT"])
        self.assertEqual("1.0", partner["BANKING_LLM_TEMPERATURE"])
        self.assertEqual(self.original, (self.root / "private/runtime.properties.before-starter").read_text())
        self.assertTrue(self.runtime.read_text().startswith(self.original))

    def test_existing_ollama_poll_timeout_is_preserved_before_any_starter_write(self):
        existing = self.original + "simulation.event.runtime.a2a.poll-timeout=90s\n"
        self.runtime.write_text(existing)
        with self.assertRaisesRegex(ValueError, "already has an A2A poll timeout"):
            self.initializer.initialize(self.platform, "ollama")
        self.assertEqual(existing, self.runtime.read_text())
        self.assertFalse((self.root / ".env").exists())
        self.assertFalse((self.root / "private").exists())

    def test_gemini_thinking_profile_sets_matching_revision_and_bounded_request_and_poll(self):
        self.initializer.initialize(self.platform, "gemini")
        partner = self.initializer.env(self.root / "private/partner.env")
        bindings = self.initializer.env(self.root / "private/runtime-bindings.properties")
        self.assertEqual("900s", bindings["simulation.event.runtime.a2a.request-timeout"])
        self.assertEqual("900s", bindings["simulation.event.runtime.a2a.poll-timeout"])
        self.assertEqual("gemini-3.6-flash", partner["BANKING_LLM_MODEL"])
        self.assertEqual("840", partner["BANKING_LLM_TIMEOUT_SECONDS"])
        self.assertEqual("32768", partner["BANKING_LLM_MAX_OUTPUT_TOKENS"])
        self.assertEqual("high", partner["BANKING_LLM_REASONING_EFFORT"])
        self.assertEqual("1.0", partner["BANKING_LLM_TEMPERATURE"])
        self.assertEqual(partner["BANKING_LLM_BINDING_REVISION"],
                         bindings["simulation.event.runtime.a2a.targets.banking-advice.binding-revision"])
        self.assertEqual(self.original, (self.root / "private/runtime.properties.before-starter").read_text())

    def test_gemini_existing_poll_timeout_is_preserved_before_any_starter_write(self):
        existing = self.original + "simulation.event.runtime.a2a.poll-timeout=90s\n"
        self.runtime.write_text(existing)
        with self.assertRaisesRegex(ValueError, "already has an A2A poll timeout"):
            self.initializer.initialize(self.platform, "gemini")
        self.assertEqual(existing, self.runtime.read_text())
        self.assertFalse((self.root / ".env").exists())
        self.assertFalse((self.root / "private").exists())

    def test_a2a_budget_and_binding_revision_follow_selected_profile(self):
        profile_path = self.root / "models/gemini.env.example"
        profile_path.write_text(profile_path.read_text().replace("TIMEOUT_SECONDS=840", "TIMEOUT_SECONDS=240")
                                .replace("banking-starter-gemini36-high-v1", "operator-reviewed-gemini-v2"))
        self.initializer.initialize(self.platform, "gemini")
        partner = self.initializer.env(self.root / "private/partner.env")
        bindings = self.initializer.env(self.root / "private/runtime-bindings.properties")
        self.assertEqual("300s", bindings["simulation.event.runtime.a2a.request-timeout"])
        self.assertEqual("300s", bindings["simulation.event.runtime.a2a.poll-timeout"])
        self.assertEqual("operator-reviewed-gemini-v2", partner["BANKING_LLM_BINDING_REVISION"])
        self.assertEqual(partner["BANKING_LLM_BINDING_REVISION"],
                         bindings["simulation.event.runtime.a2a.targets.banking-advice.binding-revision"])

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

    def test_java_monthly_initializer_installs_native_reference_domain_without_overwriting_private_state(self):
        (self.root / "starter-config.json").write_text(json.dumps({
            "schemaVersion": "astraform.banking-starter.configuration.v1", "bankImplementation": "java",
            "scenario": "monthly", "bankPort": 8094, "domainId": "banking", "nativeDomainEnabled": True,
            "requiresUnreleasedPlatform": False, "requiredPlatformCapabilities": ["retainedParticipationSupported"],
        }))
        self.initializer.initialize(self.platform, "gemini")
        settings = self.initializer.env(self.root / ".env")
        bindings = (self.root / "private/runtime-bindings.properties").read_text()
        self.assertIn("-bank:8094/mcp", bindings)
        self.assertIn("simulation.event.runtime.domain.bindings.banking-reference.domain-id=banking\n", bindings)
        self.assertIn("-bank:8094\n", bindings)
        self.assertEqual("8094", settings["BANKING_SERVICE_PORT"])
        self.assertEqual("true", settings["BANKING_NATIVE_ENABLED"])
        self.assertEqual(self.original, (self.root / "private/runtime.properties.before-starter").read_text())

    def test_python_monthly_initializer_uses_python_domain_id_and_refuses_existing_domain_owner(self):
        configuration = {"schemaVersion": "astraform.banking-starter.configuration.v1", "bankImplementation": "python",
            "scenario": "monthly", "bankPort": 8092, "domainId": "banking-python", "nativeDomainEnabled": True,
            "requiresUnreleasedPlatform": False, "requiredPlatformCapabilities": ["retainedParticipationSupported"]}
        (self.root / "starter-config.json").write_text(json.dumps(configuration))
        existing = self.original + "simulation.event.runtime.domain.bindings.banking-reference.base-url=http://another-bank\n"
        self.runtime.write_text(existing)
        with self.assertRaisesRegex(ValueError, "already has banking bindings"):
            self.initializer.initialize(self.platform, "gemini")
        self.assertEqual(existing, self.runtime.read_text())
        self.assertFalse((self.root / ".env").exists())
        self.runtime.write_text(self.original)
        self.initializer.initialize(self.platform, "gemini")
        self.assertIn("banking-reference.domain-id=banking-python\n", self.runtime.read_text())

    def test_mismatched_bank_metadata_is_refused_before_private_configuration_changes(self):
        (self.root / "starter-config.json").write_text(json.dumps({
            "schemaVersion": "astraform.banking-starter.configuration.v1", "bankImplementation": "java",
            "scenario": "issued", "bankPort": 8092, "domainId": "banking", "nativeDomainEnabled": False,
            "requiresUnreleasedPlatform": False,
        }))
        with self.assertRaisesRegex(ValueError, "Invalid packaged"):
            self.initializer.initialize(self.platform, "gemini")
        self.assertFalse((self.root / ".env").exists())
        self.assertEqual(self.original, self.runtime.read_text())


if __name__ == "__main__":
    unittest.main()
