"""Exercise safe launch persistence and read-only recovery through the SDK boundary."""
import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from urllib3.exceptions import ReadTimeoutError
from astraform_platform_client.models.agent_journey_goal_completion_policy import AgentJourneyGoalCompletionPolicy

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
        with patch.object(launcher.evidence, 'SimulationRunsApi', return_value=reads), \
                patch.object(launcher, 'launch') as launch, patch.object(launcher.evidence.time, 'sleep'):
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
        with patch.object(launcher.evidence, 'SimulationRunsApi', return_value=reads), \
                patch.object(launcher, 'launch') as launch, patch.object(launcher.evidence.time, 'sleep'):
            with self.assertRaises(ReadTimeoutError):
                launcher.main(['--inspect', 'run-1', '--output', str(self.output), '--wait'])
        launch.assert_not_called()
        self.assertEqual(3, reads.get_simulation_run.call_count)
        reads.export_simulation_run_evidence.assert_not_called()

    def test_observer_default_covers_thinking_window_without_launching_again(self):
        with patch.object(launcher.evidence, 'inspect_run', return_value=0) as inspect, \
                patch.object(launcher, 'launch') as launch:
            code = launcher.main(['--inspect', 'run-1', '--wait', '--output', str(self.output)])
        self.assertEqual(0, code)
        launch.assert_not_called()
        self.assertEqual(('run-1', self.output, True, 8220), inspect.call_args.args[2:])
        self.assertEqual(7200 + 900 + 120, inspect.call_args.args[-1])

    def test_wait_deadline_keeps_run_for_read_only_resumption(self):
        reads = MagicMock()
        reads.get_simulation_run.return_value = document({'runId': 'run-1', 'status': 'RUNNING'})
        clock = [0.0]
        def advance(seconds):
            clock[0] += seconds
        with patch.object(launcher.evidence, 'SimulationRunsApi', return_value=reads), \
                patch.object(launcher, 'launch') as launch, \
                patch.object(launcher.evidence.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(launcher.evidence.time, 'sleep', side_effect=advance):
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
        def seed(*_args):
            session = json.loads((self.output / 'session.json').read_text())
            self.assertTrue(session['scenario']['catalogDraftRequest']['requestId'])
            self.assertTrue(session['scenario']['launchRequestTemplate']['requestId'])
        # This persistence test uses the published-compatible omitted policy.
        scenario = json.loads((DIRECTORY / 'issued-statement-configuration.json').read_text())
        scenario['launchRequestTemplate']['completionPolicy'].pop('requireCustomerFinish')
        ordinary = self.output.parent / 'ordinary.json'
        ordinary.write_text(json.dumps(scenario))
        journeys = MagicMock()
        def uncertain(request, *, _request_timeout):
            saved = json.loads((self.output / 'launch-request.json').read_text())
            self.assertIsInstance(request, launcher.AgentJourneyLaunchRequest)
            with launcher.ApiClient() as client:
                self.assertEqual(saved, client.sanitize_for_serialization(request))
            self.assertEqual(str(request.request_id), saved['requestId'])
            self.assertEqual(60.0, _request_timeout)
            self.assertTrue((self.output / 'created.json').exists())
            self.assertTrue((self.output / 'published.json').exists())
            raise ReadTimeoutError(None, '/launch', 'response unknown')
        journeys.launch_agent_journey.side_effect = uncertain
        with patch.dict(os.environ, {'BANKING_SAMPLE_FILE': str(ordinary)}), \
                patch.object(launcher, 'AgentCatalogApi', return_value=catalog), \
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


    def test_invalid_finish_policy_stops_before_seed_or_catalog_writes(self):
        for value in (None, "true", "false", 1, 0, 1.0, [], {}, [True]):
            with self.subTest(value=value):
                scenario = json.loads((DIRECTORY / 'issued-statement-configuration.json').read_text())
                scenario['launchRequestTemplate']['completionPolicy']['requireCustomerFinish'] = value
                invalid = self.output.parent / 'invalid.json'
                invalid.write_text(json.dumps(scenario))
                catalog = MagicMock()
                with patch.dict(os.environ, {'BANKING_SAMPLE_FILE': str(invalid)}), \
                        patch.object(launcher, 'AgentCatalogApi', return_value=catalog), \
                        patch.object(launcher, 'bank_request') as bank, \
                        patch.object(launcher, 'AgentJourneysApi') as journeys:
                    with self.assertRaisesRegex(ValueError, 'must be a Boolean'):
                        launcher.main(['--output', str(self.output)])
                bank.assert_not_called()
                catalog.create_agent_definition.assert_not_called()
                catalog.publish_agent_definition.assert_not_called()
                journeys.assert_not_called()
                self.assertFalse(self.output.exists())


    def prepare_capabilities(self, binding="banking"):
        digest = 'sha256:' + 'a' * 64
        return {'bindingKey': binding, 'bindingRevision': 'actual-v3', 'bindingDigest': digest,
                'tools': [{'name': 'get_customer_accounts', 'descriptorDigest': digest,
                           'inputSchemaDigest': digest, 'outputSchemaDigest': digest,
                           'effectClass': 'READ_ONLY', 'scope': 'CUSTOMER_CONTEXT'}]}

    def test_prepare_only_emits_java_scenario_with_exact_grants_and_no_catalog_or_journey_writes(self):
        sample = json.loads((DIRECTORY / 'three-month-configuration.json').read_text())
        sample['catalogDraftRequest']['draft']['tools'][0]['maxCalls'] = 3
        sample['launchRequestTemplate']['completionPolicy']['requireCustomerFinish'] = True
        selected = self.output.parent / 'monthly.json'
        selected.write_text(json.dumps(sample))
        capabilities = self.prepare_capabilities('banking-scoped')
        events = MagicMock()
        events.inspect_public_customer_mcp_capabilities.return_value = document(capabilities)
        def seed(method, url, body):
            session = json.loads((self.output / 'session.json').read_text())
            self.assertEqual(sample['bankCustomerSeed'], body)
            self.assertTrue(session['scenario']['catalogDraftRequest']['requestId'])
            self.assertTrue(session['scenario']['launchRequestTemplate']['requestId'])
            self.assertEqual('PUT', method)
            self.assertTrue(url.endswith('/contexts/prepared%2Fcontext/customers/customer-z'))
            return {'seeded': True}
        with patch.dict(os.environ, {'BANKING_SAMPLE_FILE': str(selected),
                                    'BANKING_CUSTOMER_BINDING_KEY': 'banking-scoped',
                                    'BANKING_DOMAIN_BINDING_KEY': 'banking-native',
                                    'BANKING_CONTEXT_REF': 'prepared/context',
                                    'BANKING_SUBJECT_REF': 'customer-z',
                                    'BANKING_PARTNER_TARGET_AGENT_KEY': 'banking-adviser'}), \
                patch.object(launcher, 'SimulationEventsApi', return_value=events), \
                patch.object(launcher, 'AgentCatalogApi') as catalog, \
                patch.object(launcher, 'AgentJourneysApi') as journeys, \
                patch.object(launcher, 'bank_request', side_effect=seed) as bank:
            self.assertEqual(0, launcher.main(['--prepare-only', '--output', str(self.output)]))
            saved = (self.output / 'prepared-scenario.json').read_bytes()
            with self.assertRaisesRegex(ValueError, 'not empty'):
                launcher.main(['--prepare-only', '--output', str(self.output)])
        bank.assert_called_once()
        events.inspect_public_customer_mcp_capabilities.assert_called_once_with('banking-scoped', _request_timeout=60.0)
        catalog.assert_not_called()
        journeys.assert_not_called()
        prepared = json.loads(saved)
        self.assertEqual({'catalog', 'launch'}, set(prepared))
        grant = prepared['catalog']['draft']['tools'][0]
        descriptor = capabilities['tools'][0]
        self.assertEqual({'kind': 'MCP', 'toolName': 'get_customer_accounts', 'maxCalls': 3,
                          **{key: capabilities[key] for key in ('bindingKey', 'bindingRevision', 'bindingDigest')},
                          **{key: descriptor[key] for key in ('descriptorDigest', 'inputSchemaDigest', 'outputSchemaDigest',
                                                            'effectClass', 'scope')}}, grant)
        launch = prepared['launch']
        self.assertEqual([{'bindingKey': 'banking-scoped', 'contextRef': 'prepared/context',
                           'subjectRef': 'customer-z'}], launch['toolContexts'])
        self.assertEqual('banking-native', launch['domain']['bindingKey'])
        self.assertEqual('banking-scoped', launch['domain']['toolContextBindingKey'])
        self.assertEqual('banking-adviser', launch['partnerTargetAgentKey'])
        expected_policy = sample['launchRequestTemplate']['completionPolicy']
        for condition in expected_policy['partnerResponseConditions']:
            if condition['pointer'] == '/bankingContext/subjectRef':
                condition['oneOf'] = ['customer-z']
        self.assertEqual(expected_policy, launch['completionPolicy'])
        self.assertIs(True, launch['completionPolicy']['requireCustomerFinish'])
        self.assertEqual(sample['launchRequestTemplate']['facts'], launch['facts'])
        self.assertEqual(sample['launchRequestTemplate']['logicalTimeEpochMs'], launch['logicalTimeEpochMs'])
        self.assertTrue(launch['catalogAgentId'].startswith('<'))
        session = json.loads((self.output / 'session.json').read_text())
        self.assertEqual(session['scenario']['catalogDraftRequest']['requestId'], prepared['catalog']['requestId'])
        self.assertEqual(session['scenario']['launchRequestTemplate']['requestId'], launch['requestId'])
        self.assertEqual({'seeded': True}, json.loads((self.output / 'bank-seed-response.json').read_text()))
        self.assertEqual(capabilities, json.loads((self.output / 'capabilities.json').read_text()))
        self.assertEqual(saved, (self.output / 'prepared-scenario.json').read_bytes())
        self.assertFalse(any((self.output / name).exists() for name in
                             ('create-request.json', 'created.json', 'publish-request.json', 'published.json',
                              'launch-request.json', 'launch.json', 'manifest.json')))

    def test_prepare_only_cli_rejects_missing_output_wait_and_inspect_before_any_client(self):
        for arguments in (['--prepare-only'], ['--prepare-only', '--wait', '--output', str(self.output)],
                          ['--prepare-only', '--inspect', 'run-1', '--output', str(self.output)]):
            with self.subTest(arguments=arguments), patch.object(launcher, 'ApiClient') as client, \
                    patch.object(launcher, 'bank_request') as bank:
                with self.assertRaises(SystemExit) as raised:
                    launcher.main(arguments)
                self.assertEqual(2, raised.exception.code)
                client.assert_not_called()
                bank.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_prepare_only_requires_native_strict_support_before_any_effect(self):
        policy = json.loads((DIRECTORY / 'issued-statement-configuration.json').read_text())['launchRequestTemplate']['completionPolicy']
        policy.pop('requireCustomerFinish')
        unsupported = AgentJourneyGoalCompletionPolicy.from_dict(policy)
        with patch.object(launcher.AgentJourneyGoalCompletionPolicy, 'from_dict', return_value=unsupported), \
                patch.object(launcher, 'SimulationEventsApi') as events, \
                patch.object(launcher, 'AgentCatalogApi') as catalog, \
                patch.object(launcher, 'AgentJourneysApi') as journeys, \
                patch.object(launcher, 'bank_request') as bank:
            with self.assertRaisesRegex(ValueError, 'cannot preserve completionPolicy.requireCustomerFinish'):
                launcher.main(['--prepare-only', '--output', str(self.output)])
        bank.assert_not_called()
        events.assert_not_called()
        catalog.assert_not_called()
        journeys.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_prepare_only_preserves_uncertain_seed_and_refuses_another_attempt(self):
        with patch.object(launcher, 'bank_request', side_effect=launcher.URLError('response unknown')) as bank, \
                patch.object(launcher, 'SimulationEventsApi') as events, \
                patch.object(launcher, 'AgentCatalogApi') as catalog, \
                patch.object(launcher, 'AgentJourneysApi') as journeys:
            with self.assertRaises(launcher.URLError):
                launcher.main(['--prepare-only', '--output', str(self.output)])
            session = (self.output / 'session.json').read_bytes()
            seed_request = (self.output / 'bank-seed-request.json').read_bytes()
            with self.assertRaisesRegex(ValueError, 'not empty'):
                launcher.main(['--prepare-only', '--output', str(self.output)])
        bank.assert_called_once()
        events.assert_not_called()
        catalog.assert_not_called()
        journeys.assert_not_called()
        self.assertEqual(session, (self.output / 'session.json').read_bytes())
        self.assertEqual(seed_request, (self.output / 'bank-seed-request.json').read_bytes())
        self.assertFalse((self.output / 'prepared-scenario.json').exists())

    def test_prepare_only_rejects_untrusted_or_ambiguous_mcp_grant_without_platform_writes(self):
        variants = []
        for key, value in (('effectClass', 'STATE_WRITE'), ('scope', 'GLOBAL'), ('descriptorDigest', '<unresolved>')):
            capabilities = self.prepare_capabilities()
            capabilities['tools'][0][key] = value
            variants.append(capabilities)
        duplicate = self.prepare_capabilities()
        duplicate['tools'].append(dict(duplicate['tools'][0]))
        variants.append(duplicate)
        variants.append(self.prepare_capabilities('other-binding'))
        for index, capabilities in enumerate(variants):
            output = self.output.parent / ('invalid-grant-' + str(index))
            events = MagicMock()
            events.inspect_public_customer_mcp_capabilities.return_value = document(capabilities)
            with self.subTest(capabilities=capabilities), \
                    patch.object(launcher, 'bank_request', return_value={}), \
                    patch.object(launcher, 'SimulationEventsApi', return_value=events), \
                    patch.object(launcher, 'AgentCatalogApi') as catalog, \
                    patch.object(launcher, 'AgentJourneysApi') as journeys:
                with self.assertRaises(ValueError):
                    launcher.main(['--prepare-only', '--output', str(output)])
            catalog.assert_not_called()
            journeys.assert_not_called()
            self.assertFalse((output / 'prepared-scenario.json').exists())
            self.assertEqual(capabilities, json.loads((output / 'capabilities.json').read_text()))



@contextlib.contextmanager
def journey_http_fixture(status=202):
    """Owned loopback HTTP boundary; no platform, bank or model service is used."""
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            raw = self.rfile.read(int(self.headers['Content-Length']))
            request = json.loads(raw)
            requests.append({'path': self.path, 'headers': dict(self.headers),
                             'body': request, 'raw': raw})
            if status == 202:
                response = {
                    'schemaVersion': 'agent_journey_launch.v2',
                    'requestId': request['requestId'],
                    'runId': '02a6f6e0-d3ab-4af3-ab57-a12b6430f90d',
                    'agentId': 'c89179bc-01cc-4e83-97b2-1c72a752bb80',
                    'catalogAgentId': request['catalogAgentId'],
                    'catalogRevisionId': request['catalogRevisionId'],
                    'catalogDefinitionDigest': request['catalogDefinitionDigest'],
                    'displayName': 'Avery', 'modelBindingKey': 'local-customer',
                    'systemInstructionDigest': 'sha256:' + 'b' * 64,
                    'status': 'QUEUED', 'executionProfile': 'AGENT_DEFINITION_V1',
                    'replayed': False,
                    'ledgerUrl': '/api/simulation-runs/fixture/ledger',
                    'exportUrl': '/api/simulation-runs/fixture/evidence.zip'}
            else:
                response = {'code': 'INVALID_REQUEST',
                            'message': 'This fixture represents an unsupported completion policy'}
            body = json.dumps(response).encode()
            self.send_response(status)
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


class JourneyLaunchTransportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.stdout = self.enterContext(contextlib.redirect_stdout(io.StringIO()))
        self.enterContext(contextlib.redirect_stderr(io.StringIO()))

    def supports_finish_policy(self):
        """Read the selected real SDK's serializer; never mock model capability."""
        policy = json.loads((DIRECTORY / 'issued-statement-configuration.json').read_text())['launchRequestTemplate']['completionPolicy']
        return all(AgentJourneyGoalCompletionPolicy.from_dict(
            {**policy, 'requireCustomerFinish': value}).to_dict().get('requireCustomerFinish') is value
            for value in (False, True))

    @contextlib.contextmanager
    def launch_inputs(self, finish='omitted', invalid_budget=False):
        scenario = json.loads((DIRECTORY / 'issued-statement-configuration.json').read_text())
        if finish == 'omitted':
            scenario['launchRequestTemplate']['completionPolicy'].pop('requireCustomerFinish')
        else:
            scenario['launchRequestTemplate']['completionPolicy']['requireCustomerFinish'] = finish
        if invalid_budget:
            scenario['launchRequestTemplate']['completionPolicy']['maxCustomerDecisions'] = 0
        selected = self.directory / 'scenario.json'
        selected.write_text(json.dumps(scenario))
        output = self.directory / 'run'
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
        with patch.dict(os.environ, {'BANKING_SAMPLE_FILE': str(selected)}), \
                patch.object(launcher, 'AgentCatalogApi', return_value=catalog), \
                patch.object(launcher, 'SimulationEventsApi', return_value=events), \
                patch.object(launcher, 'bank_request', return_value={}) as bank:
            yield output, catalog, events, bank

    def assert_native_launch_body(self, requests, output, result, finish='omitted'):
        self.assertEqual(1, len(requests))
        self.assertEqual('/api/agent-journeys', requests[0]['path'])
        self.assertEqual('application/json', requests[0]['headers']['Content-Type'])
        saved = json.loads((output / 'launch-request.json').read_text())
        self.assertEqual(saved, requests[0]['body'])
        with launcher.ApiClient() as client:
            self.assertEqual(client.sanitize_for_serialization(
                launcher.AgentJourneyLaunchRequest.from_dict(saved)), requests[0]['body'])
        self.assertEqual(saved['requestId'], result['requestId'])
        self.assertEqual('QUEUED', result['status'])
        self.assertEqual(saved['requestId'], json.loads((output / 'session.json').read_text())['scenario']['launchRequestTemplate']['requestId'])
        if finish == 'omitted':
            self.assertNotIn('requireCustomerFinish', requests[0]['body']['completionPolicy'])
        else:
            self.assertIs(finish, requests[0]['body']['completionPolicy']['requireCustomerFinish'])

    def assert_unsupported_before_mutations(self, output, catalog, events, bank, requests):
        bank.assert_not_called()
        events.inspect_public_customer_mcp_capabilities.assert_not_called()
        catalog.create_agent_definition.assert_not_called()
        catalog.publish_agent_definition.assert_not_called()
        self.assertFalse(output.exists())
        self.assertEqual([], requests)

    def test_true_uses_capable_native_sdk_or_fails_before_mutations(self):
        with self.launch_inputs(True) as (output, catalog, events, bank), \
                journey_http_fixture() as (host, requests), \
                launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
            if self.supports_finish_policy():
                result = launcher.launch(client, host, output)
                self.assert_native_launch_body(requests, output, result, True)
                bank.assert_called_once()
            else:
                with self.assertRaisesRegex(ValueError, 'requireCustomerFinish'):
                    launcher.launch(client, host, output)
                self.assert_unsupported_before_mutations(output, catalog, events, bank, requests)

    def test_explicit_false_uses_capable_native_sdk_or_fails_before_mutations(self):
        with self.launch_inputs(False) as (output, catalog, events, bank), \
                journey_http_fixture() as (host, requests), \
                launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
            if self.supports_finish_policy():
                result = launcher.launch(client, host, output)
                self.assert_native_launch_body(requests, output, result, False)
                bank.assert_called_once()
            else:
                with self.assertRaisesRegex(ValueError, 'requireCustomerFinish'):
                    launcher.launch(client, host, output)
                self.assert_unsupported_before_mutations(output, catalog, events, bank, requests)

    def test_omission_preserves_native_http_compatibility(self):
        with self.launch_inputs() as (output, catalog, events, bank), \
                journey_http_fixture() as (host, requests), \
                launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
            result = launcher.launch(client, host, output)
        self.assert_native_launch_body(requests, output, result)
        bank.assert_called_once()
        catalog.create_agent_definition.assert_called_once()
        catalog.publish_agent_definition.assert_called_once()

    def test_existing_generated_validation_is_retained_before_journey_http(self):
        with self.launch_inputs(invalid_budget=True) as (output, catalog, events, bank), \
                journey_http_fixture() as (host, requests), \
                launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
            with self.assertRaises(ValueError):
                launcher.launch(client, host, output)
        self.assertEqual([], requests)

    def test_server_rejection_preserves_error_without_retry_or_downgrade(self):
        finish = True if self.supports_finish_policy() else 'omitted'
        with self.launch_inputs(finish) as (output, catalog, events, bank), \
                journey_http_fixture(status=400) as (host, requests), \
                launcher.ApiClient(launcher.Configuration(host=host, retries=0)) as client:
            with self.assertRaises(launcher.ApiException) as raised:
                launcher.launch(client, host, output)
        self.assertEqual(400, raised.exception.status)
        self.assertEqual('INVALID_REQUEST', raised.exception.data.code)
        self.assertEqual(1, len(requests))
        self.assertEqual(json.loads((output / 'launch-request.json').read_text()), requests[0]['body'])
        self.assertFalse((output / 'launch.json').exists())
        if finish is True:
            self.assertIs(True, requests[0]['body']['completionPolicy']['requireCustomerFinish'])


if __name__ == '__main__':
    unittest.main()
