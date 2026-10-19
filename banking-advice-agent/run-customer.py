"""Prepare or launch one banking customer, or inspect its run through the typed SDK."""

import argparse
import importlib.util
import json
import math
import re
import sys
import os
from pathlib import Path
from urllib.parse import quote
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from urllib3.exceptions import HTTPError as TransportError
from uuid import uuid4

from astraform_platform_client import ApiClient, Configuration
from astraform_platform_client.exceptions import ApiException
from astraform_platform_client.api.agent_catalog_api import AgentCatalogApi
from astraform_platform_client.api.agent_journeys_api import AgentJourneysApi
from astraform_platform_client.models.agent_definition_create_request import AgentDefinitionCreateRequest
from astraform_platform_client.models.agent_definition_publish_request import AgentDefinitionPublishRequest
from astraform_platform_client.models.agent_journey_launch_request import AgentJourneyLaunchRequest
from astraform_platform_client.api.simulation_events_api import SimulationEventsApi
from astraform_platform_client.models.agent_journey_goal_completion_policy import AgentJourneyGoalCompletionPolicy


# One shared evidence owner, shipped beside either standalone launcher.
_evidence_path = Path(__file__).resolve().with_name("customer-run-evidence.py")
if not _evidence_path.is_file():
    _evidence_path = Path(__file__).resolve().parents[1] / "integration/customer-run-evidence.py"
_evidence_spec = importlib.util.spec_from_file_location("astraform_customer_run_evidence", _evidence_path)
evidence = importlib.util.module_from_spec(_evidence_spec)
_evidence_spec.loader.exec_module(evidence)


