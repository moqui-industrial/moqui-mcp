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

For trusted local development only, the servlet may log in the configured local service account fallback.

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
2. `tools/list`
3. `resources/list`
4. `resources/read` on a known entity definition
5. `prompts/list`
6. `prompts/get` on a wiki-backed prompt

## What This Minimal Component Does Not Test

This minimal component does not expose:

- screen-derived prompts
- lookup transition resources
- prompt completion workflows
- prompt catalogs in OpenSearch

Those experiments were intentionally removed from the component.
