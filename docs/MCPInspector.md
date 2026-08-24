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

## Prompt Contract Validation

For screen-derived prompts, Inspector is best used as a protocol workbench, not as a full conversational client.

Recommended validation loop:

1. `prompts/list`
2. choose a screen-derived prompt
3. `prompts/get`
4. for lookup-backed arguments, use `completion/complete`
5. if needed, inspect the `lookup://...` resources returned by the prompt contract
6. execute the final action through `tools/call`
7. verify the resulting record through `resources/read`

Important: Inspector does not automatically perform lookup -> fill form -> submit as a business client would. The prompt contract tells the client what to do; Inspector lets you validate each protocol step explicitly.

### Validated Examples

These flows were validated directly against the running Moqui instance:

- `FindProduct.NewProductForm.createProduct`
  - resolve `ownerPartyId`
  - resolve `productTypeEnumId`
  - submit through `moqui_call_service`

- `FindOrder.CreateSalesOrder.createOrder`
  - resolve `vendorPartyId`
  - resolve `customerPartyId`
  - resolve `productStoreId`
  - resolve `facilityId`
  - submit through `moqui_call_service`

- `MoveAsset.EnterMoveAssetForm.completeMove`
  - resolve `facilityId`
  - resolve `locationSeqId`
  - submit through `moqui_execute_screen_transition` or the bound service call

### Why `prompts/list` and `moqui_search_prompt_catalog` both exist

- `prompts/list` is the MCP-native runtime prompt catalog.
- `moqui_search_prompt_catalog` is the OpenSearch/DataDocument-backed discovery tool for finding prompts by business language.

Use `prompts/list` when you already know the area or exact screen family.
Use `moqui_search_prompt_catalog` when the client needs prompt discovery by natural-language intent.

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
