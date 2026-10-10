from __future__ import annotations

import uuid
from typing import Any

from astraform.remote_domain.author_kit.fastapi import create_fastapi_app

from banking_remote_domain_python import service
from banking_remote_domain_python.main import PythonBankingPolicyWindTunnelService
from banking_remote_domain_python.main import PythonBankingRemoteDomainService


class LegacyBaselinePolicyWindTunnelService(PythonBankingPolicyWindTunnelService):
    """Adapts the SDK's legacy baseline probe without reopening Fast Batch."""

    def start_run(
        self,
        payload: dict[str, Any],
        *,
        execution_role: str = "",
    ) -> dict[str, Any]:
        preset_id = service.trim(payload.get("presetId")) or service.WIND_TUNNEL_PRESET_ID
        parameters = service.normalized_wind_tunnel_parameters(payload, preset_id)
        parameters[service.FINALIZATION_MODE_FIELD] = service.FINALIZATION_MODE_LEGACY_V1
        run_id = "banking-python-conformance-" + uuid.uuid4().hex
        run = service.new_policy_run(
            run_id,
            preset_id,
            parameters,
            status="COMPLETED",
            phase="COMPLETED",
            progress=1.0,
            bundle_ready=True,
            log_tail=["Legacy SDK baseline completed without provider execution."],
        )
        with service._POLICY_WIND_TUNNEL_LOCK:
            service._POLICY_WIND_TUNNEL_RUNS[run_id] = run
        return service.policy_wind_tunnel_run_status(run_id)


app = create_fastapi_app(
    service=PythonBankingRemoteDomainService(),
    policy_wind_tunnel_service=LegacyBaselinePolicyWindTunnelService(),
    title="Python Banking Remote Domain Conformance Fixture",
    version="0.1.0",
    description="Contract-only fixture for the SDK legacy Policy Wind Tunnel baseline.",
    media_type=service.MEDIA_TYPE,
)
