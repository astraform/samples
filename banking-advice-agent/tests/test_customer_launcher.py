"""Exercise safe launch persistence and read-only recovery through the SDK boundary."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from urllib3.exceptions import ReadTimeoutError

DIRECTORY = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('banking_customer_launcher', DIRECTORY / 'run-customer.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


def document(value):
    return SimpleNamespace(to_json=lambda: json.dumps(value))


class CustomerLauncherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name) / 'run'
        environment = patch.dict(os.environ, {
            'ASTRAFORM_API_BASE': 'http://platform:8084',
            'BANKING_SAMPLE_FILE': str(DIRECTORY / 'issued-statement-configuration.json'),
        })
        environment.start()
        self.addCleanup(environment.stop)
        self.stdout = io.StringIO()
        self.enterContext(contextlib.redirect_stdout(self.stdout))
        self.enterContext(contextlib.redirect_stderr(io.StringIO()))

    def test_inspection_retries_reads_and_exports_failed_goal_without_any_launch(self):
        run = {'runId': 'run-1', 'status': 'FAILED', 'metadata': {'nativeProgress': {
            'goal': {'status': 'ABANDONED'}, 'stopReason': 'GOAL_ABANDONED'}}}
        reads = MagicMock()
        reads.get_simulation_run.side_effect = [ReadTimeoutError(None, '/run', 'slow'), document(run)]
        reads.inspect_simulation_run_ledger.return_value = document({'events': [{'eventId': 'event-1'}]})
        reads.get_simulation_run_evidence_export_readiness_with_http_info.return_value = SimpleNamespace(status_code=204)
        reads.export_simulation_run_evidence.return_value = b'actual-export-bytes'
        with patch.object(launcher, 'SimulationRunsApi', return_value=reads), \
                patch.object(launcher, 'launch') as launch, patch.object(launcher.time, 'sleep'):
            code = launcher.main(['--inspect', 'run-1', '--output', str(self.output)])
        self.assertEqual(0, code)  # A failed simulation goal is a recorded result, not a CLI failure.
        launch.assert_not_called()
        self.assertEqual(2, reads.get_simulation_run.call_count)
        self.assertEqual(run, json.loads((self.output / 'run.json').read_text()))
        self.assertEqual(b'actual-export-bytes', (self.output / 'evidence.zip').read_bytes())
        self.assertIn('GOAL_ABANDONED', self.stdout.getvalue())

    def test_repeated_read_failures_stop_after_three_attempts(self):
        reads = MagicMock()
        reads.get_simulation_run.side_effect = ReadTimeoutError(None, '/run', 'unavailable')
        with patch.object(launcher, 'SimulationRunsApi', return_value=reads), \
                patch.object(launcher, 'launch') as launch, patch.object(launcher.time, 'sleep'):
            with self.assertRaises(ReadTimeoutError):
                launcher.main(['--inspect', 'run-1', '--output', str(self.output), '--wait'])
        launch.assert_not_called()
        self.assertEqual(3, reads.get_simulation_run.call_count)
        reads.export_simulation_run_evidence.assert_not_called()

    def test_wait_deadline_keeps_run_for_read_only_resumption(self):
        reads = MagicMock()
        reads.get_simulation_run.return_value = document({'runId': 'run-1', 'status': 'RUNNING'})
        clock = [0.0]
        def advance(seconds):
            clock[0] += seconds
        with patch.object(launcher, 'SimulationRunsApi', return_value=reads), \
                patch.object(launcher, 'launch') as launch, \
                patch.object(launcher.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(launcher.time, 'sleep', side_effect=advance):
            code = launcher.main(['--inspect', 'run-1', '--wait', '--timeout', '5', '--output', str(self.output)])
        self.assertEqual(3, code)
        launch.assert_not_called()
        reads.export_simulation_run_evidence.assert_not_called()
        self.assertEqual('RUNNING', json.loads((self.output / 'run.json').read_text())['status'])
        self.assertEqual('run-1', json.loads((self.output / 'manifest.json').read_text())['runId'])

    def test_uncertain_launch_response_keeps_exact_requests_and_refuses_relaunch(self):
        digest = 'sha256:' + 'a' * 64
        capabilities = {'bindingKey': 'banking', 'bindingRevision': 'v1', 'bindingDigest': digest,
                        'tools': [{'name': 'get_customer_accounts', 'descriptorDigest': digest,
                                   'inputSchemaDigest': digest, 'outputSchemaDigest': digest,
                                   'effectClass': 'READ_ONLY', 'scope': 'CUSTOMER_CONTEXT'}]}
        catalog = MagicMock()
        catalog.create_agent_definition.return_value = document({'definition': {
            'catalogAgentId': '8587236c-2854-462b-9fc5-d3c1d9128422',
            'draftVersion': 0, 'draftSpecDigest': digest}})
        catalog.publish_agent_definition.return_value = document({'revision': {
            'catalogRevisionId': 'd1d5e648-a161-416a-9db2-89e7b88b7425', 'catalogDefinitionDigest': digest}})
        events = MagicMock()
        events.inspect_public_customer_mcp_capabilities.return_value = document(capabilities)
        journeys = MagicMock()
        def seed(*_args):
            session = json.loads((self.output / 'session.json').read_text())
            self.assertTrue(session['scenario']['catalogDraftRequest']['requestId'])
            self.assertTrue(session['scenario']['launchRequestTemplate']['requestId'])
        def uncertain(request, **_kwargs):
            saved = json.loads((self.output / 'launch-request.json').read_text())
            self.assertEqual(saved['requestId'], str(request.request_id))
            self.assertTrue((self.output / 'created.json').exists())
            self.assertTrue((self.output / 'published.json').exists())
            raise ReadTimeoutError(None, '/launch', 'response unknown')
        journeys.launch_agent_journey.side_effect = uncertain
        with patch.object(launcher, 'AgentCatalogApi', return_value=catalog), \
                patch.object(launcher, 'SimulationEventsApi', return_value=events), \
                patch.object(launcher, 'AgentJourneysApi', return_value=journeys), \
                patch.object(launcher, 'bank_request', side_effect=seed) as bank:
            with self.assertRaises(ReadTimeoutError):
                launcher.main(['--output', str(self.output)])
            saved = (self.output / 'launch-request.json').read_bytes()
            with self.assertRaisesRegex(ValueError, 'not empty'):
                launcher.main(['--output', str(self.output)])
        bank.assert_called_once()
        journeys.launch_agent_journey.assert_called_once()
        self.assertEqual(saved, (self.output / 'launch-request.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
