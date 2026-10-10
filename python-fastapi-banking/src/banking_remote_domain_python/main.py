from __future__ import annotations

from typing import Any
from urllib.parse import unquote

from fastapi import Request
from fastapi.responses import JSONResponse
from astraform.remote_domain.author_kit.fastapi import create_fastapi_app
from astraform.remote_domain.author_kit.protocol import BinaryPayload
from astraform.remote_domain.author_kit.protocol import BinaryPayloadMetadata

from banking_remote_domain_python import service
from banking_remote_domain_python.banking_api import install_banking_api
from banking_remote_domain_python.service import MEDIA_TYPE
from banking_remote_domain_python.service import control_policy_wind_tunnel_run
from banking_remote_domain_python.service import execute_control_action
from banking_remote_domain_python.service import execute_domain_system_work_window
from banking_remote_domain_python.service import execute_work
from banking_remote_domain_python.service import inspection
from banking_remote_domain_python.service import manifest
from banking_remote_domain_python.service import policy_wind_tunnel_artifact
from banking_remote_domain_python.service import policy_wind_tunnel_artifact_metadata
from banking_remote_domain_python.service import policy_wind_tunnel_bundle
from banking_remote_domain_python.service import policy_wind_tunnel_evidence_pack
from banking_remote_domain_python.service import policy_wind_tunnel_evidence_pack_metadata
from banking_remote_domain_python.service import policy_wind_tunnel_pack
from banking_remote_domain_python.service import policy_wind_tunnel_presets
from banking_remote_domain_python.service import policy_wind_tunnel_run_status
from banking_remote_domain_python.service import population_catalog
from banking_remote_domain_python.service import product_catalog_response
from banking_remote_domain_python.service import prepare
from banking_remote_domain_python.service import shutdown
from banking_remote_domain_python.service import start_policy_wind_tunnel_run
from banking_remote_domain_python.service import status
from banking_remote_domain_python.service import WIND_TUNNEL_PROVIDER_REVISION


class PythonBankingRemoteDomainService:
    def manifest(self) -> dict[str, Any]:
        return manifest()

    def population_catalog(self) -> dict[str, Any]:
        return population_catalog()

    def prepare(self, payload: dict[str, Any]) -> dict[str, Any]:
        return prepare(payload)

    def execute_work(self, payload: dict[str, Any]) -> dict[str, Any]:
        return execute_work(payload)

    def execute_domain_system_work_window(self, payload: dict[str, Any]) -> dict[str, Any]:
        return execute_domain_system_work_window(payload)

    def execute_control_action(self, payload: dict[str, Any]) -> dict[str, Any]:
        return execute_control_action(payload)

    def status(self, payload: dict[str, Any]) -> dict[str, Any]:
        return status(payload)

    def inspection(self, payload: dict[str, Any]) -> dict[str, Any]:
        return inspection(payload)

    def shutdown(self, payload: dict[str, Any]) -> dict[str, Any]:
        return shutdown(payload)


