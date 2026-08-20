# moqui-mcp

Minimal MCP protocol component for Moqui aligned to MCP `2026-07-28`.

This component is intentionally narrow and reusable:

- `tools` expose selected Moqui service operations, lookup helpers, and the Moqui service catalog itself
- `resources` expose Moqui entities, deterministic entity records, and DataDocument definitions
- `prompts` are either stored in Moqui Wiki pages or derived at runtime from Moqui screens/transitions
- `notifications` are bridged from Moqui `NotificationMessage`

## Scope

`moqui-mcp` is the MCP-facing protocol layer. It should stay small.

It should not contain:

- agent planning engines
- algebraic metamodel logic
- skill orchestration logic
- morphism composition
- workflow state or replanning
- legacy screen-first agent runtime logic

Those responsibilities belong in other components such as `moqui-harness` and `moqui-math`.

## Internal Structure

- `src/main/groovy/org/moqui/mcp/McpClient.groovy` contains MCP protocol dispatch and Moqui integration logic.
- `src/main/groovy/org/moqui/mcp/MoquiResourceProvider.groovy` owns resources and resource templates.
- `src/main/groovy/org/moqui/mcp/CompositePromptProvider.groovy` merges wiki-backed and screen-derived prompts.
- `src/main/groovy/org/moqui/mcp/ScreenInteractionCompiler.groovy` compiles runtime `ScreenDefinition` data into prompt descriptors.
- `service/org/moqui/mcp/McpServices.xml` is a thin service facade over `McpClient`.
- `src/main/groovy/org/moqui/mcp/McpServlet.groovy` exposes the MCP Streamable HTTP endpoint on `/mcp`.
- `MoquiConf.xml` wires the `/mcp/*` filter and servlet in standard Moqui webapp configuration.

## MCP Mapping In Moqui

This component maps MCP primitives to standard Moqui concepts as follows:

- `tools` -> Moqui services and explicit lookup helpers
- `resources` -> entity schema resources, entity record resources, and DataDocument definition resources
- `prompts` -> wiki-backed prompt templates plus screen-derived interaction contracts
- `notifications` -> Moqui notification bridge

The component does not embed planning, workflow composition, or business reasoning.
It only exposes a standards-aligned MCP surface over Moqui-native capabilities.

## Current Entry Points

The generic JSON-RPC entry service is still available:

- `org.moqui.mcp.McpServices.mcp#Handle`

The MCP transport endpoint is available at:

- `/mcp`

Current status:

- the logical MCP catalog is implemented in code and exposed through Moqui services
- the component compiles and loads in Moqui
- `/mcp` responds over MCP Streamable HTTP with a single `POST` endpoint
- every request is stateless and must include `_meta.io.modelcontextprotocol/protocolVersion` and `_meta.io.modelcontextprotocol/clientCapabilities`
- the servlet sits behind the standard Moqui auth filter and expects normal Moqui authentication from remote clients
- trusted internal callers may optionally configure `-Dmoqui.mcp.serviceAccountUserId=<userId>` as an explicit fallback
- SSE and advanced notification streaming are not implemented

It dispatches these MCP methods:

- `server/discover`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/templates/list`
- `resources/read`
- `resources/subscribe`
- `resources/unsubscribe`
- `prompts/list`
- `prompts/get`
- `completion/complete`

## Tool Model

The protocol layer always exposes these built-in tools:

- `moqui_search_data_documents`
- `moqui_get_service_metadata`
- `moqui_call_service`
- `moqui_execute_screen_transition`
- `moqui_make_notification`

In addition, `tools/list` exposes the discovered Moqui services themselves as first-class MCP tools.
Each service tool uses the full Moqui service name, for example:

- `mantle.GeneralServices.lookup#ById`
- `mantle.GeneralServices.search#MantleFiltered`
- `mantle.order.OrderServices.place#Order`

The input schema for each service tool is derived from the authoritative Moqui `ServiceDefinition`.

### `moqui_search_data_documents`

This tool is the standard lookup entry point for business data.
It delegates to Moqui search services over OpenSearch-backed DataDocuments and is intended for:

- party and organization lookup
- product lookup
- facility and location lookup
- document-oriented search across denormalized business data
- future harness-side identifier resolution before mutating service calls

The intent is that agents resolve business identifiers first through document search, and only then call mutating services with explicit parameters.

### `moqui_get_service_metadata`

This tool returns the authoritative Moqui service contract for a full service name.

It includes:

- service identity and path
- authenticate / allow-remote flags
- generated input JSON Schema
- generated output JSON Schema
- base description when present in the service definition

### `moqui_call_service`

This tool invokes an existing Moqui service by full service name with an explicit parameter map.
It is intentionally low-level and does not invent workflows.

Typical uses:

- call a known business service after required identifiers have been resolved
- execute deterministic service operations from an external MCP client
- bridge a harness or another planner to Moqui-native service execution

### `moqui_make_notification`

This tool creates a standard Moqui `NotificationMessage`.
It is the current notification bridge exposed through MCP.

### Service tools as first-class MCP tools

The server exposes real Moqui services directly in `tools/list`, not only the generic `moqui_call_service` wrapper.

Use cases:

- generic MCP clients can discover callable business operations directly
- hosts can inspect the per-service JSON Schema and render input forms
- prompts and harness code can bind directly to concrete Moqui services

The low-level wrapper `moqui_call_service` remains useful for internal bindings and dynamic execution, but external clients should prefer the concrete service tool when possible.

There is no dynamic tool-provider loading in this component.
If a future integration needs more MCP tools, they should be added explicitly in `McpClient` and documented here.

## Resource Model

