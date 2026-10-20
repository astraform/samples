"""Launch one marketing customer, or inspect the same run through the typed SDK."""

import argparse
import importlib.util
import json
import math
import re
import sys
import os
from pathlib import Path
from uuid import uuid4
from urllib.error import URLError
from urllib3.exceptions import HTTPError as TransportError

from astraform_platform_client import ApiClient, Configuration
from astraform_platform_client.exceptions import ApiException
from astraform_platform_client.api.agent_catalog_api import AgentCatalogApi
from astraform_platform_client.api.agent_journeys_api import AgentJourneysApi
from astraform_platform_client.models.agent_definition_create_request import AgentDefinitionCreateRequest
from astraform_platform_client.models.agent_definition_publish_request import AgentDefinitionPublishRequest
from astraform_platform_client.models.agent_journey_launch_request import AgentJourneyLaunchRequest
from astraform_platform_client.models.agent_journey_goal_completion_policy import AgentJourneyGoalCompletionPolicy


# One shared evidence owner, shipped beside either standalone launcher.
_evidence_path = Path(__file__).resolve().with_name("customer-run-evidence.py")
if not _evidence_path.is_file():
    _evidence_path = Path(__file__).resolve().parent.parent / "customer-run-evidence.py"
_evidence_spec = importlib.util.spec_from_file_location("astraform_customer_run_evidence", _evidence_path)
evidence = importlib.util.module_from_spec(_evidence_spec)
_evidence_spec.loader.exec_module(evidence)


