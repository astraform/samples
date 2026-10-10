# Python Banking Starter v0.1.0

Run one customer simulation against a banking partner agent, using an already-issued January credit-card statement. Avery has AUD 100 available and wants to understand the payment amount, due date and whether that budget covers the minimum or the full balance.

## Compatibility

- Astraform Platform **v0.2.0**, installed from its separate deployment bundle.
- Published Python SDK **0.3.1**, included in the Banking Advice image; public contracts **1.2.0**.
- Docker with Compose v2 and Python **3.10+** on the host for initialization.
- Gemini or Ollama, configured separately for the Astraform customer and the banking partner.

`bundle-manifest.json` records the starter version, sample source revision, compatibility versions, image identities and file checksums. `image-lock.json` and `release.env` refer to the same pinned images. A bundle marked `localVerificationOnly` is a local rehearsal and requires the recorded images already installed.

## Included

- Compose deployment for the Python bank, its separate PostgreSQL database and the LLM-backed Banking Advice A2A agent.
- Gemini and Ollama partner-model templates, plus initialization that connects the bank to an initialized platform bundle.
- A configurable customer persona, goal, scoped MCP tool allowance, seeded bank records, response conditions and bounded stop limits.
- The SDK launcher inside the agent image: create and publish a customer, launch once, inspect that same run and export its evidence.
- Operator instructions, API input schema, Apache 2.0 license and attribution notice.

Both customer and partner use real language models. The customer accesses its assigned account through MCP; the partner reads the same bank through the backend API. Provider keys stay in local private configuration and are not part of this archive.

## Boundaries

This scenario uses fixed, already-issued records. It does not exercise monthly statement generation, shared time progression, multiple customers or capacity limits. The partner agent reads current records for each request and has no persistent conversation history. It cannot make or schedule payments.

The helper preserves model choices, unsuccessful goals and service failures. A terminal simulation or successful export is not a score for answer quality; inspect the exchanged messages and tool activity. Container health checks do not establish model availability or simulation success.

The starter and selected banking sample code use Apache License 2.0. The platform is distributed separately under its own terms; this starter license does not apply to it. Third-party components retain their own licenses.

## Install

Verify the archive against its matching `.sha256` file before extracting it. Follow the included `README.md` to initialize both bundles, configure the models, start the services, launch and inspect a run, and stop the starter while preserving evidence and database volumes.
