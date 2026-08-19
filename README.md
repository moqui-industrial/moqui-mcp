# moqui-mcp

Minimal MCP protocol component for Moqui.

This component is intentionally narrow and reusable:

- `tools` expose selected Moqui service operations and lookup helpers
- `resources` expose Moqui entities, records, and DataDocument definitions
- `prompts` are stored in Moqui Wiki pages
- `notifications` are bridged from Moqui `NotificationMessage`

## Scope

`moqui-mcp` is the MCP-facing protocol layer. It should stay small.

It should not contain:

- agent planning engines
- algebraic metamodel logic
- skill orchestration logic
- graph- or screen-first legacy layers

Those responsibilities belong in other components such as `moqui-harness` and `moqui-math`.

## Internal Structure

- `src/main/groovy/org/moqui/mcp/McpClient.groovy` contains MCP protocol behavior and Moqui integration logic.
- `service/org/moqui/mcp/McpServices.xml` is a thin service facade over `McpClient`, following the same general client-plus-services pattern used by other Moqui components.
- `src/main/groovy/org/moqui/mcp/McpServlet.groovy` exposes the MCP Streamable HTTP endpoint on `/mcp`.
- `MoquiConf.xml` wires the `/mcp/*` filter and servlet in standard Moqui webapp configuration.

## MCP Mapping In Moqui

This component maps MCP primitives to standard Moqui concepts as follows:

- `tools` -> Moqui services and explicit lookup helpers
- `resources` -> entity schema resources, entity record resources, and DataDocument definition resources
- `prompts` -> wiki-backed prompt templates
- `notifications` -> Moqui notification bridge

The component does not embed planning, workflow composition, or business reasoning.
It only exposes a standards-aligned MCP surface over Moqui-native capabilities.

## Current Entry Points

The generic JSON-RPC entry service is still available:

- `org.moqui.mcp.McpServices.mcp#Handle`

The MCP transport endpoint is now available at:

- `/mcp`

Current status:

- the logical MCP catalog is implemented in code and exposed through Moqui services
- the component compiles and loads in Moqui
- `/mcp` responds over MCP Streamable HTTP with a single `POST` endpoint
- every request is stateless and must include `_meta.io.modelcontextprotocol/protocolVersion`, `_meta.io.modelcontextprotocol/clientCapabilities`, and the required MCP HTTP headers
- the servlet sits behind the standard Moqui auth filter and expects normal Moqui authentication from remote clients
- trusted internal callers may optionally configure `-Dmoqui.mcp.serviceAccountUserId=<userId>` as an explicit fallback
- SSE, subscriptions, and advanced notification streaming are not implemented yet

It dispatches these MCP methods:

- `server/discover`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/read`
- `prompts/list`
- `prompts/get`

## Tool Model

The protocol layer always exposes these built-in tools:

- `moqui_search_data_documents`
- `moqui_call_service`
- `moqui_make_notification`

### `moqui_search_data_documents`

This tool is the standard lookup entry point for business data.
It delegates to Moqui search services over OpenSearch-backed DataDocuments and is intended for:

- party and organization lookup
- product lookup
- facility and location lookup
- document-oriented search across denormalized business data
- future harness-side identifier resolution before mutating service calls

The intent is that agents resolve business identifiers first through document search, and only then call mutating services with explicit parameters.

### `moqui_call_service`

This tool invokes an existing Moqui service by full service name with an explicit parameter map.
It is intentionally low-level and does not invent workflows.

Typical uses:

- call a known business service after required identifiers have been resolved
- execute deterministic service operations from an external MCP client
- bridge an MCP planner or harness to Moqui-native service execution

### `moqui_make_notification`

This tool creates a standard Moqui `NotificationMessage`.
It is the current notification bridge exposed through MCP.

There is no dynamic tool-provider loading in this component.
If a future integration needs more MCP tools, they should be added explicitly in `McpClient` and documented here instead of being discovered indirectly from legacy runtime metadata.

## Resource Model

The component currently exposes three resource families.

### Entity schema resources

URI format:

- `entity://<full.entity.name>`

These resources return structural metadata about an entity, including:

- field names
- field types
- primary-key flags
- not-null flags
- encryption flags
- relationship summaries

These are schema resources, not record resources.

### Entity record resources

URI format:

- `record://<full.entity.name>?field=value&field2=value2`

These resources return an actual entity record selected by explicit conditions.
They are intended for deterministic record inspection when the caller already knows the entity and key conditions.

### DataDocument resources

URI format:

- `datadocument://<dataDocumentId>`

These resources return DataDocument definition metadata, including:

- document identity
- index name
- primary entity
- field list
- manual data service
- manual mapping service

This is especially useful for clients that need to understand which denormalized search documents exist in OpenSearch and how they are shaped.

## Prompt Model

Prompts are read from wiki pages in wiki space:

- `MCP_PROMPTS`

This keeps prompt content in standard Moqui-managed data instead of filesystem-only prompt files.

## Notification Model

The runtime notification source is the Moqui standard `NotificationMessage` mechanism.
Today the component provides the notification creation tool, but not a full streaming notification transport.

## Supported MCP 2026-07-28 Behavior

- `server/discover` is the entry point instead of `initialize`
- `notifications/initialized` is not used
- `ping` is not implemented because it is no longer part of the current spec
- `Mcp-Session-Id` is not used because the protocol is stateless
- `MCP-Protocol-Version` must match the body metadata protocol version
- `Mcp-Method` must match the JSON-RPC method
- `Mcp-Name` is required for `tools/call`, `resources/read`, and `prompts/get`

## Authentication

This endpoint uses standard Moqui web authentication.

Recommended client options:

- HTTP Basic Auth
- `api_key` header with a valid Moqui login key
- `login_key` header with a valid Moqui login key

The component no longer assumes the demo `john.doe/moqui` account.
For local development that account may still exist, but it is not part of the component contract.

For browser-based or cross-origin clients, the servlet explicitly allows these headers:

- `Authorization`
- `api_key`
- `login_key`
- `MCP-Protocol-Version`
- `Mcp-Method`
- `Mcp-Name`

See [docs/MCPInspector.md](docs/MCPInspector.md) for Inspector setup and [tools/mcp-smoke.sh](tools/mcp-smoke.sh) for a repeatable smoke test.

## Current Conformance Boundaries

The component is aligned to the current MCP transport and request/response shape, but it remains intentionally minimal.

Implemented:

- stateless Streamable HTTP transport
- `server/discover`
- tool, resource, and prompt catalog methods
- strict header and protocol-version validation
- Moqui-authenticated execution

Not implemented:

- subscriptions
- list-changed notifications
- streaming notification delivery
- harness-side planning or workflow execution
- dynamic registration of arbitrary external tool providers

## Recommended Usage

Use `moqui-mcp` as the MCP protocol adapter for Moqui.

Use other components for higher-level behavior:

- `moqui-harness` for planning, orchestration, validation, and business execution policy
- `moqui-math` for mathematical or categorical models
- search/DataDocument definitions in Mantle or other components for rich OpenSearch lookup