def launch(client, api, output, case="advice"):
    directory = Path(__file__).resolve().parent
    catalog_api = AgentCatalogApi(client)
    if case not in ("advice", "proposal-review"):
        raise ValueError("Marketing case must be advice or proposal-review")
    scenario_name = "sample-configuration.json" if case == "advice" else "proposal-review-configuration.json"
    sample_path = Path(os.environ.get("MARKETING_SAMPLE_FILE", directory / scenario_name))
    sample = json.loads(sample_path.read_text())
    template = sample["launchRequestTemplate"]
    if "domainId" in template:
        domain_id = template["domainId"]
        if type(domain_id) is not str or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", domain_id) is None:
            raise ValueError("launch.domainId must be a lowercase domain identifier of 1 to 128 characters")
        # Probe the real generated model before output, Catalog or Journey writes.
        probe = AgentJourneyLaunchRequest.from_dict({
            "requestId": str(uuid4()), "catalogAgentId": str(uuid4()),
            "catalogRevisionId": str(uuid4()), "catalogDefinitionDigest": "sha256:" + "0" * 64,
            "instruction": "Validate declared domain identity", "domainId": domain_id})
        if client.sanitize_for_serialization(probe).get("domainId") != domain_id:
            raise ValueError(
                "The selected Python SDK cannot preserve launch.domainId. "
                "Install astraform-sdk==0.3.5 or a newer compatible release, "
                "or deliberately omit domainId in a custom scenario; this launcher "
                "refuses to remove or manually reinsert the configured value.")
    policy = sample["launchRequestTemplate"].get("completionPolicy", {})
    if "requireCustomerFinish" in policy and type(policy["requireCustomerFinish"]) is not bool:
        raise ValueError("completionPolicy.requireCustomerFinish must be a Boolean")
    if "requestFactBindingMode" in policy:
        binding_mode = policy["requestFactBindingMode"]
        if type(binding_mode) is not str or binding_mode not in ("VALIDATE", "RESOLVE_TRUSTED"):
            raise ValueError("completionPolicy.requestFactBindingMode must be VALIDATE or RESOLVE_TRUSTED")
    if "requireCustomerFinish" in policy or "requestFactBindingMode" in policy:
        serialized = client.sanitize_for_serialization(AgentJourneyGoalCompletionPolicy.from_dict(policy))
        if "requireCustomerFinish" in policy and serialized.get("requireCustomerFinish") is not policy["requireCustomerFinish"]:
            raise ValueError(
                "The selected Python SDK cannot preserve completionPolicy.requireCustomerFinish. "
                "Install astraform-sdk==0.3.5 or a newer compatible release; "
                "this launcher refuses to remove or change the configured value.")
        if "requestFactBindingMode" in policy and serialized.get("requestFactBindingMode") != policy["requestFactBindingMode"]:
            raise ValueError(
                "The selected Python SDK cannot preserve completionPolicy.requestFactBindingMode. "
                "Install astraform-sdk==0.3.5 or a newer compatible release; "
                "this launcher refuses to remove or change the configured mode.")
    catalog = sample["catalogDraftRequest"]
    catalog["requestId"] = str(uuid4())
    catalog["agentKey"] = "marketing-customer-" + uuid4().hex[:12]
    catalog["draft"]["execution"]["modelBindingKey"] = os.environ.get(
        "CUSTOMER_MODEL_BINDING_KEY", catalog["draft"]["execution"]["modelBindingKey"])
    sample["launchRequestTemplate"]["requestId"] = str(uuid4())
    if output is not None:
        output.mkdir(parents=True, exist_ok=True, mode=0o700)
        if any(output.iterdir()):
            raise ValueError("Launch output directory is not empty; inspect the existing run or choose a new directory")
        # Save operation identities before the first mutation; an uncertain response
        # must never cause an automatic replacement Catalog definition or journey.
        with (output / "session.json").open("x", encoding="utf-8") as record:
            json.dump({"apiBase": api, "scenario": sample}, record, indent=2)
            record.write("\n")
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
    launch = sample["launchRequestTemplate"]
    launch.update({"catalogAgentId": definition["catalogAgentId"],
                   "catalogRevisionId": published["catalogRevisionId"],
                   "catalogDefinitionDigest": published["catalogDefinitionDigest"]})
    launch["domain"]["bindingKey"] = os.environ.get("MARKETING_DOMAIN_BINDING_KEY", launch["domain"]["bindingKey"])
    launch["partnerTargetAgentKey"] = os.environ.get("CREATIVE_AGENCY_TARGET_KEY", launch["partnerTargetAgentKey"])
    evidence.save(output, "launch-request.json", launch)
    result = json.loads(AgentJourneysApi(client).launch_agent_journey(
        AgentJourneyLaunchRequest.from_dict(launch), _request_timeout=60.0).to_json())
    evidence.save(output, "launch.json", result)
    evidence.save(output, "manifest.json", {"apiBase": api, "runId": result["runId"],
        "catalogAgentId": definition["catalogAgentId"], "catalogRevisionId": published["catalogRevisionId"],
        "launchRequestId": launch["requestId"]})
    print(json.dumps({"catalogAgentId": definition["catalogAgentId"],
                      "catalogRevisionId": published["catalogRevisionId"],
                      "launchRequest": launch, "launch": result}, indent=2))

    return result

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", choices=("advice", "proposal-review"), default="advice",
                        help="Authored customer goal (default: advice); MARKETING_SAMPLE_FILE overrides the template")
    parser.add_argument("--output", type=Path, help="Save requests and evidence; a new launch requires an empty directory")
    parser.add_argument("--wait", action="store_true", help="Wait for this run and its terminal evidence export")
    parser.add_argument("--inspect", metavar="RUN_ID", help="Read an existing run; never create, publish or launch")
    parser.add_argument("--timeout", type=float, default=1500, help="Maximum inspection/wait seconds (default: 1500)")
    arguments = parser.parse_args(argv)
    if (arguments.wait or arguments.inspect) and arguments.output is None:
        parser.error("--wait and --inspect require --output")
    if not math.isfinite(arguments.timeout) or arguments.timeout <= 0:
        parser.error("--timeout must be a positive finite number of seconds")
    api = os.environ.get("ASTRAFORM_API_BASE", "http://localhost:8384").rstrip("/")
    for suffix in ("/api/dashboard", "/api"):
        if api.endswith(suffix):
            api = api[:-len(suffix)]
            break
    with ApiClient(Configuration(host=api, retries=0)) as client:
        run_id = arguments.inspect
        if run_id is None:
            accepted = launch(client, api, arguments.output, case=arguments.case)
            run_id = accepted["runId"] if arguments.wait else None
        if run_id is not None:
            try:
                return evidence.inspect_run(client, api, run_id, arguments.output,
                                            arguments.wait, arguments.timeout)
            except TimeoutError as error:
                print(f"Wait stopped: {error}. Continue with --inspect {run_id} --output {arguments.output}.", file=sys.stderr)
                return 3
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ApiException as error:
        print(f"Astraform API/client failure: HTTP {error.status}: {error.reason}. "
              "Keep saved requests and inspect an accepted run; do not launch a replacement.", file=sys.stderr)
        raise SystemExit(1) from None
    except (TransportError, URLError, OSError, ValueError) as error:
        print(f"Client failure: {error}. Keep saved requests and inspect the same accepted run.", file=sys.stderr)
        raise SystemExit(1) from None
