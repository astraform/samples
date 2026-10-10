from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import subprocess
import sys
import threading
import time
import zipfile
from copy import deepcopy
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient

import banking_remote_domain_python.main as main_module
from banking_remote_domain_python import service
from banking_remote_domain_python.main import app


REMOTE_JSON = "application/vnd.astraform.remote-domain.v1+json"
DOMAIN_ID = "banking-python"
STATE_SCHEMA = "banking-python.state.v1"
PACK_ID = "banking-python-policy-pack"


client = TestClient(app, headers={
    "X-Astraform-Expected-Provider-Revision": service.WIND_TUNNEL_PROVIDER_REVISION,
})


def platform_v2_launch_payload(
    run_id: str,
    *,
    candidate_rate: float = 0.08,
) -> dict[str, Any]:
    settings = {
        "schemaVersion": "scenario_lab_pinned_cohort_activation.v1",
        "agentDecisionMode": "STUB_LLM",
        "agentDecisionCadence": "EVENT_ONLY",
    }
    settings_bytes = json.dumps(settings, separators=(",", ":")).encode("utf-8")
    settings_digest = hashlib.sha256(settings_bytes).hexdigest()
    return {
        "presetId": "saver-repricing",
        "simulationRunId": run_id,
        "executionMode": "CLOCK_DRIVEN_HYBRID",
        "runPath": "CLOCK_DRIVEN_SIMULATION",
        "agentDecisionMode": "STUB_LLM",
        "agentDecisionCadence": "EVENT_ONLY",
        service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        "simulationHorizonDays": 1,
        "prePolicyDays": 0,
        "postPolicyDays": 1,
        "candidateRate": candidate_rate,
        "activationSettingsDigest": settings_digest,
        "activationSettingsSnapshot": {
            "schemaVersion": "scenario_lab_pinned_activation_settings_snapshot.v1",
            "contentType": "application/json",
            "encoding": "base64url",
            "sha256": settings_digest,
            "content": base64.urlsafe_b64encode(settings_bytes).decode("ascii").rstrip("="),
        },
    }


@pytest.mark.parametrize(
    "legacy_mode",
    [
        service.FINALIZATION_MODE_LEGACY_V1,
        service.FINALIZATION_MODE_PLATFORM_OWNED_V1,
    ],
)
def test_historical_legacy_policy_wind_tunnel_run_remains_readable_unpinned(
    monkeypatch,
    legacy_mode: str,
) -> None:
    unpinned = TestClient(app)
    run_id = "legacy-unpinned-run"
    legacy_status = {
        "runId": run_id,
        "status": "COMPLETED",
        "bundleReady": True,
        "parameters": {
            service.FINALIZATION_MODE_FIELD: legacy_mode,
        },
    }
    monkeypatch.setattr(main_module, "policy_wind_tunnel_run_status", lambda requested: legacy_status)

    status = unpinned.get(f"/policy-wind-tunnel/runs/{run_id}")
    stale = unpinned.get(
        f"/policy-wind-tunnel/runs/{run_id}",
        headers={"X-Astraform-Expected-Provider-Revision": "stale-provider"},
    )

    assert status.status_code == 200
    assert stale.status_code == 412


def test_platform_owned_v2_policy_run_requires_exact_revision(monkeypatch) -> None:
    unpinned = TestClient(app)
    run_id = "platform-v2-pinned-run"
    v2_status = {
        "runId": run_id,
        "status": "RUNNING",
        "bundleReady": False,
        "parameters": {
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        },
    }
    monkeypatch.setattr(
        main_module,
        "start_policy_wind_tunnel_run",
        lambda payload, *, execution_role: v2_status,
    )
    monkeypatch.setattr(main_module, "policy_wind_tunnel_run_status", lambda requested: v2_status)
    payload = platform_v2_launch_payload(run_id)

    missing = unpinned.post("/policy-wind-tunnel/runs", json=payload)
    mismatch = unpinned.post(
        "/policy-wind-tunnel/runs",
        headers={"X-Astraform-Expected-Provider-Revision": "stale-provider"},
        json=payload,
    )
    exact = unpinned.post(
        "/policy-wind-tunnel/runs",
        headers={
            "X-Astraform-Expected-Provider-Revision": service.WIND_TUNNEL_PROVIDER_REVISION,
        },
        json=payload,
    )
    status_missing = unpinned.get(f"/policy-wind-tunnel/runs/{run_id}")
    status_exact = unpinned.get(
        f"/policy-wind-tunnel/runs/{run_id}",
        headers={
            "X-Astraform-Expected-Provider-Revision": service.WIND_TUNNEL_PROVIDER_REVISION,
        },
    )

    assert missing.status_code == 412
    assert mismatch.status_code == 412
    assert exact.status_code == 200
    assert status_missing.status_code == 412
    assert status_exact.status_code == 200
    assert exact.headers["X-Astraform-Provider-Revision"] == service.WIND_TUNNEL_PROVIDER_REVISION
    assert service.WIND_TUNNEL_PROVIDER_REVISION.startswith(("build-sha256:", "oci-sha256:"))
    assert len(service.WIND_TUNNEL_PROVIDER_REVISION.rsplit(":", 1)[1]) == 64


def test_platform_owned_v2_status_lookup_failure_fails_closed(monkeypatch) -> None:
    unpinned = TestClient(app)
    run_id = "platform-v2-transient-status-failure"
    v2_status = {
        "runId": run_id,
        "status": "RUNNING",
        "bundleReady": False,
        "parameters": {
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        },
    }
    calls = 0

    def transient_status_lookup(requested: str) -> dict[str, Any]:
        nonlocal calls
        assert requested == run_id
        calls += 1
        if calls == 1:
            raise OSError("transient provider state lookup failure")
        return v2_status

    monkeypatch.setattr(
        main_module,
        "policy_wind_tunnel_run_status",
        transient_status_lookup,
    )

    response = unpinned.get(f"/policy-wind-tunnel/runs/{run_id}")

    assert response.status_code == 503
    assert calls == 1


def test_unknown_policy_wind_tunnel_run_preserves_not_found() -> None:
    response = TestClient(app).get(
        "/policy-wind-tunnel/runs/python-missing-lifecycle-run"
    )

    assert response.status_code == 404


@pytest.mark.parametrize(
    "content",
    [
        json.dumps(platform_v2_launch_payload("platform-v2-utf16-launch")).encode("utf-16"),
        (
            '{"presetId":"saver-repricing","simulationRunId":"platform-v2-duplicate-launch",'
            '"executionMode":"CLOCK_DRIVEN_HYBRID",'
            f'"{service.FINALIZATION_MODE_FIELD}":"LEGACY_V1",'
            f'"{service.FINALIZATION_MODE_FIELD}":"PLATFORM_OWNED_V2"}}'
        ).encode("utf-8"),
        (
            json.dumps(platform_v2_launch_payload("platform-v2-trailing-launch")).encode("utf-8")
            + b" {}"
        ),
        (
            json.dumps(platform_v2_launch_payload("platform-v2-nan-launch"))
            .replace('"candidateRate": 0.08', '"candidateRate": NaN')
            .encode("utf-8")
        ),
        (
            json.dumps(platform_v2_launch_payload("platform-v2-positive-overflow-launch"))
            .replace('"candidateRate": 0.08', '"candidateRate": 1e309')
            .encode("utf-8")
        ),
        (
            json.dumps(platform_v2_launch_payload("platform-v2-negative-overflow-launch"))
            .replace('"candidateRate": 0.08', '"candidateRate": -1e309')
            .encode("utf-8")
        ),
        b"[]",
    ],
    ids=[
        "utf16",
        "duplicate-key",
        "trailing-json",
        "nan",
        "positive-exponent-overflow",
        "negative-exponent-overflow",
        "non-object",
    ],
)
def test_platform_owned_v2_launch_requires_strict_utf8_json(
    monkeypatch,
    content: bytes,
) -> None:
    provider_invocations: list[dict[str, Any]] = []
    monkeypatch.setattr(
        main_module,
        "start_policy_wind_tunnel_run",
        lambda payload, *, execution_role: provider_invocations.append(payload) or {},
    )

    response = TestClient(app).post(
        "/policy-wind-tunnel/runs",
        headers={
            "Content-Type": "application/json",
            "X-Astraform-Expected-Provider-Revision": service.WIND_TUNNEL_PROVIDER_REVISION,
        },
        content=content,
    )

    assert response.status_code == 400
    assert provider_invocations == []


@pytest.mark.parametrize("path", ["/policy-wind-tunnel/pack", "/policy-wind-tunnel/presets"])
def test_pinned_policy_metadata_routes_prove_provider_revision(path: str) -> None:
    exact = TestClient(app).get(
        path,
        headers={
            "X-Astraform-Expected-Provider-Revision": service.WIND_TUNNEL_PROVIDER_REVISION
        },
    )
    stale = TestClient(app).get(
        path,
        headers={"X-Astraform-Expected-Provider-Revision": "build-sha256:" + "0" * 64},
    )

    assert exact.status_code == 200
    assert (
        exact.headers["X-Astraform-Provider-Revision"]
        == service.WIND_TUNNEL_PROVIDER_REVISION
    )
    assert stale.status_code == 412
    assert (
        stale.headers["X-Astraform-Provider-Revision"]
        == service.WIND_TUNNEL_PROVIDER_REVISION
    )


def test_local_provider_revision_covers_catalog_metadata_and_dependency_resolution(
    tmp_path,
) -> None:
    package_root = tmp_path / "src" / "banking_remote_domain_python"
    catalog = package_root / "population-catalogs" / "banking.population-catalog.json"
    catalog.parent.mkdir(parents=True)
    (package_root / "service.py").write_text("VALUE = 1\n", encoding="utf-8")
    catalog.write_text('{"version":1}\n', encoding="utf-8")
    (tmp_path / "pyproject.toml").write_text(
        '[project]\nname = "banking-remote-domain-python"\n',
        encoding="utf-8",
    )

    initial = service.local_provider_build_revision(
        package_root,
        tmp_path,
        {"fastapi": "0.115.0", "uvicorn": "0.32.0"},
    )
    catalog.write_text('{"version":2}\n', encoding="utf-8")
    catalog_changed = service.local_provider_build_revision(
        package_root,
        tmp_path,
        {"fastapi": "0.115.0", "uvicorn": "0.32.0"},
    )
    dependencies_changed = service.local_provider_build_revision(
        package_root,
        tmp_path,
        {"fastapi": "0.116.0", "uvicorn": "0.32.0"},
    )

    assert initial != catalog_changed
    assert catalog_changed != dependencies_changed


def test_provider_revision_can_require_independently_supplied_oci_digest(
    monkeypatch,
) -> None:
    monkeypatch.setenv(service.REQUIRE_OCI_PROVIDER_DIGEST_ENV, "true")
    monkeypatch.setenv(
        service.PROVIDER_ARTIFACT_DIGEST_ENV,
        "build-sha256:" + "a" * 64,
    )
    with pytest.raises(RuntimeError, match="independently supplied"):
        service.resolve_wind_tunnel_provider_revision()

    expected = "oci-sha256:" + "b" * 64
    monkeypatch.setenv(service.PROVIDER_ARTIFACT_DIGEST_ENV, expected)
    assert service.resolve_wind_tunnel_provider_revision() == expected


def test_provider_revision_reads_packaged_artifact_digest_file(
    tmp_path,
    monkeypatch,
) -> None:
    digest_file = tmp_path / "provider-artifact-digest"
    expected = "build-sha256:" + "c" * 64
    digest_file.write_text(expected + "\n", encoding="utf-8")
    monkeypatch.delenv(service.PROVIDER_ARTIFACT_DIGEST_ENV, raising=False)
    monkeypatch.setenv(service.PROVIDER_ARTIFACT_DIGEST_FILE_ENV, str(digest_file))
    monkeypatch.setenv(service.REQUIRE_OCI_PROVIDER_DIGEST_ENV, "false")

    assert service.resolve_wind_tunnel_provider_revision() == expected


def test_manifest_exposes_python_banking_domain() -> None:
    response = client.get("/api/remote-domain/v1/manifest")

    assert response.status_code == 200
    body = response.json()
    assert body["protocolVersion"] == "remote-domain.v1"
    assert body["domain"]["domainId"] == DOMAIN_ID
    assert body["domain"]["schemaVersion"] == STATE_SCHEMA
    assert body["transport"]["contentType"] == REMOTE_JSON
    assert body["capabilities"]["supportsScheduledWork"] is True
    assert body["capabilities"]["supportsInitialAccounts"] is True
    work_kinds = {entry["kind"] for entry in body["capabilities"]["scheduledWorkKinds"]}
    assert "transaction" in work_kinds
    assert "post_double_entry_transaction" in work_kinds
    assert "credit_card_interest_accrual" not in work_kinds
    assert "credit_card_statement_close" not in work_kinds
    assert body["capabilities"]["supportsDomainSystemWorkWindow"] is True
    domain_work_kinds = {entry["kind"] for entry in body["capabilities"]["domainSystemWorkKinds"]}
    assert "monthly_interest_accrual" in domain_work_kinds
    assert "loan_interest_accrual" in domain_work_kinds
    assert "credit_card_interest_accrual" in domain_work_kinds
    assert "credit_card_statement_close" in domain_work_kinds
    assert "credit_card_rate_change" in domain_work_kinds
    assert body["capabilities"]["supportsControlActions"] is True
    assert body["operations"]["controlAction"]["path"] == "/api/remote-domain/v1/control-actions:execute"
    assert body["operations"]["executeWork"]["timeoutMs"] >= 30_000
    assert body["operations"]["domainSystemWorkWindow"]["timeoutMs"] >= 30_000
    assert [action["actionId"] for action in body["capabilities"]["controlActions"]] == [
        "broadcast-message",
        "schedule-rate-change-plan",
    ]
    assert body["uiProfile"]["schemaVersion"] == "domain-ui-profile.v1"
    product_options = body["uiProfile"]["creatorSections"][0]["fields"][0]["options"]
    assert {option["value"] for option in product_options}.issuperset({"TS001", "SAV001", "CC001"})
    tool_names = {entry["name"] for entry in body["capabilities"]["tools"]}
    assert {"get_product_list", "post_double_entry_transaction"}.issubset(tool_names)
    assert all("effectType" not in entry for entry in body["capabilities"]["tools"])
    assert body["capabilities"]["remoteProviderLanguage"] == "python"


def test_population_catalog_exposes_provider_owned_recipes() -> None:
    response = client.get("/api/remote-domain/v1/population/catalog")

    assert response.status_code == 200
    body = response.json()
    assert body["schemaVersion"] == "domain_population_catalog.v1"
    assert body["domainId"] == DOMAIN_ID
    assert body["archetypeCatalog"]["domainId"] == DOMAIN_ID
    assert body["populationRecipes"]["domainId"] == DOMAIN_ID
    assert body["populationRecipes"]["recipes"][0]["recipeId"] == "banking-python-policy-100"
    recipe = body["populationRecipes"]["recipes"][0]
    assert recipe["segmentMix"][0]["archetypeIds"][0] == "rate-sensitive-saver"
    assert recipe["memberValidation"]["requiredDomainConfigFields"][0] == "productCode"
    assert recipe["memberValidation"]["initialAccountsRequired"] is True
    assert recipe["memberValidation"]["scheduledWorkRequired"] is False
    for segment in recipe["segmentMix"]:
        assert len(segment["memberTemplate"]["initialAccounts"]) == 1
        assert segment["memberTemplate"]["scheduledWork"] == []
    segments = {segment["segmentId"]: segment for segment in recipe["segmentMix"]}
    expected_accounts = {
        "deposit-savers": ("TS001", 2500),
        "credit-cardholders": ("CC001", -1200),
        "hardship-risk": ("TS001", 250),
        "fraud-friction": ("CC001", -600),
    }
    for segment_id, (product_code, balance) in expected_accounts.items():
        account = segments[segment_id]["memberTemplate"]["initialAccounts"][0]
        assert account["productCode"] == product_code
        assert account["balance"] == balance
    assert body["runPathEligibility"]["status"] == "SCENARIO_READY"
    assert body["runPathEligibility"]["windTunnelReady"] is True
    assert body["runPathEligibility"]["windTunnelConsumesPopulation"] is True


def test_real_llm_canary_agent_normalization_uses_budgeted_provider() -> None:
    agent = service.normalize_generated_agent(
        {
            "personaName": "Budgeted Real LLM Customer",
            "traits": ["synthetic-customer"],
            "goals": [],
            "initialAccounts": [],
        },
        "REAL_LLM",
        {
            "agentDecisionCadence": "DAILY",
            "realLlmBudget": {
                "provider": "openai",
                "model": "gemini-2.5-flash-lite",
                "maxCalls": 70,
                "maxUsd": 0.50,
            },
        },
    )

    assert agent["provider"] == "openai"
    assert agent["model"] == "gemini-2.5-flash-lite"
    assert agent["interactionMode"] == "HYBRID"
    assert agent["autonomousDecisionEnabled"] is True
    assert agent["autonomousDecisionCadence"] == "DAILY"
    assert agent["autonomousDecisionIntervalMinutes"] == 24 * 60
    assert agent["realLlmMaxDecisionCalls"] == 70
    assert "agent-decision-mode:real-llm-canary" in agent["traits"]


def test_event_only_agent_normalization_disables_calendar_decision_slots() -> None:
    agent = service.normalize_generated_agent(
        {
            "personaName": "Event Driven Customer",
            "traits": ["synthetic-customer"],
            "goals": [],
            "initialAccounts": [],
        },
        "STUB_LLM",
        {"agentDecisionCadence": "EVENT_ONLY"},
    )

    assert agent["autonomousDecisionEnabled"] is True
    assert agent["autonomousDecisionCadence"] == "EVENT_ONLY"
    assert agent["autonomousDecisionIntervalMinutes"] == 0


def test_deterministic_event_only_cadence_survives_run_normalization() -> None:
    normalized = service.normalized_wind_tunnel_parameters(
        {
            "agentDecisionMode": "DETERMINISTIC",
            "agentDecisionCadence": "EVENT_ONLY",
            "activationId": "platform-activation-opaque-123",
        },
        "saver-repricing",
    )

    assert normalized["agentDecisionMode"] == "DETERMINISTIC"
    assert normalized["agentDecisionCadence"] == "EVENT_ONLY"
    assert normalized["agentDecisionIntervalMinutes"] == 0
    assert normalized["activationId"] == "platform-activation-opaque-123"


def test_run_launch_preserves_pinned_finalization_mode_and_gates_event_only(
    monkeypatch,
) -> None:
    captured: list[dict[str, Any]] = []
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_run",
        lambda _preset, parameters, execution_role=None, launch_digest=None: captured.append(deepcopy(parameters))
        or parameters,
    )
    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: False)

    with pytest.raises(service.ProtocolError) as unavailable:
        service.start_policy_wind_tunnel_run(
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "agentDecisionMode": "STUB_LLM",
                "agentDecisionCadence": "DAILY",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            }
        )
    assert unavailable.value.code == "platform_owned_v2_storage_unavailable"
    assert captured == []

    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: True)

    pinned_v2 = service.start_policy_wind_tunnel_run(
        {
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "DAILY",
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        }
    )

    assert pinned_v2[service.FINALIZATION_MODE_FIELD] == service.FINALIZATION_MODE_PLATFORM_OWNED_V2
    assert captured[-1][service.FINALIZATION_MODE_FIELD] == service.FINALIZATION_MODE_PLATFORM_OWNED_V2

    with pytest.raises(service.ProtocolError, match="historical reads and lifecycle cleanup only"):
        service.start_policy_wind_tunnel_run(
            {
                "executionMode": "FAST_BATCH_COMPARE",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V1,
            }
        )

    with pytest.raises(service.ProtocolError, match="V1 modes are recovery-only"):
        service.start_policy_wind_tunnel_run(
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "agentDecisionMode": "STUB_LLM",
                "agentDecisionCadence": "DAILY",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V1,
            }
        )
    with pytest.raises(service.ProtocolError, match="explicitly pinned"):
        service.start_policy_wind_tunnel_run(
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "agentDecisionMode": "STUB_LLM",
                "agentDecisionCadence": "DAILY",
            }
        )
    with pytest.raises(service.ProtocolError, match="must be LEGACY_V1"):
        service.start_policy_wind_tunnel_run(
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "agentDecisionMode": "STUB_LLM",
                "agentDecisionCadence": "DAILY",
                service.FINALIZATION_MODE_FIELD: " PLATFORM_OWNED_V2 ",
            }
        )
    with pytest.raises(service.ProtocolError, match="historical reads and lifecycle cleanup only"):
        service.start_policy_wind_tunnel_run(
            {
                "executionMode": "FAST_BATCH_COMPARE",
                "agentDecisionMode": "STUB_LLM",
                "agentDecisionCadence": "EVENT_ONLY",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            }
        )


@pytest.mark.parametrize("execution_mode", [None, "FAST_BATCH_COMPARE", "UNKNOWN"])
def test_new_provider_launch_rejects_non_clock_modes_without_creating_run_or_artifacts(
    tmp_path,
    monkeypatch,
    execution_mode: str | None,
) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    payload: dict[str, Any] = {"presetId": "saver-repricing"}
    if execution_mode is not None:
        payload["executionMode"] = execution_mode
    existing_run_ids = set(service._POLICY_WIND_TUNNEL_RUNS)

    with pytest.raises(service.ProtocolError) as rejected:
        service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")

    assert rejected.value.status_code == 400
    assert "CLOCK_DRIVEN_HYBRID" in str(rejected.value)
    assert set(service._POLICY_WIND_TUNNEL_RUNS) == existing_run_ids
    assert list(tmp_path.rglob("*")) == []

    response = TestClient(app).post("/policy-wind-tunnel/runs", json=payload)
    assert response.status_code == 400
    assert "CLOCK_DRIVEN_HYBRID" in response.json()["error"]["message"]
    assert set(service._POLICY_WIND_TUNNEL_RUNS) == existing_run_ids
    assert list(tmp_path.rglob("*")) == []


def test_independent_process_launches_produce_one_activation_and_preserve_terminal_state(
    tmp_path,
    monkeypatch,
) -> None:
    gate = tmp_path / "launch.gate"
    activation_log = tmp_path / "activation.log"
    run_id = "python-cross-process-launch"
    first = launch_policy_provider_process(
        tmp_path, tmp_path / "first.ready", gate, activation_log, run_id, "0.08"
    )
    second = launch_policy_provider_process(
        tmp_path, tmp_path / "second.ready", gate, activation_log, run_id, "0.08"
    )
    await_path(tmp_path / "first.ready")
    await_path(tmp_path / "second.ready")
    gate.write_text("go", encoding="ascii")

    outcomes = {
        assert_provider_process_succeeded(first).strip(),
        assert_provider_process_succeeded(second).strip(),
    }
    assert outcomes == {"CREATED", "REPLAY"}
    assert activation_log.read_text(encoding="ascii").splitlines() == ["activation"]

    conflicting = launch_policy_provider_process(
        tmp_path, tmp_path / "conflict.ready", gate, activation_log, run_id, "0.09"
    )
    assert "CONFLICT" in assert_provider_process_succeeded(conflicting)
    assert activation_log.read_text(encoding="ascii").splitlines() == ["activation"]

    monkeypatch.setenv("ASTRAFORM_WORKSPACE_ROOT", str(tmp_path))
    terminal = service.load_policy_run_state(run_id)
    assert terminal is not None
    terminal["status"] = "COMPLETED"
    terminal["phase"] = "COMPLETED"
    service.store_policy_run_snapshot(run_id, terminal)
    terminal_replay = launch_policy_provider_process(
        tmp_path, tmp_path / "terminal.ready", gate, activation_log, run_id, "0.08"
    )
    assert_provider_process_succeeded(terminal_replay)
    assert activation_log.read_text(encoding="ascii").splitlines() == ["activation"]
    assert service.load_policy_run_state(run_id)["status"] == "COMPLETED"