def bank_request(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"} if body is not None else {}
    try:
        with urlopen(Request(url, data=data, headers=headers, method=method), timeout=60) as response:
            return json.load(response)
    except HTTPError as error:
        raise SystemExit(f"{method} {url}: HTTP {error.code}: {error.read().decode()}") from None


def launch(client, api, output, prepare_only=False):
    if prepare_only and output is None:
        raise ValueError("--prepare-only requires --output")
    directory = Path(__file__).resolve().parent
    bank = os.environ.get("BANKING_BACKEND_URL", "http://localhost:8098").rstrip("/")
    binding = os.environ.get("BANKING_CUSTOMER_BINDING_KEY", "banking")
    domain_binding = os.environ.get("BANKING_DOMAIN_BINDING_KEY", "banking-reference")
    context = os.environ.get("BANKING_CONTEXT_REF", "bank-statement-" + uuid4().hex)
    subject = os.environ.get("BANKING_SUBJECT_REF", "customer-a")
    sample_path = Path(os.environ.get("BANKING_SAMPLE_FILE", directory / "sample-configuration.json"))
    sample = json.loads(sample_path.read_text())
    template = sample["launchRequestTemplate"]
    if "domainId" in template:
        domain_id = template["domainId"]
        if type(domain_id) is not str or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", domain_id) is None:
            raise ValueError("launch.domainId must be a lowercase domain identifier of 1 to 128 characters")
        # Probe the real generated model before any bank, Catalog or Journey writes.
        probe = AgentJourneyLaunchRequest.from_dict({
            "requestId": str(uuid4()), "catalogAgentId": str(uuid4()),
            "catalogRevisionId": str(uuid4()), "catalogDefinitionDigest": "sha256:" + "0" * 64,
            "instruction": "Validate declared domain identity", "domainId": domain_id})
        if client.sanitize_for_serialization(probe).get("domainId") != domain_id:
            raise ValueError(
                "The selected Python SDK cannot preserve launch.domainId. "
                "Install astraform-remote-domain-author-kit==0.3.4 or a newer compatible release, "
                "or deliberately omit domainId in a custom scenario; this launcher "
                "refuses to remove or manually reinsert the configured value.")
    policy = sample["launchRequestTemplate"].get("completionPolicy", {})
    if "requireCustomerFinish" in policy:
        required_finish = policy["requireCustomerFinish"]
        if type(required_finish) is not bool:
            raise ValueError("completionPolicy.requireCustomerFinish must be a Boolean")
        serialized = client.sanitize_for_serialization(AgentJourneyGoalCompletionPolicy.from_dict(policy))
        if serialized.get("requireCustomerFinish") is not required_finish:
            raise ValueError(
                "The selected Python SDK cannot preserve completionPolicy.requireCustomerFinish. "
                "Install astraform-remote-domain-author-kit==0.3.4 or a newer compatible release; "
                "this launcher refuses to remove or change the configured value.")
    configured_tools = [tool for tool in sample["catalogDraftRequest"]["draft"]["tools"]
                        if tool.get("kind") == "MCP" and tool.get("toolName") == "get_customer_accounts"]
    if len(configured_tools) != 1:
        raise SystemExit("Sample must configure exactly one get_customer_accounts MCP grant")
    max_calls = configured_tools[0].get("maxCalls")
    if type(max_calls) is not int or not 1 <= max_calls <= 100:
        raise SystemExit("get_customer_accounts maxCalls must be an integer between 1 and 100")
    seed_path = os.environ.get("BANKING_SEED_FILE")
    seed = json.loads(Path(seed_path).read_text()) if seed_path else sample["bankCustomerSeed"]
    if seed["logicalTimeEpochMs"] != sample["launchRequestTemplate"]["logicalTimeEpochMs"]:
        raise SystemExit("Bank seed and simulation must start at the same logicalTimeEpochMs")
    catalog = sample["catalogDraftRequest"]
    catalog["requestId"] = str(uuid4())
    catalog["agentKey"] = "banking-customer-" + uuid4().hex[:12]
    sample["launchRequestTemplate"]["requestId"] = str(uuid4())
    if output is not None:
        output.mkdir(parents=True, exist_ok=True)
        if any(output.iterdir()):
            raise ValueError("Launch output directory is not empty; inspect the existing run or choose a new directory")
        # Persist operation identities before the first write. A transport error must not
        # cause this helper to generate a replacement customer or simulation.
        with (output / "session.json").open("x", encoding="utf-8") as record:
            json.dump({"apiBase": api, "bankBase": bank, "contextRef": context,
                       "subjectRef": subject, "scenario": sample}, record, indent=2)
            record.write("\n")
        evidence.save(output, "bank-seed-request.json", {"contextRef": context, "subjectRef": subject, "body": seed})
    seeded = bank_request("PUT", bank + "/api/banking/v1/contexts/" + quote(context, safe="")
            + "/customers/" + quote(subject, safe=""), seed)
    evidence.save(output, "bank-seed-response.json", seeded)

    capabilities = json.loads(SimulationEventsApi(client).inspect_public_customer_mcp_capabilities(
        binding, _request_timeout=60.0).to_json())
    evidence.save(output, "capabilities.json", capabilities)
    descriptors = [tool for tool in capabilities["tools"] if tool.get("name") == "get_customer_accounts"]
    if len(descriptors) != 1 or capabilities.get("bindingKey") != binding:
        raise ValueError("Expected one get_customer_accounts descriptor for the configured MCP binding")
    descriptor = descriptors[0]
    if descriptor.get("effectClass") != "READ_ONLY" or descriptor.get("scope") != "CUSTOMER_CONTEXT":
        raise ValueError("get_customer_accounts must be READ_ONLY and CUSTOMER_CONTEXT scoped")
    binding_fields = ("bindingKey", "bindingRevision", "bindingDigest")
    descriptor_fields = ("descriptorDigest", "inputSchemaDigest", "outputSchemaDigest", "effectClass", "scope")
    for metadata, names in ((capabilities, binding_fields), (descriptor, descriptor_fields)):
        if any(not isinstance(metadata.get(name), str) or not metadata[name] or "<" in metadata[name]
               for name in names):
            raise ValueError("MCP capability metadata must contain resolved binding and descriptor identities")
    grant = {"kind": "MCP", "toolName": descriptor["name"], "maxCalls": max_calls,
             **{key: capabilities[key] for key in binding_fields},
             **{key: descriptor[key] for key in descriptor_fields}}
    catalog["draft"]["tools"] = [grant]
    launch_request = sample["launchRequestTemplate"]
    launch_request["toolContexts"] = [{"bindingKey": binding, "contextRef": context, "subjectRef": subject}]
    partner_target = os.environ.get("BANKING_PARTNER_TARGET_AGENT_KEY")
    if partner_target:
        launch_request["partnerTargetAgentKey"] = partner_target
    if "domain" in launch_request:
        launch_request["domain"].update({"bindingKey": domain_binding, "toolContextBindingKey": binding})
    for condition in launch_request["completionPolicy"]["partnerResponseConditions"]:
        if condition["pointer"] == "/bankingContext/subjectRef":
            condition["oneOf"] = [subject]
    if prepare_only:
        # The normal Java SDK caller owns all subsequent Catalog and Journey writes.
        prepared = {"catalog": catalog, "launch": launch_request}
        evidence.save(output, "prepared-scenario.json", prepared)
        print(json.dumps({"preparedScenario": str(output / "prepared-scenario.json"),
                          "contextRef": context, "subjectRef": subject,
                          "catalogOrJourneyWrites": 0}, indent=2))
        return prepared

    catalog_api = AgentCatalogApi(client)
    evidence.save(output, "create-request.json", catalog)
    definition = json.loads(catalog_api.create_agent_definition(
        AgentDefinitionCreateRequest.from_dict(catalog), _request_timeout=60.0).to_json())["definition"]
    evidence.save(output, "created.json", definition)
    publish_request = {"expectedDraftVersion": definition["draftVersion"],
                       "expectedDraftSpecDigest": definition["draftSpecDigest"]}
    evidence.save(output, "publish-request.json", publish_request)
    published = json.loads(catalog_api.publish_agent_definition(
        definition["catalogAgentId"], AgentDefinitionPublishRequest.from_dict(publish_request),
        _request_timeout=60.0).to_json())["revision"]
    evidence.save(output, "published.json", published)
    launch_request.update({"catalogAgentId": definition["catalogAgentId"],
                           "catalogRevisionId": published["catalogRevisionId"],
                           "catalogDefinitionDigest": published["catalogDefinitionDigest"]})
    evidence.save(output, "launch-request.json", launch_request)
    print(json.dumps({"launchRequest": launch_request}, indent=2), flush=True)
    result = json.loads(AgentJourneysApi(client).launch_agent_journey(
        AgentJourneyLaunchRequest.from_dict(launch_request), _request_timeout=60.0).to_json())
    evidence.save(output, "launch.json", result)
    evidence.save(output, "manifest.json", {"apiBase": api, "runId": result["runId"],
                                  "catalogAgentId": definition["catalogAgentId"],
                                  "catalogRevisionId": published["catalogRevisionId"],
                                  "launchRequestId": launch_request["requestId"],
                                  "contextRef": context, "subjectRef": subject})
    print(json.dumps({"catalogAgentId": definition["catalogAgentId"],
                      "catalogRevisionId": published["catalogRevisionId"], "launch": result}, indent=2))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Save requests and evidence; use an empty directory for a new launch")
    parser.add_argument("--prepare-only", action="store_true",
                        help="Seed the bank and resolve SDK tool grants for a Java caller; do not create or launch a customer")
    parser.add_argument("--wait", action="store_true", help="Wait for this run and its terminal evidence export")
    parser.add_argument("--inspect", metavar="RUN_ID", help="Read an existing run; never seed, create, publish or launch")
    parser.add_argument("--timeout", type=float, default=8220, help="Maximum inspection/wait seconds (default: 8220)")
    args = parser.parse_args(argv)
    if args.prepare_only and (args.wait or args.inspect):
        parser.error("--prepare-only cannot be combined with --wait or --inspect")
    if (args.wait or args.inspect or args.prepare_only) and args.output is None:
        parser.error("--wait, --inspect and --prepare-only require --output")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be a positive finite number of seconds")
    api = os.environ.get("ASTRAFORM_API_BASE", "http://localhost:8484").rstrip("/")
    # Preserve the earlier helper's accepted API bases.
    for suffix in ("/api/dashboard", "/api"):
        if api.endswith(suffix):
            api = api[:-len(suffix)]
            break
    with ApiClient(Configuration(host=api, retries=0)) as client:
        if args.prepare_only:
            launch(client, api, args.output, prepare_only=True)
            return 0
        run_id = args.inspect
        if run_id is None:
            accepted = launch(client, api, args.output)
            run_id = accepted["runId"] if args.wait else None
        if run_id is not None:
            try:
                return evidence.inspect_run(client, api, run_id, args.output, args.wait, args.timeout)
            except TimeoutError as error:
                print(f"Wait stopped: {error}. Continue with --inspect {run_id} --output {args.output}.", file=sys.stderr)
                return 3
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ApiException as error:
        print(f"Astraform API/client failure: HTTP {error.status}: {error.reason}. "
              "Keep the saved requests; do not launch a replacement after an uncertain response.", file=sys.stderr)
        raise SystemExit(1) from None
    except (TransportError, URLError, OSError, ValueError) as error:
        print(f"Client failure: {error}. Keep the saved requests and inspect an accepted run instead of relaunching.", file=sys.stderr)
        raise SystemExit(1) from None
