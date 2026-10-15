import re
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "samples-ci.yml"
PROMOTION_SCRIPT = ROOT / "scripts" / "promote-immutable-image.sh"
FULL_SHA_ACTION = re.compile(r"^[^@]+@[0-9a-f]{40}$")


class SamplesCiWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow_text = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.workflow = yaml.safe_load(cls.workflow_text)
        cls.publish_job = cls.workflow["jobs"]["publish-images"]
        cls.steps = cls.publish_job["steps"]

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
        triggers = self.workflow.get("on", self.workflow.get(True))
        inputs = triggers["workflow_dispatch"]["inputs"]
        selectors = (
            "publish_java_image",
            "publish_marketing_image",
            "publish_creative_agency_image",
            "publish_python_image",
            "publish_banking_advice_image",
        )
        for selector in selectors:
            self.assertIs(inputs[selector]["default"], False)

        job_condition = " ".join(self.publish_job["if"].split())
        self.assertEqual(
            job_condition,
            "${{ github.event_name == 'workflow_dispatch' "
            "&& (github.ref == 'refs/heads/main' || github.ref_type == 'tag') "
            "&& (inputs.publish_images == true "
            "|| inputs.publish_images == 'true') }}",
        )

        tag_step = self.step("Resolve image tag")["run"]
        self.assertIn('tag="${GITHUB_REF_NAME}"', tag_step)
        self.assertIn('"refs/tags/${GITHUB_REF_NAME}"', tag_step)
        self.assertNotIn("INPUT_IMAGE_TAG", tag_step)
        self.assertIn("^v[0-9]+", tag_step)

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

        names = [step.get("name") for step in self.steps]
        tag_index = names.index("Resolve image tag")
        selection_index = names.index("Resolve image selection")
        qemu_index = next(
            index
            for index, step in enumerate(self.steps)
            if str(step.get("uses", "")).startswith("docker/setup-qemu-action@")
        )
        buildx_index = next(
            index
            for index, step in enumerate(self.steps)
            if str(step.get("uses", "")).startswith("docker/setup-buildx-action@")
        )
        login_index = next(
            index
            for index, step in enumerate(self.steps)
            if str(step.get("uses", "")).startswith("docker/login-action@")
        )
        self.assertLess(tag_index, selection_index)
        self.assertLess(selection_index, qemu_index)
        self.assertLess(selection_index, buildx_index)
        self.assertLess(selection_index, login_index)

    def test_creative_agency_candidate_is_smoked_before_promotion(self):
        names = [step.get("name") for step in self.steps]
        build_index = names.index("Build immutable Creative Agency candidate")
        smoke_index = names.index("Smoke immutable Creative Agency candidate")
        promote_index = names.index("Promote immutable Creative Agency manifest")
        self.assertLess(build_index, smoke_index)
        self.assertLess(smoke_index, promote_index)

        build = self.steps[build_index]
        selected = "${{ steps.image-selection.outputs.creative_agency == 'true' }}"
        candidate_tag = "${{ steps.creative-agency-candidate.outputs.tag }}"
        candidate_repository = (
            "${{ steps.creative-agency-candidate.outputs.repository }}"
        )
        candidate_digest = "${{ steps.creative-agency-build.outputs.digest }}"
        self.assertEqual(build["if"], selected)
        self.assertEqual(build["with"]["file"], "marketing-ops-creative-agency/Dockerfile")
        self.assertEqual(build["with"]["platforms"], "linux/amd64,linux/arm64")
        self.assertIs(build["with"]["push"], True)
        self.assertEqual(build["with"]["provenance"], "mode=max")
        self.assertIs(build["with"]["sbom"], True)
        self.assertEqual(build["with"]["tags"], candidate_tag)

        smoke_step = self.steps[smoke_index]
        self.assertEqual(smoke_step["if"], selected)
        self.assertEqual(
            smoke_step["env"],
            {
                "DIGEST": candidate_digest,
                "REPOSITORY": candidate_repository,
            },
        )
        smoke = smoke_step["run"]
        self.assertIn('"${REPOSITORY}@${DIGEST}"', smoke)
        self.assertIn("check-creative-agency-a2a-v1-live.sh", smoke)

        promote_step = self.steps[promote_index]
        self.assertEqual(promote_step["if"], selected)
        self.assertEqual(
            promote_step["env"],
            {
                "DIGEST": candidate_digest,
                "REPOSITORY": candidate_repository,
                "TAG": "${{ steps.image-tag.outputs.tag }}",
            },
        )
        promote = promote_step["run"]
        self.assertIn("promote-immutable-image.sh", promote)
        self.assertIn("marketing-ops-creative-agency", promote)
        self.assertIn("GITHUB_STEP_SUMMARY", promote)

    def test_all_published_sample_images_record_the_exact_source_revision(self):
        builds = [step for step in self.steps
                  if str(step.get("uses", "")).startswith("docker/build-push-action@")
                  and step.get("with", {}).get("push") is True]
        self.assertEqual({step["with"]["file"] for step in builds}, {
            "java-spring-banking/Dockerfile", "marketing-ops-remote/Dockerfile",
            "marketing-ops-creative-agency/Dockerfile", "python-fastapi-banking/Dockerfile",
            "banking-advice-agent/Dockerfile",
        })
        self.assertEqual(len(builds), 5)
        for build in builds:
            with self.subTest(image=build["with"]["file"]):
                labels = dict(line.split("=", 1) for line in build["with"].get("labels", "").splitlines()
                              if line.strip())
                self.assertEqual(labels.get("org.opencontainers.image.revision"), "${{ github.sha }}")

    def test_every_workflow_action_is_commit_pinned(self):
        for job in self.workflow["jobs"].values():
            for step in job.get("steps", []):
                action = step.get("uses")
                if action is not None:
                    self.assertRegex(action, FULL_SHA_ACTION)

    def test_promotion_never_moves_latest_and_rejects_non_release_tags(self):
        script = PROMOTION_SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("latest_reference", script)
        self.assertNotIn(":latest", script)
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
                "marketing-ops-creative-agency",
                "latest",
                "ghcr.io/astraform/marketing-ops-creative-agency@sha256:"
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
                "marketing-ops-creative-agency",
                "v0.2.0",
                "ghcr.io/astraform/marketing-ops-remote@sha256:" + ("0" * 64),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(wrong_repository.returncode, 0)
        self.assertIn("digest for marketing-ops-creative-agency", wrong_repository.stderr)

    def test_promotion_is_idempotent_and_fails_closed(self):
        source_digest = "c" * 64
        source_reference = (
            "ghcr.io/astraform/marketing-ops-creative-agency@sha256:"
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
                            "marketing-ops-creative-agency",
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
                ("Python", "banking-starter-bank"),
                ("Banking Advice", "banking-starter-advice"),
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