def test_credit_apr_lift_pins_and_installs_day_ten_plan_before_starting_clock(
    tmp_path,
    monkeypatch,
) -> None:
    run_id = "python-rate-change-installation-test"
    cohort_id = "python-rate-change-cohort"
    monkeypatch.setenv(service.SHARED_STORAGE_ENABLED_ENV, "true")
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    queued: list[tuple[str, str | None]] = []
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda queued_run_id, _source=None, *, execution_claim_owner=None: queued.append(
            (queued_run_id, execution_claim_owner)
        ),
    )
    payload = platform_v2_launch_payload(run_id, candidate_rate=0.24)
    cohort_agents = [
        {
            "personaName": "customer-001",
            "domainId": DOMAIN_ID,
            "model": "stub",
            "interactionMode": "SIMULATION",
            "autonomousDecisionEnabled": False,
            "goals": [],
            "initialAccounts": [{
                "accountId": "cc-001",
                "name": "Rewards Card",
                "productCode": "CC001",
                "balance": -1200,
                "limit": 3000,
                "currency": "AUD",
            }],
            "scheduledWork": [],
        },
        {
            "personaName": "customer-002",
            "domainId": DOMAIN_ID,
            "model": "stub",
            "interactionMode": "SIMULATION",
            "autonomousDecisionEnabled": False,
            "goals": [],
            "initialAccounts": [{
                "accountId": "tx-001",
                "name": "Everyday Account",
                "productCode": "TX001",
                "balance": 500,
                "currency": "AUD",
            }],
            "scheduledWork": [],
        },
    ]
    cohort_bytes = json.dumps(cohort_agents, separators=(",", ":")).encode("utf-8")
    cohort_artifact_digest = hashlib.sha256(cohort_bytes).hexdigest()
    cohort_identity_set_digest = service.rate_change_agent_identity_set_digest(
        ["customer-001", "customer-002"]
    )
    payload.update(
        {
            "presetId": "credit-apr-lift",
            "productCode": "CC001",
            "baselineRate": 0.19,
            "simulationStartEpochMs": service.DEFAULT_SIMULATION_START_EPOCH_MS,
            "simulationHorizonDays": 30,
            "prePolicyDays": 10,
            "postPolicyDays": 20,
            "cohortId": cohort_id,
            "cohortSelection": {"cohortId": cohort_id},
            "activationId": f"{run_id}-activation",
            "cohortArtifactDigest": cohort_artifact_digest,
            "cohortAgentIdentitySetDigest": cohort_identity_set_digest,
            "cohortArtifactSnapshot": {
                "schemaVersion": "scenario_lab_cohort_artifact_snapshot.v1",
                "contentType": "application/json",
                "encoding": "base64",
                "sha256": cohort_artifact_digest,
                "content": base64.b64encode(cohort_bytes).decode("ascii"),
            },
        }
    )

    accepted = service.start_policy_wind_tunnel_run(
        payload,
        execution_role="PLATFORM_ADMIN",
    )

    assert accepted["runId"] == run_id
    assert queued and queued[0][0] == run_id
    plan_path = service.clock_driven_intervention_plan_path(run_id)
    assert plan_path.is_file()
    plan = service.require_rate_change_plan_bytes(plan_path.read_bytes())
    assert plan.plan["scheduledOffsetDays"] == 10
    assert accepted["parameters"][service.CLOCK_DRIVEN_INTERVENTION_PLAN_DIGEST_FIELD] == plan.plan_digest

    monkeypatch.setattr(service, "enforce_real_llm_canary", lambda *_args: None)
    monkeypatch.setattr(service, "enforce_hybrid_cohort_limit", lambda *_args: None)
    monkeypatch.setattr(service, "apply_hybrid_replay_contract", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(service, "clear_active_agents", lambda *_args: None)
    monkeypatch.setattr(service, "activate_pinned_cohort", lambda *_args, **_kwargs: None)

    def post_install(url: str, body: dict[str, Any], **_kwargs: Any) -> dict[str, Any]:
        assert url.endswith("/api/domain-control-actions/execute")
        operation_id = body["operationId"]
        digest = body["inputs"]["planDigest"]
        return {
            "simulationRunId": run_id,
            "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
            "operationId": operation_id,
            "matchedAgents": 2,
            "succeeded": 2,
            "failed": 0,
            "results": [
                {
                    "agentId": "customer-001",
                    "cohortAgentId": "customer-001",
                    "providerRuntimeAgentId": "provider-customer-001",
                    "opportunityAgentId": "opportunity-customer-001",
                    "controlAction": {
                        "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
                        "operationId": operation_id,
                        "outcome": "PLAN_INSTALLED",
                        "planDigest": digest,
                        "eligibleAccountCount": 1,
                    },
                },
                {
                    "agentId": "customer-002",
                    "cohortAgentId": "customer-002",
                    "providerRuntimeAgentId": "provider-customer-002",
                    "opportunityAgentId": "opportunity-customer-002",
                    "controlAction": {
                        "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
                        "operationId": operation_id,
                        "outcome": "PLAN_INSTALLED",
                        "planDigest": digest,
                        "eligibleAccountCount": 0,
                    },
                },
            ],
        }

    monkeypatch.setattr(service, "post_json", post_install)
    clock_started: list[bool] = []

    def start_clock(_run_id: str, _playback_rate: float) -> bool:
        installation_path = service.clock_driven_intervention_installation_path(run_id)
        assert installation_path.is_file()
        installation = service.require_rate_change_installation_manifest_bytes(
            installation_path.read_bytes(),
            plan,
        )
        assert installation.installed_agent_count == 2
        assert installation.eligible_customer_count == 1
        service.update_policy_run(
            run_id,
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
        )
        clock_started.append(True)
        return True

    monkeypatch.setattr(service, "start_simulator_clock_after_activation", start_clock)
    monkeypatch.setattr(service, "start_clock_horizon_monitor", lambda *_args: None)

    service.execute_clock_driven_policy_wind_tunnel_run(
        run_id,
        execution_claim_owner=queued[0][1],
    )

    assert clock_started == [True]
    assert service.policy_run_record(run_id)["phase"] == "CLOCK_RUNNING"


def test_rate_change_installation_rejects_substituted_customer_identity(
    tmp_path,
    monkeypatch,
) -> None:
    run_id = "python-rate-change-substituted-customer"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    plan = service.compile_rate_change_plan(
        run_id,
        "credit-apr-lift",
        {
            "productCode": "CC001",
            "baselineRate": "0.19",
            "candidateRate": "0.24",
            "prePolicyDays": 10,
            "simulationStartEpochMs": service.DEFAULT_SIMULATION_START_EPOCH_MS,
        },
    )
    assert plan is not None
    operation_id = service.rate_change_plan_install_operation_id(plan)

    def substituted_response(_url: str, _body: dict[str, Any], **_kwargs: Any) -> dict[str, Any]:
        return {
            "simulationRunId": run_id,
            "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
            "operationId": operation_id,
            "matchedAgents": 2,
            "succeeded": 2,
            "failed": 0,
            "results": [
                {
                    "agentId": "customer-a",
                    "cohortAgentId": "customer-a",
                    "providerRuntimeAgentId": "provider-customer-a",
                    "opportunityAgentId": "opportunity-customer-a",
                    "controlAction": {
                        "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
                        "operationId": operation_id,
                        "outcome": "PLAN_INSTALLED",
                        "planDigest": plan.plan_digest,
                        "eligibleAccountCount": 1,
                    },
                },
                {
                    "agentId": "substituted-customer",
                    "cohortAgentId": "substituted-customer",
                    "providerRuntimeAgentId": "provider-substituted-customer",
                    "opportunityAgentId": "opportunity-substituted-customer",
                    "controlAction": {
                        "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
                        "operationId": operation_id,
                        "outcome": "PLAN_INSTALLED",
                        "planDigest": plan.plan_digest,
                        "eligibleAccountCount": 0,
                    },
                },
            ],
        }

    monkeypatch.setattr(service, "post_json", substituted_response)
    expected_agent_ids = {"customer-a", "customer-b"}

    with pytest.raises(RuntimeError, match="different customer set"):
        service.install_clock_driven_rate_change_plan(
            run_id,
            plan,
            expected_agent_ids,
            service.rate_change_agent_identity_set_digest(list(expected_agent_ids)),
            "c" * 64,
        )

    assert not service.clock_driven_intervention_installation_path(run_id).exists()


def launch_policy_provider_process(
    workspace: Path,
    ready: Path,
    gate: Path,
    activation_log: Path,
    run_id: str,
    candidate_rate: str,
) -> subprocess.Popen[str]:
    script = r'''
import base64
import fcntl
import hashlib
import json
import os
import sys
import time
from pathlib import Path

workspace, ready, gate, activation_log, run_id, candidate_rate = sys.argv[1:]
os.environ["ASTRAFORM_WORKSPACE_ROOT"] = workspace
os.environ["ASTRAFORM_REMOTE_DOMAIN_SHARED_STORAGE_ENABLED"] = "true"
from banking_remote_domain_python import service

Path(ready).write_text("ready", encoding="ascii")
deadline = time.monotonic() + 15
while not Path(gate).exists():
    if time.monotonic() >= deadline:
        raise RuntimeError("launch barrier timed out")
    time.sleep(0.01)

activated = False

def record_activation(_run_id, _source=None, **_kwargs):
    global activated
    activated = True
    path = Path(activation_log)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="ascii") as output:
        fcntl.flock(output.fileno(), fcntl.LOCK_EX)
        output.write("activation\n")
        output.flush()
        os.fsync(output.fileno())
        fcntl.flock(output.fileno(), fcntl.LOCK_UN)

service.start_clock_driven_policy_wind_tunnel_worker = record_activation
settings = {
    "schemaVersion": "scenario_lab_pinned_cohort_activation.v1",
    "agentDecisionMode": "STUB_LLM",
    "agentDecisionCadence": "EVENT_ONLY",
}
settings_bytes = json.dumps(settings, separators=(",", ":")).encode("utf-8")
settings_digest = hashlib.sha256(settings_bytes).hexdigest()
payload = {
    "presetId": "saver-repricing",
    "simulationRunId": run_id,
    "executionMode": "CLOCK_DRIVEN_HYBRID",
    "runPath": "CLOCK_DRIVEN_SIMULATION",
    "agentDecisionMode": "STUB_LLM",
    "agentDecisionCadence": "EVENT_ONLY",
    "opportunityGenerationFinalizationMode": "PLATFORM_OWNED_V2",
    "simulationHorizonDays": 1,
    "prePolicyDays": 0,
    "postPolicyDays": 1,
    "candidateRate": float(candidate_rate),
    "activationSettingsDigest": settings_digest,
    "activationSettingsSnapshot": {
        "schemaVersion": "scenario_lab_pinned_activation_settings_snapshot.v1",
        "contentType": "application/json",
        "encoding": "base64url",
        "sha256": settings_digest,
        "content": base64.urlsafe_b64encode(settings_bytes).decode("ascii").rstrip("="),
    },
}
try:
    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")
    print("CREATED" if activated else "REPLAY")
except service.ProtocolError as conflict:
    if conflict.status_code != 409:
        raise
    print("CONFLICT")
'''
    env = dict(os.environ)
    source_root = Path(__file__).resolve().parents[1] / "src"
    env["PYTHONPATH"] = os.pathsep.join(
        [str(source_root), env.get("PYTHONPATH", "")]
    ).rstrip(os.pathsep)
    return subprocess.Popen(
        [
            sys.executable,
            "-c",
            script,
            str(workspace),
            str(ready),
            str(gate),
            str(activation_log),
            run_id,
            candidate_rate,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=env,
    )


def await_path(path: Path) -> None:
    deadline = time.monotonic() + 15
    while not path.exists():
        if time.monotonic() >= deadline:
            raise AssertionError(f"provider subprocess did not reach launch barrier: {path}")
        time.sleep(0.01)


def assert_provider_process_succeeded(process: subprocess.Popen[str]) -> str:
    output, _ = process.communicate(timeout=20)
    assert process.returncode == 0, output
    return output


def test_launch_digest_uses_canonical_resolved_inputs(monkeypatch) -> None:
    payload = platform_v2_launch_payload("python-canonical-launch")
    first = service.normalized_wind_tunnel_parameters(payload, "saver-repricing")
    second = deepcopy(first)
    second["simulationStartEpochMs"] = first["simulationStartEpochMs"] + 10_000

    first_digest = service.policy_launch_digest(
        "saver-repricing", first, payload, "PLATFORM_ADMIN"
    )
    retry_digest = service.policy_launch_digest(
        "saver-repricing",
        second,
        {**payload, "ignoredCallerField": "not-a-resolved-input"},
        "PLATFORM_ADMIN",
    )
    changed = deepcopy(second)
    changed["candidateRate"] = 0.09

    assert retry_digest == first_digest
    assert (
        service.policy_launch_digest(
            "saver-repricing", changed, payload, "PLATFORM_ADMIN"
        )
        != first_digest
    )
    monkeypatch.setattr(
        service,
        "WIND_TUNNEL_PROVIDER_REVISION",
        "build-sha256:" + "f" * 64,
    )
    assert (
        service.policy_launch_digest(
            "saver-repricing", first, payload, "PLATFORM_ADMIN"
        )
        != first_digest
    )


def test_expired_launch_claim_is_taken_over_and_stale_owner_is_fenced(
    tmp_path,
    monkeypatch,
) -> None:
    run_id = "python-expired-launch-claim"
    payload = platform_v2_launch_payload(run_id)
    fresh_owners: list[str] = []
    recovery_owners: list[str] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: True)
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda _run_id, _source=None, *, execution_claim_owner=None: fresh_owners.append(
            execution_claim_owner
        ),
    )
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_recovery",
        lambda _run_id, *, execution_claim_owner: recovery_owners.append(
            execution_claim_owner
        ),
    )

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")
    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")

    assert len(fresh_owners) == 1
    assert recovery_owners == []
    stale_owner = fresh_owners[0]
    expired = service.load_policy_run_state(run_id)
    assert expired is not None
    expired[service.POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = 0
    service.store_policy_run_snapshot(run_id, expired)

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")

    assert len(fresh_owners) == 1
    assert len(recovery_owners) == 1
    assert recovery_owners[0] != stale_owner
    with service.policy_execution_claim_scope(run_id, stale_owner):
        with pytest.raises(service.StalePolicyExecutionClaim):
            service.update_policy_run(run_id, fields={"staleWriter": True})
        with pytest.raises(service.StalePolicyExecutionClaim):
            service.store_policy_run_snapshot(
                run_id,
                {
                    **expired,
                    "status": "FAILED",
                    "phase": "FAILED",
                },
            )
        with pytest.raises(service.StalePolicyExecutionClaim):
            service.write_finalization_receipt(run_id, {})
    with service.policy_execution_claim_scope(run_id, recovery_owners[0]):
        service.update_policy_run(run_id, fields={"currentWriter": True})
    durable = service.load_policy_run_state(run_id)
    assert durable is not None
    assert "staleWriter" not in durable
    assert durable["currentWriter"] is True


@pytest.mark.parametrize(
    ("status", "phase"),
    [
        ("PAUSED", "PAUSED"),
        ("READY_TO_FINALIZE", "READY_TO_FINALIZE"),
        ("RUNNING", "FINALIZING_REPORT"),
    ],
)
def test_expired_nonrecoverable_replay_does_not_claim_or_schedule(
    tmp_path,
    monkeypatch,
    status: str,
    phase: str,
) -> None:
    run_id = "python-nonrecoverable-replay-" + phase.lower()
    payload = platform_v2_launch_payload(run_id)
    fresh_owners: list[str] = []
    recovery_owners: list[str] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: True)
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda _run_id, _source=None, *, execution_claim_owner=None: fresh_owners.append(
            execution_claim_owner
        ),
    )
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_recovery",
        lambda _run_id, *, execution_claim_owner: recovery_owners.append(
            execution_claim_owner
        ),
    )

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")
    durable = service.load_policy_run_state(run_id)
    assert durable is not None
    original_owner = durable[service.POLICY_EXECUTION_CLAIM_OWNER_FIELD]
    durable["status"] = status
    durable["phase"] = phase
    durable[service.POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = 0
    service.store_policy_run_snapshot(run_id, durable)

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")

    replay = service.load_policy_run_state(run_id)
    assert len(fresh_owners) == 1
    assert recovery_owners == []
    assert replay is not None
    assert replay[service.POLICY_EXECUTION_CLAIM_OWNER_FIELD] == original_owner
    assert replay[service.POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] == 0


def test_expired_activation_recovery_never_repeats_activation(
    tmp_path,
    monkeypatch,
) -> None:
    run_id = "python-expired-activation-recovery"
    payload = platform_v2_launch_payload(run_id)
    fresh_owners: list[str] = []
    recovery_owners: list[str] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: True)
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda _run_id, _source=None, *, execution_claim_owner=None: fresh_owners.append(
            execution_claim_owner
        ),
    )

    def recover_now(recovered_run_id: str, *, execution_claim_owner: str) -> None:
        recovery_owners.append(execution_claim_owner)
        service.recover_existing_clock_driven_policy_wind_tunnel_run(
            recovered_run_id,
            execution_claim_owner,
        )

    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_recovery",
        recover_now,
    )
    monkeypatch.setattr(
        service,
        "apply_playback_control_for_run",
        lambda *_args, **_kwargs: True,
    )
    monkeypatch.setattr(service, "clear_hybrid_runtime_cohort", lambda _run_id: None)
    monkeypatch.setattr(
        service,
        "activate_pinned_cohort",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("expired replay must not repeat pinned activation")
        ),
    )
    monkeypatch.setattr(
        service,
        "activate_agent_chunk",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("expired replay must not repeat chunk activation")
        ),
    )

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")
    activating = service.load_policy_run_state(run_id)
    assert activating is not None
    activating["status"] = "RUNNING"
    activating["phase"] = "ACTIVATING_COHORT"
    activating[service.POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = 0
    service.store_policy_run_snapshot(run_id, activating)

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")

    recovered = service.load_policy_run_state(run_id)
    assert len(fresh_owners) == 1
    assert len(recovery_owners) == 1
    assert recovered is not None
    assert recovered["status"] == "FAILED"
    assert recovered["phase"] == "FAILED"
    assert (
        recovered["error"]["message"]
        == "Provider ownership expired during cohort activation; activation was not replayed because its committed boundary is ambiguous."
    )

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")

    assert len(fresh_owners) == 1
    assert len(recovery_owners) == 1
    assert service.load_policy_run_state(run_id)["status"] == "FAILED"


def test_expired_clock_running_recovery_restarts_only_the_horizon_monitor(
    tmp_path,
    monkeypatch,
) -> None:
    run_id = "python-expired-clock-recovery"
    payload = platform_v2_launch_payload(run_id)
    fresh_owners: list[str] = []
    monitored: list[tuple[str, str]] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: True)
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda _run_id, _source=None, *, execution_claim_owner=None: fresh_owners.append(
            execution_claim_owner
        ),
    )
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_recovery",
        lambda recovered_run_id, *, execution_claim_owner: service.recover_existing_clock_driven_policy_wind_tunnel_run(
            recovered_run_id,
            execution_claim_owner,
        ),
    )
    monkeypatch.setattr(
        service,
        "start_clock_horizon_monitor",
        lambda recovered_run_id, execution_claim_owner=None: monitored.append(
            (recovered_run_id, execution_claim_owner)
        ),
    )
    monkeypatch.setattr(
        service,
        "activate_pinned_cohort",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("clock recovery must not repeat activation")
        ),
    )
    monkeypatch.setattr(
        service,
        "activate_agent_chunk",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("clock recovery must not repeat activation")
        ),
    )

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")
    clock_running = service.load_policy_run_state(run_id)
    assert clock_running is not None
    clock_running["status"] = "RUNNING"
    clock_running["phase"] = "CLOCK_RUNNING"
    clock_running[service.POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = 0
    service.store_policy_run_snapshot(run_id, clock_running)

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")

    assert len(fresh_owners) == 1
    assert len(monitored) == 1
    assert monitored[0][0] == run_id
    assert monitored[0][1]


def test_published_terminal_receipt_wins_before_expired_launch_takeover(
    tmp_path,
    monkeypatch,
) -> None:
    run_id = "python-terminal-receipt-launch-replay"
    payload = platform_v2_launch_payload(run_id)
    fresh_scheduled: list[str] = []
    recovery_scheduled: list[str] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: True)
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda _run_id, _source=None, *, execution_claim_owner=None: fresh_scheduled.append(
            execution_claim_owner
        ),
    )
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_recovery",
        lambda _run_id, *, execution_claim_owner: recovery_scheduled.append(
            execution_claim_owner
        ),
    )
    configure_finalization_test(monkeypatch, tmp_path, run_id)

    service.start_policy_wind_tunnel_run(payload, execution_role="PLATFORM_ADMIN")
    stale_nonterminal = service.load_policy_run_state(run_id)
    assert stale_nonterminal is not None
    service.update_policy_run(
        run_id,
        status="RUNNING",
        phase="CLOCK_RUNNING",
        progress=0.55,
        bundle_ready=True,
    )
    service.finalize_clock_driven_policy_wind_tunnel_run(
        run_id,
        {
            "action": "FINALIZE",
            "operationId": service.expected_finalization_operation_id(run_id),
        },
    )
    assert service.load_finalization_receipt(run_id)["state"] == "PUBLISHED"

    stale_nonterminal[service.POLICY_EXECUTION_CLAIM_EXPIRES_AT_FIELD] = 0
    service.store_policy_run_snapshot(run_id, stale_nonterminal)
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    replay = service.start_policy_wind_tunnel_run(
        payload,
        execution_role="PLATFORM_ADMIN",
    )

    assert replay["status"] == "COMPLETED"
    assert len(fresh_scheduled) == 1
    assert recovery_scheduled == []
    assert service.load_policy_run_state(run_id)["status"] == "COMPLETED"


