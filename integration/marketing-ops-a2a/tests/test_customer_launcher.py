"""Persist marketing identities and resume reads without replacement launches."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from urllib3.exceptions import ReadTimeoutError

DIRECTORY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("marketing_customer_launcher", DIRECTORY / "run-customer.py")
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


def document(value):
    return SimpleNamespace(to_json=lambda: json.dumps(value))


class MarketingCustomerLauncherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name) / "run"
        self.enterContext(patch.dict(os.environ, {"ASTRAFORM_API_BASE": "http://platform:8084",
            "MARKETING_SAMPLE_FILE": str(DIRECTORY / "sample-configuration.json")}))
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.enterContext(contextlib.redirect_stderr(io.StringIO()))
        self.evidence = launcher.evidence_helper()
        self.enterContext(patch.object(launcher, "evidence_helper", return_value=self.evidence))

    def catalog(self):
        client = MagicMock()
        client.create_agent_definition.return_value = document({"definition": {"catalogAgentId": "catalog-1", "draftVersion": 1, "draftSpecDigest": "sha256:" + "a" * 64}})
        client.publish_agent_definition.return_value = document({"revision": {"catalogRevisionId": "revision-1", "catalogDefinitionDigest": "sha256:" + "b" * 64}})
        return client

    def test_uncertain_launch_keeps_exact_operation_ids_and_refuses_a_second_launch(self):
        catalog = self.catalog()
        def uncertain(client, request):
            self.assertEqual(request, json.loads((self.output / "launch-request.json").read_text()))
            self.assertEqual(request["requestId"], json.loads((self.output / "session.json").read_text())["scenario"]["launchRequestTemplate"]["requestId"])
            raise ReadTimeoutError(None, "/api/agent-journeys", "uncertain response")
        with patch.object(launcher, "AgentCatalogApi", return_value=catalog), patch.object(self.evidence, "launch_journey", side_effect=uncertain) as launch:
            with self.assertRaises(ReadTimeoutError):
                launcher.main(["--output", str(self.output), "--wait"])
            before = {file.name: file.read_bytes() for file in self.output.iterdir()}
            with self.assertRaisesRegex(ValueError, "not empty"):
                launcher.main(["--output", str(self.output)])
        self.assertEqual(1, catalog.create_agent_definition.call_count)
        self.assertEqual(1, catalog.publish_agent_definition.call_count)
        self.assertEqual(1, launch.call_count)
        self.assertEqual(before, {file.name: file.read_bytes() for file in self.output.iterdir()})
        self.assertFalse((self.output / "launch.json").exists())

    def test_launch_once_then_wait_inspects_the_accepted_run_and_saves_manifest(self):
        catalog = self.catalog()
        with patch.object(launcher, "AgentCatalogApi", return_value=catalog), patch.object(self.evidence, "launch_journey", return_value=document({"runId": "run-1"})) as launch, patch.object(self.evidence, "inspect_run", return_value=0) as inspect:
            self.assertEqual(0, launcher.main(["--output", str(self.output), "--wait"]))
        self.assertEqual(1, launch.call_count)
        self.assertEqual(("run-1", self.output, True, 1500), inspect.call_args.args[2:])
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual("run-1", manifest["runId"])
        self.assertEqual("http://platform:8084", manifest["apiBase"])
        self.assertEqual(manifest["launchRequestId"], json.loads((self.output / "launch-request.json").read_text())["requestId"])

    def test_inspect_failed_run_retries_only_reads_and_exports_original_outcome(self):
        reads = MagicMock()
        run = {"runId": "run-1", "status": "FAILED", "metadata": {"nativeProgress": {"goal": {"status": "ABANDONED"}, "stopReason": "GOAL_ABANDONED"}}}
        reads.get_simulation_run.side_effect = [ReadTimeoutError(None, "/run", "temporary"), document(run)]
        reads.inspect_simulation_run_ledger.return_value = document({"events": [{"eventId": "event-1"}]})
        reads.get_simulation_run_evidence_export_readiness_with_http_info.return_value = SimpleNamespace(status_code=204)
        reads.export_simulation_run_evidence.return_value = b"actual-failed-goal-export"
        with patch.object(self.evidence, "SimulationRunsApi", return_value=reads), patch.object(launcher, "launch") as launch, patch.object(self.evidence.time, "sleep"):
            self.assertEqual(0, launcher.main(["--inspect", "run-1", "--output", str(self.output)]))
        launch.assert_not_called()
        self.assertEqual(2, reads.get_simulation_run.call_count)
        self.assertEqual(run, json.loads((self.output / "run.json").read_text()))
        self.assertEqual(b"actual-failed-goal-export", (self.output / "evidence.zip").read_bytes())

    def test_read_inspection_cannot_mix_another_deployment_or_run_output(self):
        self.output.mkdir()
        (self.output / "manifest.json").write_text(json.dumps({"apiBase": "http://different:8084", "runId": "run-2"}))
        with patch.object(self.evidence, "SimulationRunsApi") as reads, patch.object(launcher, "launch") as launch:
            with self.assertRaisesRegex(ValueError, "another API deployment"):
                launcher.main(["--inspect", "run-1", "--output", str(self.output)])
        reads.assert_not_called()
        launch.assert_not_called()

    def test_observer_timeout_leaves_same_run_readable_and_never_relaunches(self):
        reads = MagicMock()
        reads.get_simulation_run.return_value = document({"runId": "run-1", "status": "RUNNING"})
        clock = [0.0]
        def advance(seconds):
            clock[0] += seconds
        with patch.object(self.evidence, "SimulationRunsApi", return_value=reads), patch.object(launcher, "launch") as launch, patch.object(self.evidence.time, "monotonic", side_effect=lambda: clock[0]), patch.object(self.evidence.time, "sleep", side_effect=advance):
            self.assertEqual(3, launcher.main(["--inspect", "run-1", "--output", str(self.output), "--wait", "--timeout", "5"]))
        launch.assert_not_called()
        self.assertEqual("run-1", json.loads((self.output / "manifest.json").read_text())["runId"])
        self.assertEqual("RUNNING", json.loads((self.output / "run.json").read_text())["status"])
        reads.export_simulation_run_evidence.assert_not_called()

    def test_non_finite_observer_limits_fail_before_any_mutation(self):
        for timeout in ("nan", "inf", "0", "-1"):
            with self.subTest(timeout=timeout), patch.object(launcher, "launch") as launch, self.assertRaises(SystemExit):
                launcher.main(["--timeout", timeout, "--output", str(self.output)])
            launch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
