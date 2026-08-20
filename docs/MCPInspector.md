# MCP Inspector And Smoke Testing

This component exposes a modern stateless MCP Streamable HTTP endpoint:

- URL: `http://<host>:<port>/mcp`
- Protocol version: `2026-07-28`

## Authentication

Use standard Moqui authentication.

Recommended for enterprise clients:

- `Authorization: Basic ...`
- `api_key: <login key>`
- `login_key: <login key>`

Do not rely on the demo `john.doe/moqui` account outside local development.

## Importable Local Config

For this workspace you can import a ready-made Inspector config file instead of entering the server manually:

```text
runtime/component/moqui-mcp/tools/inspector/moqui-mcp-inspector-local.json
```

It is configured for:

- Streamable HTTP transport
- URL `http://localhost:8081/mcp`
- protocol era `modern`
- local Basic authentication for the development user

In Inspector use:

1. `Add Servers`
2. `Import from client config`
3. select `moqui-mcp-inspector-local.json`

If the imported server still connects in legacy mode, start Inspector directly with the config file instead:

```bash
runtime/component/moqui-mcp/tools/inspector/run-inspector-local.sh
```

This forces Inspector to load the exact server definition from disk, including the `modern` protocol era.

If you need a non-interactive system identity, configure a dedicated service account and authenticate normally through Moqui, or set:

```bash
-Dmoqui.mcp.serviceAccountUserId=<trusted-internal-user>
```

This fallback is intended only for trusted internal calls that already bypass normal web authentication.

## Inspector

Official Inspector:

- `npx @modelcontextprotocol/inspector`

When connecting to Moqui:

1. Choose a remote HTTP MCP server.
2. Set protocol era to modern / current spec.
3. Point it to:

```text
http://localhost:8081/mcp
```

4. Send these headers on every request:

```text
MCP-Protocol-Version: 2026-07-28
Authorization: Basic ...
```

Optional consistency headers:

```text
Mcp-Method: <json-rpc method>
Mcp-Name: <tool/resource/prompt name when required>
```

If you prefer login keys instead of Basic Auth:

```text
api_key: <login key>
```

or:

```text
login_key: <login key>
```

Required `Mcp-Name` cases:

- `tools/call`
- `resources/read`
- `prompts/get`

## Minimal Manual Validation

Successful sequence:

1. `server/discover`
2. `tools/list`
3. `resources/read` on `moqui://entity-def/mantle.party.Person`

Expected behavior:

- request without auth: blocked by Moqui auth filter
- request with header/body mismatch: JSON-RPC error `-32020`
- request with wrong protocol version: JSON-RPC error `-32022`

## Repeatable CLI Smoke Test

Use:

```bash
./tools/mcp-smoke.sh
```

Environment variables:

```bash
MCP_BASE_URL=http://localhost:8081/mcp
MCP_BASIC_USER=my.user
MCP_BASIC_PASSWORD=my.password
```

or:

```bash
MCP_BASE_URL=http://localhost:8081/mcp
MCP_LOGIN_KEY=<login-key>
```

The script validates:

- `server/discover`
- `tools/list`
- `resources/read`
- negative header mismatch handling