def test_platform_owned_daily_activation_forwards_exact_durable_pinned_bytes(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    run_id = "python-pinned-cohort-run"
    raw_personas = [
        {
            "personaName": "Pinned Customer",
            "domainId": "banking",
            "traits": ["synthetic-customer"],
            "goals": [],
            "initialAccounts": [],
            "scheduledWork": [
                {
                    "workId": "salary-1",
                    "kind": "transaction",
                    "frequency": "FORTNIGHTLY",
                    "payload": {"type": "DEPOSIT", "amount": 1000},
                }
            ],
        }
    ]
    pinned_bytes = json.dumps(raw_personas, separators=(",", ":")).encode("utf-8")
    artifact_digest = hashlib.sha256(pinned_bytes).hexdigest()
    identity_digest = hashlib.sha256(
        json.dumps(["Pinned Customer"], separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    activation_settings = {
        "schemaVersion": "scenario_lab_pinned_cohort_activation.v1",
        "agentDecisionMode": "DETERMINISTIC",
        "agentDecisionCadence": "DAILY",
    }
    activation_settings_bytes = json.dumps(
        activation_settings, separators=(",", ":")
    ).encode("utf-8")
    activation_settings_digest = hashlib.sha256(activation_settings_bytes).hexdigest()
    activation_settings_content = base64.urlsafe_b64encode(
        activation_settings_bytes
    ).decode("ascii").rstrip("=")
    parameters = {
        "executionMode": "CLOCK_DRIVEN_HYBRID",
        "agentDecisionCadence": "DAILY",
        "activationId": "platform-activation-opaque-123",
        "cohortArtifactDigest": artifact_digest,
        "cohortAgentIdentitySetDigest": identity_digest,
        "cohortArtifactSnapshot": {
            "schemaVersion": "scenario_lab_cohort_artifact_snapshot.v1",
            "contentType": "application/json",
            "encoding": "base64",
            "sha256": artifact_digest,
            "content": base64.b64encode(pinned_bytes).decode("ascii"),
        },
        "activationSettingsDigest": activation_settings_digest,
        "activationSettingsSnapshot": {
            "schemaVersion": "scenario_lab_pinned_activation_settings_snapshot.v1",
            "contentType": "application/json",
            "encoding": "base64url",
            "sha256": activation_settings_digest,
            "content": activation_settings_content,
        },
    }
    snapshot = service.capture_platform_cohort_snapshot(parameters)
    assert snapshot is not None
    service.persist_cohort_artifact_snapshot(run_id, snapshot)

    mutable_path = tmp_path / "output" / "cohorts" / "cohort-1" / "customers.json"
    mutable_path.parent.mkdir(parents=True)
    mutable_path.write_text(
        json.dumps(raw_personas).replace('"amount": 1000', '"amount": 9999'),
        encoding="utf-8",
    )
    durable = service.load_durable_cohort_artifact_snapshot(run_id, parameters)
    captured: dict[str, Any] = {}

    def capture_post(url: str, payload: bytes, *, headers: dict[str, str], timeout: float = 30.0) -> bytes:
        captured.update(url=url, payload=payload, headers=headers)
        return b""

    monkeypatch.setattr(service, "post_bytes", capture_post)
    monkeypatch.setattr(service, "append_policy_log", lambda *_args, **_kwargs: None)
    service.activate_pinned_cohort(
        run_id,
        "cohort-1",
        durable,
        "DETERMINISTIC",
        parameters,
        1,
    )

    assert captured["url"].endswith("/api/agents/init/pinned-cohort")
    assert captured["payload"] == pinned_bytes
    assert captured["headers"]["X-Astraform-Cohort-Artifact-Digest"] == artifact_digest
    assert captured["headers"]["X-Astraform-Activation-Id"] == "platform-activation-opaque-123"
    assert captured["headers"]["X-Astraform-Activation-Settings"] == activation_settings_content
    settings = json.loads(
        base64.urlsafe_b64decode(
            captured["headers"]["X-Astraform-Activation-Settings"] + "=="
        )
    )
    assert settings == activation_settings


def test_platform_owned_pinned_activation_does_not_preclear_open_run(tmp_path, monkeypatch) -> None:
    run_id = "python-pinned-cohort-no-preclear"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    parameters = {
        "executionMode": "CLOCK_DRIVEN_HYBRID",
        service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        "runPath": "CLOCK_DRIVEN_SIMULATION",
        "agentDecisionMode": "DETERMINISTIC",
        "agentDecisionCadence": "DAILY",
        "cohortId": "cohort-pinned-no-preclear",
        "cohortArtifactDigest": "a" * 64,
        "simulationHorizonDays": 1,
    }
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            parameters,
            status="RUNNING",
            phase="QUEUED",
            progress=0.0,
            bundle_ready=False,
            log_tail=[],
        )

    activated: list[str] = []
    monkeypatch.setattr(service, "negotiated_playback_rate", lambda *_args: 600.0)
    monkeypatch.setattr(service, "apply_hybrid_replay_contract", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(
        service,
        "clear_active_agents",
        lambda *_args: (_ for _ in ()).throw(AssertionError("pinned activation must not preclear the open run")),
    )
    monkeypatch.setattr(
        service,
        "load_durable_cohort_artifact_snapshot",
        lambda *_args: {"artifactDigest": "a" * 64, "identitySetDigest": "b" * 64, "content": b"[]"},
    )
    monkeypatch.setattr(
        service,
        "load_pinned_cohort_agents_for_streaming",
        lambda *_args, **_kwargs: [{"agentId": "agent-1"}],
    )
    monkeypatch.setattr(service, "enforce_real_llm_canary", lambda *_args: None)
    monkeypatch.setattr(service, "enforce_hybrid_cohort_limit", lambda *_args: None)
    monkeypatch.setattr(
        service,
        "activate_pinned_cohort",
        lambda scoped_run_id, *_args, **_kwargs: activated.append(scoped_run_id),
    )

    def start_clock(scoped_run_id: str, _playback_rate: float) -> bool:
        service.update_policy_run(
            scoped_run_id,
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
        )
        return True

    monkeypatch.setattr(service, "start_simulator_clock_after_activation", start_clock)
    monkeypatch.setattr(service, "start_clock_horizon_monitor", lambda *_args, **_kwargs: None)

    try:
        service.execute_clock_driven_policy_wind_tunnel_run(run_id)

        run = service.policy_run_record(run_id)
        assert activated == [run_id]
        assert run["status"] == "RUNNING"
        assert run["phase"] == "CLOCK_RUNNING"
    finally:
        with service._POLICY_WIND_TUNNEL_LOCK:
            service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_pinned_snapshot_publish_failure_never_exposes_partial_final_file(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    run_id = "python-pinned-cohort-atomic-publish"
    content = b'[{"personaName":"Pinned Customer","domainId":"banking"}]'
    snapshot = {"content": content}
    final_path = service.cohort_artifact_snapshot_path(run_id)
    real_link = service.os.link

    def fail_publish(_source, _target) -> None:
        raise OSError("simulated publish interruption")

    monkeypatch.setattr(service.os, "link", fail_publish)
    with pytest.raises(OSError, match="simulated publish interruption"):
        service.persist_cohort_artifact_snapshot(run_id, snapshot)

    assert not final_path.exists()
    assert list(final_path.parent.glob(f"{final_path.name}.*.pending")) == []

    monkeypatch.setattr(service.os, "link", real_link)
    service.persist_cohort_artifact_snapshot(run_id, snapshot)

    assert final_path.read_bytes() == content


def test_real_llm_canary_rejects_non_canary_model() -> None:
    with pytest.raises(service.ProtocolError) as exc:
        service.normalize_generated_agent(
            {
                "personaName": "Wrong Model Real LLM Customer",
                "traits": ["synthetic-customer"],
                "goals": [],
                "initialAccounts": [],
            },
            "REAL_LLM",
            {
                "agentDecisionCadence": "DAILY",
                "realLlmBudget": {
                    "provider": "openai",
                    "model": "gpt-4.1-mini",
                    "maxCalls": 30,
                    "maxUsd": 0.50,
                },
            },
        )

    assert "REAL_LLM canary runs are restricted to openai/gemini-2.5-flash-lite" in str(exc.value)


def test_stub_llm_ignores_stale_real_llm_budget_model() -> None:
    agent = service.normalize_generated_agent(
        {
            "personaName": "Stub Customer",
            "traits": ["synthetic-customer"],
            "goals": [],
            "initialAccounts": [],
        },
        "STUB_LLM",
        {
            "agentDecisionCadence": "DAILY",
            "realLlmBudget": {
                "provider": "openai",
                "model": "gpt-4.1-mini",
                "maxCalls": 30,
                "maxUsd": 0.50,
            },
        },
    )

    assert agent["provider"] == "stub"
    assert agent["model"] == "stub"
    normalized = service.normalized_wind_tunnel_parameters(
        {
            "agentDecisionMode": "STUB_LLM",
            "realLlmBudget": {"provider": "openai", "model": "gpt-4.1-mini"},
        },
        "saver-repricing",
    )
    assert normalized["realLlmBudget"] == {}


def test_real_llm_budget_overages_fail_loudly() -> None:
    with pytest.raises(service.ProtocolError) as calls_exc:
        service.normalized_wind_tunnel_parameters(
            {
                "agentDecisionMode": "REAL_LLM",
                "realLlmBudget": {
                    "provider": "openai",
                    "model": "gemini-2.5-flash-lite",
                    "maxCalls": 500,
                    "maxUsd": 0.50,
                },
            },
            "saver-repricing",
        )

    assert "REAL_LLM canary maxCalls must be between 1 and 70" in str(calls_exc.value)

    with pytest.raises(service.ProtocolError) as usd_exc:
        service.normalized_wind_tunnel_parameters(
            {
                "agentDecisionMode": "REAL_LLM",
                "realLlmBudget": {
                    "provider": "openai",
                    "model": "gemini-2.5-flash-lite",
                    "maxCalls": 50,
                    "maxUsd": 99,
                },
            },
            "saver-repricing",
        )

    assert "REAL_LLM canary maxUsd must be between 0 and 1.0" in str(usd_exc.value)


def test_real_llm_canary_rejects_full_scale_cohort() -> None:
    with pytest.raises(service.ProtocolError) as exc:
        service.enforce_real_llm_canary(
            {
                "realLlmOptIn": True,
                "simulationHorizonDays": 3,
                "agentDecisionCadence": "DAILY",
                "realLlmBudget": {"maxCalls": 50, "maxUsd": 1.0},
            },
            "REAL_LLM",
            100,
        )

    assert "REAL_LLM canary runs are capped at 10 customers" in str(exc.value)


def test_real_llm_canary_allows_monthly_daily_ten_customer_budget() -> None:
    payload = {
        "realLlmOptIn": True,
        "simulationHorizonDays": 30,
        "agentDecisionCadence": "DAILY",
        "realLlmBudget": {
            "provider": "openai",
            "model": "gemini-2.5-flash-lite",
            "maxCalls": 70,
            "maxUsd": 1.0,
        },
    }

    service.enforce_real_llm_canary(payload, "REAL_LLM", 10)

    oversized_payload = dict(payload)
    oversized_payload["simulationHorizonDays"] = 31
    with pytest.raises(service.ProtocolError) as exc:
        service.enforce_real_llm_canary(oversized_payload, "REAL_LLM", 10)

    assert "REAL_LLM canary runs are capped at 30 simulated days" in str(exc.value)


def test_clock_horizon_monitor_poll_scales_with_playback_rate() -> None:
    assert service.clock_horizon_monitor_poll_seconds({"playbackRate": 1800}) < 1.1
    assert service.clock_horizon_monitor_poll_seconds({"playbackRate": 14400}) == 0.25
    assert service.clock_horizon_monitor_poll_seconds({"playbackRate": 1}) == 2.0


def test_event_only_no_token_playback_uses_higher_bound_only_when_simulator_proves_support(
    monkeypatch,
) -> None:
    event_only = {"agentDecisionCadence": "EVENT_ONLY"}
    monkeypatch.setattr(service, "simulator_max_playback_rate", lambda: 14_400.0)
    assert service.negotiated_playback_rate(
        {**event_only, "agentDecisionMode": "STUB_LLM"}, {}
    ) == 7_200.0
    assert service.negotiated_playback_rate(
        {**event_only, "agentDecisionMode": "DETERMINISTIC"}, {}
    ) == 7_200.0
    assert service.negotiated_playback_rate(
        {"agentDecisionMode": "STUB_LLM", "agentDecisionCadence": "DAILY"}, {}
    ) == 1_800.0
    assert service.negotiated_playback_rate(
        {"agentDecisionMode": "DETERMINISTIC", "agentDecisionCadence": "DAILY"}, {}
    ) == 1_800.0
    assert service.negotiated_playback_rate(
        {**event_only, "agentDecisionMode": "REAL_LLM"}, {}
    ) == service.HYBRID_REAL_LLM_MAX_PLAYBACK_RATE

    monkeypatch.setattr(service, "simulator_max_playback_rate", lambda: 1_800.0)
    assert service.negotiated_playback_rate(
        {**event_only, "agentDecisionMode": "DETERMINISTIC"}, {}
    ) == 1_800.0


def test_clock_lifecycle_ignores_clock_owned_by_another_run(monkeypatch) -> None:
    run_id = "python-stale-clock-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    monkeypatch.setattr(
        service,
        "fetch_simulator_replay_context",
        lambda: {"executionContext": {"runId": "other-active-run"}},
    )
    monkeypatch.setattr(
        service,
        "fetch_simulation_time_epoch_ms",
        lambda: service.DEFAULT_SIMULATION_START_EPOCH_MS + (30 * service.MILLIS_PER_SIMULATION_DAY),
    )
    monkeypatch.setattr(
        service,
        "finalize_clock_driven_policy_wind_tunnel_run",
        lambda _: (_ for _ in ()).throw(AssertionError("must not finalize stale run")),
    )

    service.maybe_advance_clock_driven_lifecycle(run_id)

    assert service.policy_run_record(run_id)["phase"] == "CLOCK_RUNNING"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_lifecycle_requires_explicit_clock_ownership(monkeypatch) -> None:
    run_id = "python-missing-clock-owner-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    monkeypatch.setattr(service, "fetch_simulator_replay_context", lambda: {})
    monkeypatch.setattr(
        service,
        "fetch_simulation_time_epoch_ms",
        lambda: service.DEFAULT_SIMULATION_START_EPOCH_MS + (30 * service.MILLIS_PER_SIMULATION_DAY),
    )
    monkeypatch.setattr(
        service,
        "finalize_clock_driven_policy_wind_tunnel_run",
        lambda _: (_ for _ in ()).throw(AssertionError("must not finalize without clock ownership proof")),
    )

    service.maybe_advance_clock_driven_lifecycle(run_id)

    run = service.policy_run_record(run_id)
    assert run["phase"] == "CLOCK_RUNNING"
    assert any("replay ownership could not be verified" in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_duration_requires_explicit_clock_ownership(monkeypatch) -> None:
    monkeypatch.setattr(service, "fetch_simulator_replay_context", lambda: {})
    monkeypatch.setattr(
        service,
        "fetch_simulation_time_epoch_ms",
        lambda: (_ for _ in ()).throw(AssertionError("must not read global clock without ownership proof")),
    )

    window = service.clock_driven_simulation_window(
        "python-missing-duration-owner-run",
        {"simulationHorizonDays": 1},
    )

    assert window["durationStatus"] == "UNVERIFIED_CLOCK_OWNERSHIP"
    assert window["durationProofVerified"] is False
    assert "actualDurationDays" not in window


def test_clock_horizon_stops_at_platform_finalization_handoff(tmp_path, monkeypatch) -> None:
    run_id = "python-ready-to-finalize-run"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    start_epoch_ms = 1_800_000_000_000
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "simulationHorizonDays": 1,
                "simulationStartEpochMs": start_epoch_ms,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    monkeypatch.setattr(
        service,
        "fetch_simulator_replay_context",
        lambda: {"executionContext": {"runId": run_id}},
    )
    monkeypatch.setattr(
        service,
        "fetch_simulation_time_epoch_ms",
        lambda: start_epoch_ms + service.MILLIS_PER_SIMULATION_DAY,
    )
    playback_controls: list[tuple[bool, float]] = []
    monkeypatch.setattr(
        service,
        "apply_playback_control_for_run",
        lambda _run_id, *, paused, playback_rate, action_label: (
            playback_controls.append((paused, playback_rate)) or True
        ),
    )

    service.maybe_advance_clock_driven_lifecycle(run_id)

    run = service.policy_run_record(run_id)
    assert run["status"] == "READY_TO_FINALIZE"
    assert run["phase"] == "READY_TO_FINALIZE"
    assert run["bundleReady"] is True
    assert playback_controls == [(True, 0.0)]
    assert any("Playback is paused" in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_driven_bundle_read_is_observational(monkeypatch) -> None:
    run_id = "python-observational-bundle-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    monkeypatch.setattr(
        service,
        "maybe_advance_clock_driven_lifecycle",
        lambda _: (_ for _ in ()).throw(AssertionError("bundle GET must not advance lifecycle")),
    )
    monkeypatch.setattr(
        service,
        "clock_driven_policy_wind_tunnel_bundle",
        lambda _run_id, correlation_audit=None: {
            "schemaVersion": "policy_wind_tunnel_bundle.v1",
            "summary": {},
        },
    )

    bundle = service.policy_wind_tunnel_bundle(run_id)

    assert bundle["schemaVersion"] == "policy_wind_tunnel_bundle.v1"
    run = service.policy_run_record(run_id)
    assert (run["status"], run["phase"]) == ("RUNNING", "CLOCK_RUNNING")
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_wind_tunnel_launch_anchor_is_normalized_once(monkeypatch) -> None:
    anchor = 1_800_000_000_000
    monkeypatch.setattr(service.time, "time", lambda: anchor / 1000)

    initial = service.normalized_wind_tunnel_parameters({}, service.WIND_TUNNEL_PRESET_ID)
    resumed = service.normalized_wind_tunnel_parameters(initial, service.WIND_TUNNEL_PRESET_ID)

    assert initial["simulationStartEpochMs"] == anchor
    assert resumed["simulationStartEpochMs"] == anchor
    with pytest.raises(service.ProtocolError):
        service.normalized_wind_tunnel_parameters(
            {"simulationStartEpochMs": 0},
            service.WIND_TUNNEL_PRESET_ID,
        )
    with pytest.raises(service.ProtocolError):
        service.normalized_wind_tunnel_parameters(
            {"simulationStartEpochMs": 1.5},
            service.WIND_TUNNEL_PRESET_ID,
        )

    assert service.normalize_simulation_start_epoch_ms(1.0) == 1
    assert service.normalize_simulation_start_epoch_ms(1e3) == 1_000
    assert service.normalize_simulation_start_epoch_ms(service.Decimal("1E3")) == 1_000
    assert service.normalize_simulation_start_epoch_ms(9_223_372_036_854_775_807) == 9_223_372_036_854_775_807
    for invalid in (True, "1000", 9_223_372_036_854_775_808):
        with pytest.raises(service.ProtocolError):
            service.normalize_simulation_start_epoch_ms(invalid)


def test_wind_tunnel_preserves_platform_owned_simulation_run_id(
    tmp_path,
    monkeypatch,
) -> None:
    run_id = "policy-wt-platform-owned-python-test"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(service, "platform_owned_v2_storage_ready", lambda: True)
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda _run_id, _source=None, *, execution_claim_owner=None: None,
    )

    status = service.start_policy_wind_tunnel_run(
        platform_v2_launch_payload(run_id),
        execution_role="PLATFORM_ADMIN",
    )

    assert status["runId"] == run_id
    assert service.policy_run_record(run_id)["parameters"]["simulationRunId"] == run_id
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    for invalid in ("../not-a-run", "x" * 256):
        with pytest.raises(service.ProtocolError):
            service.normalized_wind_tunnel_parameters(
                {"simulationRunId": invalid},
                service.WIND_TUNNEL_PRESET_ID,
            )


def test_clock_duration_reports_unverified_when_clock_time_unavailable(monkeypatch) -> None:
    monkeypatch.setattr(
        service,
        "fetch_simulator_replay_context",
        lambda: {"executionContext": {"runId": "python-duration-missing-time-run"}},
    )
    monkeypatch.setattr(service, "fetch_simulation_time_epoch_ms", lambda: None)

    window = service.clock_driven_simulation_window(
        "python-duration-missing-time-run",
        {"simulationHorizonDays": 1},
    )

    assert window["durationStatus"] == "UNVERIFIED_CLOCK_TIME"
    assert window["durationProofVerified"] is False
    assert window["durationUnverifiedReason"] == "Simulator clock time was unavailable after replay ownership was verified."
    assert "actualDurationDays" not in window


def test_manual_clock_controls_require_explicit_clock_ownership(monkeypatch) -> None:
    run_id = "python-manual-controls-missing-owner-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    monkeypatch.setattr(service, "fetch_simulator_replay_context", lambda: {})
    monkeypatch.setattr(
        service,
        "post_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("POST /api/clock/replay failed with status 409")),
    )

    pause_response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "PAUSE"})
    resume_response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert pause_response.status_code == 200
    assert resume_response.status_code == 200
    run = service.policy_run_record(run_id)
    assert run["status"] == "RUNNING"
    assert run["phase"] == "CLOCK_RUNNING"
    assert any("Pause requested. Simulator playback was not changed because replay ownership changed" in line for line in run["logTail"])
    assert any("Resume ignored because run state is RUNNING/CLOCK_RUNNING." in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_running_clock_can_change_playback_rate_without_resume(monkeypatch) -> None:
    run_id = "python-running-playback-rate-change"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "playbackRate": 1800.0,
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    replay_posts: list[dict[str, Any]] = []

    def fake_post_json(url: str, payload: dict[str, Any], *, headers: dict[str, str] | None = None) -> dict[str, Any]:
        replay_posts.append(payload)
        return {}

    monkeypatch.setattr(service, "post_json", fake_post_json)

    response = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={"action": "SET_PLAYBACK_RATE", "playbackRate": 600.0},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "RUNNING"
    assert body["phase"] == "CLOCK_RUNNING"
    assert body["parameters"]["playbackRate"] == 600.0
    assert replay_posts[0]["expectedRunId"] == run_id
    assert replay_posts[0]["playback"] == {"paused": False, "rate": 600.0}
    assert any("Playback rate changed to 600x." in line for line in body["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_requested_playback_rate_ignores_invalid_explicit_rate() -> None:
    parameters = {"agentDecisionMode": "STUB_LLM", "playbackRate": 600.0}

    assert service.requested_playback_rate(parameters, {"playbackRate": 0}) == 600.0
    assert service.requested_playback_rate(parameters, {"playbackRate": -10}) == 600.0
    assert service.requested_playback_rate(parameters, {"playbackRate": float("nan")}) == 600.0
    assert service.requested_playback_rate(parameters, {"playbackRate": "not-a-number"}) == 600.0


def test_pause_resume_preserves_operator_selected_playback_rate(monkeypatch) -> None:
    run_id = "python-pause-resume-playback-rate-preservation"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "playbackRate": 1800.0,
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    replay_posts: list[dict[str, Any]] = []

    def fake_post_json(url: str, payload: dict[str, Any], *, headers: dict[str, str] | None = None) -> dict[str, Any]:
        replay_posts.append(payload)
        return {}

    monkeypatch.setattr(service, "post_json", fake_post_json)

    set_response = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={"action": "SET_PLAYBACK_RATE", "playbackRate": 600.0},
    )
    pause_response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "PAUSE"})
    resume_response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert set_response.status_code == 200
    assert pause_response.status_code == 200
    assert resume_response.status_code == 200
    body = resume_response.json()
    assert body["status"] == "RUNNING"
    assert body["phase"] == "CLOCK_RUNNING"
    assert body["parameters"]["playbackRate"] == 600.0
    assert replay_posts[0]["playback"] == {"paused": False, "rate": 600.0}
    assert replay_posts[1]["playback"] == {"paused": True, "rate": 0.0}
    assert replay_posts[2]["playback"] == {"paused": False, "rate": 600.0}
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_resume_does_not_start_playback_during_activation(monkeypatch) -> None:
    run_id = "python-resume-during-activation-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="ACTIVATING_COHORT",
            progress=0.25,
            bundle_ready=False,
            log_tail=[],
        )
    monkeypatch.setattr(
        service,
        "post_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("resume during activation must not write playback")),
    )

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    assert response.json()["status"] == "RUNNING"
    assert response.json()["phase"] == "ACTIVATING_COHORT"
    run = service.policy_run_record(run_id)
    assert any("Resume ignored because run state is RUNNING/ACTIVATING_COHORT." in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_pause_before_replay_ownership_still_marks_run_paused(monkeypatch) -> None:
    run_id = "python-pause-before-replay-owner-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="PREPARING_CLOCK",
            progress=0.05,
            bundle_ready=False,
            log_tail=[],
        )
    monkeypatch.setattr(
        service,
        "post_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("POST /api/clock/replay failed with status 409")),
    )

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "PAUSE"})

    assert response.status_code == 200
    assert response.json()["status"] == "PAUSED"
    assert response.json()["phase"] == "PAUSED"
    run = service.policy_run_record(run_id)
    assert any("Run paused before simulator clock playback started." in line for line in run["logTail"])
    assert any("Pause requested. Simulator playback was not changed because replay ownership changed" in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_resume_from_preparing_pause_requeues_setup_worker(monkeypatch) -> None:
    run_id = "python-resume-from-preparing-pause-run"
    queued_workers: list[tuple[str, str | None]] = []
    with service._POLICY_WIND_TUNNEL_LOCK:
        run = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="PAUSED",
            phase="PAUSED",
            progress=0.05,
            bundle_ready=False,
            log_tail=[],
        )
        run["pausedFromPhase"] = "PREPARING_CLOCK"
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = run
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda resumed_run_id, _source=None, *, execution_claim_owner=None: queued_workers.append(
            (resumed_run_id, execution_claim_owner)
        ),
    )

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    assert response.json()["status"] == "RUNNING"
    assert response.json()["phase"] == "PREPARING_CLOCK"
    assert len(queued_workers) == 1
    assert queued_workers[0][0] == run_id
    assert queued_workers[0][1]
    run = service.policy_run_record(run_id)
    assert any("Restarting clock-driven setup from PREPARING_CLOCK." in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_resume_from_activation_pause_requeues_setup_worker_from_clean_preparing_phase(monkeypatch) -> None:
    run_id = "python-resume-from-activation-pause-run"
    queued_workers: list[tuple[str, str | None]] = []
    with service._POLICY_WIND_TUNNEL_LOCK:
        run = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="PAUSED",
            phase="PAUSED",
            progress=0.30,
            bundle_ready=False,
            log_tail=[],
        )
        run["pausedFromPhase"] = "ACTIVATING_COHORT"
        run["activatedCount"] = 50
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = run
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda resumed_run_id, _source=None, *, execution_claim_owner=None: queued_workers.append(
            (resumed_run_id, execution_claim_owner)
        ),
    )

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    assert response.json()["status"] == "RUNNING"
    assert response.json()["phase"] == "PREPARING_CLOCK"
    assert len(queued_workers) == 1
    assert queued_workers[0][0] == run_id
    assert queued_workers[0][1]
    run = service.policy_run_record(run_id)
    assert "activatedCount" not in run
    assert any("Restarting clock-driven setup from ACTIVATING_COHORT." in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_resumed_preparing_worker_does_not_steal_replay_from_another_run(monkeypatch) -> None:
    run_id = "python-resume-preparing-conflict-run"
    replay_payloads: list[dict[str, object]] = []
    with service._POLICY_WIND_TUNNEL_LOCK:
        run = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "cohortId": "banking-balanced-banking-100-test",
                "simulationHorizonDays": 1,
                "prePolicyDays": 0,
                "postPolicyDays": 1,
            },
            status="PAUSED",
            phase="PAUSED",
            progress=0.05,
            bundle_ready=False,
            log_tail=[],
        )
        run["pausedFromPhase"] = "PREPARING_CLOCK"
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = run

    def fake_start_worker(
        resumed_run_id: str,
        resume_source_phase: str | None = None,
        *,
        execution_claim_owner: str | None = None,
    ) -> None:
        service.execute_clock_driven_policy_wind_tunnel_run(
            resumed_run_id,
            resume_source_phase,
            execution_claim_owner,
        )

    def fake_get_json(url: str) -> dict[str, object]:
        if url.endswith("/api/clock/rate/info"):
            return {"max": 1800.0}
        return {}

    def fake_post_json(url: str, payload: object, **_kwargs: object) -> dict[str, object]:
        if url.endswith("/api/clock/replay"):
            replay_payloads.append(dict(payload))
            raise RuntimeError("POST /api/clock/replay failed with status 409")
        raise AssertionError("resumed setup must not call non-replay POST after replay ownership conflict")

    monkeypatch.setattr(service, "start_clock_driven_policy_wind_tunnel_worker", fake_start_worker)
    monkeypatch.setattr(service, "get_json", fake_get_json)
    monkeypatch.setattr(service, "post_json", fake_post_json)
    monkeypatch.setattr(service, "load_generated_cohort_agents_for_streaming", lambda *_args, **_kwargs: [{"agentId": "agent-1"}])
    monkeypatch.setattr(service, "clear_active_agents", lambda _run_id: (_ for _ in ()).throw(AssertionError("must not clear active agents after replay ownership conflict")))

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    run = service.policy_run_record(run_id)
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert [payload.get("expectedRunId") for payload in replay_payloads] == [run_id, ""]
    assert any(
        "Setup remains paused because simulator replay ownership was not empty or owned by this run." in line
        for line in run["logTail"]
    )
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_resumed_activation_worker_requires_existing_replay_ownership(monkeypatch) -> None:
    run_id = "python-resume-activation-conflict-run"
    replay_payloads: list[dict[str, object]] = []
    with service._POLICY_WIND_TUNNEL_LOCK:
        run = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "cohortId": "banking-balanced-banking-100-test",
                "simulationHorizonDays": 1,
                "prePolicyDays": 0,
                "postPolicyDays": 1,
            },
            status="PAUSED",
            phase="PAUSED",
            progress=0.30,
            bundle_ready=False,
            log_tail=[],
        )
        run["pausedFromPhase"] = "ACTIVATING_COHORT"
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = run

    def fake_start_worker(
        resumed_run_id: str,
        resume_source_phase: str | None = None,
        *,
        execution_claim_owner: str | None = None,
    ) -> None:
        service.execute_clock_driven_policy_wind_tunnel_run(
            resumed_run_id,
            resume_source_phase,
            execution_claim_owner,
        )

    def fake_get_json(url: str) -> dict[str, object]:
        if url.endswith("/api/clock/rate/info"):
            return {"max": 1800.0}
        return {}

    def fake_post_json(url: str, payload: object, **_kwargs: object) -> dict[str, object]:
        if url.endswith("/api/clock/replay"):
            replay_payloads.append(dict(payload))
            raise RuntimeError("POST /api/clock/replay failed with status 409")
        raise AssertionError("resumed setup must not call non-replay POST after replay ownership conflict")

    monkeypatch.setattr(service, "start_clock_driven_policy_wind_tunnel_worker", fake_start_worker)
    monkeypatch.setattr(service, "get_json", fake_get_json)
    monkeypatch.setattr(service, "post_json", fake_post_json)
    monkeypatch.setattr(service, "load_generated_cohort_agents_for_streaming", lambda *_args, **_kwargs: [{"agentId": "agent-1"}])
    monkeypatch.setattr(service, "clear_active_agents", lambda _run_id: (_ for _ in ()).throw(AssertionError("must not clear active agents after replay ownership conflict")))

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    run = service.policy_run_record(run_id)
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert [payload.get("expectedRunId") for payload in replay_payloads] == [run_id]

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    run = service.policy_run_record(run_id)
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert [payload.get("expectedRunId") for payload in replay_payloads] == [run_id, run_id]
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_initial_worker_does_not_steal_replay_from_another_run(monkeypatch) -> None:
    run_id = "python-initial-owned-replay-conflict-run"
    replay_payloads: list[dict[str, object]] = []
    fail_replay_claim = {"enabled": True}
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "cohortId": "banking-balanced-banking-100-test",
                "simulationHorizonDays": 1,
                "prePolicyDays": 0,
                "postPolicyDays": 1,
            },
            status="RUNNING",
            phase="QUEUED",
            progress=0.0,
            bundle_ready=False,
            log_tail=[],
        )

    def fake_get_json(url: str) -> dict[str, object]:
        if url.endswith("/api/clock/rate/info"):
            return {"max": 1800.0}
        return {}

    def fake_post_json(url: str, payload: object, **_kwargs: object) -> dict[str, object]:
        if url.endswith("/api/clock/replay"):
            replay_payloads.append(dict(payload))
            if fail_replay_claim["enabled"]:
                raise RuntimeError("POST /api/clock/replay failed with status 409")
            return {}
        return {}

    monkeypatch.setattr(service, "get_json", fake_get_json)
    monkeypatch.setattr(service, "post_json", fake_post_json)
    monkeypatch.setattr(
        service,
        "start_clock_driven_policy_wind_tunnel_worker",
        lambda resumed_run_id, source=None, *, execution_claim_owner=None: (
            service.execute_clock_driven_policy_wind_tunnel_run(
                resumed_run_id,
                source,
                execution_claim_owner,
            )
        ),
    )
    monkeypatch.setattr(service, "clear_active_agents", lambda _run_id: None)
    monkeypatch.setattr(service, "load_generated_cohort_agents_for_streaming", lambda *_args, **_kwargs: [{"agentId": "agent-1"}])
    monkeypatch.setattr(service, "enforce_hybrid_cohort_limit", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(service, "activate_agent_chunk", lambda *_args, **_kwargs: None)

    service.execute_clock_driven_policy_wind_tunnel_run(run_id)

    run = service.policy_run_record(run_id)
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert [payload.get("expectedRunId") for payload in replay_payloads] == [""]
    assert any(
        "Start requested. Setup remains paused because simulator replay ownership was not empty or owned by this run." in line
        for line in run["logTail"]
    )

    fail_replay_claim["enabled"] = False
    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    run = service.policy_run_record(run_id)
    assert run["status"] == "RUNNING"
    assert run["phase"] == "CLOCK_RUNNING"
    assert [payload.get("expectedRunId") for payload in replay_payloads[:2]] == ["", run_id]
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_resume_does_not_interrupt_finalization(tmp_path, monkeypatch) -> None:
    run_id = "python-resume-during-finalization-run"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_LEGACY_V1,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="FINALIZING_REPORT",
            progress=0.92,
            bundle_ready=True,
            log_tail=[],
        )
    monkeypatch.setattr(
        service,
        "post_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("resume during finalization must not write playback")),
    )

    response = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json={"action": "RESUME"})

    assert response.status_code == 200
    assert response.json()["status"] == "RUNNING"
    assert response.json()["phase"] == "FINALIZING_REPORT"
    run = service.policy_run_record(run_id)
    assert any("Resume ignored because run state is RUNNING/FINALIZING_REPORT." in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_activation_start_uses_expected_run_id_cas(monkeypatch) -> None:
    run_id = "python-start-cas-conflict-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="ACTIVATING_COHORT",
            progress=0.50,
            bundle_ready=False,
            log_tail=[],
        )
    replay_posts: list[dict[str, Any]] = []

    def fake_post_json(url: str, payload: dict[str, Any], *, headers: dict[str, str] | None = None) -> dict[str, Any]:
        replay_posts.append(payload)
        raise RuntimeError("POST /api/clock/replay failed with status 409")

    monkeypatch.setattr(service, "post_json", fake_post_json)

    assert service.start_simulator_clock_after_activation(run_id, 1800.0) is False

    run = service.policy_run_record(run_id)
    assert run["status"] == "RUNNING"
    assert run["phase"] == "ACTIVATING_COHORT"
    assert replay_posts[0]["expectedRunId"] == run_id
    assert replay_posts[0]["playback"]["paused"] is False
    assert any("Start requested. Simulator playback was not changed because replay ownership changed" in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_activation_start_does_not_override_pause(monkeypatch) -> None:
    run_id = "python-start-paused-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
            },
            status="PAUSED",
            phase="PAUSED",
            progress=0.50,
            bundle_ready=False,
            log_tail=[],
        )
    monkeypatch.setattr(
        service,
        "post_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("paused activation must not start clock")),
    )

    assert service.start_simulator_clock_after_activation(run_id, 1800.0) is False

    run = service.policy_run_record(run_id)
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert any(
        "Clock-driven start worker stopped before starting simulator clock because run state is PAUSED/PAUSED."
        in line
        for line in run["logTail"]
    )
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_driven_worker_does_not_overwrite_pause_after_activation_chunk(monkeypatch) -> None:
    run_id = "python-worker-paused-during-activation"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "cohortId": "cohort-paused-during-activation",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="QUEUED",
            progress=0.0,
            bundle_ready=False,
            log_tail=[],
        )
    monkeypatch.setattr(service, "apply_hybrid_replay_contract", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(service, "clear_active_agents", lambda _run_id: None)
    monkeypatch.setattr(
        service,
        "load_generated_cohort_agents_for_streaming",
        lambda *_args, **_kwargs: [{"agentId": "agent-1"}],
    )
    monkeypatch.setattr(service, "enforce_hybrid_cohort_limit", lambda *_args, **_kwargs: None)

    def fake_activate_agent_chunk(*_args: Any, **_kwargs: Any) -> None:
        service.update_policy_run(run_id, status="PAUSED", phase="PAUSED", progress=0.25, bundle_ready=False)

    monkeypatch.setattr(service, "activate_agent_chunk", fake_activate_agent_chunk)
    monkeypatch.setattr(
        service,
        "post_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("paused activation must not start clock")),
    )

    service.execute_clock_driven_policy_wind_tunnel_run(run_id)

    run = service.policy_run_record(run_id)
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert any(
        "Clock-driven start worker stopped after activating chunk 1 because run state is PAUSED/PAUSED."
        in line
        for line in run["logTail"]
    )
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_driven_worker_does_not_overwrite_pause_before_activation_transition(monkeypatch) -> None:
    run_id = "python-worker-paused-before-activation"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "cohortId": "cohort-paused-before-activation",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="QUEUED",
            progress=0.0,
            bundle_ready=False,
            log_tail=[],
        )
    monkeypatch.setattr(service, "apply_hybrid_replay_contract", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(service, "clear_active_agents", lambda _run_id: None)

    def fake_load_generated_cohort_agents_for_streaming(*_args: Any, **_kwargs: Any) -> list[dict[str, Any]]:
        service.update_policy_run(run_id, status="PAUSED", phase="PAUSED", progress=0.10, bundle_ready=False)
        return [{"agentId": "agent-1"}]

    monkeypatch.setattr(service, "load_generated_cohort_agents_for_streaming", fake_load_generated_cohort_agents_for_streaming)
    monkeypatch.setattr(service, "enforce_hybrid_cohort_limit", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        service,
        "activate_agent_chunk",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("paused pre-activation run must not activate agents")),
    )
    monkeypatch.setattr(
        service,
        "post_json",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("paused pre-activation run must not start clock")),
    )

    service.execute_clock_driven_policy_wind_tunnel_run(run_id)

    run = service.policy_run_record(run_id)
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert any(
        "Clock-driven start worker stopped before preparing simulator clock because run state is PAUSED/PAUSED."
        in line
        for line in run["logTail"]
    )
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_agentic_summary_surfaces_unverified_duration_warning() -> None:
    summary = service.build_agentic_simulation_summary(
        {
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "HOURLY",
            "simulationHorizonDays": 1,
            "playbackRate": 1800.0,
        },
        100,
        {"summary": {}, "totals": {}},
        {},
        {
            "configuredDurationDays": 1,
            "durationStatus": "UNVERIFIED_CLOCK_OWNERSHIP",
            "durationProofVerified": False,
            "durationUnverifiedReason": "Simulator replay ownership was missing or unavailable.",
        },
        finalized=False,
    )

    assert summary["durationStatus"] == "UNVERIFIED_CLOCK_OWNERSHIP"
    assert summary["durationProofVerified"] is False
    assert summary["durationUnverifiedReason"] == "Simulator replay ownership was missing or unavailable."
    assert summary["evidenceIntegrityWarnings"][0]["code"] == "SIMULATION_DURATION_UNVERIFIED"


def test_finalize_does_not_mutate_simulator_replay_ownership(tmp_path, monkeypatch) -> None:
    run_id = "python-finalize-missing-owner-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(
        service,
        "fetch_decision_opportunity_reconciliation",
        lambda requested_run_id: empty_opportunity_reconciliation(requested_run_id),
    )
    monkeypatch.setattr(service, "fetch_simulator_replay_context", lambda: {})
    replay_posts: list[tuple[object, ...]] = []
    monkeypatch.setattr(service, "post_json", lambda *args, **_kwargs: replay_posts.append(args))
    monkeypatch.setattr(
        service,
        "clock_driven_policy_wind_tunnel_bundle",
        lambda _run_id, finalized=False, correlation_audit=None: {
            "summary": {
                "summaryMode": "CLOCK_DRIVEN_HYBRID_FINALIZED" if finalized else "CLOCK_DRIVEN_HYBRID_LIVE",
                "agenticSimulation": {},
            }
        },
    )
    monkeypatch.setattr(service, "notify_simulation_run_completed", lambda *_: True)

    response = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={
            "action": "FINALIZE",
            "operationId": service.expected_finalization_operation_id(run_id),
        },
    )

    assert response.status_code == 200
    assert response.json()["status"] == "COMPLETED"
    assert replay_posts == []
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def install_finalizable_policy_run(run_id: str) -> None:
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "agentDecisionCadence": "EVENT_ONLY",
                "simulationHorizonDays": 1,
                service.FINALIZATION_MODE_FIELD: (
                    service.FINALIZATION_MODE_PLATFORM_OWNED_V2
                ),
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )


