from __future__ import annotations

from contextlib import asynccontextmanager
from unittest.mock import Mock

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
import pytest

from banking_remote_domain_python.accounts import BankingAccountError, BankingAccountRepository
from banking_remote_domain_python.banking_api import install_banking_api


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
def account_view():
    return {
        "contextRef": "demo", "subjectRef": "customer-a", "version": 1,
        "logicalTimeEpochMs": 1770249600000,
        "accounts": [{
            "accountId": "customer-a-credit", "name": "Credit card", "productCode": "CC001",
            "balance": "-1200", "currency": "AUD", "limit": "5000", "currentMinPayment": "48",
        }],
        "statements": [],
    }


def bank_app(bank):
    app = FastAPI()
    install_banking_api(app, lambda: bank, lambda: None)
    return app


@asynccontextmanager
async def mcp_session(app):
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app)) as http:
            async with streamable_http_client("http://bank/mcp", http_client=http) as (read, write, _):
                async with ClientSession(read, write) as session:
                    await session.initialize()
                    yield session


def test_bank_rest_forwards_setup_month_and_complete_read_guard(account_view):
    bank = Mock(spec=BankingAccountRepository)
    bank.seed.return_value = account_view
    bank.read.return_value = {**account_view, "statementPeriod": "2026-01"}
    path = "/api/banking/v1/contexts/demo/customers/customer-a"
    seed = {"logicalTimeEpochMs": account_view["logicalTimeEpochMs"], "accounts": account_view["accounts"]}
    guard = {"contextId": "native-demo", "resourceVersion": 1, "logicalTimeEpochMs": account_view["logicalTimeEpochMs"]}

    with TestClient(bank_app(bank)) as client:
        assert client.put(path, json=seed).json() == account_view
        result = client.get(path + "/accounts", params={**guard, "statementPeriod": "2026-01"})
        assert result.status_code == 200
        assert result.json()["statementPeriod"] == "2026-01"
        bank.seed.assert_called_once_with("demo", "customer-a", seed)
        bank.read.assert_called_once_with("demo", "customer-a", guard, "2026-01")

        assert client.get(path + "/accounts", params={"contextId": "native-demo"}).status_code == 400
        bank.read.assert_called_once()
        bank.read.side_effect = BankingAccountError(409, "Stale banking read context")
        assert client.get(path + "/accounts").status_code == 409
        bank.read.side_effect = ValueError("Invalid statementPeriod")
        assert client.get(path + "/accounts", params={"statementPeriod": "2026-13"}).status_code == 400


@pytest.mark.anyio
async def test_official_mcp_client_discovers_and_uses_only_trusted_customer_context(account_view):
    bank = Mock(spec=BankingAccountRepository)
    bank.read.return_value = account_view
    guard = {"contextId": "native-demo", "resourceVersion": 1, "logicalTimeEpochMs": account_view["logicalTimeEpochMs"]}
    binding = {"contextRef": "demo", "subjectRef": "customer-a", "readContext": guard}

    async with mcp_session(bank_app(bank)) as session:
        tools = (await session.list_tools()).tools
        assert [tool.name for tool in tools] == ["get_customer_accounts"]
        assert tools[0].inputSchema == {"type": "object", "properties": {}, "additionalProperties": False}
        assert tools[0].annotations.readOnlyHint is True
        assert tools[0].annotations.destructiveHint is False
        assert tools[0].outputSchema["properties"]["statements"]["maxItems"] == 100
        result = await session.call_tool("get_customer_accounts", {}, meta={"astraform.customerContext": binding})
        assert not result.isError
        assert result.structuredContent == account_view
        bank.read.assert_called_once_with("demo", "customer-a", guard)

        for arguments, meta in [
            ({"subjectRef": "customer-b"}, {"astraform.customerContext": binding}),
            ({"accountId": "customer-b-credit"}, {"astraform.customerContext": binding}),
            ({}, {}),
            ({}, {"astraform.customerContext": {**binding, "accountId": "customer-b-credit"}}),
            ({}, {"astraform.customerContext": {**binding, "readContext": None}}),
        ]:
            rejected = await session.call_tool("get_customer_accounts", arguments, meta=meta)
            assert rejected.isError
            assert rejected.structuredContent is None
        bank.read.assert_called_once()

        bank.read.side_effect = BankingAccountError(409, "Stale banking read context")
        stale = await session.call_tool("get_customer_accounts", {}, meta={"astraform.customerContext": binding})
        assert stale.isError
        assert "Stale banking read context" in stale.content[0].text


def test_unconfigured_bank_does_not_expose_read_or_mcp_transport():
    with TestClient(bank_app(None)) as client:
        assert client.get("/api/banking/v1/contexts/demo/customers/customer-a/accounts").status_code == 404
        assert client.post("/mcp", json={}).status_code == 404
