# moqui-mcp

Lightweight MCP protocol component for Moqui.

This component is intentionally narrow:

- `tools` are the built-in Moqui MCP tools exposed by this component
- `resources` are Moqui entity schemas, records, and DataDocument definitions
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

## Current Entry Points

The generic JSON-RPC entry service is still available:

- `org.moqui.mcp.McpServices.mcp#Handle`

The MCP transport endpoint is now available at:

- `/mcp`

Current status:

- the logical MCP catalog is implemented in services
- the component compiles and loads in Moqui
- `/mcp` responds over Streamable HTTP and supports `initialize`, `ping`, `tools/list`, `resources/list`, `resources/read`, `prompts/list`, and `prompts/get`
- the servlet uses the standard Moqui auth filter and can log in the configured MCP service account for local MCP calls
- SSE and advanced notification streaming are not implemented yet

It dispatches these MCP methods:

- `server/discover`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/read`
- `prompts/list`
- `prompts/get`
- `subscriptions/listen`

## Tool Model

The protocol layer always exposes these built-in tools:

- `moqui_search_data_documents`
- `moqui_call_service`
- `moqui_make_notification`

There is no dynamic tool-provider loading in this component.
If a future integration needs more MCP tools, they should be added explicitly in `McpClient` and documented here instead of being discovered indirectly from legacy runtime metadata.

## Prompt Model

Prompts are read from wiki pages in wiki space:

- `MCP_PROMPTS`

## Notification Model

Subscriptions are stored in:

- `moqui.mcp.McpSubscription`

The runtime notification source is the Moqui standard `NotificationMessage` mechanism.
Today the component provides the notification creation tool, but not a full streaming notification transport.

## Architectural Direction

The component follows MCP 2026-07-28 and maps core primitives as follows:

- tools -> service execution and lookup/search services
- resources -> entity/data/document resources
- prompts -> wiki-backed templates
- notifications -> Moqui notification bridge