def test_unknown_run_is_not_synthesized_without_explicit_legacy_opt_in(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-unknown-v2-run"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    with pytest.raises(service.ProtocolError) as error:
        service.policy_run_record(run_id)

    assert error.value.status_code == 404
    assert error.value.code == "unknown_policy_wind_tunnel_run"
    legacy = service.policy_run_record(run_id, allow_legacy_synthesis=True)
    assert legacy["status"] == "COMPLETED"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def configure_finalization_test(
    monkeypatch,
    tmp_path,
    run_id: str,
    notification_handler=None,
) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(
        service,
        "fetch_decision_opportunity_reconciliation",
        lambda requested_run_id: empty_opportunity_reconciliation(requested_run_id),
    )
    monkeypatch.setattr(
        service,
        "clock_driven_policy_wind_tunnel_bundle",
        lambda _run_id, finalized=False, correlation_audit=None: {
            "summary": {
                "summaryMode": (
                    "CLOCK_DRIVEN_HYBRID_FINALIZED"
                    if finalized
                    else "CLOCK_DRIVEN_HYBRID_LIVE"
                ),
                "agenticSimulation": {},
            }
        },
    )
    monkeypatch.setattr(
        service,
        "notify_simulation_run_completed",
        notification_handler or (lambda _payload: True),
    )


def test_provider_run_state_survives_independent_process_replacement(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-shared-provider-state"
    monkeypatch.setenv("ASTRAFORM_WORKSPACE_ROOT", str(tmp_path))
    accepted_run = service.new_policy_run(
        run_id,
        "hardship-relief",
        {
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        },
        status="RUNNING",
        phase="QUEUED",
        progress=0.0,
        bundle_ready=False,
        log_tail=["accepted"],
    )
    service.store_policy_run_snapshot(run_id, accepted_run)

    package_root = str((Path(__file__).resolve().parents[1] / "src"))
    environment = dict(os.environ)
    environment["ASTRAFORM_WORKSPACE_ROOT"] = str(tmp_path)
    environment["PYTHONPATH"] = package_root
    replacement = subprocess.run(
        [
            sys.executable,
            "-c",
            (
                "from banking_remote_domain_python import service; "
                "service.update_policy_run('python-shared-provider-state', "
                "status='COMPLETED', phase='COMPLETED', progress=1.0, bundle_ready=True); "
                "print(service.policy_run_record('python-shared-provider-state')['status'])"
            ),
        ],
        check=True,
        capture_output=True,
        text=True,
        env=environment,
    )

    assert replacement.stdout.strip() == "COMPLETED"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)
    recovered = service.policy_run_record(run_id)
    assert recovered["status"] == "COMPLETED"
    assert recovered["phase"] == "COMPLETED"


def test_finalization_run_directory_creation_fsyncs_its_parent(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalization-durable-directory"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    synced: list[Any] = []
    monkeypatch.setattr(service, "fsync_directory", lambda path: synced.append(path.resolve()))

    with service.finalization_receipt_lock(run_id):
        pass

    run_root = service.finalization_run_directory(run_id)
    assert run_root.exists()
    assert run_root.parent.resolve() in synced


def test_finalize_operation_id_is_idempotent_and_rejects_conflicting_input(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-idempotency-run"
    operation_id = service.expected_finalization_operation_id(run_id)
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    notifications: list[tuple] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(
        service,
        "fetch_decision_opportunity_reconciliation",
        lambda requested_run_id: empty_opportunity_reconciliation(requested_run_id),
    )
    monkeypatch.setattr(
        service,
        "clock_driven_policy_wind_tunnel_bundle",
        lambda _run_id, finalized=False, correlation_audit=None: {
            "summary": {
                "summaryMode": "CLOCK_DRIVEN_HYBRID_FINALIZED" if finalized else "CLOCK_DRIVEN_HYBRID_LIVE",
                "agenticSimulation": {},
            }
        },
    )
    monkeypatch.setattr(
        service,
        "notify_simulation_run_completed",
        lambda *args: notifications.append(args) or True,
    )

    request = {"action": "FINALIZE", "operationId": operation_id}
    first = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json=request)
    replay = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json=request)
    conflict = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={**request, "contradictory": True},
    )
    action_conflict = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={"action": "CANCEL", "operationId": operation_id},
    )

    assert first.status_code == 200
    assert first.json()["status"] == "COMPLETED"
    assert replay.status_code == 200
    assert replay.json()["status"] == "COMPLETED"
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "finalization_operation_conflict"
    assert action_conflict.status_code == 400
    assert action_conflict.json()["error"]["code"] == "invalid_finalization_operation_action"
    assert len(notifications) == 1
    assert service.policy_run_record(run_id)[service.FINALIZATION_OPERATION_FIELD]["operationId"] == operation_id
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_finalization_receipt_resumes_partial_publication_and_survives_restart(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-restart-run"
    operation_id = service.expected_finalization_operation_id(run_id)
    install_finalizable_policy_run(run_id)
    notifications: list[tuple] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(
        service,
        "fetch_decision_opportunity_reconciliation",
        lambda requested_run_id: empty_opportunity_reconciliation(requested_run_id),
    )
    monkeypatch.setattr(
        service,
        "clock_driven_policy_wind_tunnel_bundle",
        lambda _run_id, finalized=False, correlation_audit=None: {
            "summary": {
                "summaryMode": (
                    "CLOCK_DRIVEN_HYBRID_FINALIZED"
                    if finalized
                    else "CLOCK_DRIVEN_HYBRID_LIVE"
                ),
                "agenticSimulation": {},
            }
        },
    )
    monkeypatch.setattr(
        service,
        "notify_simulation_run_completed",
        lambda *args: notifications.append(args) or True,
    )
    original_artifact_writer = service.write_clock_driven_artifact_files
    original_receipt_writer = service.write_finalization_receipt
    publication_attempts = 0

    def count_artifact_publication(requested_run_id: str, summary: dict) -> None:
        nonlocal publication_attempts
        publication_attempts += 1
        original_artifact_writer(requested_run_id, summary)

    def interrupt_after_prepared(requested_run_id: str, receipt: dict) -> None:
        original_receipt_writer(requested_run_id, receipt)
        if receipt["state"] == service.FINALIZATION_RECEIPT_PREPARED:
            raise RuntimeError("simulated process interruption after PREPARED")

    monkeypatch.setattr(
        service, "write_clock_driven_artifact_files", count_artifact_publication
    )
    monkeypatch.setattr(service, "write_finalization_receipt", interrupt_after_prepared)
    request = {"action": "FINALIZE", "operationId": operation_id}

    with pytest.raises(RuntimeError, match="simulated process interruption after PREPARED"):
        client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json=request)

    assert publication_attempts == 1
    receipt = service.load_finalization_receipt(run_id)
    assert receipt["state"] == "PREPARED"
    assert receipt["providerRevision"] == service.WIND_TUNNEL_PROVIDER_REVISION
    assert set(receipt) == service.FINALIZATION_RECEIPT_FIELDS
    assert set(receipt["runSnapshot"]).issubset(
        service.FINALIZATION_RUN_SNAPSHOT_FIELDS
    )
    assert receipt["runSnapshot"]["status"] == "RUNNING"
    assert receipt["runSnapshot"]["metadata"]["providerRun"]["runId"] == run_id
    for entry in receipt["artifactManifest"].values():
        artifact = tmp_path.joinpath(entry["artifactRef"])
        content = artifact.read_bytes()
        assert len(content) == entry["byteLength"]
        assert hashlib.sha256(content).hexdigest() == entry["sha256"]
    assert notifications == []

    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)
    monkeypatch.setattr(service, "write_finalization_receipt", original_receipt_writer)
    resumed = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json=request
    )

    assert resumed.status_code == 200
    assert resumed.json()["status"] == "COMPLETED"
    assert service.load_finalization_receipt(run_id)["state"] == "PUBLISHED"
    assert publication_attempts == 1
    assert len(notifications) == 1

    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)
    replay = client.post(f"/policy-wind-tunnel/runs/{run_id}/lifecycle", json=request)

    assert replay.status_code == 200
    assert replay.json()["status"] == "COMPLETED"
    assert len(notifications) == 1
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_finalization_receipt_rejects_non_schema_run_snapshot_fields(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-closed-snapshot-run"
    install_finalizable_policy_run(run_id)
    configure_finalization_test(monkeypatch, tmp_path, run_id)
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }
    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    receipt_path = service.finalization_receipt_path(run_id)
    receipt = json.loads(receipt_path.read_text())
    receipt["runSnapshot"]["parameters"] = {"laundered": True}
    receipt_path.write_text(json.dumps(receipt))
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    with pytest.raises(RuntimeError, match="receipt is invalid"):
        service.load_finalization_receipt(run_id)


@pytest.mark.parametrize(
    "payload",
    [
        b'{"state":"PREPARED","state":"PUBLISHED"}',
        b'{"value":NaN}',
        b'{} {}',
        b'{"value":"\xff"}',
    ],
)
def test_finalization_receipt_json_parser_rejects_ambiguous_bytes(payload: bytes) -> None:
    with pytest.raises(RuntimeError, match="could not be read"):
        service.strict_json_object(payload, "Scenario Lab finalization receipt could not be read")


@pytest.mark.parametrize(
    ("receipt_state", "run_status"),
    [("PREPARED", "COMPLETED"), ("PUBLISHED", "RUNNING")],
)
def test_finalization_receipt_rejects_contradictory_state_matrix(
    receipt_state: str, run_status: str
) -> None:
    run_id = f"python-invalid-receipt-{receipt_state.lower()}-{run_status.lower()}"
    operation = {
        "operationId": service.expected_finalization_operation_id(run_id),
        "inputDigest": service.expected_finalization_input_digest(run_id),
    }
    run = service.new_policy_run(
        run_id,
        "hardship-relief",
        {
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        },
        status=run_status,
        phase=run_status,
        progress=1.0 if run_status != "RUNNING" else 0.92,
        bundle_ready=True,
        log_tail=[],
    )
    manifest = {
        artifact_type: {
            "artifactRef": "output/finalization/" + filename,
            "contentType": content_type,
            "sha256": "a" * 64,
            "byteLength": 1,
        }
        for artifact_type, (filename, content_type) in service.FINALIZATION_ARTIFACT_SPECS.items()
    }
    receipt = service.finalization_receipt(
        run_id,
        service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        operation,
        operation["inputDigest"],
        receipt_state,
        run,
        manifest,
    )

    with pytest.raises(RuntimeError, match="receipt is invalid"):
        service.validate_stored_finalization_receipt(receipt, run_id)


def test_finalization_receipt_rejects_artifact_path_escape() -> None:
    run_id = "python-invalid-receipt-artifact-path"
    operation = {
        "operationId": service.expected_finalization_operation_id(run_id),
        "inputDigest": service.expected_finalization_input_digest(run_id),
    }
    run = service.new_policy_run(
        run_id,
        "hardship-relief",
        {
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        },
        status="RUNNING",
        phase="FINALIZING_REPORT",
        progress=0.92,
        bundle_ready=True,
        log_tail=[],
    )
    manifest = {
        artifact_type: {
            "artifactRef": "output/finalization/" + filename,
            "contentType": content_type,
            "sha256": "a" * 64,
            "byteLength": 1,
        }
        for artifact_type, (filename, content_type) in service.FINALIZATION_ARTIFACT_SPECS.items()
    }
    manifest["summary"]["artifactRef"] = "../scenario-lab-summary.json"
    receipt = service.finalization_receipt(
        run_id,
        service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        operation,
        operation["inputDigest"],
        service.FINALIZATION_RECEIPT_PREPARED,
        run,
        manifest,
    )

    with pytest.raises(RuntimeError, match="receipt is invalid"):
        service.validate_stored_finalization_receipt(receipt, run_id)


def test_artifact_write_failure_does_not_create_prepared_receipt(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-write-failure-run"
    install_finalizable_policy_run(run_id)
    configure_finalization_test(monkeypatch, tmp_path, run_id)
    monkeypatch.setattr(
        service,
        "write_clock_driven_artifact_files",
        lambda *_args: (_ for _ in ()).throw(RuntimeError("simulated artifact failure")),
    )

    with pytest.raises(RuntimeError, match="simulated artifact failure"):
        service.finalize_clock_driven_policy_wind_tunnel_run(
            run_id,
            {
                "action": "FINALIZE",
                "operationId": service.expected_finalization_operation_id(run_id),
            },
        )

    assert not service.finalization_receipt_path(run_id).exists()
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_prepared_receipt_fails_closed_when_artifact_is_missing(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-missing-artifact-run"
    install_finalizable_policy_run(run_id)
    configure_finalization_test(monkeypatch, tmp_path, run_id)
    original_receipt_writer = service.write_finalization_receipt

    def interrupt_after_prepared(requested_run_id: str, receipt: dict) -> None:
        original_receipt_writer(requested_run_id, receipt)
        if receipt["state"] == service.FINALIZATION_RECEIPT_PREPARED:
            raise RuntimeError("interrupt after PREPARED")

    monkeypatch.setattr(service, "write_finalization_receipt", interrupt_after_prepared)
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }
    with pytest.raises(RuntimeError, match="interrupt after PREPARED"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    receipt = service.load_finalization_receipt(run_id)
    summary_path = tmp_path.joinpath(receipt["artifactManifest"]["summary"]["artifactRef"])
    summary_path.unlink()
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)
    monkeypatch.setattr(service, "write_finalization_receipt", original_receipt_writer)

    with pytest.raises(RuntimeError, match="not durably published"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)

    assert not summary_path.exists()
    persisted_receipt = json.loads(service.finalization_receipt_path(run_id).read_text())
    assert persisted_receipt["state"] == "PREPARED"


def test_published_receipt_fails_closed_when_artifact_is_altered(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-altered-artifact-run"
    install_finalizable_policy_run(run_id)
    notifications: list[dict] = []
    configure_finalization_test(
        monkeypatch,
        tmp_path,
        run_id,
        lambda payload: notifications.append(deepcopy(payload)) or True,
    )
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }
    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    receipt = service.load_finalization_receipt(run_id)
    summary_path = tmp_path.joinpath(receipt["artifactManifest"]["summary"]["artifactRef"])
    summary_path.write_text('{"tampered":true}')

    with pytest.raises(RuntimeError, match="integrity did not match"):
        service.policy_wind_tunnel_bundle(run_id)

    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    with pytest.raises(RuntimeError, match="integrity did not match"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)

    assert len(notifications) == 1


def test_prepared_receipt_rejects_incomplete_artifact_manifest(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-incomplete-manifest-run"
    install_finalizable_policy_run(run_id)
    configure_finalization_test(monkeypatch, tmp_path, run_id)
    original_receipt_writer = service.write_finalization_receipt

    def interrupt_after_prepared(requested_run_id: str, receipt: dict) -> None:
        original_receipt_writer(requested_run_id, receipt)
        if receipt["state"] == service.FINALIZATION_RECEIPT_PREPARED:
            raise RuntimeError("interrupt after PREPARED")

    monkeypatch.setattr(service, "write_finalization_receipt", interrupt_after_prepared)
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }
    with pytest.raises(RuntimeError, match="interrupt after PREPARED"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    monkeypatch.setattr(service, "write_finalization_receipt", original_receipt_writer)
    receipt_path = service.finalization_receipt_path(run_id)
    receipt = json.loads(receipt_path.read_text())
    receipt["artifactManifest"].pop("hybridAgentAuditJson")
    receipt_path.write_text(json.dumps(receipt))
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    with pytest.raises(RuntimeError, match="receipt is invalid"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)


def test_published_receipt_rejects_different_provider_revision(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-provider-revision-run"
    install_finalizable_policy_run(run_id)
    configure_finalization_test(monkeypatch, tmp_path, run_id)
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }
    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    receipt_path = service.finalization_receipt_path(run_id)
    receipt = json.loads(receipt_path.read_text())
    receipt["providerRevision"] = "oci-sha256:" + ("0" * 64)
    receipt_path.write_text(json.dumps(receipt))
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    with pytest.raises(RuntimeError, match="receipt is invalid"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)


@pytest.mark.parametrize("terminal_status", ["CANCELLED", "FAILED"])
def test_prepared_receipt_never_resurrects_terminal_run(
    tmp_path, monkeypatch, terminal_status: str
) -> None:
    run_id = "python-finalize-terminal-wins-" + terminal_status.lower()
    install_finalizable_policy_run(run_id)
    notifications: list[dict] = []
    configure_finalization_test(
        monkeypatch,
        tmp_path,
        run_id,
        lambda payload: notifications.append(deepcopy(payload)) or True,
    )
    original_receipt_writer = service.write_finalization_receipt

    def interrupt_after_prepared(requested_run_id: str, receipt: dict) -> None:
        original_receipt_writer(requested_run_id, receipt)
        if receipt["state"] == service.FINALIZATION_RECEIPT_PREPARED:
            raise RuntimeError("interrupt after PREPARED")

    monkeypatch.setattr(service, "write_finalization_receipt", interrupt_after_prepared)
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }
    with pytest.raises(RuntimeError, match="interrupt after PREPARED"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    monkeypatch.setattr(service, "write_finalization_receipt", original_receipt_writer)
    if terminal_status == "CANCELLED":
        monkeypatch.setattr(
            service, "apply_playback_control_for_run", lambda *_args, **_kwargs: True
        )
        service.control_policy_wind_tunnel_run(run_id, {"action": "CANCEL"})
    else:
        monkeypatch.setattr(
            service, "apply_playback_control_for_run", lambda *_args, **_kwargs: True
        )
        monkeypatch.setattr(service, "clear_hybrid_runtime_cohort", lambda *_args: None)
        service.handle_clock_driven_worker_failure(run_id, RuntimeError("terminal failure"))
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)

    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)

    assert service.policy_run_record(run_id)["status"] == terminal_status
    receipt = service.load_finalization_receipt(run_id)
    assert receipt["state"] == "PUBLISHED"
    assert receipt["completionNotificationState"] == "NOT_REQUIRED"
    assert notifications == []
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_cancel_after_prepared_receipt_prevents_completion_notification(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-prepared-cancel-run"
    install_finalizable_policy_run(run_id)
    notifications: list[dict] = []
    configure_finalization_test(
        monkeypatch,
        tmp_path,
        run_id,
        lambda payload: notifications.append(deepcopy(payload)) or True,
    )
    original_receipt_writer = service.write_finalization_receipt

    def interrupt_after_prepared(requested_run_id: str, receipt: dict) -> None:
        original_receipt_writer(requested_run_id, receipt)
        if receipt["state"] == service.FINALIZATION_RECEIPT_PREPARED:
            raise RuntimeError("interrupt after PREPARED")

    monkeypatch.setattr(service, "write_finalization_receipt", interrupt_after_prepared)
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }
    with pytest.raises(RuntimeError, match="interrupt after PREPARED"):
        service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    monkeypatch.setattr(service, "write_finalization_receipt", original_receipt_writer)

    service.control_policy_wind_tunnel_run(run_id, {"action": "CANCEL"})
    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)

    run = service.policy_run_record(run_id)
    receipt = service.load_finalization_receipt(run_id)
    assert run["status"] == "CANCELLED"
    assert run["phase"] == "CANCELLED"
    assert receipt["state"] == "PUBLISHED"
    assert receipt["completionNotificationState"] == "NOT_REQUIRED"
    assert notifications == []
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_completion_notification_retries_identical_published_payload(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-notification-retry-run"
    install_finalizable_policy_run(run_id)
    payloads: list[dict] = []

    def notify(payload: dict) -> bool:
        receipt = service.load_finalization_receipt(run_id)
        assert receipt["state"] == "PUBLISHED"
        assert receipt["completionNotificationState"] == "PENDING"
        payloads.append(deepcopy(payload))
        return len(payloads) > 1

    configure_finalization_test(monkeypatch, tmp_path, run_id, notify)
    request = {
        "action": "FINALIZE",
        "operationId": service.expected_finalization_operation_id(run_id),
    }

    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    assert service.load_finalization_receipt(run_id)["completionNotificationState"] == "PENDING"
    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)
    service.finalize_clock_driven_policy_wind_tunnel_run(run_id, request)

    assert len(payloads) == 2
    assert payloads[0] == payloads[1]
    assert payloads[0]["operationId"] == request["operationId"]
    assert service.load_finalization_receipt(run_id)["completionNotificationState"] == "CONFIRMED"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_concurrent_finalizers_share_one_durable_publication(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-shared-storage-run"
    operation_id = service.expected_finalization_operation_id(run_id)
    install_finalizable_policy_run(run_id)
    notifications: list[tuple] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(
        service,
        "fetch_decision_opportunity_reconciliation",
        lambda requested_run_id: empty_opportunity_reconciliation(requested_run_id),
    )
    monkeypatch.setattr(
        service,
        "clock_driven_policy_wind_tunnel_bundle",
        lambda _run_id, finalized=False, correlation_audit=None: {
            "summary": {
                "summaryMode": "CLOCK_DRIVEN_HYBRID_FINALIZED",
                "agenticSimulation": {},
            }
        },
    )
    monkeypatch.setattr(
        service,
        "notify_simulation_run_completed",
        lambda *args: notifications.append(args) or True,
    )
    start = threading.Barrier(3)
    failures: list[BaseException] = []

    def finalize() -> None:
        try:
            start.wait(timeout=5)
            service.finalize_clock_driven_policy_wind_tunnel_run(
                run_id, {"action": "FINALIZE", "operationId": operation_id}
            )
        except BaseException as exc:  # pragma: no cover - asserted below
            failures.append(exc)

    first = threading.Thread(target=finalize, name="python-finalizer-one")
    second = threading.Thread(target=finalize, name="python-finalizer-two")
    first.start()
    second.start()
    start.wait(timeout=5)
    first.join(timeout=10)
    second.join(timeout=10)

    assert not first.is_alive()
    assert not second.is_alive()
    assert failures == []
    assert service.load_finalization_receipt(run_id)["state"] == "PUBLISHED"
    assert service.policy_run_record(run_id)["status"] == "COMPLETED"
    assert len(notifications) == 1
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_platform_owned_v2_finalization_rejects_missing_operation_before_mutation(
    tmp_path, monkeypatch
) -> None:
    run_id = "python-finalize-missing-operation-run"
    install_finalizable_policy_run(run_id)
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)

    response = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={"action": "FINALIZE"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "finalization_operation_required"
    run = service.policy_run_record(run_id)
    assert run["status"] == "RUNNING"
    assert run["phase"] == "CLOCK_RUNNING"
    assert not service.finalization_run_directory(run_id).exists()
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_finalization_capability_is_request_scoped_and_never_persisted(monkeypatch) -> None:
    run_id = "python-private-correlation-capability-run"
    install_finalizable_policy_run(run_id)
    observed: list[str] = []

    def control(_run_id: str, _payload: dict[str, Any]) -> dict[str, Any]:
        observed.append(service.current_opportunity_correlation_capability())
        return service.policy_wind_tunnel_run_status(_run_id)

    monkeypatch.setattr(main_module, "control_policy_wind_tunnel_run", control)
    private_client = TestClient(
        app,
        headers={
            "X-Astraform-Expected-Provider-Revision": service.WIND_TUNNEL_PROVIDER_REVISION,
            service.OPPORTUNITY_CORRELATION_CAPABILITY_HEADER: "private-five-minute-capability",
        },
    )

    response = private_client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={"action": "FINALIZE", "operationId": "scenario-lab-finalize:test"},
    )

    assert response.status_code == 200
    assert observed == ["private-five-minute-capability"]
    assert not any(
        key.startswith("opportunityCorrelation")
        for key in response.json()["parameters"]
    )
    with pytest.raises(RuntimeError, match="private opportunity correlation capability"):
        service.current_opportunity_correlation_capability()
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


