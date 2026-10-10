#!/usr/bin/env bash

set -euo pipefail

A2A_PROTOCOL_VERSION="1.0"
CREATIVE_AGENCY_URL="${CREATIVE_AGENCY_A2A_URL:-http://127.0.0.1:${MARKETING_OPS_CREATIVE_AGENCY_HOST_PORT:-8091}}"
CREATIVE_AGENCY_URL="${CREATIVE_AGENCY_URL%/}"
PARTNER_TOKEN="${CREATIVE_AGENCY_A2A_TOKEN:-}"

log() {
  printf '[creative-agency-a2a-v1] %s\n' "$*"
}

fail() {
  log "FAIL: $*"
  exit 1
}

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || fail "Missing required command: $cmd"
}

require_cmd curl
require_cmd jq

RUN_DIR="$(mktemp -d /tmp/creative_agency_a2a_v1.XXXXXX)"
trap 'rm -rf "'"$RUN_DIR"'"' EXIT

AUTH_HEADERS=(-H 'Accept: application/json')
if [[ -n "$PARTNER_TOKEN" ]]; then
  AUTH_HEADERS+=(-H "X-Astraform-Agent-Token: $PARTNER_TOKEN")
fi

log "Checking the protocol-1.0 Agent Card"
card_status="$(curl -sS --connect-timeout 5 --max-time 15 \
  -o "$RUN_DIR/agent-card.json" -w '%{http_code}' \
  -H 'Accept: application/json' \
  -H "A2A-Version: $A2A_PROTOCOL_VERSION" \
  "$CREATIVE_AGENCY_URL/.well-known/agent-card.json" || true)"
[[ "$card_status" == "200" ]] || fail "Agent Card request failed (HTTP $card_status)"

jq -e --arg version "$A2A_PROTOCOL_VERSION" '
  (.supportedInterfaces | type) == "array"
  and (.supportedInterfaces | length) > 0
  and all(.supportedInterfaces[]; .protocolVersion == $version)
  and any(.supportedInterfaces[];
    .protocolBinding == "JSONRPC"
    and .protocolVersion == $version
    and ((.url // "") | endswith("/a2a")))
' "$RUN_DIR/agent-card.json" >/dev/null \
  || fail "Agent Card does not advertise only A2A protocol 1.0 JSON-RPC interfaces"

request_id="creative-agency-v1-$(date +%s)-$$"
jq -n \
  --arg requestId "$request_id" \
  --arg messageId "message-$request_id" \
  --arg contextId "context-$request_id" \
  '{
    jsonrpc: "2.0",
    id: $requestId,
    method: "SendMessage",
    params: {
      message: {
        role: "ROLE_USER",
        parts: [{text: "Rework this paid-social hero around one promise and one CTA."}],
        messageId: $messageId,
        contextId: $contextId
      },
      metadata: {
        scenario: "creative-rework",
        personaName: "Protocol 1.0 Smoke",
        agentKey: "creative-agency",
        domainId: "marketing-ops"
      }
    }
  }' > "$RUN_DIR/send-message.json"

log "Sending a protocol-1.0 SendMessage request"
send_status="$(curl -sS --connect-timeout 5 --max-time 40 \
  -o "$RUN_DIR/send-message-response.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "A2A-Version: $A2A_PROTOCOL_VERSION" \
  "${AUTH_HEADERS[@]}" \
  --data-binary "@$RUN_DIR/send-message.json" \
  "$CREATIVE_AGENCY_URL/a2a" || true)"
[[ "$send_status" == "200" ]] || fail "SendMessage request failed (HTTP $send_status)"

jq -e --arg requestId "$request_id" '
  .jsonrpc == "2.0"
  and .id == $requestId
  and (.error == null)
  and .result.task.status.state == "TASK_STATE_COMPLETED"
  and (((.result.task.status.message.parts[0].text // "")) | contains("Creative Agency Response"))
  and .result.task.status.message.metadata.scenario == "creative-rework"
' "$RUN_DIR/send-message-response.json" >/dev/null \
  || fail "Protocol-1.0 response did not contain the completed creative-agency task"

log "Confirming that task-management operations cannot bypass the version header"
jq -n --arg requestId "missing-version-$request_id" '
  {
    jsonrpc: "2.0",
    id: $requestId,
    method: "ListTasks",
    params: {
      pageSize: 10,
      tenant: "creative-agency-v1-smoke"
    }
  }
' > "$RUN_DIR/missing-version.json"

missing_version_status="$(curl -sS --connect-timeout 5 --max-time 15 \
  -o "$RUN_DIR/missing-version-response.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  "${AUTH_HEADERS[@]}" \
  --data-binary "@$RUN_DIR/missing-version.json" \
  "$CREATIVE_AGENCY_URL/a2a" || true)"
[[ "$missing_version_status" == "200" ]] \
  || fail "Missing-version rejection returned unexpected HTTP $missing_version_status"
jq -e '.error.code == -32009' "$RUN_DIR/missing-version-response.json" >/dev/null \
  || fail "ListTasks without a version header was not rejected with VersionNotSupportedError"

log "PASS protocolVersion=1.0 method=SendMessage legacyFallback=false"
