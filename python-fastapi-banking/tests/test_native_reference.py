"""Native lifecycle checks use the sample's separate PostgreSQL backend."""
from copy import deepcopy
from datetime import UTC, datetime
import json
import os
from pathlib import Path
import time
import uuid

import pytest
from fastapi.testclient import TestClient
from banking_remote_domain_python import service
from banking_remote_domain_python.main import app


def epoch(value):
    return int(datetime.fromisoformat(value).replace(tzinfo=UTC).timestamp() * 1000)


@pytest.fixture
def bank(monkeypatch):
    dsn = os.getenv("BANKING_TEST_DATABASE_URL")
    if not dsn:
        pytest.skip("Set BANKING_TEST_DATABASE_URL to the separate sample PostgreSQL database")
    service.close_account_repository()
    monkeypatch.setenv("BANKING_BACKEND_ENABLED", "true")
    monkeypatch.setenv("BANKING_BACKEND_DATABASE_URL", dsn)
    monkeypatch.setenv("ASTRAFORM_NATIVE_SIMULATION_ENABLED", "true")
    monkeypatch.setenv("ASTRAFORM_NATIVE_SIMULATION_PROFILE", service.REFERENCE_PROFILE)
    context = "native-" + uuid.uuid4().hex
    scenario = json.loads((Path(__file__).parents[2] / "banking-advice-agent/three-month-configuration.json").read_text())
    seed = scenario["bankCustomerSeed"]
    service.get_account_repository().seed(context, "customer-a", seed)
    with TestClient(app) as client:
        yield client, context, seed["logicalTimeEpochMs"]
    service.close_account_repository()


def request(operation, context, start):
    now = time.time_ns() // 1_000_000
    identity = uuid.uuid4().hex
    return {"protocolVersion": "remote-domain.v1", "requestId": identity,
            "operation": operation, "idempotencyKey": identity,
            "sentAtEpochMs": now, "deadlineEpochMs": now + 60000,
            "host": {"service": "agent-runtime", "instanceId": "native-test", "platformVersion": "0.1.1"},
            "domain": {"domainId": "banking-python"},
            "agent": {"agentId": "customer-a", "personaName": "Avery", "interactionMode": "SIMULATION"},
            "payload": {"personaConfiguration": {}, "initialProjectionHints": {"nativeSimulation": {
                "profile": service.REFERENCE_PROFILE, "contextId": context, "expectedStateVersion": 0,
                "logicalTimeEpochMs": start, "logicalHorizonEpochMs": epoch("2026-04-02"),
                "customerContext": {"contextRef": context, "subjectRef": "customer-a"}}}}}


def window(prepared, context, through):
    metadata = prepared["nextState"]["data"]["nativeSimulation"]
    result = request("domain-system-work-window", context, metadata["processedThroughEpochMs"])
    result["state"] = prepared["nextState"]
    result["payload"] = {"timeWindow": {"previousTimeEpochMs": metadata["processedThroughEpochMs"], "currentTimeEpochMs": through},
        "executionMode": "EVALUATE_AND_EXECUTE", "workKinds": [service.NATIVE_STATEMENT_KIND], "maxWorkItems": 1,
        "context": {"nativeSimulation": {"profile": service.REFERENCE_PROFILE, "contextId": context,
            "expectedStateVersion": metadata["stateVersion"], "resourceVersion": metadata["resourceVersion"],
            "logicalHorizonEpochMs": metadata["logicalHorizonEpochMs"], "customerContext": metadata["customerContext"]}}}
    return result


def post(client, payload):
    response = client.post("/api/remote-domain/v1/" + payload["operation"], json=payload)
    assert response.status_code == 200, response.text
    return response.json()


def test_monthly_windows_use_bank_records_and_survive_restart(bank):
    client, context, start = bank
    manifest_response = client.get("/api/remote-domain/v1/manifest")
    declared = manifest_response.json()
    assert manifest_response.headers["X-Astraform-Provider-Revision"] == declared["capabilities"]["nativeSimulation"]["providerRevision"]
    assert declared["capabilities"]["nativeSimulation"]["initialBoundaryMode"] == "ATTACH_PREPOPULATED_STATE"
    prepare = request("prepare", context, start)
    prepared = post(client, prepare)
    assert set(prepared["nextState"]["data"]) == {"nativeSimulation"}
    observation = prepared["result"]["projection"]["runtimeMetadata"]["nativeSimulation"]["observation"]
    assert observation["observedAtUtc"] == "2026-01-15T00:00:00Z"
    assert observation["nextStatementAtUtc"] == "2026-02-01T00:00:00Z"
    previous = prepared
    for boundary, period in [("2026-02-01", "2026-01"), ("2026-03-01", "2026-02"), ("2026-04-01", "2026-03")]:
        intent = window(previous, context, epoch(boundary))
        previous = post(client, intent)
        metadata = previous["nextState"]["data"]["nativeSimulation"]
        observation = previous["result"]["projection"]["runtimeMetadata"]["nativeSimulation"]["observation"]
        assert observation["observedAtUtc"] == boundary + "T00:00:00Z"
        guard = {"contextId": context, "resourceVersion": metadata["resourceVersion"], "logicalTimeEpochMs": epoch(boundary)}
        result = service.get_account_repository().read(context, "customer-a", guard, period)
        assert len(result["statements"]) == 1
        assert result["statements"][0]["minimumAmountDue"] == "48"
        assert previous["result"]["domainSystemWork"]["decisionTriggers"]
    service.close_account_repository()
    assert post(client, intent) == previous
    assert post(client, prepare) == prepared
    assert len(service.get_account_repository().read(context, "customer-a", guard)["statements"]) == 3
    final = post(client, window(previous, context, epoch("2026-04-02")))
    assert final["result"]["domainSystemWork"]["executedWorkCount"] == 0
    assert "nextStatementAtUtc" not in final["result"]["projection"]["runtimeMetadata"]["nativeSimulation"]["observation"]
    assert final["nextState"]["data"]["nativeSimulation"]["processedThroughEpochMs"] == epoch("2026-04-02")


@pytest.mark.parametrize("invalid", ["skip-boundary", "stale-version", "wrong-owner", "checkpoint-data", "expired"])
def test_rejected_native_window_does_not_change_bank(bank, invalid):
    client, context, start = bank
    prepared = post(client, request("prepare", context, start))
    intent = window(prepared, context, epoch("2026-02-01"))
    if invalid == "skip-boundary":
        intent["payload"]["timeWindow"]["currentTimeEpochMs"] = epoch("2026-03-01")
    elif invalid == "stale-version":
        intent["payload"]["context"]["nativeSimulation"]["resourceVersion"] = 2
    elif invalid == "wrong-owner":
        intent["agent"]["agentId"] = "another-customer"
    elif invalid == "checkpoint-data":
        intent["state"] = deepcopy(intent["state"])
        intent["state"]["data"]["accounts"] = [{"balance": "100"}]
    else:
        intent["sentAtEpochMs"] = 1
        intent["deadlineEpochMs"] = 2
    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=intent)
    assert response.status_code in {409, 422}, response.text
    view = service.get_account_repository().read(context, "customer-a", {"contextId": context, "resourceVersion": 1, "logicalTimeEpochMs": start})
    assert view["statements"] == []