@pytest.mark.parametrize("terminal_status", ["FAILED", "CANCELLED"])
def test_v2_terminal_failure_without_receipt_publishes_exact_terminal_receipt(
    tmp_path, monkeypatch, terminal_status: str
) -> None:
    run_id = "python-terminal-no-receipt-" + terminal_status.lower()
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "agentDecisionCadence": "EVENT_ONLY",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            },
            status=terminal_status,
            phase=terminal_status,
            progress=1.0,
            bundle_ready=True,
            log_tail=[],
        )

    service.finalize_clock_driven_policy_wind_tunnel_run(
        run_id,
        {
            "action": "FINALIZE",
            "operationId": service.expected_finalization_operation_id(run_id),
        },
    )

    assert service.policy_run_record(run_id)["status"] == terminal_status
    receipt = service.load_finalization_receipt(run_id)
    assert receipt["state"] == service.FINALIZATION_RECEIPT_PUBLISHED
    assert receipt["runSnapshot"]["status"] == terminal_status
    assert receipt["completionNotificationState"] == service.FINALIZATION_NOTIFICATION_NOT_REQUIRED
    assert receipt["completionNotificationPayload"] == {}
    with pytest.raises(service.ProtocolError) as conflict:
        service.finalize_clock_driven_policy_wind_tunnel_run(
            run_id,
            {
                "action": "FINALIZE",
                "operationId": service.expected_finalization_operation_id(run_id),
                "contradictory": True,
            },
        )
    assert conflict.value.code == "finalization_operation_conflict"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_v2_completed_run_without_receipt_fails_closed(tmp_path, monkeypatch) -> None:
    run_id = "python-completed-no-receipt"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            },
            status="COMPLETED",
            phase="COMPLETED",
            progress=1.0,
            bundle_ready=True,
            log_tail=[],
        )

    with pytest.raises(service.ProtocolError) as error:
        service.finalize_clock_driven_policy_wind_tunnel_run(
            run_id,
            {
                "action": "FINALIZE",
                "operationId": service.expected_finalization_operation_id(run_id),
            },
        )

    assert error.value.code == "finalization_receipt_missing"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_legacy_v1_finalization_rejects_v2_operation_id(tmp_path, monkeypatch) -> None:
    run_id = "python-legacy-v1-operation-rejected"
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V1,
            },
            status="FAILED",
            phase="FAILED",
            progress=1.0,
            bundle_ready=True,
            log_tail=[],
        )

    with pytest.raises(service.ProtocolError) as error:
        service.finalize_clock_driven_policy_wind_tunnel_run(
            run_id,
            {
                "action": "FINALIZE",
                "operationId": service.expected_finalization_operation_id(run_id),
            },
        )

    assert error.value.code == "legacy_finalization_operation_not_supported"
    assert not service.finalization_receipt_path(run_id).exists()
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_finalize_completion_does_not_overwrite_cancelled_run(tmp_path, monkeypatch) -> None:
    run_id = "python-finalize-cancelled-during-build-run"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "simulationHorizonDays": 1,
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            },
            status="RUNNING",
            phase="CLOCK_RUNNING",
            progress=0.55,
            bundle_ready=True,
            log_tail=[],
        )
    notifications: list[tuple] = []
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    monkeypatch.setattr(
        service,
        "fetch_decision_opportunity_reconciliation",
        lambda requested_run_id: empty_opportunity_reconciliation(requested_run_id),
    )
    monkeypatch.setattr(service, "apply_playback_control_for_run", lambda *_args, **_kwargs: True)

    def cancel_during_bundle_build(
        _run_id: str,
        finalized: bool = False,
        correlation_audit: dict | None = None,
    ) -> dict:
        assert finalized is True
        service.update_policy_run(run_id, status="CANCELLED", phase="CANCELLED", progress=1.0, bundle_ready=True)
        service.append_policy_log(run_id, "Cancel requested during finalization.")
        return {
            "summary": {
                "summaryMode": "CLOCK_DRIVEN_HYBRID_FINALIZED",
                "agenticSimulation": {},
            }
        }

    monkeypatch.setattr(service, "clock_driven_policy_wind_tunnel_bundle", cancel_during_bundle_build)
    monkeypatch.setattr(
        service,
        "notify_simulation_run_completed",
        lambda *args: notifications.append(args) or True,
    )

    response = client.post(
        f"/policy-wind-tunnel/runs/{run_id}/lifecycle",
        json={
            "action": "FINALIZE",
            "operationId": service.expected_finalization_operation_id(run_id),
        },
    )

    assert response.status_code == 200
    assert response.json()["status"] == "CANCELLED"
    assert response.json()["phase"] == "CANCELLED"
    run = service.policy_run_record(run_id)
    assert not notifications
    assert any("completion was not applied because run state changed to CANCELLED/CANCELLED" in line for line in run["logTail"])
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_driven_worker_failure_does_not_overwrite_cancelled_run(monkeypatch) -> None:
    run_id = "python-worker-failure-after-cancel"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "cohortId": "cohort-failure-after-cancel",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="QUEUED",
            progress=0.0,
            bundle_ready=False,
            log_tail=[],
        )
    monkeypatch.setattr(service, "apply_hybrid_replay_contract", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(service, "load_generated_cohort_agents_for_streaming", lambda *_args, **_kwargs: [{"agentId": "agent-1"}])
    cleared_run_ids: list[str] = []

    def fake_clear_active_agents(scoped_run_id: str) -> None:
        cleared_run_ids.append(scoped_run_id)
        service.update_policy_run(run_id, status="CANCELLED", phase="CANCELLED", progress=1.0, bundle_ready=True)
        raise RuntimeError("late reset failure")

    monkeypatch.setattr(service, "clear_active_agents", fake_clear_active_agents)

    service.execute_clock_driven_policy_wind_tunnel_run(run_id)

    run = service.policy_run_record(run_id)
    assert cleared_run_ids == [run_id]
    assert run["status"] == "CANCELLED"
    assert run["phase"] == "CANCELLED"
    assert any(
        "Run worker failed after lifecycle moved away from a failable state; preserving current run state."
        in line
        for line in run["logTail"]
    )
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_driven_worker_failure_does_not_overwrite_paused_run(monkeypatch) -> None:
    run_id = "python-worker-failure-after-pause"
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS[run_id] = service.new_policy_run(
            run_id,
            "hardship-relief",
            {
                "executionMode": "CLOCK_DRIVEN_HYBRID",
                service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
                "runPath": "CLOCK_DRIVEN_SIMULATION",
                "agentDecisionMode": "STUB_LLM",
                "cohortId": "cohort-failure-after-pause",
                "simulationHorizonDays": 1,
            },
            status="RUNNING",
            phase="QUEUED",
            progress=0.0,
            bundle_ready=False,
            log_tail=[],
        )
    monkeypatch.setattr(service, "apply_hybrid_replay_contract", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(service, "load_generated_cohort_agents_for_streaming", lambda *_args, **_kwargs: [{"agentId": "agent-1"}])
    cleared_run_ids: list[str] = []

    def fake_clear_active_agents(scoped_run_id: str) -> None:
        cleared_run_ids.append(scoped_run_id)
        service.update_policy_run(
            run_id,
            status="PAUSED",
            phase="PAUSED",
            progress=0.12,
            bundle_ready=False,
            paused_from_phase="PREPARING_CLOCK",
        )
        raise RuntimeError("late reset failure")

    monkeypatch.setattr(service, "clear_active_agents", fake_clear_active_agents)

    service.execute_clock_driven_policy_wind_tunnel_run(run_id)

    run = service.policy_run_record(run_id)
    assert cleared_run_ids == [run_id]
    assert run["status"] == "PAUSED"
    assert run["phase"] == "PAUSED"
    assert any(
        "Run worker failed after lifecycle moved away from a failable state; preserving current run state."
        in line
        for line in run["logTail"]
    )
    with service._POLICY_WIND_TUNNEL_LOCK:
        service._POLICY_WIND_TUNNEL_RUNS.pop(run_id, None)


def test_clock_driven_artifact_writer_persists_scoped_hybrid_audit(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)

    def reject_unscoped_audit(run_id: str | None = None, **_: object) -> dict:
        if run_id is None:
            raise AssertionError("hybrid audit fallback must stay run-scoped")
        return {"agentCount": 0, "runId": run_id}

    monkeypatch.setattr(service, "load_runtime_decision_audit", reject_unscoped_audit)
    summary = {
        "hybrid": {
            "agentAudit": {
                "agentCount": 1,
                "runId": "run-python-1",
            }
        }
    }

    service.write_clock_driven_artifact_files("run-python-1", summary)

    artifact = tmp_path.joinpath(
        "output",
        "experiments",
        "banking-python-scenario-lab",
        "runs",
        "run-python-1",
        "summary",
        "hybrid-agent-audit.json",
    )
    payload = json.loads(artifact.read_text(encoding="utf-8"))
    assert payload == {"agentCount": 1, "runId": "run-python-1"}


def test_clock_driven_artifact_writer_uses_compact_audit_fallback(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)

    def compact_audit(run_id: str | None = None, *, include_decisions: bool = True) -> dict:
        assert run_id == "run-python-compact"
        assert include_decisions is False
        return {"agentCount": 0, "runId": run_id, "decisionsOmitted": True}

    monkeypatch.setattr(service, "load_runtime_decision_audit", compact_audit)

    service.write_clock_driven_artifact_files("run-python-compact", {})

    artifact = tmp_path.joinpath(
        "output",
        "experiments",
        "banking-python-scenario-lab",
        "runs",
        "run-python-compact",
        "summary",
        "hybrid-agent-audit.json",
    )
    payload = json.loads(artifact.read_text(encoding="utf-8"))
    assert payload == {"agentCount": 0, "runId": "run-python-compact", "decisionsOmitted": True}


def test_agentic_summary_explains_admitted_turns_without_audited_decisions() -> None:
    summary = service.build_agentic_simulation_summary(
        {
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "DAILY",
            "agentDecisionIntervalMinutes": 24 * 60,
            "simulationHorizonDays": 1,
            "playbackRate": 14400,
        },
        100,
        {
            "summary": {
                "agentCount": 1,
                "decisionCount": 0,
                "fallbackCount": 0,
                "decisionOpportunityCount": 3,
                "decisionAdmittedCount": 2,
                "decisionDeferredCount": 1,
                "decisionDeferredByReason": {"busyness_probability_skip": 1},
            }
        },
        {},
        {},
        finalized=False,
    )

    assert summary["actualDecisionCount"] == 0
    assert summary["observedDecisionOpportunities"] == 3
    assert summary["admittedDecisionOpportunities"] == 2
    assert "runtime checked 3" in summary["decisionCoverageExplanation"]
    assert "admitted 2" in summary["decisionCoverageExplanation"]
    assert "captured 0 audited" in summary["decisionCoverageExplanation"]


def test_agentic_summary_preserves_inconsistent_admission_evidence() -> None:
    summary = service.build_agentic_simulation_summary(
        {
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "DAILY",
            "agentDecisionIntervalMinutes": 24 * 60,
            "simulationHorizonDays": 1,
            "playbackRate": 14400,
        },
        100,
        {
            "summary": {
                "agentCount": 1,
                "decisionCount": 3,
                "fallbackCount": 0,
                "decisionOpportunityCount": 4,
                "decisionAdmittedCount": 2,
                "decisionDeferredCount": 2,
                "decisionDeferredByReason": {"busyness_probability_skip": 2},
            }
        },
        {},
        {},
        finalized=True,
    )

    assert summary["observedDecisionOpportunities"] == 4
    assert summary["admittedDecisionOpportunities"] == 2
    assert summary["actualDecisionCount"] == 3
    assert summary["deferredDecisionOpportunities"] == 2
    assert summary["evidenceIntegrityWarnings"][0]["code"] == "AUDITED_DECISIONS_EXCEED_ADMITTED_TURNS"
    assert "Evidence integrity warning" in summary["decisionCoverageExplanation"]


def test_finalized_agentic_summary_uses_reconciled_opportunity_ledger_truth() -> None:
    run_id = "run-python-opportunity-reconciled"
    status_counts = {
        "PENDING": 0,
        "RUNNING": 0,
        "SUCCEEDED": 2,
        "DEFERRED": 0,
        "BLOCKED": 0,
        "FAILED": 0,
        "CANCELLED": 0,
    }
    reconciliation = dict(empty_opportunity_reconciliation(run_id))
    reconciliation.update(
        {
            "status": "RECONCILED",
            "expectedGeneratedOpportunities": 2,
            "generatedOpportunities": 2,
            "terminalOpportunities": 2,
            "validDecisions": 2,
            "statusCounts": status_counts,
        }
    )
    agent_audit = {
        "schemaVersion": "customer_decision_opportunity_agent_audit.v1",
        "simulationRunId": run_id,
        "agentId": "runtime-1",
        "generatedOpportunities": 2,
        "terminalOpportunities": 2,
        "remainingBacklog": 0,
        "admittedOpportunities": 2,
        "totalAttempts": 2,
        "validDecisions": 2,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": status_counts,
        "deferredReasonCounts": {},
        "historicalDeferralCount": 0,
        "historicalDeferralReasonCounts": {},
    }
    opportunity_ledger = service.build_opportunity_ledger_totals(
        [{"opportunityAudit": agent_audit}]
    )

    summary = service.build_agentic_simulation_summary(
        {
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "DAILY",
            "agentDecisionIntervalMinutes": 24 * 60,
            "simulationHorizonDays": 30,
            "playbackRate": 3600,
        },
        100,
        {
            "summary": {
                "agentCount": 1,
                "decisionCount": 30,
                "decisionOpportunityCount": 3000,
                "decisionAdmittedCount": 30,
                "opportunityLedger": opportunity_ledger,
            },
            "agents": [{"opportunityAudit": agent_audit}],
        },
        {},
        {},
        finalized=True,
        simulation_run_id=run_id,
        opportunity_reconciliation=reconciliation,
    )

    assert summary["decisionOpportunitySource"] == "CUSTOMER_DECISION_OPPORTUNITY_LEDGER"
    assert summary["expectedDecisionOpportunities"] == 2
    assert summary["observedDecisionOpportunities"] == 2
    assert summary["admittedDecisionOpportunities"] == 2
    assert summary["actualDecisionCount"] == 2
    assert summary["decisionCoverageStatus"] == "CAPTURED"
    assert summary["opportunityAdmissionCountVerified"] is True


def test_reconciled_run_with_incomplete_agent_artifact_is_partial() -> None:
    run_id = "run-python-incomplete-agent-audit"
    reconciliation = dict(empty_opportunity_reconciliation(run_id))
    complete_empty_ledger = service.build_opportunity_ledger_totals([])
    incomplete_ledger = dict(complete_empty_ledger)
    incomplete_ledger["agentCount"] = 1

    partial, verified, reason = service.assess_opportunity_evidence(
        True,
        {"opportunityLedger": incomplete_ledger, "agents": []},
        run_id,
        reconciliation,
    )

    assert partial is True
    assert verified is False
    assert "complete, run-scoped per-agent audit" in reason


def test_positive_legacy_unspecified_debt_makes_reconciled_report_partial() -> None:
    run_id = "run-python-legacy-reason-debt"
    reconciliation = dict(empty_opportunity_reconciliation(run_id))
    reconciliation.update({
        "status": "RECONCILED",
        "expectedGeneratedOpportunities": 1,
        "generatedOpportunities": 1,
        "terminalOpportunities": 1,
        "validDecisions": 1,
        "statusCounts": {
            "PENDING": 0,
            "RUNNING": 0,
            "SUCCEEDED": 1,
            "DEFERRED": 0,
            "BLOCKED": 0,
            "FAILED": 0,
            "CANCELLED": 0,
        },
    })
    legacy_audit = {
        "schemaVersion": "customer_decision_opportunity_agent_audit.v1",
        "simulationRunId": run_id,
        "agentId": "runtime-legacy",
        "generatedOpportunities": 1,
        "terminalOpportunities": 1,
        "remainingBacklog": 0,
        "admittedOpportunities": 1,
        "totalAttempts": 1,
        "validDecisions": 1,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": reconciliation["statusCounts"],
        "deferredReasonCounts": {},
        "historicalDeferralCount": 1,
        "historicalDeferralReasonCounts": {"unspecified": 1},
    }
    agents = [{"opportunityAudit": legacy_audit}]
    opportunity_ledger = service.build_opportunity_ledger_totals(agents)

    partial, verified, _reason = service.assess_opportunity_evidence(
        True,
        {"opportunityLedger": opportunity_ledger, "agents": agents},
        run_id,
        reconciliation,
    )

    assert opportunity_ledger["invalidAgentAuditCount"] == 1
    assert partial is True
    assert verified is False


def test_opportunity_audit_rejects_missing_and_malformed_reason_maps() -> None:
    valid_audit = {
        "schemaVersion": "customer_decision_opportunity_agent_audit.v1",
        "simulationRunId": "run-python-reason-validation",
        "agentId": "runtime-1",
        "generatedOpportunities": 0,
        "terminalOpportunities": 0,
        "remainingBacklog": 0,
        "admittedOpportunities": 0,
        "totalAttempts": 0,
        "validDecisions": 0,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": {status: 0 for status in service.OPPORTUNITY_LIFECYCLE_STATUSES},
        "deferredReasonCounts": {},
        "historicalDeferralCount": 0,
        "historicalDeferralReasonCounts": {},
    }
    assert service.opportunity_agent_audit_is_valid(valid_audit) is True

    legacy_audit = dict(valid_audit)
    legacy_audit["deferredReasonCounts"] = {"unspecified": 0}
    legacy_audit["historicalDeferralReasonCounts"] = {"unspecified": 0}
    assert service.opportunity_agent_audit_is_valid(legacy_audit) is True

    for field in ("deferredReasonCounts", "historicalDeferralReasonCounts"):
        missing = dict(valid_audit)
        missing.pop(field)
        assert service.opportunity_agent_audit_is_valid(missing) is False

        for invalid in (
            None,
            "not-an-object",
            {"": 0},
            {" provider_unavailable ": 0},
            {"x" * 129: 0},
            {"valid_customer_decision_turn": 0},
            {"future_provider_pause": 0},
            {"provider_unavailable_\u00e9": 0},
            {"unspecified": 1},
        ):
            malformed = dict(valid_audit)
            malformed[field] = invalid
            assert service.opportunity_agent_audit_is_valid(malformed) is False


def test_opportunity_audit_deferred_reasons_match_v1_lifecycle_contract() -> None:
    assert service.OPPORTUNITY_DEFERRED_REASON_CODES_V1 == frozenset({
        "background_admission_limit",
        "background_turn_capacity_timeout",
        "background_turn_capacity_interrupted",
        "permit_timeout",
        "autonomous_backfill_window_limit",
        "agent_not_active",
        "worker_lease_expired",
        "worker_transient_error",
        "provider_pacing_limit",
        "provider_unavailable",
        "provider_rate_limited",
        "provider_timeout",
        "provider_transient_error",
        "opportunity_worker_authorization_unavailable",
    })
    assert service.LEGACY_UNSPECIFIED_DEFERRED_REASON_CODE == "unspecified"
    assert service.strict_opportunity_reason_counts({"unspecified": 0}) == {"unspecified": 0}
    assert service.strict_opportunity_reason_counts({"unspecified": 1}) is None


def test_runtime_decision_audit_does_not_truncate_agents(monkeypatch) -> None:
    monkeypatch.setattr(
        service,
        "get_json",
        lambda _url: [
            {"agentId": f"agent-{index}", "eventCount": 0, "fallbackCount": 0}
            for index in range(101)
        ],
    )

    audit = service.load_runtime_decision_audit("run-python-101", include_decisions=False)

    assert len(audit["agents"]) == 101
    assert audit["summary"]["agentCount"] == 101


def test_runtime_decision_audit_settle_tracks_window_counters(monkeypatch) -> None:
    audits = [
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 0,
                "decisionOpportunityCount": 0,
                "decisionAdmittedCount": 0,
                "decisionDeferredCount": 0,
                "fallbackCount": 0,
            }
        },
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 0,
                "decisionOpportunityCount": 0,
                "decisionAdmittedCount": 0,
                "decisionDeferredCount": 0,
                "fallbackCount": 0,
            }
        },
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 0,
                "decisionOpportunityCount": 0,
                "decisionAdmittedCount": 0,
                "decisionDeferredCount": 0,
                "fallbackCount": 0,
            }
        },
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 2,
                "decisionOpportunityCount": 72,
                "decisionAdmittedCount": 0,
                "decisionDeferredCount": 72,
                "fallbackCount": 0,
            }
        },
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 2,
                "decisionOpportunityCount": 72,
                "decisionAdmittedCount": 0,
                "decisionDeferredCount": 72,
                "fallbackCount": 0,
            }
        },
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 2,
                "decisionOpportunityCount": 72,
                "decisionAdmittedCount": 48,
                "decisionDeferredCount": 24,
                "fallbackCount": 0,
            }
        },
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 2,
                "decisionOpportunityCount": 72,
                "decisionAdmittedCount": 48,
                "decisionDeferredCount": 24,
                "fallbackCount": 0,
            }
        },
        {
            "summary": {
                "agentCount": 100,
                "decisionCount": 12,
                "decisionWindowCount": 2,
                "decisionOpportunityCount": 72,
                "decisionAdmittedCount": 48,
                "decisionDeferredCount": 24,
                "fallbackCount": 0,
            }
        },
    ]
    calls = {"count": 0}

    def load_audit(run_id: str | None = None, *, include_decisions: bool = True) -> dict:
        assert run_id == "settle-python-run"
        assert include_decisions is False
        index = min(calls["count"], len(audits) - 1)
        calls["count"] += 1
        return audits[index]

    monkeypatch.setattr(service, "load_runtime_decision_audit", load_audit)
    monkeypatch.setattr(
        service,
        "wait_for_runtime_tick_drain",
        lambda run_id=None: service.runtime_tick_drain_result(True, "DRAINED", "verified"),
    )
    monkeypatch.setattr(service, "HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MS", 1_000)
    monkeypatch.setattr(service, "HYBRID_DECISION_AUDIT_SETTLE_POLL_MS", 0)
    monkeypatch.setattr(service, "HYBRID_DECISION_AUDIT_STABLE_SAMPLES", 2)

    settled = service.wait_for_runtime_decision_audit_to_settle("settle-python-run")

    assert calls["count"] == 8
    assert settled["summary"]["decisionCount"] == 12
    assert settled["summary"]["decisionWindowCount"] == 2
    assert settled["summary"]["decisionOpportunityCount"] == 72
    assert settled["summary"]["decisionAdmittedCount"] == 48
    assert settled["runtimeTickDrain"]["verified"] is True


@pytest.mark.parametrize(
    ("decision_mode", "decision_cadence"),
    [
        (service.AGENT_DECISION_MODE_STUB_LLM, service.AGENT_DECISION_CADENCE_EVENT_ONLY),
        (service.AGENT_DECISION_MODE_REAL_LLM, service.AGENT_DECISION_CADENCE_DAILY),
    ],
)
def test_finalized_runtime_decision_audit_fetches_full_rows_after_compact_settle(
    monkeypatch,
    decision_mode: str,
    decision_cadence: str,
) -> None:
    compact = {
        "agents": [{"agentId": "agent-1", "decisionsOmitted": True}],
        "runtimeTickDrain": {"verified": True, "status": "DRAINED"},
    }
    full = {
        "agents": [{
            "agentId": "agent-1",
            "decisionsOmitted": False,
            "decisions": [
                {"opportunityId": "opportunity-1"},
                {"opportunityId": "opportunity-2"},
            ],
        }],
    }
    full_fetches: list[bool] = []

    monkeypatch.setattr(
        service,
        "wait_for_runtime_decision_audit_to_settle",
        lambda run_id: compact if run_id == "finalized-full-run" else {},
    )

    def load_audit(run_id: str | None = None, *, include_decisions: bool = True) -> dict:
        assert run_id == "finalized-full-run"
        full_fetches.append(include_decisions)
        return dict(full)

    monkeypatch.setattr(service, "load_runtime_decision_audit", load_audit)

    audit = service.finalized_runtime_decision_audit(
        "finalized-full-run",
        {
            "agentDecisionMode": decision_mode,
            "agentDecisionCadence": decision_cadence,
        },
    )

    assert full_fetches == [True]
    assert len(audit["agents"][0]["decisions"]) == 2
    assert audit["runtimeTickDrain"] == compact["runtimeTickDrain"]


def test_finalized_runtime_decision_audit_keeps_legacy_stub_compact(monkeypatch) -> None:
    compact = {
        "agents": [{"agentId": "agent-1", "decisionsOmitted": True}],
        "runtimeTickDrain": {"verified": True, "status": "DRAINED"},
    }
    monkeypatch.setattr(service, "wait_for_runtime_decision_audit_to_settle", lambda _run_id: compact)
    monkeypatch.setattr(
        service,
        "load_runtime_decision_audit",
        lambda *_args, **_kwargs: pytest.fail("legacy Stub finalization must not fetch full decision rows"),
    )

    audit = service.finalized_runtime_decision_audit(
        "legacy-stub-run",
        {
            "agentDecisionMode": service.AGENT_DECISION_MODE_STUB_LLM,
            "agentDecisionCadence": service.AGENT_DECISION_CADENCE_DAILY,
        },
    )

    assert audit is compact


def test_runtime_tick_drain_waits_for_agent_backlog(monkeypatch) -> None:
    telemetry = [
        {"scope": "simulationRun", "simulationRunId": "python-drain-run", "activeTickTasks": 1, "runningAgents": 1, "pendingAgents": 0},
        {"scope": "simulationRun", "simulationRunId": "python-drain-run", "activeTickTasks": 0, "runningAgents": 0, "pendingAgents": 2},
        {"scope": "simulationRun", "simulationRunId": "python-drain-run", "activeTickTasks": 0, "runningAgents": 0, "pendingAgents": 0},
        {"scope": "simulationRun", "simulationRunId": "python-drain-run", "activeTickTasks": 0, "runningAgents": 0, "pendingAgents": 0},
    ]
    calls = {"count": 0}

    def get_telemetry(method: str, url: str, **kwargs: Any) -> dict:
        assert method == "GET"
        assert url.endswith("/api/agents/runtime-state?simulationRunId=python-drain-run")
        index = min(calls["count"], len(telemetry) - 1)
        calls["count"] += 1
        return telemetry[index]

    monkeypatch.setattr(service, "http_json", get_telemetry)
    monkeypatch.setattr(service, "HYBRID_DECISION_AUDIT_SETTLE_TIMEOUT_MS", 1_000)
    monkeypatch.setattr(service, "HYBRID_DECISION_AUDIT_SETTLE_POLL_MS", 0)
    monkeypatch.setattr(service, "RUNTIME_TICK_DRAIN_STABLE_SAMPLES", 2)

    result = service.wait_for_runtime_tick_drain("python-drain-run")

    assert calls["count"] == 4
    assert result["verified"] is True
    assert result["status"] == "DRAINED"


def test_runtime_tick_drain_rejects_unscoped_runtime_state(monkeypatch) -> None:
    calls = {"count": 0}

    def get_telemetry(method: str, url: str, **kwargs: Any) -> dict:
        assert method == "GET"
        assert url.endswith("/api/agents/runtime-state?simulationRunId=python-drain-run")
        calls["count"] += 1
        return {
            "scope": "global",
            "activeTickTasks": 0,
            "runningAgents": 0,
            "pendingAgents": 0,
        }

    monkeypatch.setattr(service, "http_json", get_telemetry)
    monkeypatch.setattr(service, "HYBRID_DECISION_AUDIT_SETTLE_POLL_MS", 0)
    monkeypatch.setattr(service, "RUNTIME_TICK_DRAIN_STABLE_SAMPLES", 2)

    result = service.wait_for_runtime_tick_drain("python-drain-run")

    assert calls["count"] == 1
    assert result["verified"] is False
    assert result["status"] == "UNVERIFIED_SCOPE"


def test_agentic_summary_warns_when_runtime_tick_drain_unverified() -> None:
    summary = service.build_agentic_simulation_summary(
        {
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "DAILY",
            "agentDecisionIntervalMinutes": 24 * 60,
            "simulationHorizonDays": 1,
            "playbackRate": 14400,
        },
        100,
        {
            "runtimeTickDrain": service.runtime_tick_drain_result(
                False,
                "UNVERIFIED_SCOPE",
                "Runtime tick drain could not be verified because agent-runtime returned an unscoped payload.",
            ),
            "summary": {
                "agentCount": 1,
                "decisionCount": 2,
                "fallbackCount": 0,
                "decisionOpportunityCount": 3,
                "decisionAdmittedCount": 2,
                "decisionDeferredCount": 1,
            },
        },
        {},
        {},
        finalized=True,
    )

    warnings = summary["evidenceIntegrityWarnings"]
    assert warnings[0]["code"] == "RUNTIME_TICK_DRAIN_UNVERIFIED"
    assert warnings[0]["drainStatus"] == "UNVERIFIED_SCOPE"
    assert "Evidence integrity warning" in summary["decisionCoverageExplanation"]


def test_clock_driven_scores_use_checkpoint_payload_rejections(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    run_id = "python-scorecard-run"
    checkpoint_dir = tmp_path / "output" / "simulation-runs" / run_id / "checkpoints"
    checkpoint_dir.mkdir(parents=True)
    for index in range(30):
        checkpoint = {
            "nextState": {
                "data": {
                    "domainId": DOMAIN_ID,
                    "personaName": f"Rejected customer {index + 1}",
                    "lastAction": "post_double_entry_transaction",
                    "executedWorkCount": 1,
                    "appliedTransactionCount": 0,
                    "rejectedTransactionCount": 1,
                    "executionLog": [
                        {
                            "workId": f"work-{index + 1}",
                            "status": "REJECTED",
                        }
                    ],
                }
            },
            "runtimeMetadata": {"domainId": DOMAIN_ID},
            "statusView": {"customerState": {"currentBalance": 1000 - index}},
        }
        checkpoint_dir.joinpath(f"checkpoint-{index:03d}.json").write_text(json.dumps(checkpoint), encoding="utf-8")

    mutations = service.build_clock_driven_state_mutations(run_id, 100, finalized=True, ledger={"checkpoints": []})
    rejected_count = service.count_rejected_checkpoint_work(run_id, mutations)
    scores = service.build_clock_driven_domain_scores(
        100,
        50,
        timeline_event_count=10,
        state_change_count=len(mutations),
        rejected_work_count=rejected_count,
        finalized=True,
    )

    assert len(mutations) == 30
    assert {mutation["mutationType"] for mutation in mutations} == {"REMOTE_DOMAIN_WORK_REJECTED"}
    assert rejected_count == 30
    assert [(score["scorecardId"], score["status"], score["recommendation"]) for score in scores] == [
        ("banking.customer-impact.v1", "SCORED", "BLOCK"),
        ("banking.evidence-readiness.v1", "SCORED", "WATCH"),
    ]


def test_clock_driven_score_deduplicates_cumulative_rejected_checkpoints(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    run_id = "python-scorecard-repeated-rejection-run"
    checkpoint_dir = tmp_path / "output" / "simulation-runs" / run_id / "checkpoints"
    checkpoint_dir.mkdir(parents=True)
    for index in range(30):
        checkpoint = {
            "nextState": {
                "data": {
                    "domainId": DOMAIN_ID,
                    "agentId": "Repeated customer",
                    "personaName": "Repeated customer",
                    "lastAction": "post_double_entry_transaction",
                    "executedWorkCount": 1,
                    "appliedTransactionCount": 0,
                    "rejectedTransactionCount": 1,
                    "executionLog": [
                        {
                            "workId": "same-rejected-work",
                            "occurrenceKey": "same-rejected-work-2026-01",
                            "status": "REJECTED",
                        }
                    ],
                }
            },
            "runtimeMetadata": {"domainId": DOMAIN_ID},
            "statusView": {"customerState": {"agentId": "Repeated customer", "currentBalance": 1000 - index}},
        }
        checkpoint_dir.joinpath(f"checkpoint-{index:03d}.json").write_text(json.dumps(checkpoint), encoding="utf-8")

    mutations = service.build_clock_driven_state_mutations(run_id, 100, finalized=True, ledger={"checkpoints": []})
    rejected_count = service.count_rejected_checkpoint_work(run_id, mutations)
    scores = service.build_clock_driven_domain_scores(
        100,
        50,
        timeline_event_count=10,
        state_change_count=len(mutations),
        rejected_work_count=rejected_count,
        finalized=True,
    )

    assert len(mutations) == 30
    assert {mutation["mutationType"] for mutation in mutations} == {"REMOTE_DOMAIN_WORK_REJECTED"}
    assert rejected_count == 1
    assert [(score["scorecardId"], score["status"], score["recommendation"]) for score in scores] == [
        ("banking.customer-impact.v1", "SCORED", "WATCH"),
        ("banking.evidence-readiness.v1", "SCORED", "WATCH"),
    ]


def test_clock_driven_score_counts_anonymous_rejected_checkpoints_fail_open(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(service, "workspace_root", lambda: tmp_path)
    run_id = "python-scorecard-anonymous-rejection-run"
    checkpoint_dir = tmp_path / "output" / "simulation-runs" / run_id / "checkpoints"
    checkpoint_dir.mkdir(parents=True)
    for index in range(30):
        checkpoint = {
            "nextState": {
                "data": {
                    "domainId": DOMAIN_ID,
                    "agentId": "Anonymous customer",
                    "personaName": "Anonymous customer",
                    "lastAction": "post_double_entry_transaction",
                    "executedWorkCount": 1,
                    "appliedTransactionCount": 0,
                    "rejectedTransactionCount": 0,
                    "executionLog": [
                        {
                            "description": "Scheduled payment",
                            "status": "REJECTED",
                        }
                    ],
                }
            },
            "runtimeMetadata": {"domainId": DOMAIN_ID},
            "statusView": {"customerState": {"agentId": "Anonymous customer", "currentBalance": 1000 - index}},
        }
        checkpoint_dir.joinpath(f"checkpoint-{index:03d}.json").write_text(json.dumps(checkpoint), encoding="utf-8")

    mutations = service.build_clock_driven_state_mutations(run_id, 100, finalized=True, ledger={"checkpoints": []})
    rejected_count = service.count_rejected_checkpoint_work(run_id, mutations)
    scores = service.build_clock_driven_domain_scores(
        100,
        50,
        timeline_event_count=10,
        state_change_count=len(mutations),
        rejected_work_count=rejected_count,
        finalized=True,
    )

    assert len(mutations) == 30
    assert {mutation["mutationType"] for mutation in mutations} == {"REMOTE_DOMAIN_WORK_REJECTED"}
    assert rejected_count == 30
    assert [(score["scorecardId"], score["status"], score["recommendation"]) for score in scores] == [
        ("banking.customer-impact.v1", "SCORED", "BLOCK"),
        ("banking.evidence-readiness.v1", "SCORED", "WATCH"),
    ]


def test_product_catalog_matches_banking_shape_and_filters_category() -> None:
    response = client.get("/api/products")

    assert response.status_code == 200
    products = response.json()["data"]["products"]
    assert [product["productCode"] for product in products] == [
        "TS001",
        "SAV001",
        "CC001",
        "CC002",
        "HL001",
        "SL001",
        "CL001",
        "BL001",
        "TD001",
    ]

    filtered = client.get("/api/products", params={"productCategory": "CRED_AND_CHRG_CARDS"}).json()
    assert [product["productCode"] for product in filtered["data"]["products"]] == ["CC001", "CC002"]


def test_prepare_and_projection_flow_accepts_vendor_media_type() -> None:
    prepare_response = client.post(
        "/api/remote-domain/v1/prepare",
        headers={"Content-Type": REMOTE_JSON, "Accept": REMOTE_JSON},
        json=prepare_request(),
    )

    assert prepare_response.status_code == 200
    prepared = prepare_response.json()
    assert prepared["result"]["runtimeIdentity"] == f"{DOMAIN_ID}::taylor"
    assert prepared["nextState"]["schemaVersion"] == STATE_SCHEMA
    assert prepared["nextState"]["data"]["accounts"][0]["productCode"] == "TS001"
    assert prepared["nextState"]["data"]["scheduledWork"][0]["kind"] == "transaction"
    assert prepared["result"]["projection"]["statusView"]["totalBalance"] == "1250.00"
    assert prepared["result"]["projection"]["statusView"]["scheduledTransactions"][0]["description"] == "Monthly Rent"
    assert prepared["result"]["projection"]["lifecycleSchedule"][0]["frequency"] == "Monthly"

    status_response = client.post(
        "/api/remote-domain/v1/status",
        json=projection_request("status", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
    )

    assert status_response.status_code == 200
    status_body = status_response.json()
    projection = status_body["result"]["projection"]
    assert projection["statusView"]["transactionCount"] == 0
    assert projection["runtimeMetadata"]["domainProfile"] == "banking-python-remote"


def test_prepare_persists_validated_domain_decision_trigger_policy() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["decisionTriggerPolicy"] = {
        "monthly_review": {"enabled": True, "priority": "NORMAL"},
        "interest_charged": {"minimumAmount": 15},
    }

    response = client.post("/api/remote-domain/v1/prepare", json=request)

    assert response.status_code == 200
    policy = response.json()["nextState"]["data"]["decisionTriggerPolicy"]
    assert policy["monthly_review"]["enabled"] is True
    assert policy["monthly_review"]["priority"] == "NORMAL"
    assert policy["interest_charged"]["minimumAmount"] == "15.00"


def test_prepare_rejects_malformed_domain_decision_trigger_policy() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["decisionTriggerPolicy"] = {
        "monthly_review": {"enabled": "yes"},
    }

    response = client.post("/api/remote-domain/v1/prepare", json=request)

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_decision_trigger_policy"
    assert "must be boolean" in response.json()["error"]["message"]


def test_prepare_rejects_duplicate_initial_account_ids() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "duplicate-card",
            "name": "First Card",
            "productCode": "CC001",
            "balance": -1200,
            "currency": "AUD",
        },
        {
            "accountId": "duplicate-card",
            "name": "Second Card",
            "productCode": "CC001",
            "balance": -400,
            "currency": "AUD",
        },
    ]

    response = client.post("/api/remote-domain/v1/prepare", json=request)

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "duplicate_initial_account_id"
    assert "duplicate accountId" in response.json()["error"]["message"]


def test_execute_work_applies_transaction_scheduled_work_shape() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    execute_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-scheduled-transaction-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "rent-work-1",
                        "workType": "DOMAIN_COMMAND",
                        "occurrenceKey": "banking-python|rent|2026-05-01",
                        "description": "Monthly Rent",
                        "payload": {
                            "kind": "transaction",
                            "type": "Payment",
                            "amount": 250,
                            "isAmountExact": True,
                            "details": {"from": ["Everyday Saver"], "to": ["Landlord"]},
                        },
                    }
                ],
            }
        },
    }

    response = client.post("/api/remote-domain/v1/execute-work", json=execute_request)

    assert response.status_code == 200
    body = response.json()
    state = body["nextState"]["data"]
    assert state["executedWorkCount"] == 1
    assert state["appliedTransactionCount"] == 1
    assert state["accounts"][0]["balance"] == "1000.00"
    assert state["transactionPostings"][0]["occurrenceKey"] == "banking-python|rent|2026-05-01"
    assert body["result"]["evidenceEvents"][0]["eventType"] == "remote_domain_transaction_posted"


def test_execute_work_skips_unknown_monthly_interest_without_crashing() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-unknown-1",
            "name": "Unknown Account",
            "productCode": "ZZ001",
            "balance": 1200,
            "currency": "AUD",
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    execute_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-unknown-monthly-interest-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "unknown-interest-work",
                        "workType": "DOMAIN_COMMAND",
                        "occurrenceKey": "banking-python|interest|unknown|2026-04",
                        "description": "Unknown-product monthly interest",
                        "payload": {
                            "kind": "monthly_interest_accrual",
                            "annualRate": 0.03,
                        },
                    }
                ],
            }
        },
    }

    response = client.post("/api/remote-domain/v1/execute-work", json=execute_request)

    assert response.status_code == 200
    body = response.json()
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "1200.00"
    assert body["nextState"]["data"]["lastAction"] == "remote_domain_interest_checked_noop"
    assert body["result"]["evidenceEvents"] == []