class PythonBankingPolicyWindTunnelService:
    def pack(self) -> dict[str, Any]:
        return policy_wind_tunnel_pack()

    def presets(self) -> dict[str, Any]:
        return policy_wind_tunnel_presets()

    def start_run(self, payload: dict[str, Any], *, execution_role: str = "") -> dict[str, Any]:
        return start_policy_wind_tunnel_run(payload, execution_role=execution_role)

    def get_run(self, run_id: str) -> dict[str, Any]:
        return policy_wind_tunnel_run_status(run_id)

    def control_run(self, run_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        return control_policy_wind_tunnel_run(run_id, payload)

    def bundle(self, run_id: str) -> dict[str, Any]:
        return policy_wind_tunnel_bundle(run_id)

    def artifact(self, run_id: str, artifact_type: str) -> BinaryPayload:
        metadata = policy_wind_tunnel_artifact_metadata(run_id, artifact_type)
        return BinaryPayload(
            content=policy_wind_tunnel_artifact(run_id, artifact_type),
            media_type=metadata.media_type,
            filename=metadata.filename,
        )

    def artifact_metadata(self, run_id: str, artifact_type: str) -> BinaryPayloadMetadata:
        return policy_wind_tunnel_artifact_metadata(run_id, artifact_type)

    def evidence_pack(self, run_id: str) -> BinaryPayload:
        return BinaryPayload(
            content=policy_wind_tunnel_evidence_pack(run_id),
            media_type="application/zip",
            filename=f"{run_id}-evidence-pack.zip",
        )

    def evidence_pack_metadata(self, run_id: str) -> BinaryPayloadMetadata:
        return policy_wind_tunnel_evidence_pack_metadata(run_id)


app = create_fastapi_app(
    service=PythonBankingRemoteDomainService(),
    policy_wind_tunnel_service=PythonBankingPolicyWindTunnelService(),
    title="Python Banking Remote Domain",
    version="0.1.0",
    description="Python banking reference implementation for remote-domain.v1 and remote Policy Wind Tunnel.",
    media_type=MEDIA_TYPE,
)


install_banking_api(
    app,
    lambda: service.get_account_repository(),
    lambda: service.close_account_repository(),
)


@app.middleware("http")
async def bind_private_opportunity_correlation_capability(request: Request, call_next):
    token = service.bind_opportunity_correlation_capability(
        request.headers.get(service.OPPORTUNITY_CORRELATION_CAPABILITY_HEADER)
    )
    try:
        return await call_next(request)
    finally:
        service.reset_opportunity_correlation_capability(token)


@app.middleware("http")
async def require_pinned_policy_wind_tunnel_revision(request: Request, call_next):
    expected_revision = request.headers.get("X-Astraform-Expected-Provider-Revision")
    initial_pack_metadata = (
        request.method.upper() == "GET"
        and request.url.path.endswith("/policy-wind-tunnel/pack")
    )
    initial_native_manifest = (
        request.method.upper() == "GET"
        and request.url.path == "/api/remote-domain/v1/manifest"
        and service.native_reference_enabled()
    )
    try:
        finalization_mode = await policy_wind_tunnel_finalization_mode(request)
    except PolicyWindTunnelModeResolutionError as error:
        return JSONResponse(
            status_code=error.status_code,
            content={"detail": error.detail},
            headers={"X-Astraform-Provider-Revision": WIND_TUNNEL_PROVIDER_REVISION},
        )
    requires_revision = finalization_mode == service.FINALIZATION_MODE_PLATFORM_OWNED_V2
    revision_negotiated = requires_revision or expected_revision is not None
    if revision_negotiated and expected_revision != WIND_TUNNEL_PROVIDER_REVISION:
        return JSONResponse(
            status_code=412,
            content={"detail": "Policy Wind Tunnel provider revision does not match the pinned run contract"},
            headers={"X-Astraform-Provider-Revision": WIND_TUNNEL_PROVIDER_REVISION},
        )
    response = await call_next(request)
    if initial_pack_metadata or initial_native_manifest or revision_negotiated:
        response.headers["X-Astraform-Provider-Revision"] = WIND_TUNNEL_PROVIDER_REVISION
    return response


class PolicyWindTunnelModeResolutionError(RuntimeError):
    def __init__(self, status_code: int, detail: str) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


async def policy_wind_tunnel_finalization_mode(request: Request) -> str | None:
    path = request.url.path
    if request.method.upper() == "POST" and path.endswith("/policy-wind-tunnel/runs"):
        try:
            payload = service.strict_json_object(
                await request.body(),
                "Policy Wind Tunnel launch body must be one strict UTF-8 JSON object",
            )
        except RuntimeError as error:
            raise PolicyWindTunnelModeResolutionError(400, str(error)) from error
        raw_mode = payload.get(service.FINALIZATION_MODE_FIELD)
        if raw_mode in {
            service.FINALIZATION_MODE_LEGACY_V1,
            service.FINALIZATION_MODE_PLATFORM_OWNED_V1,
            service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
        }:
            return raw_mode
        if raw_mode in {None, ""} and payload.get("executionMode") != "CLOCK_DRIVEN_HYBRID":
            return service.FINALIZATION_MODE_LEGACY_V1
        raise PolicyWindTunnelModeResolutionError(
            409,
            "Policy Wind Tunnel launch finalization mode is missing or unsupported",
        )

    run_marker = "/policy-wind-tunnel/runs/"
    if run_marker not in path:
        return None
    run_suffix = path.split(run_marker, 1)[1]
    run_id = unquote(run_suffix.split("/", 1)[0])
    if not run_id:
        raise PolicyWindTunnelModeResolutionError(
            400,
            "Policy Wind Tunnel run identity is invalid",
        )
    try:
        status = policy_wind_tunnel_run_status(run_id)
    except service.ProtocolError as error:
        raise PolicyWindTunnelModeResolutionError(
            error.status_code,
            str(error),
        ) from error
    except Exception as error:
        raise PolicyWindTunnelModeResolutionError(
            503,
            "Policy Wind Tunnel lifecycle mode is temporarily unavailable",
        ) from error
    parameters = status.get("parameters")
    if not isinstance(parameters, dict):
        raise PolicyWindTunnelModeResolutionError(
            409,
            "Policy Wind Tunnel lifecycle mode is missing or corrupt",
        )
    mode = parameters.get(service.FINALIZATION_MODE_FIELD)
    if mode in {
        service.FINALIZATION_MODE_LEGACY_V1,
        service.FINALIZATION_MODE_PLATFORM_OWNED_V1,
        service.FINALIZATION_MODE_PLATFORM_OWNED_V2,
    }:
        return mode
    raise PolicyWindTunnelModeResolutionError(
        409,
        "Policy Wind Tunnel lifecycle mode is missing or unsupported",
    )


@app.get("/api/products")
async def get_products(productCategory: str | None = None) -> dict[str, Any]:
    return product_catalog_response(productCategory)
