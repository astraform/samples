import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "samples-ci.yml"
PROMOTION_SCRIPT = ROOT / "scripts" / "promote-immutable-image.sh"


class SamplesCiWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
        cls.steps = workflow["jobs"]["publish-images"]["steps"]

    def step(self, name):
        return next(step for step in self.steps if step.get("name") == name)

    def run_step(self, name, environment):
        with tempfile.NamedTemporaryFile() as output:
            process_environment = os.environ.copy()
            process_environment.update(environment)
            process_environment["GITHUB_OUTPUT"] = output.name
            result = subprocess.run(
                ["bash", "-c", self.step(name)["run"]],
                check=False,
                capture_output=True,
                text=True,
                env=process_environment,
            )
            output.seek(0)
            return result, output.read().decode("utf-8")

    def test_manual_publication_uses_an_exact_source_identity(self):
        tag_result, tag_output = self.run_step(
            "Resolve image tag",
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/tags/v0.2.0",
                "GITHUB_REF_NAME": "v0.2.0",
            },
        )
        self.assertEqual(tag_result.returncode, 0, tag_result.stderr)
        self.assertEqual(tag_output, "tag=v0.2.0\n")
        branch_result, branch_output = self.run_step(
            "Resolve image tag",
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/main",
                "GITHUB_REF_NAME": "main",
                "GITHUB_SHA": "a" * 40,
            },
        )
        self.assertEqual(branch_result.returncode, 0, branch_result.stderr)
        self.assertEqual(branch_output, "tag=sha-" + "a" * 40 + "\n")
        other_branch, _ = self.run_step("Resolve image tag", {
            "GITHUB_REF": "refs/heads/feature/example", "GITHUB_REF_NAME": "feature/example",
            "GITHUB_SHA": "a" * 40,
        })
        self.assertNotEqual(other_branch.returncode, 0)

        banking_selection, banking_selection_output = self.run_step(
            "Resolve image selection",
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "INPUT_PUBLISH_JAVA_IMAGE": "false",
                "INPUT_PUBLISH_MARKETING_IMAGE": "false",
                "INPUT_PUBLISH_CREATIVE_AGENCY_IMAGE": "false",
                "INPUT_PUBLISH_PYTHON_IMAGE": "true",
                "INPUT_PUBLISH_BANKING_ADVICE_IMAGE": "true",
            },
        )
        self.assertEqual(banking_selection.returncode, 0, banking_selection.stderr)
        self.assertEqual(
            set(banking_selection_output.splitlines()),
            {
                "java=false",
                "marketing=false",
                "creative_agency=false",
                "python=true",
                "banking_advice=true",
            },
        )
        empty_selection, _ = self.run_step(
            "Resolve image selection",
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "INPUT_PUBLISH_JAVA_IMAGE": "false",
                "INPUT_PUBLISH_MARKETING_IMAGE": "false",
                "INPUT_PUBLISH_CREATIVE_AGENCY_IMAGE": "false",
                "INPUT_PUBLISH_PYTHON_IMAGE": "false",
                "INPUT_PUBLISH_BANKING_ADVICE_IMAGE": "false",
            },
        )
        self.assertNotEqual(empty_selection.returncode, 0)

    def test_promotion_never_moves_latest_and_rejects_non_release_tags(self):
        syntax = subprocess.run(
            ["bash", "-n", str(PROMOTION_SCRIPT)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(syntax.returncode, 0, syntax.stderr)

        rejected = subprocess.run(
            [
                "bash",
                str(PROMOTION_SCRIPT),
                "samples-marketing-ops-creative-agency",
                "latest",
                "ghcr.io/astraform/samples-marketing-ops-creative-agency@sha256:"
                + ("0" * 64),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("vMAJOR.MINOR.PATCH", rejected.stderr)

        wrong_repository = subprocess.run(
            [
                "bash",
                str(PROMOTION_SCRIPT),
                "samples-marketing-ops-creative-agency",
                "v0.2.0",
                "ghcr.io/astraform/samples-marketing-ops-remote@sha256:" + ("0" * 64),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(wrong_repository.returncode, 0)
        self.assertIn("digest for samples-marketing-ops-creative-agency", wrong_repository.stderr)

    def test_promotion_is_idempotent_and_fails_closed(self):
        source_digest = "c" * 64
        source_reference = (
            "ghcr.io/astraform/samples-marketing-ops-creative-agency@sha256:"
            + source_digest
        )
        scenarios = {
            "absent": 0,
            "existing-same": 0,
            "existing-different": 1,
            "registry-error": 1,
            "final-mismatch": 1,
        }
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            fake_docker = directory_path / "docker"
            fake_docker.write_text(
                """#!/usr/bin/env python3
import json
import os
import sys

args = sys.argv[1:]
log_path = os.environ["FAKE_DOCKER_LOG"]
with open(log_path, "a", encoding="utf-8") as log:
    log.write(" ".join(args) + "\\n")
scenario = os.environ["FAKE_DOCKER_SCENARIO"]
reference = args[-1]
same = {"manifests": [{"digest": "sha256:" + ("a" * 64)}, {"digest": "sha256:" + ("b" * 64)}]}
different = {"manifests": [{"digest": "sha256:" + ("d" * 64)}]}
if args[:3] == ["buildx", "imagetools", "create"]:
    raise SystemExit(0)
if "@sha256:" in reference:
    print(json.dumps(same))
    raise SystemExit(0)
created = "imagetools create" in open(log_path, encoding="utf-8").read()
if scenario == "registry-error":
    print("registry unavailable", file=sys.stderr)
    raise SystemExit(1)
if scenario in {"absent", "final-mismatch"} and not created:
    print("manifest unknown", file=sys.stderr)
    raise SystemExit(1)
if scenario in {"existing-different", "final-mismatch"}:
    print(json.dumps(different))
else:
    print(json.dumps(same))
""",
                encoding="utf-8",
            )
            fake_docker.chmod(0o755)

            for scenario, expected_success in scenarios.items():
                with self.subTest(scenario=scenario):
                    log_path = directory_path / f"{scenario}.log"
                    environment = os.environ.copy()
                    environment["PATH"] = f"{directory}:{environment['PATH']}"
                    environment["FAKE_DOCKER_LOG"] = str(log_path)
                    environment["FAKE_DOCKER_SCENARIO"] = scenario
                    result = subprocess.run(
                        [
                            "bash",
                            str(PROMOTION_SCRIPT),
                            "samples-marketing-ops-creative-agency",
                            "v0.2.0",
                            source_reference,
                        ],
                        check=False,
                        capture_output=True,
                        text=True,
                        env=environment,
                    )
                    self.assertEqual(result.returncode == 0, expected_success == 0)
                    log = log_path.read_text(encoding="utf-8")
                    self.assertNotIn(":latest", log)
                    create_count = log.count("buildx imagetools create")
                    if scenario in {"absent", "final-mismatch"}:
                        self.assertEqual(create_count, 1)
                    else:
                        self.assertEqual(create_count, 0)

            for sample, image in (
                ("Python", "samples-banking-starter-bank"),
                ("Banking Advice", "samples-banking-starter-advice"),
            ):
                with self.subTest(sample=sample):
                    coordinates, output = self.run_step(
                        f"Resolve {sample} candidate coordinates",
                        {"GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"},
                    )
                    self.assertEqual(coordinates.returncode, 0, coordinates.stderr)
                    values = dict(line.split("=", 1) for line in output.splitlines())
                    self.assertEqual(
                        values["tag"], f"ghcr.io/astraform/{image}:candidate-123-1"
                    )
                    log_path = directory_path / f"{image}.log"
                    environment = os.environ.copy()
                    environment.update(
                        PATH=f"{directory}:{environment['PATH']}",
                        FAKE_DOCKER_LOG=str(log_path),
                        FAKE_DOCKER_SCENARIO="absent",
                        DIGEST=f"sha256:{source_digest}",
                        REPOSITORY=values["repository"],
                        TAG="sha-" + "a" * 40,
                        GITHUB_STEP_SUMMARY=str(directory_path / "summary"),
                    )
                    result = subprocess.run(
                        ["bash", "-c", self.step(
                            f"Promote immutable {sample} manifest"
                        )["run"]],
                        check=False,
                        capture_output=True,
                        text=True,
                        cwd=ROOT,
                        env=environment,
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn(
                        f"imagetools create --tag ghcr.io/astraform/{image}:sha-{'a' * 40} "
                        f"ghcr.io/astraform/{image}@sha256:{source_digest}",
                        log_path.read_text(encoding="utf-8"),
                    )


if __name__ == "__main__":
    unittest.main()
