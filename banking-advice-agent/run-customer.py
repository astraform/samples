"""Launch one banking customer, or inspect the same run through the published SDK."""

import argparse
import json
import math
import sys
import time
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
from astraform_platform_client.api.simulation_runs_api import SimulationRunsApi


def bank_request(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"} if body is not None else {}
    try:
        with urlopen(Request(url, data=data, headers=headers, method=method), timeout=60) as response:
            return json.load(response)
    except HTTPError as error:
        raise SystemExit(f"{method} {url}: HTTP {error.code}: {error.read().decode()}") from None


def save(directory, filename, value):
    if directory is None:
        return
    path = directory / filename
    temporary = path.with_name("." + path.name + ".tmp")
    try:
        temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def read_api(operation, deadline, output, label):
    """Retry only read-only requests, never creation, publication or launch."""
    for attempt in range(3):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Inspection deadline reached; the simulation was not cancelled")
        try:
            return operation(_request_timeout=float(min(30, remaining)))
        except (ApiException, TransportError, OSError) as error:
            retryable = not isinstance(error, ApiException) or error.status in {0, 408, 429, 500, 502, 503, 504}
            if not retryable or attempt == 2:
                raise
            record = {"operation": label, "attempt": attempt + 1,
                      "errorType": type(error).__name__, "httpStatus": getattr(error, "status", None)}
            with (output / "read-errors.jsonl").open("a", encoding="utf-8") as log:
                log.write(json.dumps(record) + "\n")
            print(f"Temporary {label} error; retrying the same run's read request.", file=sys.stderr)
            time.sleep(min(2 ** attempt, max(0, deadline - time.monotonic())))


def inspect_run(client, api, run_id, output, wait, timeout):
    output.mkdir(parents=True, exist_ok=True)
    for filename in ("session.json", "manifest.json"):
        path = output / filename
        if path.exists():
            previous = json.loads(path.read_text(encoding="utf-8"))
            if previous["apiBase"] != api or previous.get("runId", run_id) != run_id:
                raise ValueError("Output directory belongs to another API deployment or run")
    saved_launch = output / "launch.json"
    if saved_launch.exists() and json.loads(saved_launch.read_text(encoding="utf-8"))["runId"] != run_id:
        raise ValueError("Output directory belongs to another run")
    if not (output / "manifest.json").exists():
        save(output, "manifest.json", {"apiBase": api, "runId": run_id})
    runs = SimulationRunsApi(client)
    deadline = time.monotonic() + timeout
    while True:
        run = json.loads(read_api(lambda **kwargs: runs.get_simulation_run(run_id, **kwargs),
                                 deadline, output, "status").to_json())
        save(output, "run.json", run)
        terminal = run["status"] in {"COMPLETED", "FAILED", "CANCELLED"}
        if terminal or not wait:
            ledger = read_api(lambda **kwargs: runs.inspect_simulation_run_ledger(run_id, limit=100, **kwargs),
                              deadline, output, "ledger")
            save(output, "ledger.json", json.loads(ledger.to_json()))
            if terminal:
                progress = run.get("metadata", {}).get("nativeProgress", {})
                print(json.dumps({"runId": run_id, "status": run["status"],
                                  "goal": progress.get("goal"), "stopReason": progress.get("stopReason")}, indent=2))
                try:
                    ready = read_api(lambda **kwargs: runs.get_simulation_run_evidence_export_readiness_with_http_info(
                        run_id, **kwargs), deadline, output, "export readiness").status_code == 204
                except ApiException as error:
                    if error.status != 409:
                        raise
                    ready = False
                if ready:
                    evidence = read_api(lambda **kwargs: runs.export_simulation_run_evidence(run_id, **kwargs),
                                        deadline, output, "export")
                    temporary = output / ".evidence.zip.tmp"
                    temporary.write_bytes(evidence)
                    temporary.replace(output / "evidence.zip")
                    print("Saved evidence.zip and ledger.json (up to 100 entries per ledger category). "
                          "The complete export preserves the actual outcome, including failed goals.")
                    return 0
                print("Run is terminal; evidence export is not ready yet. Inspect this same run again.")
            else:
                print(f"Run {run_id} is {run['status']}. Inspect this same run again; do not relaunch.")
            if not wait:
                return 0
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Inspection deadline reached; the simulation was not cancelled")
        time.sleep(min(3, remaining))


def launch(client, api, output):
    directory = Path(__file__).resolve().parent
    catalog_api = AgentCatalogApi(client)
    bank = os.environ.get("BANKING_BACKEND_URL", "http://localhost:8098").rstrip("/")
    binding = os.environ.get("BANKING_CUSTOMER_BINDING_KEY", "banking")
    domain_binding = os.environ.get("BANKING_DOMAIN_BINDING_KEY", "banking-reference")
    context = os.environ.get("BANKING_CONTEXT_REF", "bank-statement-" + uuid4().hex)
    subject = os.environ.get("BANKING_SUBJECT_REF", "customer-a")
    sample_path = Path(os.environ.get("BANKING_SAMPLE_FILE", directory / "sample-configuration.json"))
    sample = json.loads(sample_path.read_text())
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
        save(output, "bank-seed-request.json", {"contextRef": context, "subjectRef": subject, "body": seed})
    bank_request("PUT", bank + "/api/banking/v1/contexts/" + quote(context, safe="")
            + "/customers/" + quote(subject, safe=""), seed)

    capabilities = json.loads(SimulationEventsApi(client).inspect_public_customer_mcp_capabilities(
        binding, _request_timeout=60.0).to_json())
    descriptor = next(tool for tool in capabilities["tools"] if tool["name"] == "get_customer_accounts")
    grant = {"kind": "MCP", "toolName": descriptor["name"], "maxCalls": max_calls,
             **{key: capabilities[key] for key in ("bindingKey", "bindingRevision", "bindingDigest")},
             **{key: descriptor[key] for key in ("descriptorDigest", "inputSchemaDigest", "outputSchemaDigest",
                                               "effectClass", "scope")}}
    catalog["draft"]["tools"] = [grant]
    save(output, "create-request.json", catalog)
    definition = json.loads(catalog_api.create_agent_definition(
        AgentDefinitionCreateRequest.from_dict(catalog), _request_timeout=60.0).to_json())["definition"]
    save(output, "created.json", definition)
    publish_request = {"expectedDraftVersion": definition["draftVersion"],
                       "expectedDraftSpecDigest": definition["draftSpecDigest"]}
    save(output, "publish-request.json", publish_request)
    published = json.loads(catalog_api.publish_agent_definition(
        definition["catalogAgentId"], AgentDefinitionPublishRequest.from_dict(publish_request),
        _request_timeout=60.0).to_json())["revision"]
    save(output, "published.json", published)
    launch = sample["launchRequestTemplate"]
    launch.update({"catalogAgentId": definition["catalogAgentId"],
                   "catalogRevisionId": published["catalogRevisionId"],
                   "catalogDefinitionDigest": published["catalogDefinitionDigest"],
                   "toolContexts": [{"bindingKey": binding, "contextRef": context, "subjectRef": subject}]})
    partner_target = os.environ.get("BANKING_PARTNER_TARGET_AGENT_KEY")
    if partner_target:
        launch["partnerTargetAgentKey"] = partner_target
    if "domain" in launch:
        launch["domain"].update({"bindingKey": domain_binding, "toolContextBindingKey": binding})
    for condition in launch["completionPolicy"]["partnerResponseConditions"]:
        if condition["pointer"] == "/bankingContext/subjectRef":
            condition["oneOf"] = [subject]
    save(output, "launch-request.json", launch)
    print(json.dumps({"launchRequest": launch}, indent=2), flush=True)
    result = json.loads(AgentJourneysApi(client).launch_agent_journey(
        AgentJourneyLaunchRequest.from_dict(launch), _request_timeout=60.0).to_json())
    save(output, "launch.json", result)
    save(output, "manifest.json", {"apiBase": api, "runId": result["runId"],
                                  "catalogAgentId": definition["catalogAgentId"],
                                  "catalogRevisionId": published["catalogRevisionId"],
                                  "launchRequestId": launch["requestId"],
                                  "contextRef": context, "subjectRef": subject})
    print(json.dumps({"catalogAgentId": definition["catalogAgentId"],
                      "catalogRevisionId": published["catalogRevisionId"], "launch": result}, indent=2))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Save requests and evidence; use an empty directory for a new launch")
    parser.add_argument("--wait", action="store_true", help="Wait for this run and its terminal evidence export")
    parser.add_argument("--inspect", metavar="RUN_ID", help="Read an existing run; never seed, create, publish or launch")
    parser.add_argument("--timeout", type=float, default=900, help="Maximum inspection/wait seconds (default: 900)")
    args = parser.parse_args(argv)
    if (args.wait or args.inspect) and args.output is None:
        parser.error("--wait and --inspect require --output")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout must be a positive finite number of seconds")
    api = os.environ.get("ASTRAFORM_API_BASE", "http://localhost:8484").rstrip("/")
    # Preserve the earlier helper's accepted API bases.
    for suffix in ("/api/dashboard", "/api"):
        if api.endswith(suffix):
            api = api[:-len(suffix)]
            break
    with ApiClient(Configuration(host=api, retries=0)) as client:
        run_id = args.inspect
        if run_id is None:
            accepted = launch(client, api, args.output)
            run_id = accepted["runId"] if args.wait else None
        if run_id is not None:
            try:
                return inspect_run(client, api, run_id, args.output, args.wait, args.timeout)
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
