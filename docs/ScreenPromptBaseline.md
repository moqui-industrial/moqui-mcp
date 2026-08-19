# Screen Prompt Baseline

Date: 2026-08-19

Branch baseline:

- `baseline/mcp-2026-screen-prompts-20260819`

Branch di lavoro:

- `feature/mcp-screen-prompts-2026`

Baseline commit:

- `ea106eaf82c6df359a4546360fc233158ea02a9a`

## Current Component Role

`moqui-mcp` is currently a minimal MCP protocol adapter for Moqui.

It already provides:

- stateless MCP Streamable HTTP on `/mcp`
- MCP protocol version `2026-07-28`
- Moqui-authenticated request handling
- basic tool, resource, and prompt catalog exposure

It does not yet compile screen/form definitions into prompt contracts.

## Confirmed Runtime Surface

### Supported MCP methods

Implemented in `McpClient.handle()`:

- `server/discover`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/read`
- `prompts/list`
- `prompts/get`

Not yet implemented:

- `resources/templates/list`
- `completion/complete`

### Servlet behavior

Implemented in `McpServlet.groovy`:

- accepts only `POST`
- validates `MCP-Protocol-Version`
- validates `Mcp-Method`
- validates `Mcp-Name` for:
  - `tools/call`
  - `resources/read`
  - `prompts/get`
- validates `_meta.io.modelcontextprotocol/protocolVersion`
- validates `_meta.io.modelcontextprotocol/clientCapabilities`
- writes JSON-RPC errors with correct HTTP status

Authentication model:

- standard Moqui auth via `MoquiAuthFilter`
- optional fallback `-Dmoqui.mcp.serviceAccountUserId=<userId>` for trusted internal calls

### Smoke validation already confirmed

Validated on isolated runtime:

- `server/discover`
- `tools/list`
- `resources/read`
- negative mismatch handling for header/body method mismatch

## Current Tool Catalog

Exposed built-in tools:

- `moqui_search_data_documents`
- `moqui_call_service`
- `moqui_make_notification`

Current behavior:

- `moqui_search_data_documents` delegates to `mantle.GeneralServices.search#General`
- `moqui_call_service` invokes a named Moqui service with explicit parameters
- `moqui_make_notification` bridges to `NotificationMessage`

Confirmed gap:

- missing `moqui_get_service_metadata`
- missing generic transition executor for screen transitions with embedded logic

## Current Resource Model

Current URI families:

- `entity://<entityName>`
- `record://<entityName>?field=value`
- `datadocument://<dataDocumentId>`

Current behavior:

- `entity://` returns schema-style metadata
- `record://` executes `.one()` with arbitrary conditions
- `datadocument://` returns DataDocument definition metadata and fields

Confirmed gaps:

- no `resources/templates/list`
- no canonical resource template model
- `record://` is not deterministic because it does not require a complete PK
- no explicit PK tokenization
- no explicit filtering strategy for sensitive record fields beyond raw entity access

## Current Prompt Model

Prompts are currently backed only by:

- `moqui.resource.wiki.WikiPage`
- constrained to wiki space `MCP_PROMPTS`

Current behavior:

- `prompts/list` returns wiki page paths
- `prompts/get` returns a single user-role message with page text
- `arguments` passed to `prompts/get` are ignored

Confirmed gaps:

- no screen-derived prompts
- no parameterized prompt rendering
- no lookup/completion metadata for prompt arguments
- no binding from prompt to service or transition execution
- no navigation semantics between prompts

## Current Architectural Mismatch Against The New Plan

The new architecture requires:

- entities and DataDocuments as resources
- services and explicit server-side operations as tools
- screens/forms as prompt contracts

The current component satisfies only the first two partially and the third not at all.

Today there is no compiled interaction contract of the form:

```text
ScreenDefinition/Form/Transition
  -> PromptDescriptor
  -> ArgumentDescriptor[]
  -> CompletionDescriptor[]
  -> ExecutionBinding?
  -> NavigationBinding[]
  -> ResourceBinding[]
```

## Confirmed Gaps To Address Next

Priority gaps confirmed by code inspection:

1. Add `resources/templates/list`
2. Add `completion/complete`
3. Pass full request params through the dispatch path instead of only selected submaps
4. Preserve support for `_meta`, `cursor`, `requestState`, `inputResponses`, and future context fields
5. Add screen-derived prompt provider
6. Add prompt argument rendering instead of ignoring arguments
7. Add multi round-trip `input_required` / elicitation handling
8. Replace `record://` arbitrary lookup with deterministic PK-based resource reads
9. Add `moqui_get_service_metadata`
10. Add safe execution binding for screen transitions not reducible to a single service call

## Explicit Non-Goals For This Component

Still out of scope for `moqui-mcp`:

- algebraic morphism composition
- autonomous workflow planning
- agent-driven multi-step business orchestration
- category-theory execution plans
- harness runtime policies

These remain responsibilities of `moqui-harness` and related components.
