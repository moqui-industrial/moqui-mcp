# moqui-mcp

Lightweight MCP protocol component for Moqui.

This component is intentionally narrow:

- `tools` are Moqui services and registered MCP tool providers
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

## Current Entry Point

The generic JSON-RPC entry service is:

- `org.moqui.mcp.McpServices.mcp#Handle`

Current status:

- the logical MCP catalog is implemented in services
- the component can be loaded and compiled in Moqui
- a dedicated MCP transport endpoint such as stdio, Streamable HTTP, or SSE is not yet wired
- `/mcp` is therefore not yet available to tools such as MCP Inspector

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

The built-in tools are:

- `moqui_search_data_documents`
- `moqui_call_service`
- `moqui_make_notification`

Additional tools may be exposed through standard Moqui `ServiceRegister` rows with:

- `serviceTypeEnumId = McpToolProvider`

## Prompt Model

Prompts are read from wiki pages in wiki space:

- `MCP_PROMPTS`

## Notification Model

Subscriptions are stored in:

- `moqui.mcp.McpSubscription`

The runtime notification source is the Moqui standard `NotificationMessage` mechanism.

## Architectural Direction

The component follows MCP 2026-07-28 and maps core primitives as follows:

- tools -> service execution and lookup/search services
- resources -> entity/data/document resources
- prompts -> wiki-backed templates
- notifications -> Moqui notification bridge
