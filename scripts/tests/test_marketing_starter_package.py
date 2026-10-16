"""Local marketing starter topology, provenance and private initialization checks."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


packager = load("marketing_starter_package", ROOT / "scripts/package-marketing-starter.py")
initializer = load("marketing_starter_init", ROOT / "deploy/marketing-starter/init.py")


class MarketingStarterPackageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.images = {key: "astraform-test/" + key + ":local" for key in ("domain", "agency", "tools")}

    def registry(self, command, **kwargs):
        configuration = json.loads((Path(kwargs["env"]["DOCKER_CONFIG"]) / "config.json").read_text())
        self.assertEqual({"cliPluginsExtraDirs"}, set(configuration))
        if command[-1] == "{{json .Manifest}}":
            return json.dumps({"digest": "sha256:" + "a" * 64, "manifests": [
                {"platform": {"os": "linux", "architecture": arch}} for arch in ("amd64", "arm64")]})
        return json.dumps({f"linux/{arch}": {"os": "linux", "architecture": arch,
                           "config": {"Labels": {"org.opencontainers.image.revision": "a" * 40}}}
                           for arch in ("amd64", "arm64")})

    def test_public_marketing_scenarios_use_pinned_anonymous_images_and_released_dependencies(self):
        images = {key: f"registry.example/{key}@sha256:" + "a" * 64 for key in self.images}
        source = self.directory / "public-source"
        for relative in {**packager.FILES,
                         "scheduled": "integration/marketing-ops-a2a/sample-configuration.json",
                         "current": "integration/marketing-ops-a2a/current-state-configuration.json",
                         "notes": "docs/releases/v0.1.0.md"}.values():
            target = source / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        for scenario, name in (("scheduled", "sample-configuration.json"),
                               ("current", "current-state-configuration.json")):
            for finish in (False, True):
                selected = json.loads((ROOT / "integration/marketing-ops-a2a" / name).read_text())
                selected["launchRequestTemplate"]["completionPolicy"]["requireCustomerFinish"] = finish
                (source / "integration/marketing-ops-a2a" / name).write_text(json.dumps(selected))
                with self.subTest(scenario=scenario, finish=finish), \
                     patch.object(packager, "ROOT", source), \
                     patch.object(packager.packaging, "ROOT", source), \
                     patch.object(packager.packaging.subprocess, "check_output", side_effect=self.registry):
                    bundle = packager.package("v0.1.0", images, self.directory / f"public-{scenario}-{finish}",
                                              False, "a" * 40, scenario, "v0.3.0")
                manifest = json.loads((bundle / "bundle-manifest.json").read_text())
                configuration = json.loads((bundle / "starter-config.json").read_text())
                self.assertNotIn("localVerificationOnly", manifest)
                self.assertNotIn("localImageIds", manifest)
                self.assertFalse(configuration["requiresUnreleasedPlatform"])
                self.assertFalse(configuration["requiresUnreleasedCustomerSdk"])
                self.assertEqual(["completionPolicy.requireCustomerFinish"], configuration["requiredPlatformCapabilities"])
                self.assertEqual(["completionPolicy.requireCustomerFinish"], configuration["requiredCustomerSdkCapabilities"])
                self.assertIs(finish, manifest["requiredPlatform"]["completionPolicy.requireCustomerFinish"])
                self.assertEqual("v0.3.0", manifest["requiredPlatform"]["release"])
                self.assertEqual(scenario, configuration["scenario"])
                self.assertEqual(selected, json.loads((bundle / "scenario.json").read_text()))
                for relative, metadata in manifest["files"].items():
                    self.assertEqual(packager.packaging.file_hash(bundle / relative), metadata["sha256"])

    def test_public_platform_declaration_does_not_allow_tags_private_images_or_local_markers(self):
        images = {key: f"registry.example/{key}@sha256:" + "a" * 64 for key in self.images}
        with patch.object(packager.packaging.subprocess, "check_output") as docker:
            for release in (None, "v0.2.0", "v1.0.0", "v0.3.0-rc.1", "0.3.0"):
                with self.subTest(release=release), self.assertRaises(ValueError):
                    packager.package("v0.1.0", images, self.directory / "invalid-platform", False,
                                     "a" * 40, platform_release=release)
            with self.assertRaisesRegex(ValueError, "cannot be combined"):
                packager.package("v0.1.0", images, self.directory / "mixed", True,
                                 "a" * 40, platform_release="v0.3.0")
            with self.assertRaisesRegex(ValueError, "digest-pinned"):
                packager.package("v0.1.0", self.images, self.directory / "tags", False,
                                 "a" * 40, platform_release="v0.3.0")
            docker.assert_not_called()
        with patch.object(packager.packaging.subprocess, "check_output",
                          side_effect=subprocess.CalledProcessError(1, ["docker"], stderr="unauthorized")):
            with self.assertRaises(subprocess.CalledProcessError):
                packager.package("v0.1.0", images, self.directory / "private", False,
                                 "a" * 40, platform_release="v0.3.0")
        for name in ("invalid-platform", "mixed", "tags", "private"):
            self.assertFalse((self.directory / name).exists())

    def test_both_scenarios_share_writer_and_ship_hash_verified_scripts_not_credentials(self):
        for scenario in ("scheduled", "current"):
            with self.subTest(scenario=scenario), patch.object(packager.packaging.subprocess, "check_output", return_value="sha256:" + "a" * 64 + "\n"):
                bundle = packager.package("v0.1.0", self.images, self.directory / scenario, True, "a" * 40, scenario)
            manifest = json.loads((bundle / "bundle-manifest.json").read_text())
            self.assertTrue(manifest["localVerificationOnly"])
            self.assertTrue(manifest["requiredPlatform"]["completionPolicy.requireCustomerFinish"])
            configuration = json.loads((bundle / "starter-config.json").read_text())
            self.assertTrue(configuration["requiresUnreleasedPlatform"])
            self.assertFalse(configuration["requiresUnreleasedCustomerSdk"])
            self.assertEqual(["completionPolicy.requireCustomerFinish"], configuration["requiredPlatformCapabilities"])
            self.assertEqual(["completionPolicy.requireCustomerFinish"], configuration["requiredCustomerSdkCapabilities"])
            original = "sample-configuration.json" if scenario == "scheduled" else "current-state-configuration.json"
            self.assertEqual((ROOT / "integration/marketing-ops-a2a" / original).read_bytes(), (bundle / "scenario.json").read_bytes())
            self.assertEqual((ROOT / "integration/marketing-ops-a2a/run-customer.py").read_bytes(), (bundle / "run-customer.py").read_bytes())
            self.assertEqual((ROOT / "integration/customer-run-evidence.py").read_bytes(), (bundle / "customer-run-evidence.py").read_bytes())
            self.assertEqual(scenario, json.loads((bundle / "starter-config.json").read_text())["scenario"])
            for name, metadata in manifest["files"].items():
                self.assertEqual(packager.packaging.file_hash(bundle / name), metadata["sha256"])
                self.assertFalse(name.startswith(("private/", "runs/")))
            self.assertEqual({key.upper() + "_IMAGE" for key in self.images}, set(initializer.env(bundle / "release.env")))
            checksum = Path(str(bundle) + ".tar.gz.sha256")
            archive = Path(str(bundle) + ".tar.gz")
            self.assertEqual(f"{packager.packaging.file_hash(archive)}  {archive.name}\n", checksum.read_text())

    def test_finish_metadata_preserves_omission_and_both_boolean_values(self):
        source = self.directory / "source"
        files = {**packager.FILES,
                 "scheduled": "integration/marketing-ops-a2a/sample-configuration.json",
                 "current": "integration/marketing-ops-a2a/current-state-configuration.json",
                 "notes": "docs/releases/v0.1.0.md"}
        for relative in files.values():
            target = source / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        for scenario, name in (("scheduled", "sample-configuration.json"),
                               ("current", "current-state-configuration.json")):
            for value in (None, False, True):
                selected = json.loads((ROOT / "integration/marketing-ops-a2a" / name).read_text())
                policy = selected["launchRequestTemplate"]["completionPolicy"]
                if value is None:
                    policy.pop("requireCustomerFinish")
                else:
                    policy["requireCustomerFinish"] = value
                (source / "integration/marketing-ops-a2a" / name).write_text(json.dumps(selected))
                with self.subTest(scenario=scenario, value=value), \
                     patch.object(packager, "ROOT", source), \
                     patch.object(packager.packaging, "ROOT", source), \
                     patch.object(packager.packaging.subprocess, "check_output", return_value="sha256:" + "a" * 64 + "\n"):
                    bundle = packager.package("v0.1.0", self.images,
                                              self.directory / f"{scenario}-{value}", True, "a" * 40, scenario)
                manifest = json.loads((bundle / "bundle-manifest.json").read_text())
                configuration = json.loads((bundle / "starter-config.json").read_text())
                explicit = value is not None
                self.assertEqual(explicit, configuration["requiresUnreleasedPlatform"])
                self.assertFalse(configuration["requiresUnreleasedCustomerSdk"])
                capabilities = ["completionPolicy.requireCustomerFinish"] if explicit else []
                self.assertEqual(capabilities, configuration["requiredPlatformCapabilities"])
                self.assertEqual(capabilities, configuration["requiredCustomerSdkCapabilities"])
                if explicit:
                    self.assertIs(value, manifest["requiredPlatform"]["completionPolicy.requireCustomerFinish"])
                else:
                    self.assertNotIn("requiredPlatform", manifest)
                self.assertEqual(selected, json.loads((bundle / "scenario.json").read_text()))

    def test_invalid_finish_value_fails_before_images_or_archive(self):
        source = self.directory / "invalid-source"
        for scenario, name in (("scheduled", "sample-configuration.json"),
                               ("current", "current-state-configuration.json")):
            target = source / "integration/marketing-ops-a2a" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            for value in (None, "true", 0, 1, [], {}):
                selected = json.loads((ROOT / "integration/marketing-ops-a2a" / name).read_text())
                selected["launchRequestTemplate"]["completionPolicy"]["requireCustomerFinish"] = value
                target.write_text(json.dumps(selected))
                output = self.directory / f"invalid-{scenario}"
                with self.subTest(scenario=scenario, value=value), \
                     patch.object(packager, "ROOT", source), \
                     patch.object(packager.packaging, "ROOT", source), \
                     patch.object(packager.packaging.subprocess, "check_output") as docker:
                    with self.assertRaisesRegex(ValueError, "must be a Boolean"):
                        packager.package("v0.1.0", self.images, output, True, "a" * 40, scenario)
                    docker.assert_not_called()
                self.assertFalse(output.exists())

    def test_standalone_archive_imports_shared_evidence_without_banking_source(self):
        with patch.object(packager.packaging.subprocess, "check_output", return_value="sha256:" + "a" * 64 + "\n"):
            bundle = packager.package("v0.1.0", self.images, self.directory / "standalone", True, "a" * 40)
        result = subprocess.run([sys.executable, "-B", "run-customer.py", "--help"],
                                cwd=bundle, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--inspect", result.stdout)
        (bundle / "customer-run-evidence.py").unlink()
        missing = subprocess.run([sys.executable, "-B", "run-customer.py", "--help"],
                                 cwd=bundle, capture_output=True, text=True)
        self.assertNotEqual(0, missing.returncode)

    def test_missing_platform_release_and_extra_image_roles_fail_before_docker_or_output(self):
        with patch.object(packager.packaging.subprocess, "check_output") as docker:
            with self.assertRaisesRegex(ValueError, "platform-release"):
                packager.package("v0.1.0", self.images, self.directory / "public", False, "a" * 40)
            with self.assertRaisesRegex(ValueError, "exactly domain"):
                packager.package("v0.1.0", {**self.images, "postgres": "postgres:16"}, self.directory / "extra", True, "a" * 40)
            docker.assert_not_called()
        self.assertFalse((self.directory / "public").exists())
        self.assertFalse((self.directory / "extra").exists())


class MarketingStarterInitializationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.starter = self.directory / "starter"
        self.starter.mkdir()
        (self.starter / "models").mkdir()
        for profile in ("gemini", "ollama"):
            shutil.copyfile(ROOT / f"deploy/marketing-starter/models/{profile}.env.example", self.starter / f"models/{profile}.env.example")
        shutil.copyfile(ROOT / "integration/marketing-ops-a2a/input.schema.json", self.starter / "input.schema.json")
        (self.starter / "starter-config.json").write_text(json.dumps({"schemaVersion": "astraform.marketing-starter.configuration.v1", "scenario": "scheduled", "domainId": "marketing-ops", "domainPort": 8090, "agencyPort": 8091}))
        self.images = {key: "astraform-test/" + key + ":local" for key in ("domain", "agency", "tools")}
        (self.starter / "image-lock.json").write_text(json.dumps(self.images))
        (self.starter / "release.env").write_text("".join(key.upper() + "_IMAGE=" + value + "\n" for key, value in self.images.items()))
        self.platform = self.directory / "platform"
        (self.platform / "private").mkdir(parents=True)
        (self.platform / ".env").write_text("COMPOSE_PROJECT_NAME=astraform-trial\n")
        self.runtime = self.platform / "private/runtime.properties"
        self.original = "# Existing private settings\nexisting.setting=preserved\n"
        self.runtime.write_text(self.original)
        self.enterContext(patch.object(initializer, "ROOT", self.starter))

    def test_token_matches_partner_and_runtime_private_configuration_and_existing_inode_is_preserved(self):
        inode = self.runtime.stat().st_ino
        initializer.initialize(self.platform, "gemini")
        partner = initializer.env(self.starter / "private/partner.env")
        bindings = initializer.env(self.starter / "private/runtime-bindings.properties")
        self.assertEqual(partner["CREATIVE_AGENCY_A2A_TOKEN"], bindings["simulation.event.runtime.a2a.targets.creative-agency.token"])
        self.assertEqual(64, len(partner["CREATIVE_AGENCY_A2A_TOKEN"]))
        self.assertEqual(partner["CREATIVE_AGENCY_LLM_BINDING_REVISION"], bindings["simulation.event.runtime.a2a.targets.creative-agency.binding-revision"])
        self.assertEqual("300s", bindings["simulation.event.runtime.a2a.request-timeout"])
        self.assertEqual("300s", bindings["simulation.event.runtime.a2a.poll-timeout"])
        self.assertEqual("gemini-3.6-flash", partner["CREATIVE_AGENCY_LLM_MODEL"])
        self.assertEqual("high", partner["CREATIVE_AGENCY_LLM_REASONING_EFFORT"])
        self.assertEqual("max-completion-tokens", partner["CREATIVE_AGENCY_LLM_TOKEN_LIMIT_PARAMETER"])
        self.assertEqual("16384", partner["CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS"])
        self.assertEqual("120s", partner["CREATIVE_AGENCY_LLM_READ_TIMEOUT"])
        self.assertEqual("300", partner["CREATIVE_AGENCY_A2A_AGENT_TIMEOUT_SECONDS"])
        self.assertEqual(inode, self.runtime.stat().st_ino)
        self.assertEqual(self.original, (self.starter / "private/runtime.properties.before-starter").read_text())
        for file in (self.starter / "private").iterdir():
            self.assertEqual(0o600, file.stat().st_mode & 0o777)
        self.assertLessEqual(int(partner["CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS"]), 16384)
        before = self.runtime.read_bytes()
        with self.assertRaisesRegex(ValueError, "already initialized"):
            initializer.initialize(self.platform, "ollama")
        self.assertEqual(before, self.runtime.read_bytes())

    def test_a2a_budget_and_revision_follow_selected_profile(self):
        profile_path = self.starter / "models/gemini.env.example"
        profile_path.write_text(profile_path.read_text().replace("TIMEOUT_SECONDS=300", "TIMEOUT_SECONDS=240")
                                .replace("marketing-starter-gemini36-high-v1", "operator-reviewed-gemini-v2"))
        initializer.initialize(self.platform, "gemini")
        partner = initializer.env(self.starter / "private/partner.env")
        bindings = initializer.env(self.starter / "private/runtime-bindings.properties")
        self.assertEqual("240s", bindings["simulation.event.runtime.a2a.request-timeout"])
        self.assertEqual("240s", bindings["simulation.event.runtime.a2a.poll-timeout"])
        self.assertEqual("operator-reviewed-gemini-v2", partner["CREATIVE_AGENCY_LLM_BINDING_REVISION"])
        self.assertEqual(partner["CREATIVE_AGENCY_LLM_BINDING_REVISION"],
                         bindings["simulation.event.runtime.a2a.targets.creative-agency.binding-revision"])

    def test_ollama_profile_and_a2a_bounds_remain_unchanged(self):
        initializer.initialize(self.platform, "ollama")
        partner = initializer.env(self.starter / "private/partner.env")
        bindings = initializer.env(self.starter / "private/runtime-bindings.properties")
        self.assertEqual("qwen3.5:4b", partner["CREATIVE_AGENCY_LLM_MODEL"])
        self.assertEqual("none", partner["CREATIVE_AGENCY_LLM_REASONING_EFFORT"])
        self.assertEqual("2048", partner["CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS"])
        self.assertEqual("90s", partner["CREATIVE_AGENCY_LLM_READ_TIMEOUT"])
        self.assertEqual("110", partner["CREATIVE_AGENCY_A2A_AGENT_TIMEOUT_SECONDS"])
        self.assertEqual("150s", bindings["simulation.event.runtime.a2a.request-timeout"])
        self.assertEqual("150s", bindings["simulation.event.runtime.a2a.poll-timeout"])
        self.assertEqual(partner["CREATIVE_AGENCY_LLM_BINDING_REVISION"],
                         bindings["simulation.event.runtime.a2a.targets.creative-agency.binding-revision"])

    def test_existing_domain_a2a_and_timeout_settings_are_never_overwritten(self):
        for conflict in ("simulation.event.runtime.domain.bindings.marketing-ops.base-url=x",
                         "simulation.event.runtime.a2a.targets.creative-agency.token=existing-private-token",
                         "simulation.event.runtime.a2a.request-timeout=60s",
                         "simulation.event.runtime.a2a.poll-timeout=60s"):
            with self.subTest(conflict=conflict):
                self.runtime.write_text(self.original + conflict + "\n")
                with self.assertRaisesRegex(ValueError, "already has marketing"):
                    initializer.initialize(self.platform, "gemini")
                self.assertEqual(self.original + conflict + "\n", self.runtime.read_text())
                self.assertFalse((self.starter / ".env").exists())

    def test_compose_uses_saved_identity_even_when_shell_targets_an_unrelated_project(self):
        initializer.initialize(self.platform, "ollama")
        saved = initializer.env(self.starter / ".env")
        with patch.dict(os.environ, {"COMPOSE_PROJECT_NAME": "unrelated-production", "AGENCY_IMAGE": "unrelated/image:latest"}), patch.object(initializer.subprocess, "run") as run:
            run.return_value.returncode = 0
            self.assertEqual(0, initializer.compose(["down"]))
        command = run.call_args.args[0]
        self.assertEqual(saved["COMPOSE_PROJECT_NAME"], command[command.index("--project-name") + 1])
        self.assertEqual(saved["COMPOSE_PROJECT_NAME"], run.call_args.kwargs["env"]["COMPOSE_PROJECT_NAME"])
        self.assertEqual(self.images["agency"], run.call_args.kwargs["env"]["AGENCY_IMAGE"])


if __name__ == "__main__":
    unittest.main()
