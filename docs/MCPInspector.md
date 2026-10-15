# MCP Inspector

`moqui-mcp` exposes a stateless MCP Streamable HTTP endpoint:

- `http://<host>:<port>/mcp`

Protocol version:

- `2026-07-28`

## Authentication

Use normal Moqui authentication:

- `Authorization: Basic ...`
- `api_key: <login key>`
- `login_key: <login key>`

The checked-in client configurations contain no credentials. Add an authentication header locally or launch Inspector with a credential-injection mechanism appropriate for the environment; do not commit it.

For trusted local development only, the servlet may log in the configured local service account fallback. It is disabled by default and requires both `moqui.mcp.serviceAccountEnabled=true` and `moqui.mcp.serviceAccountUserId`.

## Importable Client Config

Local Inspector config:

```text
runtime/component/moqui-mcp/tools/inspector/moqui-mcp-inspector-local.json
```

Shell helper:

```bash
runtime/component/moqui-mcp/tools/inspector/run-inspector-local.sh
```

## Minimal Validation Sequence

Recommended manual checks in Inspector:

1. `server/discover`
2. `tools/list`: the llm-client tools of the profile (`request`, `browse`, `run_service`, `find_basic`) and `find_document`
3. `tools/call` `browse` with `{"q": "find product"}`: screen hits with `url`

## What This Component Does Not Expose

Resources, prompts and the llm-client skills. Entity data is reached through the llm-client tools.
