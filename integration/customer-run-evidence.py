"""Save and inspect existing sample runs; this module never launches a customer."""

import json
import sys
import time
from urllib3.exceptions import HTTPError as TransportError
from astraform_platform_client.exceptions import ApiException
from astraform_platform_client.api.simulation_runs_api import SimulationRunsApi


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
