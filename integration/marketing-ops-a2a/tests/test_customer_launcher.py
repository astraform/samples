"""Persist marketing identities and resume reads without replacement launches."""
import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from urllib3.exceptions import ReadTimeoutError
from astraform_platform_client.models.agent_journey_goal_completion_policy import AgentJourneyGoalCompletionPolicy

DIRECTORY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("marketing_customer_launcher", DIRECTORY / "run-customer.py")
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


def document(value):
    return SimpleNamespace(to_json=lambda: json.dumps(value))


@contextlib.contextmanager
def journey_http_fixture():
    """Owned SDK HTTP boundary; no platform or model service is used."""
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            raw = self.rfile.read(int(self.headers['Content-Length']))
            request = json.loads(raw)
            requests.append({'path': self.path, 'headers': dict(self.headers),
                             'body': request, 'raw': raw})
            response = {
                'schemaVersion': 'agent_journey_launch.v2',
                'requestId': request['requestId'],
                'runId': '02a6f6e0-d3ab-4af3-ab57-a12b6430f90d',
                'agentId': 'c89179bc-01cc-4e83-97b2-1c72a752bb80',
                'catalogAgentId': request['catalogAgentId'],
                'catalogRevisionId': request['catalogRevisionId'],
                'catalogDefinitionDigest': request['catalogDefinitionDigest'],
                'displayName': 'Jordan', 'modelBindingKey': 'local-customer',
                'systemInstructionDigest': 'sha256:' + 'b' * 64,
                'status': 'QUEUED', 'executionProfile': 'AGENT_DEFINITION_V1',
                'replayed': False,
                'ledgerUrl': '/api/simulation-runs/fixture/ledger',
                'exportUrl': '/api/simulation-runs/fixture/evidence.zip'}
            body = json.dumps(response).encode()
            self.send_response(202)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', requests
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)


class MarketingCustomerLauncherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name) / "run"
        # Preserve receipt-only transport/recovery coverage as an explicit custom scenario.
        receipt_only = json.loads((DIRECTORY / "sample-configuration.json").read_text())
        receipt_only["launchRequestTemplate"]["completionPolicy"].pop("requireCustomerFinish", None)
        self.receipt_only_sample = self.output.parent / "receipt-only.json"
        self.receipt_only_sample.write_text(json.dumps(receipt_only))
        self.enterContext(patch.dict(os.environ, {"ASTRAFORM_API_BASE": "http://platform:8084",
            "MARKETING_SAMPLE_FILE": str(DIRECTORY / "sample-configuration.json")}))
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.enterContext(contextlib.redirect_stderr(io.StringIO()))
        self.evidence = launcher.evidence

    def catalog(self):
        client = MagicMock()
        client.create_agent_definition.return_value = document({"definition": {"catalogAgentId": "00000000-0000-4000-8000-000000000001", "draftVersion": 1, "draftSpecDigest": "sha256:" + "a" * 64}})
        client.publish_agent_definition.return_value = document({"revision": {"catalogRevisionId": "00000000-0000-4000-8000-000000000002", "catalogDefinitionDigest": "sha256:" + "b" * 64}})
        return client

    def test_uncertain_launch_keeps_exact_operation_ids_and_refuses_a_second_launch(self):
        self.enterContext(patch.dict(os.environ, {"MARKETING_SAMPLE_FILE": str(self.receipt_only_sample)}))
        catalog = self.catalog()
        journeys = MagicMock()
        def uncertain(request, *, _request_timeout):
            self.assertIsInstance(request, launcher.AgentJourneyLaunchRequest)
            with launcher.ApiClient() as client:
                self.assertEqual(client.sanitize_for_serialization(request), json.loads((self.output / "launch-request.json").read_text()))
            self.assertEqual(str(request.request_id), json.loads((self.output / "session.json").read_text())["scenario"]["launchRequestTemplate"]["requestId"])
            self.assertEqual(60.0, _request_timeout)
            raise ReadTimeoutError(None, "/api/agent-journeys", "uncertain response")
        journeys.launch_agent_journey.side_effect = uncertain
        with patch.object(launcher, "AgentCatalogApi", return_value=catalog), patch.object(launcher, "AgentJourneysApi", return_value=journeys):
            with self.assertRaises(ReadTimeoutError):
                launcher.main(["--output", str(self.output), "--wait"])
            before = {file.name: file.read_bytes() for file in self.output.iterdir()}
            with self.assertRaisesRegex(ValueError, "not empty"):
                launcher.main(["--output", str(self.output)])
        self.assertEqual(1, catalog.create_agent_definition.call_count)
        self.assertEqual(1, catalog.publish_agent_definition.call_count)
        self.assertEqual(1, journeys.launch_agent_journey.call_count)
        self.assertEqual(before, {file.name: file.read_bytes() for file in self.output.iterdir()})
        self.assertFalse((self.output / "launch.json").exists())

    def test_launch_once_then_wait_inspects_the_accepted_run_and_saves_manifest(self):
        self.enterContext(patch.dict(os.environ, {"MARKETING_SAMPLE_FILE": str(self.receipt_only_sample)}))
        catalog = self.catalog()
        journeys = MagicMock()
        journeys.launch_agent_journey.return_value = document({"runId": "run-1"})
        # The committed evidence helper owns saving/inspection, not journey launch.
        evidence = SimpleNamespace(save=self.evidence.save, inspect_run=MagicMock(return_value=0))
        with patch.object(launcher, "AgentCatalogApi", return_value=catalog), patch.object(launcher, "AgentJourneysApi", return_value=journeys), patch.object(launcher, "evidence", evidence):
            self.assertEqual(0, launcher.main(["--output", str(self.output), "--wait"]))
        journeys.launch_agent_journey.assert_called_once()
        request = journeys.launch_agent_journey.call_args.args[0]
        self.assertIsInstance(request, launcher.AgentJourneyLaunchRequest)
        with launcher.ApiClient() as client:
            self.assertEqual(client.sanitize_for_serialization(request), json.loads((self.output / "launch-request.json").read_text()))
        self.assertEqual({"_request_timeout": 60.0}, journeys.launch_agent_journey.call_args.kwargs)
        self.assertEqual(("run-1", self.output, True, 1500), evidence.inspect_run.call_args.args[2:])
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


    def supports_finish_policy(self):
        """Inspect the selected real SDK serializer without injecting capability."""
        policy = json.loads((DIRECTORY / 'sample-configuration.json').read_text())['launchRequestTemplate']['completionPolicy']
        return all(AgentJourneyGoalCompletionPolicy.from_dict(
            {**policy, 'requireCustomerFinish': value}).to_dict().get('requireCustomerFinish') is value
            for value in (False, True))

    def assert_native_launch_body(self, client, requests, output, result):
        self.assertEqual(1, len(requests))
        self.assertEqual('/api/agent-journeys', requests[0]['path'])
        self.assertEqual('application/json', requests[0]['headers']['Content-Type'])
        saved = json.loads((output / 'launch-request.json').read_text())
        self.assertEqual(saved, requests[0]['body'])
        self.assertEqual(client.sanitize_for_serialization(
            launcher.AgentJourneyLaunchRequest.from_dict(saved)), requests[0]['body'])
        self.assertEqual(saved['requestId'], result['requestId'])
        self.assertEqual(saved['requestId'], json.loads((output / 'session.json').read_text())['scenario']['launchRequestTemplate']['requestId'])
        self.assertEqual('QUEUED', result['status'])

    def test_custom_finish_flags_use_capable_native_sdk_or_reject_before_writes(self):
        for value in (False, True):
            with self.subTest(requireCustomerFinish=value):
                scenario = json.loads((DIRECTORY / 'sample-configuration.json').read_text())
                scenario['launchRequestTemplate']['completionPolicy']['requireCustomerFinish'] = value
                selected = self.output.parent / ('strict-' + str(value) + '.json')
                selected.write_text(json.dumps(scenario))
                output = self.output.parent / ('strict-' + str(value))
                catalog = self.catalog()
                with patch.dict(os.environ, {'MARKETING_SAMPLE_FILE': str(selected)}), \
                        patch.object(launcher, 'AgentCatalogApi', return_value=catalog), \
                        journey_http_fixture() as (host, requests), \
                        launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
                    if self.supports_finish_policy():
                        result = launcher.launch(client, host, output)
                        self.assert_native_launch_body(client, requests, output, result)
                        self.assertIs(value, requests[0]['body']['completionPolicy']['requireCustomerFinish'])
                        catalog.create_agent_definition.assert_called_once()
                        catalog.publish_agent_definition.assert_called_once()
                    else:
                        with self.assertRaisesRegex(ValueError, 'requireCustomerFinish'):
                            launcher.launch(client, host, output)
                        catalog.create_agent_definition.assert_not_called()
                        catalog.publish_agent_definition.assert_not_called()
                        self.assertFalse(output.exists())
                        self.assertEqual([], requests)

    def test_default_templates_require_finish_with_native_sdk_or_reject_before_writes(self):
        for name in ("sample-configuration.json", "current-state-configuration.json"):
            with self.subTest(template=name):
                selected = DIRECTORY / name
                policy = json.loads(selected.read_text())["launchRequestTemplate"]["completionPolicy"]
                self.assertIs(True, policy["requireCustomerFinish"])
                output = self.output.parent / ("default-" + selected.stem)
                catalog = self.catalog()
                with patch.dict(os.environ, {"MARKETING_SAMPLE_FILE": str(selected)}), \
                        patch.object(launcher, "AgentCatalogApi", return_value=catalog), \
                        journey_http_fixture() as (host, requests), \
                        launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
                    if self.supports_finish_policy():
                        result = launcher.launch(client, host, output)
                        self.assert_native_launch_body(client, requests, output, result)
                        self.assertIs(True, requests[0]["body"]["completionPolicy"]["requireCustomerFinish"])
                        catalog.create_agent_definition.assert_called_once()
                        catalog.publish_agent_definition.assert_called_once()
                    else:
                        with self.assertRaisesRegex(ValueError, "requireCustomerFinish"):
                            launcher.launch(client, host, output)
                        catalog.create_agent_definition.assert_not_called()
                        catalog.publish_agent_definition.assert_not_called()
                        self.assertFalse(output.exists())
                        self.assertEqual([], requests)

    def test_omitted_finish_flag_uses_actual_native_sdk_http(self):
        self.enterContext(patch.dict(os.environ, {"MARKETING_SAMPLE_FILE": str(self.receipt_only_sample)}))
        catalog = self.catalog()
        with patch.object(launcher, 'AgentCatalogApi', return_value=catalog), \
                journey_http_fixture() as (host, requests), \
                launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
            result = launcher.launch(client, host, self.output)
            self.assert_native_launch_body(client, requests, self.output, result)
        self.assertNotIn('requireCustomerFinish', requests[0]['body']['completionPolicy'])
        catalog.create_agent_definition.assert_called_once()
        catalog.publish_agent_definition.assert_called_once()

    def test_invalid_custom_finish_values_fail_before_catalog_output_or_http(self):
        with journey_http_fixture() as (host, requests), \
                launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
            for value in (None, 'true', 'false', 1, 0, 1.0, [], {}, [True]):
                with self.subTest(value=value):
                    scenario = json.loads((DIRECTORY / 'sample-configuration.json').read_text())
                    scenario['launchRequestTemplate']['completionPolicy']['requireCustomerFinish'] = value
                    selected = self.output.parent / 'invalid.json'
                    selected.write_text(json.dumps(scenario))
                    catalog = self.catalog()
                    with patch.dict(os.environ, {'MARKETING_SAMPLE_FILE': str(selected)}), \
                            patch.object(launcher, 'AgentCatalogApi', return_value=catalog):
                        with self.assertRaisesRegex(ValueError, 'must be a Boolean'):
                            launcher.launch(client, host, self.output)
                    catalog.create_agent_definition.assert_not_called()
                    catalog.publish_agent_definition.assert_not_called()
                    self.assertFalse(self.output.exists())
                    self.assertEqual([], requests)


if __name__ == "__main__":
    unittest.main()
