"""Local marketing starter topology, provenance and private initialization checks."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
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

    def test_both_scenarios_share_writer_and_ship_hash_verified_scripts_not_credentials(self):
        for scenario in ("scheduled", "current"):
            with self.subTest(scenario=scenario), patch.object(packager.packaging.subprocess, "check_output", return_value="sha256:" + "a" * 64 + "\n"):
                bundle = packager.package("v0.1.0", self.images, self.directory / scenario, True, "a" * 40, scenario)
            manifest = json.loads((bundle / "bundle-manifest.json").read_text())
            self.assertTrue(manifest["localVerificationOnly"])
            self.assertEqual({"platform": "0.2.0", "javaSdk": "0.4.2", "pythonSdk": "0.3.2", "contracts": "1.2.0"}, manifest["compatibility"])
            self.assertEqual((ROOT / "integration/marketing-ops-a2a/run-customer.py").read_bytes(), (bundle / "run-customer.py").read_bytes())
            self.assertEqual((ROOT / "banking-advice-agent/run-customer.py").read_bytes(), (bundle / "customer-run-evidence.py").read_bytes())
            self.assertEqual(scenario, json.loads((bundle / "starter-config.json").read_text())["scenario"])
            for name, metadata in manifest["files"].items():
                self.assertEqual(packager.packaging.file_hash(bundle / name), metadata["sha256"])
                self.assertFalse(name.startswith(("private/", "runs/")))
            self.assertEqual({key.upper() + "_IMAGE" for key in self.images}, set(initializer.env(bundle / "release.env")))
            checksum = Path(str(bundle) + ".tar.gz.sha256")
            archive = Path(str(bundle) + ".tar.gz")
            self.assertEqual(f"{packager.packaging.file_hash(archive)}  {archive.name}\n", checksum.read_text())

    def test_public_packaging_and_extra_image_roles_are_rejected_before_docker_or_output(self):
        with patch.object(packager.packaging.subprocess, "check_output") as docker:
            with self.assertRaisesRegex(ValueError, "local verification"):
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
        self.assertEqual("150s", bindings["simulation.event.runtime.a2a.request-timeout"])
        self.assertEqual(inode, self.runtime.stat().st_ino)
        self.assertEqual(self.original, (self.starter / "private/runtime.properties.before-starter").read_text())
        for file in (self.starter / "private").iterdir():
            self.assertEqual(0o600, file.stat().st_mode & 0o777)
        self.assertLessEqual(int(partner["CREATIVE_AGENCY_LLM_MAX_OUTPUT_TOKENS"]), 16384)
        before = self.runtime.read_bytes()
        with self.assertRaisesRegex(ValueError, "already initialized"):
            initializer.initialize(self.platform, "ollama")
        self.assertEqual(before, self.runtime.read_bytes())

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