The component exposes canonical resource templates and resource reads.

### Resource templates

Current URI templates:

- `moqui://entity-def/{entityName}`
- `moqui://entity/{entityName}/{primaryKeyToken}`
- `moqui://data-document/{dataDocumentId}`

### Entity schema resources

URI format:

- `moqui://entity-def/<full.entity.name>`

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

- `moqui://entity/<full.entity.name>/<primaryKeyToken>`

These resources return an actual entity record selected by a complete primary key token.
The component does not treat arbitrary query conditions as canonical record resources.

### DataDocument resources

URI format:

- `moqui://data-document/<dataDocumentId>`

These resources return DataDocument definition metadata, including:

- document identity
- index name
- primary entity
- field list
- manual data service
- manual mapping service

This is especially useful for clients that need to understand which denormalized search documents exist in OpenSearch and how they are shaped.

## Prompt Model

Prompts come from two sources.

### Wiki-backed prompts

Prompts can be read from wiki pages in wiki space:

- `MCP_PROMPTS`

This keeps prompt content in standard Moqui-managed data instead of filesystem-only prompt files.

### Screen-derived prompts

The component also derives prompts at runtime from Moqui screen transitions when the transition is safely reducible to a single bound service call.

Current rules:

- only service-bound transitions are exposed as automatic prompts
- automatic/internal transitions such as `actions`, `formSelectColumns`, `formSaveFind`, and `screenDoc` are excluded
- each derived prompt carries:
  - originating `screenLocation`
  - `transitionName`
  - bound `serviceName`
  - argument list derived from the target service contract plus explicit transition/path parameters

Current boundary:

- transitions with custom XML Actions or multi-step logic are not yet exposed as executable MCP prompts
- those interactions remain future work and may eventually bind to a dedicated `moqui_execute_screen_transition` tool

### Prompt arguments and elicitation

For screen-derived prompts:

- `prompts/get` evaluates provided arguments
- if required arguments are missing, the server returns `resultType: input_required`
- the response includes a simple `elicitation/create` form schema and an opaque `requestState`
- the client may retry `prompts/get` with `inputResponses` and `requestState`

This keeps `moqui-mcp` stateless while still supporting multi-round prompt completion.

### Example screen-derived round trip

1. Call `prompts/get` for a screen-derived prompt name.
2. Read `_meta.org.moqui/promptBinding`.
3. Execute the referenced tool with the resolved parameters.

Example binding payload:

```json
{
  "toolName": "moqui_execute_screen_transition",
  "screenLocation": "component://SimpleScreens/screen/SimpleScreens/Facility/EditFacility.xml",
  "transitionName": "getFacilityList",
  "parameters": {
    "facilityName": "Retail"
  }
}
```

Then call `tools/call` with:

```json
{
  "name": "moqui_execute_screen_transition",
  "arguments": {
    "screenLocation": "component://SimpleScreens/screen/SimpleScreens/Facility/EditFacility.xml",
    "transitionName": "getFacilityList",
    "parameters": {
      "facilityName": "Retail"
    }
  }
}
```

## Notification Model

The runtime notification source is the Moqui standard `NotificationMessage` mechanism.
Today the component provides the notification creation tool and resource subscription registration, but not a full streaming notification transport.

## Supported MCP 2026-07-28 Behavior

- `server/discover` is the entry point instead of `initialize`
- `notifications/initialized` is not used
- `ping` is not implemented because it is no longer part of the current spec
- `Mcp-Session-Id` is not used because the protocol is stateless
- `MCP-Protocol-Version` must match the body metadata protocol version
- `Mcp-Method` is optional for JSON-RPC `POST` requests and, when present, must match the JSON-RPC method
- `Mcp-Name` is optional for JSON-RPC `POST` requests and, when present, must match the selected tool name, prompt name, or resource URI
- `tools/list`, `resources/list`, `resources/templates/list`, and `prompts/list` support `cursor` and `pageSize`
- `resources/subscribe` and `resources/unsubscribe` register resource interest for the authenticated principal, but do not yet emit server-pushed `notifications/resources/updated` events

## Authentication

This endpoint uses standard Moqui web authentication.

Recommended client options:

- HTTP Basic Auth
- `api_key` header with a valid Moqui login key
- `login_key` header with a valid Moqui login key

The component no longer assumes the demo `john.doe/moqui` account.
For local development that account may still exist, but it is not part of the component contract.

## Inspector and Client Notes

- After changing the tool catalog, reconnect the MCP client so it refreshes `tools/list`.
- If a client sends header `Mcp-Name`, it must equal the selected tool name, prompt name, or resource URI.
- If a client sends header `Mcp-Method`, it must equal the JSON-RPC method in the request body.

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
- tool, resource, resource-template, prompt, and completion catalog methods
- strict header and protocol-version validation
- Moqui-authenticated execution
- wiki-backed prompts
- screen-derived prompts for direct service-bound transitions
- deterministic primary-key-based entity record resources

Not implemented:

- subscriptions
- list-changed notifications
- streaming notification delivery
- generic execution of complex screen transitions with custom XML Actions
- harness-side planning or workflow execution
- dynamic registration of arbitrary external tool providers

## Recommended Usage

Use `moqui-mcp` as the MCP protocol adapter for Moqui.

Use other components for higher-level behavior:

- `moqui-harness` for planning, orchestration, validation, and business execution policy
- `moqui-math` for mathematical or categorical models
- search/DataDocument definitions in Mantle or other components for rich OpenSearch lookup

See [docs/HarnessBoundary.md](docs/HarnessBoundary.md) for the explicit architectural split between `moqui-mcp` and `moqui-harness`.