def test_execute_work_applies_credit_card_interest_and_statement_close_without_drools() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "cc-interest-work",
            "kind": "credit_card_interest_accrual",
            "description": "Credit card interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.24, "accountName": "Rewards Card"},
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    assert prepared["nextState"]["data"]["scheduledWork"][0]["kind"] == "credit_card_interest_accrual"

    interest_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-cc-interest-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "cc-interest-work",
                        "workType": "DOMAIN_COMMAND",
                        "occurrenceKey": "banking-python|cc-interest|2026-04",
                        "description": "Credit card interest",
                        "payload": {
                            "kind": "credit_card_interest_accrual",
                            "accountName": "Rewards Card",
                            "annualRate": 0.24,
                            "minPaymentPercentage": 0.04,
                        },
                    }
                ],
            }
        },
    }
    interest_response = client.post("/api/remote-domain/v1/execute-work", json=interest_request)

    assert interest_response.status_code == 200
    interest_body = interest_response.json()
    interest_state = interest_body["nextState"]["data"]
    assert interest_state["accounts"][0]["balance"] == "-1224.00"
    assert interest_state["accounts"][0]["currentMinPayment"] == "48.96"
    assert interest_state["transactionPostings"][0]["eventType"] == "remote_domain_credit_card_interest_charged"
    assert interest_state["transactionPostings"][0]["interestAmount"] == "24.00"
    assert interest_body["result"]["evidenceEvents"][0]["eventType"] == "remote_domain_credit_card_interest_charged"
    interest_evidence = interest_body["result"]["evidenceEvents"][0]["payload"]
    assert interest_evidence["occurrenceKey"] == "banking-python|cc-interest|2026-04"
    assert interest_evidence["accountId"] == "acct-card-1"
    assert interest_evidence["runtimeAccountId"] == 1
    assert interest_evidence["accountName"] == "Rewards Card"
    assert interest_evidence["productCode"] == "CC001"
    assert interest_evidence["openingBalance"] == "-1200.00"
    assert interest_evidence["annualRate"] == "0.24"
    assert interest_evidence["interestAmount"] == "24.00"
    assert interest_evidence["newBalance"] == "-1224.00"
    assert interest_evidence["minimumPayment"] == "48.96"

    statement_request = {
        **projection_request("execute-work", interest_body["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-cc-statement-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "cc-statement-work",
                        "workType": "DOMAIN_COMMAND",
                        "occurrenceKey": "banking-python|cc-statement|2026-04",
                        "description": "Credit card statement close",
                        "payload": {
                            "kind": "credit_card_statement_close",
                            "accountName": "Rewards Card",
                            "statementStartDate": "2026-04-01",
                            "statementEndDate": "2026-04-30",
                            "dueDays": 14,
                            "minPaymentPercentage": 0.04,
                        },
                    }
                ],
            }
        },
    }
    statement_response = client.post("/api/remote-domain/v1/execute-work", json=statement_request)

    assert statement_response.status_code == 200
    statement_body = statement_response.json()
    statement_state = statement_body["nextState"]["data"]
    assert statement_state["appliedDomainSystemWorkCount"] == 2
    assert statement_state["creditCardBills"][0]["eventType"] == "remote_domain_credit_card_statement_closed"
    assert statement_state["creditCardBills"][0]["accountId"] == "acct-card-1"
    assert statement_state["creditCardBills"][0]["runtimeAccountId"] == 1
    assert statement_state["creditCardBills"][0]["closingAmount"] == "-1224.00"
    assert statement_state["creditCardBills"][0]["minimumAmountDue"] == "48.96"
    assert statement_state["creditCardBills"][0]["statementStartDate"] == "2026-04-01"
    assert statement_state["creditCardBills"][0]["statementEndDate"] == "2026-04-30"
    assert statement_state["creditCardBills"][0]["dueDate"] == "2026-05-14"
    assert statement_body["result"]["projection"]["statusView"]["creditCardBills"][0]["dueDate"] == "2026-05-14"
    statement_evidence = statement_body["result"]["evidenceEvents"][0]["payload"]
    assert statement_evidence["occurrenceKey"] == "banking-python|cc-statement|2026-04"
    assert statement_evidence["accountId"] == "acct-card-1"
    assert statement_evidence["runtimeAccountId"] == 1
    assert statement_evidence["accountName"] == "Rewards Card"
    assert statement_evidence["productCode"] == "CC001"
    assert statement_evidence["closingAmount"] == "-1224.00"
    assert statement_evidence["minimumAmountDue"] == "48.96"
    assert statement_evidence["statementStartDate"] == "2026-04-01"
    assert statement_evidence["statementEndDate"] == "2026-04-30"
    assert statement_evidence["dueDate"] == "2026-05-14"


