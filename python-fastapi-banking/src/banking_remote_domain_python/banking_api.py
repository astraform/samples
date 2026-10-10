"""Bank-owned setup/read API and the standard customer-scoped MCP transport."""
from __future__ import annotations

from contextlib import asynccontextmanager
from importlib.resources import files
import json
import logging
from typing import Any, Callable

import anyio
from astraform.remote_domain.author_kit.fastapi import request_payload
from astraform.remote_domain.author_kit.protocol import ProtocolError
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from mcp import types
from mcp.server import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from starlette.routing import Route
from starlette.types import Receive, Scope, Send

from banking_remote_domain_python.accounts import BankingAccountError, BankingAccountRepository

LOGGER = logging.getLogger(__name__)
CUSTOMER_CONTEXT_META = "astraform.customerContext"
BANKING_PATH = "/api/banking/v1/contexts/{contextRef}/customers/{subjectRef}"


class BankingMcpEndpoint:
    """Bind the official MCP ASGI transport to exactly /mcp, without a redirect."""

    def __init__(self) -> None:
        self.manager: StreamableHTTPSessionManager | None = None

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if self.manager is None:
            await JSONResponse(status_code=404, content={"error": "Banking backend is disabled"})(scope, receive, send)
            return
        await self.manager.handle_request(scope, receive, send)


def install_banking_api(
    app: FastAPI,
    get_repository: Callable[[], BankingAccountRepository | None],
    close_repository: Callable[[], None],
) -> None:
    """Attach bank APIs without replacing the SDK's remote-domain routes/lifespan."""
    endpoint = BankingMcpEndpoint()
    original_lifespan = app.router.lifespan_context

    def repository() -> BankingAccountRepository:
        result = get_repository()
        if result is None:
            raise BankingAccountError(404, "Banking backend is disabled")
        return result

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        async with original_lifespan(application):
            try:
                bank = await anyio.to_thread.run_sync(get_repository)
                if bank is None:
                    yield
                else:
                    # The database owns state and receipts. MCP itself needs no durable session.
                    endpoint.manager = StreamableHTTPSessionManager(
                        customer_accounts_server(repository), json_response=True, stateless=True
                    )
                    async with endpoint.manager.run():
                        yield
            finally:
                endpoint.manager = None
                await anyio.to_thread.run_sync(close_repository)

    app.router.lifespan_context = lifespan
    app.router.routes.append(Route("/mcp", endpoint=endpoint, methods=["GET", "POST", "DELETE"]))

    @app.put(BANKING_PATH)
    async def populate_customer(contextRef: str, subjectRef: str, request: Request):
        try:
            payload = await request_payload(request)
            return await anyio.to_thread.run_sync(repository().seed, contextRef, subjectRef, payload)
        except (BankingAccountError, ProtocolError) as error:
            return JSONResponse(status_code=error.status_code, content={"error": str(error)})
        except ValueError as error:
            return JSONResponse(status_code=400, content={"error": str(error)})

    @app.get(BANKING_PATH + "/accounts")
    def read_customer(
        contextRef: str,
        subjectRef: str,
        contextId: str | None = None,
        resourceVersion: int | None = None,
        logicalTimeEpochMs: int | None = None,
        statementPeriod: str | None = None,
    ):
        try:
            fields = (contextId, resourceVersion, logicalTimeEpochMs)
            guard = None
            if any(value is not None for value in fields):
                if any(value is None for value in fields):
                    raise ValueError("All three read context fields are required together")
                guard = {"contextId": contextId, "resourceVersion": resourceVersion, "logicalTimeEpochMs": logicalTimeEpochMs}
            return repository().read(contextRef, subjectRef, guard, statementPeriod)
        except BankingAccountError as error:
            return JSONResponse(status_code=error.status_code, content={"error": str(error)})
        except ValueError as error:
            return JSONResponse(status_code=400, content={"error": str(error)})


def customer_accounts_server(get_repository: Callable[[], BankingAccountRepository]) -> Server:
    server = Server("astraform-sample-banking-python", version="0.1.0")
    schema = json.loads(
        files("banking_remote_domain_python").joinpath("schemas/banking-customer-accounts.v1.schema.json").read_text()
    )

    @server.list_tools()
    async def list_tools() -> list[types.Tool]:
        return [types.Tool(
            name="get_customer_accounts",
            description="Read your own accounts and statements from the bank. No account or customer selectors are accepted; your configured customer context determines access.",
            inputSchema={"type": "object", "properties": {}, "additionalProperties": False},
            outputSchema=schema,
            annotations=types.ToolAnnotations(
                readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False
            ),
        )]

    @server.call_tool()
    async def call_tool(name: str, arguments: dict[str, Any]) -> dict[str, Any] | types.CallToolResult:
        try:
            if name != "get_customer_accounts":
                raise ValueError("Unknown banking tool")
            if arguments:
                raise ValueError("get_customer_accounts accepts exactly {}; customer/account selectors are not allowed")
            meta = server.request_context.meta
            context = None if meta is None else meta.model_dump().get(CUSTOMER_CONTEXT_META)
            if (
                not isinstance(context, dict)
                or not {"contextRef", "subjectRef"}.issubset(context)
                or not set(context).issubset({"contextRef", "subjectRef", "readContext"})
            ):
                raise ValueError("A trusted astraform.customerContext binding is required")
            if "readContext" in context and not isinstance(context["readContext"], dict):
                raise ValueError("Invalid readContext")
            return await anyio.to_thread.run_sync(
                get_repository().read, context["contextRef"], context["subjectRef"], context.get("readContext")
            )
        except (ValueError, BankingAccountError) as error:
            return types.CallToolResult(content=[types.TextContent(type="text", text=str(error))], isError=True)
        except Exception:
            LOGGER.exception("Customer banking MCP read failed")
            return types.CallToolResult(
                content=[types.TextContent(type="text", text="Banking account read is temporarily unavailable")],
                isError=True,
            )

    return server