def test_domain_system_work_window_executes_python_owned_credit_card_rules() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "cc-interest-work",
            "kind": "credit_card_interest_accrual",
            "description": "Credit card interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.24, "accountName": "Rewards Card"},
        },
        {
            "workId": "cc-statement-work",
            "kind": "credit_card_statement_close",
            "description": "Credit card statement close",
            "frequency": "MONTHLY",
            "payload": {
                "accountName": "Rewards Card",
                "statementStartDate": "2026-04-01",
                "statementEndDate": "2026-04-30",
                "dueDays": 14,
                "minPaymentPercentage": 0.04,
            },
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["credit_card_interest_accrual", "credit_card_statement_close"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    assert body["operation"] == "domain-system-work-window"
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "-1224.00"
    assert body["nextState"]["data"]["creditCardBills"][0]["dueDate"] == "2026-05-14"
    assert body["result"]["domainSystemWork"]["evaluatedWorkCount"] == 2
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 2
    assert [item["kind"] for item in body["result"]["domainSystemWork"]["workResults"]] == [
        "credit_card_interest_accrual",
        "credit_card_statement_close",
    ]
    assert body["result"]["domainSystemWork"]["nextDueHints"][0]["workKinds"] == [
        "credit_card_interest_accrual",
        "credit_card_statement_close",
    ]
    assert [event["eventType"] for event in body["result"]["evidenceEvents"]] == [
        "remote_domain_credit_card_interest_charged",
        "remote_domain_credit_card_statement_closed",
    ]
    interest_evidence = body["result"]["evidenceEvents"][0]["payload"]
    assert interest_evidence["occurrenceKey"] == "banking-python|domain-system|cc-interest-work|2026-04-01"
    assert interest_evidence["accountId"] == "acct-card-1"
    assert interest_evidence["runtimeAccountId"] == 1
    assert interest_evidence["accountName"] == "Rewards Card"
    assert interest_evidence["productCode"] == "CC001"
    assert interest_evidence["openingBalance"] == "-1200.00"
    assert interest_evidence["annualRate"] == "0.24"
    assert interest_evidence["interestAmount"] == "24.00"
    assert interest_evidence["newBalance"] == "-1224.00"
    assert interest_evidence["minimumPayment"] == "48.96"
    statement_evidence = body["result"]["evidenceEvents"][1]["payload"]
    assert statement_evidence["occurrenceKey"] == "banking-python|domain-system|cc-statement-work|2026-04-01"
    assert statement_evidence["accountId"] == "acct-card-1"
    assert statement_evidence["runtimeAccountId"] == 1
    assert statement_evidence["accountName"] == "Rewards Card"
    assert statement_evidence["productCode"] == "CC001"
    assert statement_evidence["closingAmount"] == "-1224.00"
    assert statement_evidence["minimumAmountDue"] == "48.96"
    assert statement_evidence["statementStartDate"] == "2026-04-01"
    assert statement_evidence["statementEndDate"] == "2026-04-30"
    assert statement_evidence["dueDate"] == "2026-05-14"
    state_deltas = body["result"]["domainSystemWork"]["stateDeltas"]
    assert state_deltas == [event["payload"] for event in body["result"]["evidenceEvents"]]
    assert state_deltas[0]["occurrenceKey"] == "banking-python|domain-system|cc-interest-work|2026-04-01"
    assert state_deltas[0]["accountId"] == "acct-card-1"
    assert state_deltas[0]["runtimeAccountId"] == 1
    assert state_deltas[0]["accountName"] == "Rewards Card"
    assert state_deltas[0]["productCode"] == "CC001"
    assert state_deltas[0]["openingBalance"] == "-1200.00"
    assert state_deltas[0]["annualRate"] == "0.24"
    assert state_deltas[0]["interestAmount"] == "24.00"
    assert state_deltas[0]["newBalance"] == "-1224.00"
    assert state_deltas[0]["minimumPayment"] == "48.96"
    assert state_deltas[1]["occurrenceKey"] == "banking-python|domain-system|cc-statement-work|2026-04-01"
    assert state_deltas[1]["accountId"] == "acct-card-1"
    assert state_deltas[1]["runtimeAccountId"] == 1
    assert state_deltas[1]["accountName"] == "Rewards Card"
    assert state_deltas[1]["productCode"] == "CC001"
    assert state_deltas[1]["closingAmount"] == "-1224.00"
    assert state_deltas[1]["minimumAmountDue"] == "48.96"
    assert state_deltas[1]["statementStartDate"] == "2026-04-01"
    assert state_deltas[1]["statementEndDate"] == "2026-04-30"
    assert state_deltas[1]["dueDate"] == "2026-05-14"
    decision_triggers = body["result"]["domainSystemWork"]["decisionTriggers"]
    assert len(decision_triggers) == 1
    statement_trigger = decision_triggers[0]
    assert statement_trigger["schemaVersion"] == "customer_decision_trigger.v1"
    assert statement_trigger["triggerId"] == (
        "statement_close:banking-python|domain-system|cc-statement-work|2026-04-01|account:acct-card-1"
    )
    assert statement_trigger["triggerType"] == "statement_close"
    assert statement_trigger["triggerRef"] == (
        "banking-python|domain-system|cc-statement-work|2026-04-01|account:acct-card-1"
    )
    assert statement_trigger["scheduledForEpochMs"] == 1_777_000_000_000
    assert statement_trigger["priority"] == "NORMAL"
    assert statement_trigger["reason"] == "Credit card statement closed"
    assert statement_trigger["evidenceRefs"] == [
        "banking-python|domain-system|cc-statement-work|2026-04-01|account:acct-card-1"
    ]
    assert statement_trigger["payload"]["policyVersion"] == "banking.customer-decision-trigger-policy.v1"
    assert statement_trigger["payload"]["numericEncoding"] == "canonical_decimal_string.v1"
    assert statement_trigger["payload"]["normalizedEventType"] == "statement_close"
    assert statement_trigger["payload"]["eventType"] == "remote_domain_credit_card_statement_closed"
    assert statement_trigger["payload"]["accountId"] == "acct-card-1"
    assert statement_trigger["payload"]["runtimeAccountId"] == "1"
    assert statement_trigger["payload"]["dueDate"] == "2026-05-14"


def test_domain_system_work_window_plans_monthly_statement_from_prepared_credit_card_account() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = []
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    mid_month_request = {
        **projection_request(
            "domain-system-work-window",
            prepared["nextState"],
            prepared["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": "auto-domain-system-window-1",
        "payload": {
            "timeWindow": {
                "previousTimeEpochMs": 1_777_000_000_000,
                "currentTimeEpochMs": 1_777_000_060_000,
            },
            "workKinds": ["credit_card_statement_close"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    before_boundary = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json=mid_month_request,
    )

    assert before_boundary.status_code == 200
    before_boundary_body = before_boundary.json()
    before_boundary_window = before_boundary_body["result"]["domainSystemWork"]
    assert before_boundary_window["evaluatedWorkCount"] == 0
    assert before_boundary_window["decisionTriggers"] == []
    assert before_boundary_window["nextDueHints"][0]["nextDueAt"] == "2026-05-01T00:00:00Z"

    just_before_may = 1_777_593_540_000
    may_boundary = 1_777_593_600_000
    boundary_request = {
        **projection_request(
            "domain-system-work-window",
            before_boundary_body["nextState"],
            before_boundary_body["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": "auto-domain-system-window-2",
        "payload": {
            "timeWindow": {
                "previousTimeEpochMs": just_before_may,
                "currentTimeEpochMs": may_boundary,
            },
            "workKinds": ["credit_card_statement_close"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }
    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=boundary_request)

    assert response.status_code == 200
    body = response.json()
    work_window = body["result"]["domainSystemWork"]
    assert work_window["evaluatedWorkCount"] == 1
    assert work_window["executedWorkCount"] == 1
    assert work_window["workResults"] == [
        {
            "workId": "auto-credit-card-statements",
            "kind": "credit_card_statement_close",
            "outcome": "APPLIED",
            "occurrenceKey": "banking-python|domain-system|auto-credit-card-statements|2026-05-01",
            "scheduledForEpochMs": may_boundary,
            "details": {
                "description": "Monthly credit card statement close",
                "evidenceEventCount": 1,
            },
        }
    ]
    assert len(work_window["decisionTriggers"]) == 1
    trigger = work_window["decisionTriggers"][0]
    assert trigger["triggerType"] == "statement_close"
    assert trigger["triggerRef"] == (
        "banking-python|domain-system|auto-credit-card-statements|2026-05-01|account:acct-card-1"
    )
    assert trigger["scheduledForEpochMs"] == may_boundary
    assert trigger["payload"]["accountId"] == "acct-card-1"
    assert trigger["payload"]["statementStartDate"] == "2026-04-01"
    assert trigger["payload"]["statementEndDate"] == "2026-04-30"

    replay_request = {
        **projection_request(
            "domain-system-work-window",
            body["nextState"],
            body["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": "auto-domain-system-window-3",
        "payload": boundary_request["payload"],
    }
    replay = client.post("/api/remote-domain/v1/domain-system-work-window", json=replay_request)

    assert replay.status_code == 200
    replay_window = replay.json()["result"]["domainSystemWork"]
    assert replay_window["evaluatedWorkCount"] == 0
    assert replay_window["executedWorkCount"] == 0
    assert replay_window["decisionTriggers"] == []
    assert replay.json()["nextState"] == body["nextState"]


def test_domain_system_work_window_enumerates_completed_statement_months_or_fails_at_capacity() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = []
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    march_15 = 1_773_532_800_000
    july_1 = 1_782_864_000_000

    def window(max_work_items: int, key: str) -> dict:
        return {
            **projection_request(
                "domain-system-work-window",
                prepared["nextState"],
                prepared["result"]["runtimeIdentity"],
            ),
            "idempotencyKey": key,
            "payload": {
                "timeWindow": {
                    "previousTimeEpochMs": march_15,
                    "currentTimeEpochMs": july_1,
                },
                "workKinds": ["credit_card_statement_close"],
                "maxWorkItems": max_work_items,
                "executionMode": "EVALUATE_AND_EXECUTE",
            },
        }

    response = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json=window(10, "auto-domain-system-multi-month-1"),
    )

    assert response.status_code == 200
    work_window = response.json()["result"]["domainSystemWork"]
    assert [result["occurrenceKey"] for result in work_window["workResults"]] == [
        "banking-python|domain-system|auto-credit-card-statements|2026-04-01",
        "banking-python|domain-system|auto-credit-card-statements|2026-05-01",
        "banking-python|domain-system|auto-credit-card-statements|2026-06-01",
        "banking-python|domain-system|auto-credit-card-statements|2026-07-01",
    ]
    assert len(work_window["decisionTriggers"]) == 4
    assert [trigger["scheduledForEpochMs"] for trigger in work_window["decisionTriggers"]] == [
        1_775_001_600_000,
        1_777_593_600_000,
        1_780_272_000_000,
        1_782_864_000_000,
    ]
    assert len({trigger["triggerId"] for trigger in work_window["decisionTriggers"]}) == 4
    assert [
        (
            trigger["payload"]["statementStartDate"],
            trigger["payload"]["statementEndDate"],
        )
        for trigger in work_window["decisionTriggers"]
    ] == [
        ("2026-03-01", "2026-03-31"),
        ("2026-04-01", "2026-04-30"),
        ("2026-05-01", "2026-05-31"),
        ("2026-06-01", "2026-06-30"),
    ]

    undersized = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json=window(3, "auto-domain-system-multi-month-2"),
    )

    assert undersized.status_code == 422
    error = undersized.json()["error"]
    assert error["code"] == "domain_system_work_window_capacity_exceeded"
    assert "cannot publish every completed monthly statement" in error["message"]


def test_auto_statement_triggers_only_for_indebted_credit_cards() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-debt-card",
            "name": "Debt Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "currency": "AUD",
        },
        {
            "accountId": "acct-credit-card",
            "name": "Credit Balance Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": 100,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = []
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    response = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json={
            **projection_request(
                "domain-system-work-window",
                prepared["nextState"],
                prepared["result"]["runtimeIdentity"],
            ),
            "idempotencyKey": "auto-domain-system-mixed-balances",
            "payload": {
                "timeWindow": {
                    "previousTimeEpochMs": 1_777_593_540_000,
                    "currentTimeEpochMs": 1_777_593_600_000,
                },
                "workKinds": ["credit_card_statement_close"],
                "maxWorkItems": 10,
                "executionMode": "EVALUATE_AND_EXECUTE",
            },
        },
    )

    assert response.status_code == 200
    body = response.json()
    evidence = body["result"]["evidenceEvents"]
    assert len(evidence) == 2
    credit_event = next(event for event in evidence if event["payload"]["accountId"] == "acct-credit-card")
    assert credit_event["payload"]["minimumAmountDue"] == "0.00"
    triggers = body["result"]["domainSystemWork"]["decisionTriggers"]
    assert len(triggers) == 1
    assert triggers[0]["payload"]["accountId"] == "acct-debt-card"


def test_domain_system_work_window_executes_python_owned_monthly_interest() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-saver-1",
            "name": "Everyday Saver",
            "productCode": "TS001",
            "productCategory": "TRANS_AND_SAVINGS_ACCOUNTS",
            "balance": 1200,
            "currency": "AUD",
        },
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -300,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "savings-interest-work",
            "kind": "monthly_interest_accrual",
            "description": "Monthly savings interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.03},
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-savings-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["monthly_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "1203.00"
    assert body["nextState"]["data"]["accounts"][1]["balance"] == "-300.00"
    assert body["result"]["domainSystemWork"]["evaluatedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["workResults"][0]["kind"] == "monthly_interest_accrual"
    assert body["result"]["domainSystemWork"]["decisionTriggers"] == []
    assert body["result"]["evidenceEvents"][0]["eventType"] == "remote_domain_interest_accrued"
    interest_evidence = body["result"]["evidenceEvents"][0]["payload"]
    assert interest_evidence["occurrenceKey"] == "banking-python|domain-system|savings-interest-work|2026-04-01"
    assert interest_evidence["annualRate"] == "0.03"
    assert interest_evidence["monthlyRate"] == "0.0025"
    assert interest_evidence["openingTotalBalance"] == "1200.00"
    assert interest_evidence["interestAmount"] == "3.00"
    assert interest_evidence["newTotalBalance"] == "1203.00"
    assert interest_evidence["affectedAccountCount"] == 1
    assert interest_evidence["accountInterest"][0]["accountId"] == "acct-saver-1"
    assert interest_evidence["accountInterest"][0]["accountName"] == "Everyday Saver"
    assert interest_evidence["accountInterest"][0]["productCode"] == "TS001"
    assert interest_evidence["accountInterest"][0]["productCategory"] == "TRANS_AND_SAVINGS_ACCOUNTS"
    assert interest_evidence["accountInterest"][0]["openingBalance"] == "1200.00"
    assert interest_evidence["accountInterest"][0]["interestAmount"] == "3.00"
    assert interest_evidence["accountInterest"][0]["newBalance"] == "1203.00"
    assert body["result"]["domainSystemWork"]["stateDeltas"] == [interest_evidence]
    execution_log = body["nextState"]["data"]["executionLog"]
    assert len(execution_log) == 1
    assert execution_log[0]["status"] == "APPLIED"
    assert execution_log[0]["kind"] == "monthly_interest_accrual"
    assert execution_log[0]["occurrenceKey"] == interest_evidence["occurrenceKey"]

    repeated_request = {
        **projection_request("domain-system-work-window", body["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-savings-window-1-repeat",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["monthly_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    repeated = client.post("/api/remote-domain/v1/domain-system-work-window", json=repeated_request).json()

    assert repeated["nextState"]["data"]["accounts"][0]["balance"] == "1203.00"
    assert repeated["result"]["domainSystemWork"]["evaluatedWorkCount"] == 0
    assert repeated["result"]["domainSystemWork"]["executedWorkCount"] == 0
    assert repeated["result"]["evidenceEvents"] == []
    assert len(repeated["nextState"]["data"]["executionLog"]) == 1


def test_monthly_interest_skips_unknown_products_without_applied_evidence() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-unknown-1",
            "name": "Unknown Account",
            "productCode": "ZZ001",
            "balance": 1200,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "unknown-interest-work",
            "kind": "monthly_interest_accrual",
            "description": "Monthly unknown-product interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.03},
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-unknown-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["monthly_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "1200.00"
    assert body["result"]["domainSystemWork"]["evaluatedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 0
    assert body["result"]["domainSystemWork"]["workResults"][0]["kind"] == "monthly_interest_accrual"
    assert body["result"]["domainSystemWork"]["workResults"][0]["outcome"] == "SKIPPED"
    assert body["result"]["evidenceEvents"] == []
    assert body["result"]["domainSystemWork"]["stateDeltas"] == []


def test_mixed_work_window_reports_skipped_and_applied_items_independently() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-unknown-1",
            "name": "Unknown Account",
            "productCode": "ZZ001",
            "balance": 1200,
            "currency": "AUD",
        },
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "unknown-interest-work",
            "kind": "monthly_interest_accrual",
            "description": "Monthly unknown-product interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.03},
        },
        {
            "workId": "cc-interest-work",
            "kind": "credit_card_interest_accrual",
            "description": "Credit card interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.24, "accountName": "Rewards Card"},
        },
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-mixed-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["monthly_interest_accrual", "credit_card_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    work_results = body["result"]["domainSystemWork"]["workResults"]
    assert [(item["kind"], item["outcome"]) for item in work_results] == [
        ("monthly_interest_accrual", "SKIPPED"),
        ("credit_card_interest_accrual", "APPLIED"),
    ]
    assert work_results[0]["details"]["evidenceEventCount"] == 0
    assert work_results[1]["details"]["evidenceEventCount"] == 1
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 1
    assert [event["eventType"] for event in body["result"]["evidenceEvents"]] == [
        "remote_domain_credit_card_interest_charged"
    ]


def test_credit_card_work_item_counts_once_when_multiple_accounts_emit_evidence() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        },
        {
            "accountId": "acct-card-2",
            "name": "Travel Card",
            "productCode": "CC002",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -600,
            "limit": 2000,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "cc-interest-work",
            "kind": "credit_card_interest_accrual",
            "description": "Credit card interest",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.24},
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-cc-multi-account-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["credit_card_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    work_result = body["result"]["domainSystemWork"]["workResults"][0]
    assert body["result"]["domainSystemWork"]["evaluatedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 1
    assert work_result["kind"] == "credit_card_interest_accrual"
    assert work_result["outcome"] == "APPLIED"
    assert work_result["details"]["evidenceEventCount"] == 2
    assert len(body["result"]["evidenceEvents"]) == 2
    assert body["nextState"]["data"]["executedWorkCount"] == 1
    assert len(body["nextState"]["data"]["executionLog"]) == 1
    assert body["nextState"]["data"]["executionLog"][0]["status"] == "APPLIED"


def test_credit_card_account_id_target_does_not_widen_to_other_cards() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        },
        {
            "accountId": "acct-card-2",
            "name": "Travel Card",
            "productCode": "CC002",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -600,
            "limit": 2000,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "targeted-cc-interest-work",
            "kind": "credit_card_interest_accrual",
            "description": "Targeted credit card interest",
            "frequency": "MONTHLY",
            "payload": {"accountId": "acct-card-1", "annualRate": 0.24},
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-cc-target-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["credit_card_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "-1224.00"
    assert body["nextState"]["data"]["accounts"][1]["balance"] == "-600.00"
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["workResults"][0]["details"]["evidenceEventCount"] == 1
    assert [event["payload"]["accountName"] for event in body["result"]["evidenceEvents"]] == ["Rewards Card"]
    evidence = body["result"]["evidenceEvents"][0]["payload"]
    assert evidence["accountId"] == "acct-card-1"
    assert evidence["runtimeAccountId"] == 1


def test_credit_card_interest_skip_does_not_emit_state_delta() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "missing-cc-interest-work",
            "kind": "credit_card_interest_accrual",
            "description": "Missing target credit card interest",
            "frequency": "MONTHLY",
            "payload": {"accountId": "acct-card-missing", "annualRate": 0.24},
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-cc-interest-missing-target-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["credit_card_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    work_result = body["result"]["domainSystemWork"]["workResults"][0]
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "-1200.00"
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 0
    assert work_result["kind"] == "credit_card_interest_accrual"
    assert work_result["outcome"] == "SKIPPED"
    assert work_result["details"]["evidenceEventCount"] == 0
    assert body["result"]["evidenceEvents"] == []
    assert body["result"]["domainSystemWork"]["stateDeltas"] == []


def test_credit_card_statement_skip_does_not_emit_state_delta() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "productCategory": "CRED_AND_CHRG_CARDS",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "missing-cc-statement-work",
            "kind": "credit_card_statement_close",
            "description": "Missing target credit card statement",
            "frequency": "MONTHLY",
            "payload": {
                "accountId": "acct-card-missing",
                "statementStartDate": "2026-04-01",
                "statementEndDate": "2026-04-30",
            },
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-cc-statement-missing-target-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["credit_card_statement_close"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    work_result = body["result"]["domainSystemWork"]["workResults"][0]
    assert "currentMinPayment" not in body["nextState"]["data"]["accounts"][0]
    assert body["nextState"]["data"]["creditCardBills"] == []
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 0
    assert work_result["kind"] == "credit_card_statement_close"
    assert work_result["outcome"] == "SKIPPED"
    assert work_result["details"]["evidenceEventCount"] == 0
    assert body["result"]["evidenceEvents"] == []
    assert body["result"]["domainSystemWork"]["stateDeltas"] == []


def test_domain_system_work_window_executes_python_owned_loan_interest() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-home-loan-1",
            "name": "Home Mortgage",
            "productCode": "HL001",
            "productCategory": "RESIDENTIAL_MORTGAGES",
            "balance": -300000,
            "currency": "AUD",
        },
        {
            "accountId": "acct-student-loan-1",
            "name": "Study Loan",
            "productCode": "SL001",
            "productCategory": "PERS_LOANS",
            "balance": -24000,
            "currency": "AUD",
        },
        {
            "accountId": "acct-car-loan-1",
            "name": "Car Loan",
            "productCode": "CL001",
            "productCategory": "PERS_LOANS",
            "balance": -12000,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "loan-interest-work",
            "kind": "loan_interest_accrual",
            "description": "Loan interest progression",
            "frequency": "MONTHLY",
            "payload": {"annualRate": 0.06, "repaymentBaseAmount": 600},
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-loan-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["loan_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "-299400.00"
    assert body["nextState"]["data"]["accounts"][1]["balance"] == "-24120.00"
    assert body["nextState"]["data"]["accounts"][2]["balance"] == "-12000.00"
    assert body["result"]["domainSystemWork"]["evaluatedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["workResults"][0]["kind"] == "loan_interest_accrual"
    assert body["result"]["domainSystemWork"]["workResults"][0]["outcome"] == "APPLIED"
    assert body["result"]["domainSystemWork"]["workResults"][0]["details"]["evidenceEventCount"] == 2
    assert [event["payload"]["loanKind"] for event in body["result"]["evidenceEvents"]] == [
        "HOME_LOAN",
        "STUDENT_LOAN",
    ]
    home_evidence = body["result"]["evidenceEvents"][0]["payload"]
    assert home_evidence["occurrenceKey"] == "banking-python|domain-system|loan-interest-work|2026-04-01"
    assert home_evidence["accountId"] == "acct-home-loan-1"
    assert home_evidence["runtimeAccountId"] == 1
    assert home_evidence["openingBalance"] == "-300000.00"
    assert home_evidence["interestAmount"] == "1500.00"
    assert home_evidence["repaymentAmount"] == "2100.00"
    assert home_evidence["principalReductionAmount"] == "600.00"
    assert home_evidence["newBalance"] == "-299400.00"
    student_evidence = body["result"]["evidenceEvents"][1]["payload"]
    assert student_evidence["accountId"] == "acct-student-loan-1"
    assert student_evidence["openingBalance"] == "-24000.00"
    assert student_evidence["interestAmount"] == "120.00"
    assert student_evidence["repaymentAmount"] == "0.00"
    assert student_evidence["newBalance"] == "-24120.00"
    assert body["nextState"]["data"]["executedWorkCount"] == 1
    assert len(body["nextState"]["data"]["executionLog"]) == 1

    repeated_request = {
        **projection_request("domain-system-work-window", body["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-loan-window-1-repeat",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["loan_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    repeated = client.post("/api/remote-domain/v1/domain-system-work-window", json=repeated_request).json()

    assert repeated["result"]["domainSystemWork"]["evaluatedWorkCount"] == 0
    assert repeated["result"]["domainSystemWork"]["executedWorkCount"] == 0
    assert repeated["nextState"]["data"]["accounts"][0]["balance"] == "-299400.00"
    assert repeated["nextState"]["data"]["accounts"][1]["balance"] == "-24120.00"
    assert repeated["result"]["evidenceEvents"] == []


def test_loan_interest_account_id_target_does_not_widen_to_other_loans() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-home-loan-1",
            "name": "Home Mortgage",
            "productCode": "HL001",
            "balance": -300000,
            "currency": "AUD",
        },
        {
            "accountId": "acct-student-loan-1",
            "name": "Study Loan",
            "productCode": "SL001",
            "balance": -24000,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "targeted-loan-interest-work",
            "kind": "loan_interest_accrual",
            "description": "Targeted loan interest progression",
            "frequency": "MONTHLY",
            "payload": {
                "accountId": "acct-home-loan-1",
                "annualRate": 0.06,
                "repaymentBaseAmount": 600,
            },
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-loan-target-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["loan_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "-299400.00"
    assert body["nextState"]["data"]["accounts"][1]["balance"] == "-24000.00"
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["workResults"][0]["details"]["evidenceEventCount"] == 1
    assert [event["payload"]["accountName"] for event in body["result"]["evidenceEvents"]] == ["Home Mortgage"]
    assert body["result"]["evidenceEvents"][0]["payload"]["accountId"] == "acct-home-loan-1"
    assert body["result"]["evidenceEvents"][0]["payload"]["runtimeAccountId"] == 1


def test_loan_interest_missing_account_id_target_has_no_state_delta() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-home-loan-1",
            "name": "Home Mortgage",
            "productCode": "HL001",
            "balance": -300000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "missing-target-loan-interest-work",
            "kind": "loan_interest_accrual",
            "description": "Missing target loan interest progression",
            "frequency": "MONTHLY",
            "payload": {
                "accountId": "acct-missing-loan",
                "annualRate": 0.06,
                "repaymentBaseAmount": 600,
            },
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    work_window_request = {
        **projection_request("domain-system-work-window", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "domain-system-loan-missing-target-window-1",
        "payload": {
            "timeWindow": {"previousTimeEpochMs": 1, "currentTimeEpochMs": 1_777_000_000_000},
            "workKinds": ["loan_interest_accrual"],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }

    response = client.post("/api/remote-domain/v1/domain-system-work-window", json=work_window_request)

    assert response.status_code == 200
    body = response.json()
    assert body["nextState"]["data"]["accounts"][0]["balance"] == "-300000.00"
    assert body["result"]["domainSystemWork"]["evaluatedWorkCount"] == 1
    assert body["result"]["domainSystemWork"]["executedWorkCount"] == 0
    assert body["result"]["domainSystemWork"]["workResults"][0]["outcome"] == "SKIPPED"
    assert body["result"]["domainSystemWork"]["workResults"][0]["details"]["evidenceEventCount"] == 0
    assert body["result"]["domainSystemWork"]["stateDeltas"] == []
    assert body["result"]["evidenceEvents"] == []


def test_control_action_records_domain_owned_intervention() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    request = {
        **projection_request("control-action", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "control-action-1",
        "payload": {
            "actionId": "broadcast-message",
            "operationId": "operator-control-action-1",
            "target": {"scope": "AGENT", "agentIds": ["agent-1"]},
            "inputs": {"message": "Branch network outage", "severity": "WARN"},
            "requestedBy": "operator",
            "reason": "Exercise domain-owned control action.",
            "simulationTimeEpochMs": 30,
        },
    }

    response = client.post("/api/remote-domain/v1/control-actions:execute", json=request)

    assert response.status_code == 200
    body = response.json()
    assert body["operation"] == "control-action"
    assert body["result"]["controlAction"]["actionId"] == "broadcast-message"
    assert body["result"]["evidenceEvents"][0]["eventType"] == "python_banking_control_action_applied"
    assert body["nextState"]["data"]["controlActions"][0]["inputs"]["message"] == "Branch network outage"
    assert body["result"]["projection"]["statusView"]["lastControlAction"]["targetScope"] == "AGENT"


def test_clock_driven_rate_change_commits_at_day_ten_before_one_correlated_trigger() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-rewards-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        },
        {
            "accountId": "acct-transaction-1",
            "name": "Everyday Account",
            "productCode": "TX001",
            "balance": 500,
            "currency": "AUD",
        },
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = []
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    snapshot = service.compile_rate_change_plan(
        "run-rate-change-001",
        "credit-apr-lift",
        rate_change_parameters(),
    )
    assert snapshot is not None
    operation_id = service.rate_change_plan_install_operation_id(snapshot)
    install_request = {
        **projection_request(
            "control-action",
            prepared["nextState"],
            prepared["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": f"control-action:{operation_id}",
        "payload": {
            "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
            "operationId": operation_id,
            "target": {
                "scope": "BROADCAST",
                "selector": {"simulationRunId": "run-rate-change-001"},
            },
            "inputs": snapshot.as_dict(),
            "requestedBy": "banking-python-policy-wind-tunnel-provider",
            "reason": "Install rate-change plan",
            "simulationTimeEpochMs": 1_777_000_000_000,
        },
    }
    installed_response = client.post(
        "/api/remote-domain/v1/control-actions:execute",
        json=install_request,
    )
    assert installed_response.status_code == 200
    installed = installed_response.json()
    installed_accounts = installed["nextState"]["data"]["accounts"]
    assert installed_accounts[0]["interestRate"] == "0.19"
    assert installed_accounts[0]["apr"] == "0.19"
    assert "interestRate" not in installed_accounts[1]
    assert "apr" not in installed_accounts[1]

    scheduled_for = snapshot.plan["scheduledForEpochMs"]
    before_request = {
        **projection_request(
            "domain-system-work-window",
            installed["nextState"],
            installed["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": "rate-change-before-day-10",
        "payload": {
            "timeWindow": {
                "previousTimeEpochMs": scheduled_for - 86_400_000,
                "currentTimeEpochMs": scheduled_for - 1,
            },
            "workKinds": [service.RATE_CHANGE_WORK_KIND],
            "maxWorkItems": 10,
            "executionMode": "EVALUATE_AND_EXECUTE",
        },
    }
    before = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json=before_request,
    ).json()
    assert before["result"]["domainSystemWork"]["evaluatedWorkCount"] == 0
    assert before["result"]["domainSystemWork"]["decisionTriggers"] == []

    day_ten_request = {
        **before_request,
        "requestId": "req-rate-change-day-10",
        "idempotencyKey": "rate-change-at-day-10",
        "state": before["nextState"],
        "payload": {
            **before_request["payload"],
            "timeWindow": {
                "previousTimeEpochMs": scheduled_for - 1,
                "currentTimeEpochMs": scheduled_for,
            },
        },
    }
    at_day_ten_response = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json=day_ten_request,
    )
    assert at_day_ten_response.status_code == 200
    at_day_ten = at_day_ten_response.json()
    changed_accounts = at_day_ten["nextState"]["data"]["accounts"]
    assert changed_accounts[0]["interestRate"] == "0.24"
    assert changed_accounts[0]["apr"] == "0.24"
    assert changed_accounts[0]["rateChangeOperationId"] == snapshot.plan["mutationOperationId"]
    assert "rateChangeOperationId" not in changed_accounts[1]
    triggers = at_day_ten["result"]["domainSystemWork"]["decisionTriggers"]
    assert len(triggers) == 1
    trigger = triggers[0]
    assert trigger["triggerType"] == "rate_or_policy_change"
    assert trigger["scheduledForEpochMs"] == scheduled_for
    assert trigger["payload"] | {
        "simulationRunId": "run-rate-change-001",
        "customerId": "banking-python::Taylor",
        "interventionId": "policy_rate_candidate_shift",
        "operationId": snapshot.plan["mutationOperationId"],
        "planDigest": snapshot.plan_digest,
        "oldRate": "0.19",
        "newRate": "0.24",
        "affectedAccountCount": "1",
    } == trigger["payload"]
    operation_receipt = at_day_ten["nextState"]["data"][
        "appliedInterventionOperations"
    ][0]
    assert operation_receipt | {
        "workId": snapshot.plan["workId"],
        "occurrenceKey": (
            "banking|clock-driven-rate-change|"
            f"{snapshot.plan['mutationOperationId']}"
        ),
        "scheduledForEpochMs": scheduled_for,
        "appliedAtEpochMs": scheduled_for,
        "affectedAccountCount": 1,
        "affectedAccountIds": ["acct-rewards-card-1"],
        "outcome": "APPLIED",
    } == operation_receipt

    contradictory_state = deepcopy(at_day_ten["nextState"])
    contradictory_state["data"]["appliedInterventionOperations"][0][
        "outcome"
    ] = "SKIPPED"
    contradictory_state["data"]["executionLog"] = []
    contradictory_request = {
        **day_ten_request,
        "requestId": "req-rate-change-contradictory-replay",
        "idempotencyKey": "rate-change-contradictory-replay",
        "state": contradictory_state,
    }
    contradictory_replay = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json=contradictory_request,
    )
    assert contradictory_replay.status_code == 200
    contradictory_result = contradictory_replay.json()["result"]["domainSystemWork"]
    assert contradictory_result["decisionTriggers"] == []
    assert contradictory_result["workResults"][0]["outcome"] == "REJECTED"

    retry_request = {
        **day_ten_request,
        "requestId": "req-rate-change-retry",
        "idempotencyKey": "rate-change-retry",
        "state": at_day_ten["nextState"],
        "payload": {
            **day_ten_request["payload"],
            "timeWindow": {
                "previousTimeEpochMs": scheduled_for,
                "currentTimeEpochMs": scheduled_for + 86_400_000,
            },
        },
    }
    retried = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json=retry_request,
    ).json()
    assert retried["result"]["domainSystemWork"]["evaluatedWorkCount"] == 0
    assert retried["result"]["domainSystemWork"]["decisionTriggers"] == []
    assert len(retried["nextState"]["data"]["appliedInterventionOperations"]) == 1

    interest_request = {
        **projection_request(
            "execute-work",
            retried["nextState"],
            retried["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": "post-rate-interest",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": scheduled_for + 86_400_000},
                "dueWork": [
                    {
                        "workId": "post-rate-interest",
                        "workType": "DOMAIN_COMMAND",
                        "occurrenceKey": "banking|interest|post-rate",
                        "scheduledForEpochMs": scheduled_for + 86_400_000,
                        "attemptNumber": 1,
                        "description": "Interest after rate change",
                        "payload": {
                            "kind": "credit_card_interest_accrual",
                            "productCode": "CC001",
                            "annualRate": "0.19",
                        },
                    }
                ],
            }
        },
    }
    interest = client.post("/api/remote-domain/v1/execute-work", json=interest_request).json()
    assert interest["result"]["evidenceEvents"][0]["payload"]["annualRate"] == "0.24"


def test_rate_change_catch_up_precedes_interest_and_uses_actual_application_time() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-rewards-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = [
        {
            "workId": "catch-up-interest",
            "kind": "credit_card_interest_accrual",
            "description": "Interest in the catch-up window",
            "payload": {
                "kind": "credit_card_interest_accrual",
                "productCode": "CC001",
                "annualRate": "0.19",
            },
        }
    ]
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    snapshot = service.compile_rate_change_plan(
        "run-rate-change-catch-up",
        "credit-apr-lift",
        rate_change_parameters(),
    )
    assert snapshot is not None
    operation_id = service.rate_change_plan_install_operation_id(snapshot)
    installed = client.post(
        "/api/remote-domain/v1/control-actions:execute",
        json={
            **projection_request(
                "control-action",
                prepared["nextState"],
                prepared["result"]["runtimeIdentity"],
            ),
            "idempotencyKey": f"control-action:{operation_id}",
            "payload": {
                "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
                "operationId": operation_id,
                "target": {
                    "scope": "BROADCAST",
                    "selector": {"simulationRunId": "run-rate-change-catch-up"},
                },
                "inputs": snapshot.as_dict(),
                "requestedBy": "banking-python-policy-wind-tunnel-provider",
                "reason": "Install rate-change plan",
                "simulationTimeEpochMs": 1_777_000_000_000,
            },
        },
    ).json()
    scheduled_for = snapshot.plan["scheduledForEpochMs"]
    actual_application_time = scheduled_for + (2 * 86_400_000)

    executed_response = client.post(
        "/api/remote-domain/v1/domain-system-work-window",
        json={
            **projection_request(
                "domain-system-work-window",
                installed["nextState"],
                installed["result"]["runtimeIdentity"],
            ),
            "idempotencyKey": "rate-change-catch-up-window",
            "payload": {
                "timeWindow": {
                    "previousTimeEpochMs": scheduled_for - 86_400_000,
                    "currentTimeEpochMs": actual_application_time,
                },
                "workKinds": [
                    service.RATE_CHANGE_WORK_KIND,
                    "credit_card_interest_accrual",
                ],
                "maxWorkItems": 10,
                "executionMode": "EVALUATE_AND_EXECUTE",
            },
        },
    )

    assert executed_response.status_code == 200
    executed = executed_response.json()
    domain_work = executed["result"]["domainSystemWork"]
    assert [result["kind"] for result in domain_work["workResults"]] == [
        service.RATE_CHANGE_WORK_KIND,
        "credit_card_interest_accrual",
    ]
    operation = executed["nextState"]["data"]["appliedInterventionOperations"][0]
    assert operation["scheduledForEpochMs"] == scheduled_for
    assert operation["effectiveAtEpochMs"] == scheduled_for
    assert operation["appliedAtEpochMs"] == actual_application_time
    trigger = domain_work["decisionTriggers"][0]
    assert trigger["scheduledForEpochMs"] == actual_application_time
    assert trigger["payload"]["scheduledForEpochMs"] == str(scheduled_for)
    assert trigger["payload"]["effectiveAtEpochMs"] == str(scheduled_for)
    assert trigger["payload"]["appliedAtEpochMs"] == str(actual_application_time)
    interest_events = [
        event["payload"]
        for event in executed["result"]["evidenceEvents"]
        if event["eventType"] == "remote_domain_credit_card_interest_charged"
    ]
    assert len(interest_events) == 1
    assert interest_events[0]["annualRate"] == "0.24"


def test_rate_change_plan_operation_reuse_with_contradictory_facts_fails_closed() -> None:
    request = prepare_request()
    request["payload"]["personaConfiguration"]["initialAccounts"] = [
        {
            "accountId": "acct-rewards-card-1",
            "name": "Rewards Card",
            "productCode": "CC001",
            "balance": -1200,
            "limit": 3000,
            "currency": "AUD",
        }
    ]
    request["payload"]["personaConfiguration"]["scheduledWork"] = []
    prepared = client.post("/api/remote-domain/v1/prepare", json=request).json()
    snapshot = service.compile_rate_change_plan(
        "run-rate-change-001",
        "credit-apr-lift",
        rate_change_parameters(),
    )
    assert snapshot is not None
    operation_id = service.rate_change_plan_install_operation_id(snapshot)
    install_request = {
        **projection_request(
            "control-action",
            prepared["nextState"],
            prepared["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": f"control-action:{operation_id}",
        "payload": {
            "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
            "operationId": operation_id,
            "target": {
                "scope": "BROADCAST",
                "selector": {"simulationRunId": "run-rate-change-001"},
            },
            "inputs": snapshot.as_dict(),
            "requestedBy": "banking-python-policy-wind-tunnel-provider",
            "reason": "Install rate-change plan",
            "simulationTimeEpochMs": 1_777_000_000_000,
        },
    }
    first = client.post("/api/remote-domain/v1/control-actions:execute", json=install_request)
    contradiction = deepcopy(install_request)
    contradiction["requestId"] = "req-rate-change-conflict"
    contradiction["state"] = first.json()["nextState"]
    contradiction["payload"]["inputs"]["planDigest"] = "0" * 64

    conflict = client.post("/api/remote-domain/v1/control-actions:execute", json=contradiction)

    assert first.status_code == 200
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "control_action_idempotency_conflict"


@pytest.mark.parametrize("selector", [{}, {"simulationRunId": "other-run"}])
def test_rate_change_plan_rejects_missing_or_cross_run_target_selector(
    selector: dict[str, Any],
) -> None:
    prepared = client.post(
        "/api/remote-domain/v1/prepare",
        json=prepare_request(),
    ).json()
    snapshot = service.compile_rate_change_plan(
        "run-rate-change-target-binding",
        "credit-apr-lift",
        rate_change_parameters(),
    )
    assert snapshot is not None
    operation_id = service.rate_change_plan_install_operation_id(snapshot)
    request = {
        **projection_request(
            "control-action",
            prepared["nextState"],
            prepared["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": f"control-action:{operation_id}",
        "payload": {
            "actionId": service.RATE_CHANGE_PLAN_ACTION_ID,
            "operationId": operation_id,
            "target": {"scope": "BROADCAST", "selector": selector},
            "inputs": snapshot.as_dict(),
            "requestedBy": "banking-python-policy-wind-tunnel-provider",
            "reason": "Install rate-change plan",
            "simulationTimeEpochMs": service.DEFAULT_SIMULATION_START_EPOCH_MS,
        },
    }

    response = client.post(
        "/api/remote-domain/v1/control-actions:execute",
        json=request,
    )

    assert response.status_code == 400
    assert "clockDrivenInterventionPlan" not in prepared["nextState"]["data"]
    assert "appliedInterventionOperations" not in prepared["nextState"]["data"]


def test_control_action_is_idempotent_by_operation_id() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    request = {
        **projection_request("control-action", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "control-action-1",
        "payload": {
            "actionId": "broadcast-message",
            "operationId": "operator-control-action-1",
            "target": {"scope": "AGENT", "agentIds": ["agent-1"]},
            "inputs": {"message": "Branch network outage", "severity": "WARN"},
            "requestedBy": "operator",
            "reason": "Exercise domain-owned control action.",
            "simulationTimeEpochMs": 30,
        },
    }

    first = client.post("/api/remote-domain/v1/control-actions:execute", json=request)
    retry_request = {
        **request,
        "requestId": "00000000-0000-4000-8000-000000000002",
        "state": first.json()["nextState"],
    }
    second = client.post("/api/remote-domain/v1/control-actions:execute", json=retry_request)

    assert first.status_code == 200
    assert second.status_code == 200
    body = second.json()
    assert len(body["nextState"]["data"]["controlActions"]) == 1
    assert body["result"]["controlAction"]["operationId"] == "operator-control-action-1"
    assert body["result"]["evidenceEvents"][0]["correlationId"] == "operator-control-action-1"


def test_control_action_rejects_operation_id_reuse_with_different_inputs() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    request = {
        **projection_request("control-action", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "control-action-1",
        "payload": {
            "actionId": "broadcast-message",
            "operationId": "operator-control-action-1",
            "target": {"scope": "AGENT", "agentIds": ["agent-1"]},
            "inputs": {"message": "Branch network outage", "severity": "WARN"},
            "requestedBy": "operator",
            "reason": "Exercise domain-owned control action.",
            "simulationTimeEpochMs": 30,
        },
    }

    first = client.post("/api/remote-domain/v1/control-actions:execute", json=request)
    conflicting_request = {
        **request,
        "requestId": "00000000-0000-4000-8000-000000000003",
        "state": first.json()["nextState"],
        "payload": {
            **request["payload"],
            "inputs": {"message": "Different message", "severity": "WARN"},
        },
    }
    conflict = client.post("/api/remote-domain/v1/control-actions:execute", json=conflicting_request)

    assert first.status_code == 200
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "control_action_idempotency_conflict"


def test_execute_work_posts_transaction_and_returns_evidence() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    execute_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "work-transaction-1",
                        "workType": "DOMAIN_COMMAND",
                        "description": "Childcare fees",
                        "payload": {
                            "workKind": "post_double_entry_transaction",
                            "amount": -42.50,
                            "currency": "AUD",
                            "description": "Childcare fees",
                        },
                    }
                ],
            }
        },
    }

    response = client.post("/api/remote-domain/v1/execute-work", json=execute_request)

    assert response.status_code == 200
    body = response.json()
    state = body["nextState"]["data"]
    assert state["transactions"][0]["description"] == "Childcare fees"
    assert state["transactions"][0]["counterparty"] == "python-banking-counterparty"
    assert body["result"]["projection"]["statusView"]["totalBalance"] == "1207.50"
    assert body["result"]["projection"]["statusView"]["transactionCount"] == 1
    assert body["result"]["evidenceEvents"][0]["eventType"] == "remote_domain_transaction_posted"


def test_execute_work_does_not_reapply_a_stable_scheduled_work_identity() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    due_work = {
        "workId": "work-transaction-stable",
        "workType": "DOMAIN_COMMAND",
        "occurrenceKey": "banking-python|transaction|2026-05-01",
        "attemptNumber": 1,
        "description": "Childcare fees once",
        "payload": {
            "workKind": "post_double_entry_transaction",
            "amount": 42.50,
            "currency": "AUD",
            "description": "Childcare fees once",
        },
    }
    first_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-scheduled-attempt-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [due_work],
            }
        },
    }

    first = client.post("/api/remote-domain/v1/execute-work", json=first_request).json()
    replay_work = {
        **due_work,
        "attemptNumber": 2,
        "occurrenceKey": "banking-python|transaction|changed-attempt-metadata",
    }
    replay_request = {
        **projection_request("execute-work", first["nextState"], first["result"]["runtimeIdentity"]),
        "requestId": "req-execute-scheduled-attempt-2",
        "idempotencyKey": "execute-scheduled-attempt-2",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [replay_work],
            }
        },
    }

    replay = client.post("/api/remote-domain/v1/execute-work", json=replay_request).json()

    assert replay["nextState"] == first["nextState"]
    assert len(replay["nextState"]["data"]["transactions"]) == 1
    assert len(replay["nextState"]["data"]["transactionPostings"]) == 1
    assert len(replay["nextState"]["data"]["executionLog"]) == 1
    assert replay["nextState"]["data"]["accounts"][0]["balance"] == "1207.50"
    assert replay["result"]["toolResult"]["idempotentReplay"] is True
    assert replay["result"]["toolResult"]["workId"] == "work-transaction-stable"
    assert replay["result"]["evidenceEvents"][0]["eventType"] == "remote_domain_tool_effect_replayed"
    assert replay["result"]["evidenceEvents"][0]["outcome"] == "NOOP"
    assert replay["result"]["evidenceEvents"][0]["workId"] == "work-transaction-stable"


def test_execute_work_reexecutes_read_only_tool_without_mutating_state() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    execute_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-read-only-attempt-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "work-products-read-only",
                        "workType": "TOOL_CALL",
                        "payload": {
                            "kind": "tool-call",
                            "toolName": "get_product_list",
                            "arguments": {},
                        },
                    }
                ],
            }
        },
    }

    first = client.post("/api/remote-domain/v1/execute-work", json=execute_request).json()
    replay_request = {
        **projection_request("execute-work", first["nextState"], first["result"]["runtimeIdentity"]),
        "requestId": "req-execute-read-only-attempt-2",
        "idempotencyKey": "execute-read-only-attempt-2",
        "payload": execute_request["payload"],
    }
    replay = client.post("/api/remote-domain/v1/execute-work", json=replay_request).json()

    assert first["nextState"] == prepared["nextState"]
    assert replay["nextState"] == first["nextState"]
    assert len(first["result"]["toolResult"]["products"]) == 9
    assert replay["result"]["toolResult"]["products"] == first["result"]["toolResult"]["products"]
    assert "idempotentReplay" not in replay["result"]["toolResult"]
    assert replay["result"]["evidenceEvents"][0]["eventType"] == "remote_domain_product_list_returned"


def test_execute_work_handles_banking_tool_calls() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    execute_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-tool-call-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "work-products-1",
                        "workType": "TOOL_CALL",
                        "payload": {
                            "kind": "tool-call",
                            "toolName": "get_product_list",
                            "arguments": {"productCategory": "TRANS_AND_SAVINGS_ACCOUNTS"},
                        },
                    },
                    {
                        "workId": "work-double-entry-1",
                        "workType": "TOOL_CALL",
                        "description": "Move funds to offset",
                        "payload": {
                            "kind": "tool-call",
                            "toolName": "post_double_entry_transaction",
                            "arguments": {
                                "debits": [{"accountName": "Everyday Saver", "amount": 50}],
                                "credits": [{"accountName": "Offset Account", "amount": 50}],
                            },
                        },
                    },
                ],
            }
        },
    }

    response = client.post("/api/remote-domain/v1/execute-work", json=execute_request)

    assert response.status_code == 200
    body = response.json()
    events = body["result"]["evidenceEvents"]
    assert [event["eventType"] for event in events] == [
        "remote_domain_product_list_returned",
        "remote_domain_double_entry_tool_call",
    ]
    state = body["nextState"]["data"]
    assert state["transactionPostings"][0]["toolName"] == "post_double_entry_transaction"
    assert state["accounts"][0]["balance"] == "1200.00"
    assert state["accounts"][1]["balance"] == "50.00"
    assert body["result"]["projection"]["statusView"]["transactionPostingCount"] == 1


def test_execute_work_does_not_reapply_a_stable_remote_tool_work_id() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    execute_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-tool-call-attempt-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [
                    {
                        "workId": "work-double-entry-stable",
                        "workType": "TOOL_CALL",
                        "description": "Move funds to offset once",
                        "payload": {
                            "kind": "tool-call",
                            "toolName": "post_double_entry_transaction",
                            "customerDecisionOpportunityAttempt": 1,
                            "arguments": {
                                "debits": [{"accountName": "Everyday Saver", "amount": 50}],
                                "credits": [{"accountName": "Offset Account", "amount": 50}],
                            },
                        },
                    }
                ],
            }
        },
    }

    first = client.post("/api/remote-domain/v1/execute-work", json=execute_request)
    assert first.status_code == 200
    first_body = first.json()

    replay_request = {
        **projection_request(
            "execute-work",
            first_body["nextState"],
            first_body["result"]["runtimeIdentity"],
        ),
        "requestId": "req-execute-work-attempt-2",
        "idempotencyKey": "execute-tool-call-attempt-2",
        "payload": execute_request["payload"],
    }
    replay_request["payload"]["turnContext"]["dueWork"][0]["payload"][
        "customerDecisionOpportunityAttempt"
    ] = 2
    replay = client.post("/api/remote-domain/v1/execute-work", json=replay_request)

    assert replay.status_code == 200
    replay_body = replay.json()
    replay_state = replay_body["nextState"]["data"]
    assert replay_body["nextState"] == first_body["nextState"]
    assert len(first_body["nextState"]["data"]["transactionPostings"]) == 1
    assert len(replay_state["transactionPostings"]) == 1
    assert replay_state["accounts"][0]["balance"] == "1200.00"
    assert replay_state["accounts"][1]["balance"] == "50.00"
    assert replay_body["result"]["toolResult"]["success"] is True
    assert replay_body["result"]["toolResult"]["idempotentReplay"] is True
    assert replay_body["result"]["evidenceEvents"][0]["eventType"] == "remote_domain_tool_effect_replayed"
    assert replay_body["result"]["evidenceEvents"][0]["outcome"] == "NOOP"


def test_rejected_double_entry_does_not_partially_mutate_or_compound_on_retry() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()
    due_work = {
        "workId": "work-double-entry-invalid",
        "workType": "TOOL_CALL",
        "description": "Reject missing credit account atomically",
        "payload": {
            "kind": "tool-call",
            "toolName": "post_double_entry_transaction",
            "arguments": {
                "debits": [{"accountName": "Everyday Saver", "amount": 50}],
                "credits": [{"accountName": "Missing Account", "amount": 50}],
            },
        },
    }
    first_request = {
        **projection_request("execute-work", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
        "idempotencyKey": "execute-invalid-tool-attempt-1",
        "payload": {
            "turnContext": {
                "timeWindow": {"currentTimeEpochMs": 30},
                "dueWork": [due_work],
            }
        },
    }

    first = client.post("/api/remote-domain/v1/execute-work", json=first_request).json()
    retry_request = {
        **projection_request(
            "execute-work",
            first["nextState"],
            first["result"]["runtimeIdentity"],
        ),
        "idempotencyKey": "execute-invalid-tool-attempt-2",
        "payload": first_request["payload"],
    }
    retry = client.post("/api/remote-domain/v1/execute-work", json=retry_request).json()

    assert first["result"]["toolResult"]["success"] is False
    assert retry["result"]["toolResult"]["success"] is False
    assert first["nextState"]["data"]["transactionPostings"] == []
    assert retry["nextState"]["data"]["transactionPostings"] == []
    assert first["nextState"]["data"]["accounts"][0]["balance"] == "1250.00"
    assert retry["nextState"]["data"]["accounts"][0]["balance"] == "1250.00"


def test_shutdown_acknowledges_request() -> None:
    prepared = client.post("/api/remote-domain/v1/prepare", json=prepare_request()).json()

    response = client.post(
        "/api/remote-domain/v1/shutdown",
        json=projection_request("shutdown", prepared["nextState"], prepared["result"]["runtimeIdentity"]),
    )

    assert response.status_code == 200
    assert response.json()["acknowledged"] is True


def test_policy_wind_tunnel_pack_exposes_python_banking_scorecards(
    tmp_path, monkeypatch
) -> None:
    monkeypatch.setenv("ASTRAFORM_WORKSPACE_ROOT", str(tmp_path))
    monkeypatch.setenv(service.SHARED_STORAGE_ENABLED_ENV, "true")
    response = client.get("/policy-wind-tunnel/pack")

    assert response.status_code == 200
    assert response.headers.get_list("X-Astraform-Provider-Revision") == [
        service.WIND_TUNNEL_PROVIDER_REVISION
    ]
    body = response.json()
    assert body["packId"] == PACK_ID
    assert body["domainId"] == DOMAIN_ID
    assert body["capabilities"]["remoteProviderReady"] is True
    assert body["capabilities"]["remoteProviderLanguage"] == "python"
    assert body["capabilities"]["executionModes"] == ["CLOCK_DRIVEN_HYBRID"]
    assert body["capabilities"]["opportunityGenerationFinalizationMode"] == "PLATFORM_OWNED_V2"
    assert body["capabilities"]["platformOwnedV2StorageReady"] is True
    assert body["capabilities"]["cohortActivationMode"] == "PLATFORM_PINNED_BYTES_V1"
    assert body["capabilities"]["domainOutcomeScorecardRoles"] == {
        "schemaVersion": "domain_outcome_scorecard_roles.v1",
        "roles": [
            {
                "scorecardId": "banking.customer-impact.v1",
                "role": "BUSINESS_OUTCOME",
            },
            {
                "scorecardId": "banking.evidence-readiness.v1",
                "role": "PROOF_GATE",
            },
        ],
    }
    assert service.finalization_artifact_root() == tmp_path.joinpath(
        "output", "experiments", "banking-python-scenario-lab", "runs"
    )
    assert not service.finalization_artifact_root().joinpath(
        ".astraform-storage-probe"
    ).exists()
    real_llm_policy = body["capabilities"]["realLlmCanaryPolicy"]
    assert real_llm_policy["schemaVersion"] == "real_llm_canary_policy.v1"
    assert real_llm_policy["maxDays"] == 30
    assert real_llm_policy["maxCalls"] == 70
    assert real_llm_policy["allowedCadences"] == ["EVENT_ONLY", "DAILY", "WEEKLY"]
    assert body["outcomeSchema"]["schemaVersion"] == "domain_outcome_schema.v1"
    assert body["outcomeScorecard"]["schemaVersion"] == "domain_outcome_scorecard.v1"
    assert [scorecard["scorecardId"] for scorecard in body["outcomeScorecards"]] == [
        "banking.customer-impact.v1",
        "banking.evidence-readiness.v1",
    ]
    assert [expression["expressionId"] for expression in body["policyExpressions"]] == [
        "banking.rate.candidate-rate-floor",
        "banking.hardship.threshold-required",
    ]
    assert all(
        scorecard["appliesTo"]["executionModes"] == ["CLOCK_DRIVEN_HYBRID"]
        for scorecard in body["outcomeScorecards"]
    )
    assert body["policyExpressions"][0]["language"] == "cel"
    assert body["controlSchema"]["properties"]["baselineRate"]["type"] == "number"


def test_policy_wind_tunnel_pack_does_not_advertise_v2_without_shared_storage(
    tmp_path, monkeypatch
) -> None:
    monkeypatch.setenv("ASTRAFORM_WORKSPACE_ROOT", str(tmp_path))
    monkeypatch.delenv(service.SHARED_STORAGE_ENABLED_ENV, raising=False)

    body = client.get("/policy-wind-tunnel/pack").json()

    assert body["capabilities"]["opportunityGenerationFinalizationMode"] == "LEGACY_V1"
    assert body["capabilities"]["platformOwnedV2StorageReady"] is False
    assert body["controlSchema"]["properties"]["candidateRate"]["type"] == "number"
    assert any(field["name"] == "baselineRate" for field in body["uiSchema"]["fields"])
    assert any(field["name"] == "candidateRate" for field in body["uiSchema"]["fields"])

    presets_response = client.get("/policy-wind-tunnel/presets")
    assert presets_response.status_code == 200
    presets = presets_response.json()["presets"]
    preset_ids = {preset["presetId"] for preset in presets}
    assert {
        "saver-repricing",
        "saver-stimulus",
        "credit-apr-lift",
        "credit-stress",
        "credit-limit-lift",
        "credit-limit-tighten",
        "low-rate-limit-tighten",
        "hardship-relief",
        "hardship-threshold-tradeoff",
        "fraud-alert-friction",
        "hardship-boost",
        "fraud-card-alert",
    }.issubset(preset_ids)
    credit_apr_lift = next(preset for preset in presets if preset["presetId"] == "credit-apr-lift")
    assert credit_apr_lift["certified"] is True
    assert credit_apr_lift["guidedEditableFields"] == ["baselineRate", "candidateRate"]
    assert credit_apr_lift["defaults"]["productCode"] == "CC001"
    assert credit_apr_lift["defaults"]["baselineRate"] == 0.19
    assert credit_apr_lift["defaults"]["candidateRate"] == 0.24


def test_policy_wind_tunnel_run_bundle_and_evidence_pack() -> None:
    parameters = service.normalized_wind_tunnel_parameters({}, "hardship-python-smoke")
    parameters[service.FINALIZATION_MODE_FIELD] = service.FINALIZATION_MODE_LEGACY_V1
    run = service.new_policy_run(
        "banking-python-wt-hardship-python-smoke",
        "hardship-python-smoke",
        parameters,
        status="COMPLETED",
        phase="COMPLETED",
        progress=1.0,
        bundle_ready=True,
        log_tail=["Historical Fast Batch fixture."],
    )
    service.store_policy_run_snapshot(run["runId"], run)

    bundle_response = client.get(f"/policy-wind-tunnel/runs/{run['runId']}/bundle")
    assert bundle_response.status_code == 200
    bundle = bundle_response.json()
    assert bundle["schemaVersion"] == "policy_wind_tunnel_bundle.v1"
    assert bundle["summarySchemaVersion"] == "policy_wind_tunnel_summary.v1"
    assert bundle["domainId"] == DOMAIN_ID
    assert bundle["packId"] == PACK_ID
    assert bundle["summary"]["metrics"]["customerCount"] == 100

    artifact_response = client.get(f"/policy-wind-tunnel/runs/{run['runId']}/artifacts/summary")
    assert artifact_response.status_code == 200
    assert artifact_response.json()["metrics"]["reviewCapacity"] == 12

    lifecycle_response = client.post(
        f"/policy-wind-tunnel/runs/{run['runId']}/lifecycle",
        json={"action": "CANCEL"},
    )
    assert lifecycle_response.status_code == 200
    lifecycle = lifecycle_response.json()
    assert lifecycle["runId"] == run["runId"]
    assert lifecycle["status"] == "COMPLETED"
    assert lifecycle["lifecycleAction"] == "CANCEL"

    evidence_pack_response = client.get(f"/policy-wind-tunnel/runs/{run['runId']}/evidence-pack")
    assert evidence_pack_response.status_code == 200
    with zipfile.ZipFile(io.BytesIO(evidence_pack_response.content)) as archive:
        assert sorted(archive.namelist()) == ["events.jsonl", "manifest.json", "scorecards.json", "summary.json"]


def test_clock_driven_wind_tunnel_100_customer_stub_mode_acceptance(
    tmp_path,
    monkeypatch,
) -> None:
    cohort_id = "banking-python-clock-driven-100-stub-acceptance"
    cohort_dir = tmp_path / "output" / "cohorts" / cohort_id
    cohort_dir.mkdir(parents=True)
    customers = [
        {
            "personaName": f"Python Clock Customer {index:03d}",
            "domainId": "banking-python",
            "agentType": "Customer",
            "provider": "stub",
            "model": "stub",
            "interactionMode": "SIMULATION",
            "traits": ["synthetic-customer", "python-acceptance"],
            "goals": [{"goalId": "keep-buffer", "description": "Keep a cash buffer"}],
            "initialAccounts": [
                {
                    "name": "Everyday",
                    "productCode": "TS001",
                    "balance": 1000 + index,
                    "currency": "AUD",
                }
            ],
            "scheduledWork": [
                {
                    "workId": f"salary-{index:03d}",
                    "description": "Salary Deposit",
                    "kind": "transaction",
                    "frequency": "FORTNIGHTLY",
                    "payload": {"type": "DEPOSIT", "amount": 1000},
                }
            ],
        }
        for index in range(100)
    ]
    (cohort_dir / "customers.json").write_text(json.dumps(customers), encoding="utf-8")
    monkeypatch.setenv("ASTRAFORM_WORKSPACE_ROOT", str(tmp_path))
    monkeypatch.setenv(service.SHARED_STORAGE_ENABLED_ENV, "true")
    activation_settings = {
        "schemaVersion": "scenario_lab_pinned_cohort_activation.v1",
        "agentDecisionMode": "STUB_LLM",
        "agentDecisionCadence": "HOURLY",
    }
    activation_settings_bytes = json.dumps(
        activation_settings,
        separators=(",", ":"),
    ).encode("utf-8")
    activation_settings_digest = hashlib.sha256(activation_settings_bytes).hexdigest()
    calls: list[tuple[str, str, object, dict[str, str]]] = []
    hybrid_decision_run_ids: list[str] = []
    simulation_time_ms = service.DEFAULT_SIMULATION_START_EPOCH_MS
    active_replay_owner = {"runId": ""}

    def audit_agents_for(run_id: str) -> list[dict]:
        return [
            {
                "personaName": f"Python Clock Customer {index:03d}",
                "segment": "acceptance",
                "eventCount": 72,
                "totalDecisionCount": 72,
                "fallbackCount": 1 if index == 0 else 0,
                "decisionWindowCount": 720,
                "decisionOpportunityCount": 720,
                "decisionAdmittedCount": 480,
                "decisionDeferredCount": 240,
                "decisionDeferredByReason": {"busyness_probability_skip": 240},
                "decisionWindowSummary": {
                    "windowCount": 720,
                    "opportunityCount": 720,
                    "admittedCount": 480,
                    "deferredCount": 240,
                    "deferredByReason": {"busyness_probability_skip": 240},
                },
                "effectiveProvider": "stub",
                "effectiveModel": "stub",
                "simulationRunId": run_id,
                "decisions": [
                    {
                        "action": "ASK_FOR_HELP",
                        "reason": "Stub decision recorded for no-token Python Scenario Lab proof.",
                        "fallbackApplied": index == 0,
                        "responseFormat": "structured",
                        "contractSchemaVersion": "hybrid_micro_decision_v1",
                        "simulationRunId": run_id,
                    }
                ],
            }
            for index in range(100)
        ]

    def fake_http_json(method, url, payload=None, *, headers=None, timeout=30.0):
        calls.append((method, url, payload, headers or {}))
        if "/api/agents/runtime-state" in url:
            query_run_id = parse_qs(urlparse(url).query).get("simulationRunId", [""])[0]
            assert query_run_id == run["runId"]
            return {
                "schemaVersion": "agent_runtime_state_v1",
                "scope": "simulationRun",
                "simulationRunId": query_run_id,
                "activeAgents": 100,
                "activeTickTasks": 0,
                "runningAgents": 0,
                "pendingAgents": 0,
                "idle": True,
            }
        return {}

    def fake_post_json(url, payload, *, headers=None):
        calls.append(("POST", url, payload, headers or {}))
        return {}

    def fake_get_json(url):
        if url.endswith("/api/clock/rate/info"):
            return {"max": 14400.0}
        if url.endswith("/api/clock/replay"):
            return {"executionContext": {"runId": active_replay_owner["runId"]}} if active_replay_owner["runId"] else {}
        if url.endswith("/api/clock/time"):
            return simulation_time_ms
        if "/api/agents/hybrid-decisions" in url:
            query = parse_qs(urlparse(url).query)
            query_run_id = query.get("simulationRunId", [""])[0]
            assert query.get("includeDecisions", [""])[0] == "false"
            assert query_run_id
            hybrid_decision_run_ids.append(query_run_id)
            return audit_agents_for(query_run_id)
        if "/decision-opportunities/reconciliation" in url:
            query_run_id = url.split("/api/agents/simulation-runs/", 1)[1].split("/", 1)[0]
            return empty_opportunity_reconciliation(query_run_id)
        if "/api/simulation-runs/" in url and "/ledger" in url:
            run_id = url.split("/api/simulation-runs/", 1)[1].split("/", 1)[0].split("?", 1)[0]
            return {
                "agents": [
                    {"agentId": f"Python Clock Customer {index:03d}", "domainId": DOMAIN_ID}
                    for index in range(100)
                ],
                "events": [
                    {
                        "eventId": "evt-agentic-window-1",
                        "eventType": "agentic.decision.window",
                        "category": "agent_decision",
                        "outcome": "RECORDED",
                        "source": "agent-runtime",
                    },
                    {
                        "eventId": "evt-remote-work-1",
                        "eventType": "remote-domain.execute-work",
                        "category": "remote_domain",
                        "outcome": "RECORDED",
                        "source": "agent-runtime",
                    },
                ],
                "checkpoints": [
                    {
                        "checkpointId": "checkpoint-python-001",
                        "agentId": "Python Clock Customer 000",
                        "domainId": DOMAIN_ID,
                        "checkpointType": "remote_domain_transaction_posted",
                        "artifactRef": f"output/simulation-runs/{run_id}/checkpoints/checkpoint-python-001.json",
                    },
                    {
                        "checkpointId": "checkpoint-python-002",
                        "agentId": "Python Clock Customer 001",
                        "domainId": DOMAIN_ID,
                        "checkpointType": "remote_domain_state_checkpoint",
                        "artifactRef": f"output/simulation-runs/{run_id}/checkpoints/checkpoint-python-002.json",
                    },
                ],
            }
        if "/api/simulation-runs/" in url:
            return {"agentCount": 100, "eventCount": 2, "checkpointCount": 2}
        return {}

    monkeypatch.setattr(service, "http_json", fake_http_json)
    monkeypatch.setattr(service, "post_json", fake_post_json)
    monkeypatch.setattr(service, "get_json", fake_get_json)

    run_response = client.post(
        "/policy-wind-tunnel/runs",
        json={
            "presetId": "hardship-python-smoke",
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "HOURLY",
            "agentDecisionIntervalMinutes": 60,
            "cohortId": cohort_id,
            "cohortTargetSize": 100,
            "simulationHorizonDays": 30,
            "simulationStartEpochMs": service.DEFAULT_SIMULATION_START_EPOCH_MS,
            "activationSettingsDigest": activation_settings_digest,
            "activationSettingsSnapshot": {
                "schemaVersion": "scenario_lab_pinned_activation_settings_snapshot.v1",
                "contentType": "application/json",
                "encoding": "base64url",
                "sha256": activation_settings_digest,
                "content": base64.urlsafe_b64encode(activation_settings_bytes)
                .decode("ascii")
                .rstrip("="),
            },
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        },
    )

    assert run_response.status_code == 200
    run = run_response.json()
    active_replay_owner["runId"] = run["runId"]
    assert run["status"] == "RUNNING"
    for _ in range(100):
        status = client.get(f"/policy-wind-tunnel/runs/{run['runId']}").json()
        if status["phase"] == "CLOCK_RUNNING":
            break
        time.sleep(0.01)
    assert status["phase"] == "CLOCK_RUNNING"
    assert status["bundleReady"] is True
    assert status["parameters"]["playbackRate"] == 1800.0
    replay_calls = [call for call in calls if call[1].endswith("/api/clock/replay")]
    assert replay_calls[0][2]["expectedRunId"] == ""
    assert replay_calls[0][2]["simulationStartEpochMs"] == service.DEFAULT_SIMULATION_START_EPOCH_MS
    assert replay_calls[1][2]["expectedRunId"] == run["runId"]
    assert replay_calls[1][2]["playback"]["paused"] is False

    init_calls = [call for call in calls if call[1].endswith("/api/agents/init")]
    assert len(init_calls) == 2
    assert [len(call[2]) for call in init_calls] == [50, 50]
    _, _, _, headers = init_calls[0]
    assert headers["X-Astraform-Run-Id"] == run["runId"]
    assert headers["X-Astraform-Cohort-Id"] == cohort_id
    assert headers["X-Astraform-Init-Max-Concurrency"] == "4"
    activated_agents = [agent for call in init_calls for agent in call[2]]
    assert len(activated_agents) == 100
    assert all(agent["domainId"] == DOMAIN_ID for agent in activated_agents)
    assert all(agent["provider"] == "stub" for agent in activated_agents)
    assert all(agent["model"] == "stub" for agent in activated_agents)
    assert all(agent["interactionMode"] == "HYBRID" for agent in activated_agents)
    assert all(agent["autonomousDecisionEnabled"] is True for agent in activated_agents)
    assert all(agent["autonomousDecisionCadence"] == "HOURLY" for agent in activated_agents)
    assert all(agent["autonomousDecisionIntervalMinutes"] == 60 for agent in activated_agents)
    assert all(agent["scheduledWork"] for agent in activated_agents)
    assert all("agentDecisionMode" not in agent for agent in activated_agents)
    for index, agent in enumerate(activated_agents):
        scheduled_work = agent["scheduledWork"][0]
        assert scheduled_work["workId"] == f"salary-{index:03d}"
        assert scheduled_work["kind"] == "transaction"
        assert scheduled_work["frequency"] == "FORTNIGHTLY"
        assert scheduled_work["payload"]["type"] == "DEPOSIT"
        assert scheduled_work["payload"]["amount"] == 1000

    live_bundle = client.get(f"/policy-wind-tunnel/runs/{run['runId']}/bundle").json()
    assert live_bundle["summary"]["summaryMode"] == "CLOCK_DRIVEN_HYBRID_LIVE"
    assert live_bundle["summary"]["headline"]["simulatedSubjects"] == 100
    live_agentic = live_bundle["summary"]["agenticSimulation"]
    assert live_agentic["schemaVersion"] == "agentic_customer_simulation_summary.v1"
    assert live_agentic["agentDecisionMode"] == "STUB_LLM"
    assert live_agentic["decisionCadence"] == "HOURLY"
    assert live_agentic["simulationHorizonDays"] == 30
    assert live_agentic["configuredDurationDays"] == 30
    assert live_agentic["expectedDecisionOpportunities"] == 72000
    assert live_agentic["observedDecisionOpportunities"] == 72000
    assert live_agentic["admittedDecisionOpportunities"] == 48000
    assert live_agentic["deferredDecisionOpportunities"] == 24000
    assert live_agentic["deferredDecisionReasons"] == {"busyness_probability_skip": 24000}
    assert live_agentic["decisionAdmissionRate"] == 0.6667
    assert live_agentic["actualDecisionCount"] == 7200
    assert live_agentic["auditedAgentCount"] == 100
    assert live_agentic["fallbackCount"] == 1
    assert live_agentic["decisionCoverageStatus"] == "CAPTURING"
    assert "runtime checked 72000" in live_agentic["decisionCoverageExplanation"]
    assert "admitted 48000" in live_agentic["decisionCoverageExplanation"]
    assert "captured 7200 audited" in live_agentic["decisionCoverageExplanation"]

    simulation_time_ms = service.DEFAULT_SIMULATION_START_EPOCH_MS + int(30.25 * service.MILLIS_PER_SIMULATION_DAY)
    finalize_response = client.post(
        f"/policy-wind-tunnel/runs/{run['runId']}/lifecycle",
        json={
            "action": "FINALIZE",
            "operationId": service.expected_finalization_operation_id(run["runId"]),
        },
    )
    assert finalize_response.status_code == 200
    assert finalize_response.json()["status"] == "COMPLETED"
    finalized_bundle = client.get(f"/policy-wind-tunnel/runs/{run['runId']}/bundle").json()
    assert finalized_bundle["summary"]["summaryMode"] == "CLOCK_DRIVEN_HYBRID_FINALIZED"
    assert finalized_bundle["summary"]["domainOutcomeScores"][0]["schemaVersion"] == "domain_outcome_score.v1"
    assert finalized_bundle["summary"]["domainOutcomeScores"][0]["status"] == "SCORED"
    assert (
        finalized_bundle["summary"]["agenticSimulation"]["decisionCoverageStatus"]
        == "NO_CUSTOMER_DECISIONS_TRIGGERED"
    )
    assert finalized_bundle["summary"]["opportunityReconciliation"]["status"] == "EMPTY"
    assert finalized_bundle["summary"]["opportunityReconciliation"]["ledgerReconciled"] is True
    assert (
        finalized_bundle["summary"]["agenticSimulation"]["opportunityReconciliation"]
        == finalized_bundle["summary"]["opportunityReconciliation"]
    )
    assert (
        finalized_bundle["summary"]["agenticSimulation"]["decisionOpportunitySource"]
        == "CUSTOMER_DECISION_OPPORTUNITY_LEDGER"
    )
    assert finalized_bundle["summary"]["agenticSimulation"]["expectedDecisionOpportunities"] == 0
    assert finalized_bundle["summary"]["agenticSimulation"]["observedDecisionOpportunities"] == 0
    assert finalized_bundle["summary"]["agenticSimulation"]["admittedDecisionOpportunities"] == 0
    assert finalized_bundle["summary"]["agenticSimulation"]["actualDecisionCount"] == 0
    assert finalized_bundle["summary"]["runConfig"]["configuredDurationDays"] == 30
    assert finalized_bundle["summary"]["runConfig"]["actualDurationDays"] == 30.25
    assert finalized_bundle["summary"]["runConfig"]["horizonOvershootDays"] == 0.25
    assert finalized_bundle["summary"]["runConfig"]["durationStatus"] == "HORIZON_OVERRUN"
    assert finalized_bundle["summary"]["simulationTimeline"]["actualDurationDays"] == 30.25
    assert finalized_bundle["summary"]["simulationTimeline"]["durationStatus"] == "HORIZON_OVERRUN"
    assert finalized_bundle["summary"]["agenticSimulation"]["actualDurationDays"] == 30.25
    assert finalized_bundle["summary"]["agenticSimulation"]["durationStatus"] == "HORIZON_OVERRUN"
    assert finalized_bundle["summary"]["hybridAgentAudit"]["runtimeTickDrain"]["verified"] is True
    assert finalized_bundle["summary"]["hybridAgentAudit"]["runtimeTickDrain"]["status"] == "DRAINED"
    assert finalized_bundle["summary"]["metrics"]["ledgerAgentCount"] == 100
    assert finalized_bundle["summary"]["metrics"]["ledgerEventCount"] == 2
    assert finalized_bundle["summary"]["metrics"]["ledgerCheckpointCount"] == 2
    assert not any("/decision-opportunities/generation/seal" in call[1] for call in calls)
    assert not any(
        "openai" in call[1].lower() or "embedding" in call[1].lower()
        for call in calls
    )
    simulation_time_ms = service.DEFAULT_SIMULATION_START_EPOCH_MS + int(33.75 * service.MILLIS_PER_SIMULATION_DAY)
    reread_finalized_bundle = client.get(f"/policy-wind-tunnel/runs/{run['runId']}/bundle").json()
    assert reread_finalized_bundle["summary"]["runConfig"]["actualDurationDays"] == 30.25
    assert reread_finalized_bundle["summary"]["simulationTimeline"]["actualDurationDays"] == 30.25
    assert reread_finalized_bundle["summary"]["agenticSimulation"]["actualDurationDays"] == 30.25
    assert reread_finalized_bundle["summary"]["agenticSimulation"]["durationStatus"] == "HORIZON_OVERRUN"
    assert reread_finalized_bundle["summary"]["hybridAgentAudit"]["runtimeTickDrain"]["verified"] is True
    mutations = finalized_bundle["summary"]["branchStateProjection"]["mutations"]
    assert any(mutation["mutationType"] == "REMOTE_DOMAIN_TRANSACTION_POSTED" for mutation in mutations)
    assert len(finalized_bundle["summary"]["sideEffectLedger"]["entries"]) >= 4
    audit_artifact = tmp_path.joinpath(
        "output",
        "experiments",
        "banking-python-scenario-lab",
        "runs",
        run["runId"],
        "summary",
        "hybrid-agent-audit.json",
    )
    assert json.loads(audit_artifact.read_text(encoding="utf-8"))["totals"]["decisionCount"] == 7200
    assert set(hybrid_decision_run_ids) == {run["runId"]}
    simulation_run_updates = [
        call for call in calls
        if call[0] == "PATCH" and f"/api/simulation-runs/{run['runId']}" in call[1]
    ]
    assert simulation_run_updates
    _, _, simulation_run_payload, _ = simulation_run_updates[-1]
    assert simulation_run_payload["status"] == "COMPLETED"
    assert simulation_run_payload["summaryPath"].endswith("scenario-lab-summary.json")
    assert simulation_run_payload["metadata"]["scenarioLab"]["summaryMode"] == "CLOCK_DRIVEN_HYBRID_FINALIZED"


def test_clock_driven_wind_tunnel_rejects_unbounded_agent_decision_cadence() -> None:
    response = client.post(
        "/policy-wind-tunnel/runs",
        json={
            "presetId": "hardship-python-smoke",
            "executionMode": "CLOCK_DRIVEN_HYBRID",
            service.FINALIZATION_MODE_FIELD: service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
            "agentDecisionMode": "STUB_LLM",
            "agentDecisionCadence": "CUSTOM_INTERVAL",
            "agentDecisionIntervalMinutes": 1,
            "cohortId": "banking-python-clock-driven-test-2",
            "simulationHorizonDays": 1,
        },
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_agent_decision_cadence"


def test_event_only_decision_cadence_has_no_calendar_interval() -> None:
    assert service.normalize_agent_decision_cadence("EVENT_ONLY") == "EVENT_ONLY"
    assert service.agent_decision_interval_minutes("EVENT_ONLY") == 0


def test_opportunity_reconciliation_backlog_and_evidence_gaps_are_partial() -> None:
    assert service.opportunity_reconciliation_is_partial(
        empty_opportunity_reconciliation("run-test")
    ) is False
    assert service.opportunity_reconciliation_is_partial(
        {
            "schemaVersion": "customer_decision_opportunity_reconciliation.v1",
            "simulationRunId": "run-test",
            "ledgerReconciled": True,
        }
    ) is True
    assert service.opportunity_reconciliation_is_valid(
        {
            "schemaVersion": "customer_decision_opportunity_reconciliation.v1",
            "simulationRunId": "run-test",
            "ledgerReconciled": True,
        },
        "run-test",
    ) is False
    missing_fence_proof = empty_opportunity_reconciliation("run-test")
    missing_fence_proof.pop("generationSealedReplayFenceDigest")
    assert service.opportunity_reconciliation_is_valid(
        missing_fence_proof,
        "run-test",
    ) is False


def test_opportunity_reconciliation_requires_manifest_coverage_and_tick_debt_semantics() -> None:
    manifest_open = unsealed_opportunity_reconciliation("run-open")
    manifest_open.update(
        {
            "status": "INITIALIZATION_MANIFEST_OPEN",
            "initializationManifestStatus": "OPEN",
            "expectedInitializationAgents": 2,
            "registeredInitializationAgents": 1,
            "resolvedInitializationAgents": 0,
        }
    )
    assert service.opportunity_reconciliation_is_valid(manifest_open, "run-open") is True

    false_generation_open = dict(manifest_open)
    false_generation_open["status"] = "GENERATION_OPEN"
    assert service.opportunity_reconciliation_is_valid(false_generation_open, "run-open") is False

    tick_debt = unsealed_opportunity_reconciliation("run-tick-debt")
    tick_debt.update(
        {
            "status": "TICK_FAILURE_DEBT",
            "tickFailureCount": 1,
            "tickFailureLedgerCount": 1,
        }
    )
    assert service.opportunity_reconciliation_is_valid(tick_debt, "run-tick-debt") is True

    laundered_tick_debt = dict(tick_debt)
    laundered_tick_debt["status"] = "GENERATION_OPEN"
    assert service.opportunity_reconciliation_is_valid(laundered_tick_debt, "run-tick-debt") is False

    legacy_unverified = unsealed_opportunity_reconciliation("run-legacy")
    legacy_unverified.update(
        {
            "status": "INITIALIZATION_MANIFEST_UNVERIFIED",
            "initializationManifestStatus": "LEGACY_UNVERIFIED",
            "expectedInitializationAgents": 0,
            "registeredInitializationAgents": 0,
            "resolvedInitializationAgents": 0,
        }
    )
    assert service.opportunity_reconciliation_is_valid(legacy_unverified, "run-legacy") is True

    sealed_legacy_unverified = empty_opportunity_reconciliation("run-sealed-legacy")
    sealed_legacy_unverified.update(
        {
            "status": "INITIALIZATION_MANIFEST_UNVERIFIED",
            "ledgerReconciled": False,
            "initializationManifestStatus": "LEGACY_UNVERIFIED",
            "expectedInitializationAgents": 0,
            "registeredInitializationAgents": 0,
            "resolvedInitializationAgents": 0,
        }
    )
    assert service.opportunity_reconciliation_is_valid(
        sealed_legacy_unverified,
        "run-sealed-legacy",
    ) is True

    inconsistent_tick_ledger = dict(tick_debt)
    inconsistent_tick_ledger.update(
        {
            "status": "TICK_FAILURE_LEDGER_INCONSISTENT",
            "tickFailureLedgerCount": 2,
        }
    )
    assert service.opportunity_reconciliation_is_valid(
        inconsistent_tick_ledger,
        "run-tick-debt",
    ) is True
    inconsistent_tick_ledger["status"] = "TICK_FAILURE_DEBT"
    assert service.opportunity_reconciliation_is_valid(
        inconsistent_tick_ledger,
        "run-tick-debt",
    ) is False


def empty_opportunity_reconciliation(run_id: str) -> dict:
    return {
        "schemaVersion": "customer_decision_opportunity_reconciliation.v1",
        "simulationRunId": run_id,
        "status": "EMPTY",
        "ledgerReconciled": True,
        "generationMode": "EVENT_DRIVEN_DECISION_TRIGGERS",
        "generationSealed": True,
        "observedWorkWindows": 1,
        "generationObservedAt": "2026-07-11T00:00:00Z",
        "generationSealedAt": "2026-07-12T00:00:00Z",
        "generationSealedReplayContractVersion": 7,
        "generationSealedReplayFenceDigest": "a" * 64,
        "generationSealedReplayFenceLeaseOwner": "scenario-lab-finalizer:test",
        "generationSealedReplayFenceExpiresAtEpochMs": 1_800_000_000_000,
        "initializationManifestStatus": "CLOSED",
        "expectedInitializationAgents": 1,
        "registeredInitializationAgents": 1,
        "resolvedInitializationAgents": 1,
        "tickFailureCount": 0,
        "tickFailureLedgerCount": 0,
        "expectedGeneratedOpportunities": 0,
        "rejectedTriggers": 0,
        "migrationAnomalies": 0,
        "generatedOpportunities": 0,
        "terminalOpportunities": 0,
        "remainingBacklog": 0,
        "validDecisions": 0,
        "recoverableRecordedDecisions": 0,
        "succeededWithoutDecisionEvidence": 0,
        "decisionEvidenceGaps": 0,
        "statusCounts": {
            "PENDING": 0,
            "RUNNING": 0,
            "SUCCEEDED": 0,
            "DEFERRED": 0,
            "BLOCKED": 0,
            "FAILED": 0,
            "CANCELLED": 0,
        },
        "asOf": "2026-07-12T00:00:00Z",
    }


def unsealed_opportunity_reconciliation(run_id: str) -> dict:
    reconciliation = empty_opportunity_reconciliation(run_id)
    reconciliation.update(
        {
            "ledgerReconciled": False,
            "generationSealed": False,
            "observedWorkWindows": 0,
            "generationSealedAt": None,
            "generationSealedReplayContractVersion": None,
            "generationSealedReplayFenceDigest": None,
            "generationSealedReplayFenceLeaseOwner": None,
            "generationSealedReplayFenceExpiresAtEpochMs": None,
        }
    )
    return reconciliation


def prepare_request() -> dict:
    return {
        "protocolVersion": "remote-domain.v1",
        "requestId": "req-prepare-1",
        "operation": "prepare",
        "idempotencyKey": "prepare-1",
        "sentAtEpochMs": 1,
        "deadlineEpochMs": 5000,
        "host": {"service": "agent-runtime", "instanceId": "local", "platformVersion": "0.1.0"},
        "domain": {"domainId": DOMAIN_ID},
        "agent": {
            "agentId": f"{DOMAIN_ID}::Taylor",
            "personaName": "Taylor",
            "interactionMode": "SIMULATION",
        },
        "payload": {
            "personaConfiguration": {
                "personaName": "Taylor",
                "personaDescription": "Banking Python protocol smoke-test persona",
                "traits": ["hardship"],
                "initialAccounts": [
                    {
                        "accountId": "acct-taylor-1",
                        "name": "Everyday Saver",
                        "productCode": "TS001",
                        "balance": 1250,
                        "currency": "AUD",
                    },
                    {
                        "accountId": "acct-taylor-2",
                        "name": "Offset Account",
                        "productCode": "SAV001",
                        "balance": 0,
                        "currency": "AUD",
                    }
                ],
                "scheduledWork": [
                    {
                        "workId": "rent-work-1",
                        "kind": "transaction",
                        "description": "Monthly Rent",
                        "frequency": "Monthly",
                        "time": "09:30",
                        "payload": {
                            "type": "Payment",
                            "amount": 250,
                            "day": "15",
                            "isAmountExact": True,
                            "details": {"from": ["Everyday Saver"], "to": ["Landlord"]},
                        },
                    }
                ],
            }
        },
    }


def rate_change_parameters() -> dict[str, Any]:
    return {
        "productCode": "CC001",
        "baselineRate": "0.19",
        "candidateRate": "0.24",
        "prePolicyDays": "10",
        "simulationStartEpochMs": 1_777_000_000_000,
        "baselineLimit": None,
        "candidateLimit": None,
        "candidateStimulusAmount": "0",
        "candidateCrash": "false",
        "candidateHardshipScan": "false",
        "candidateFraudAlert": "false",
    }


def projection_request(operation: str, state: dict, runtime_identity: str) -> dict:
    return {
        "protocolVersion": "remote-domain.v1",
        "requestId": f"req-{operation}",
        "operation": operation,
        "sentAtEpochMs": 10,
        "deadlineEpochMs": 5000,
        "host": {"service": "agent-runtime", "instanceId": "local", "platformVersion": "0.1.0"},
        "domain": {"domainId": DOMAIN_ID},
        "agent": {
            "agentId": f"{DOMAIN_ID}::Taylor",
            "runtimeIdentity": runtime_identity,
            "personaName": "Taylor",
        },
        "state": state,
    }
