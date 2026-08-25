# moqui-mcp

Minimal MCP protocol component for Moqui, aligned to MCP `2026-07-28`.

This component is intentionally narrow:

- `tools` expose Moqui services
- `resources` expose Moqui entities, view-entities, records, and DataDocument definitions
- `prompts` come only from Moqui `WikiPage` content in the `MCP_PROMPTS` wiki space
- `notifications` are bridged through standard Moqui `NotificationMessage`

This component does **not** try to convert rich ERP screens into conversational workflows.
Complex UI work remains in Moqui screens. `moqui-mcp` only publishes a minimal, standards-aligned MCP surface.

## Scope

`moqui-mcp` should stay small and stable.

It should not contain:

- screen-derived prompt generation
- lookup transitions exposed as MCP resources
- OpenSearch prompt catalogs
- skill orchestration
- workflow planning
- algebraic models or morphism composition

Those responsibilities belong elsewhere, such as `moqui-harness`, `moqui-math`, or curated offline skill repositories.

## MCP Mapping

### Tools

Tools map to Moqui services.

The server exposes:

- built-in helper tools
- concrete Moqui services discovered from the service facade, when `allow-remote="true"` and authorized for the current user

Built-in helper tools:

- `moqui_search_data_documents`
- `moqui_get_service_metadata`
- `moqui_call_service`
- `moqui_make_notification`

### Resources

Resources map to Moqui data and metadata.

Canonical resource templates:

- `moqui://entity-def/{entityName}`
- `moqui://entity/{entityName}/{primaryKeyToken}`
- `moqui://data-document/{dataDocumentId}`

These cover:

- entity and view-entity definitions
- deterministic entity record reads by primary key
- DataDocument definition metadata

### Prompts

Prompts come only from `WikiPage` rows in the `MCP_PROMPTS` wiki space.

This is intentionally manual and curated. A wiki-backed MCP prompt is:

- discoverable with `prompts/list`
- retrievable with `prompts/get`
- parameterized through simple `${argName}` substitutions

The prompt text is for the LLM. The business-facing discovery language can be curated directly in the wiki page title and content.

If the `MCP_PROMPTS` wiki space contains no pages, `prompts/list` will correctly return an empty list.

## Security

`moqui-mcp` relies on standard Moqui artifact-aware security.

- service tools are visible only if the current user is allowed to view or invoke the corresponding Moqui service
- entity and DataDocument resources are visible only if the current user is allowed to view the underlying artifact

There is no separate MCP-specific authorization model in this component.

## Transport

The MCP Streamable HTTP endpoint is:

- `/mcp`

The generic Moqui service facade entrypoint remains:

- `org.moqui.mcp.McpServices.mcp#Handle`

## Runtime Notes

- requests are stateless
- remote callers should use normal Moqui authentication
- trusted local callers may optionally use the local service-account fallback configured through JVM properties

## Internal Structure

- `src/main/groovy/org/moqui/mcp/McpServlet.groovy`
  HTTP MCP servlet
- `src/main/groovy/org/moqui/mcp/McpClient.groovy`
  MCP dispatch and Moqui integration
- `src/main/groovy/org/moqui/mcp/MoquiResourceProvider.groovy`
  resource and resource-template publishing
- `src/main/groovy/org/moqui/mcp/WikiPromptProvider.groovy`
  wiki-backed prompt provider
- `service/org/moqui/mcp/McpServices.xml`
  thin Moqui service wrappers over `McpClient`

## Design Decision

This component no longer treats Moqui screens as MCP prompts.

Reason:

- simple conversational prompts work well for small, atomic ERP interactions
- rich screens such as order, invoice, shipment, and payment workspaces are operational consoles, not prompts
- those screens should remain graphical UI, or be documented separately as curated agent skills outside this minimal MCP protocol component

## Inspector

An MCP Inspector client config is provided in:

- `docs/mcp-inspector.client.json`

The shell helper is:

- `tools/inspector/run-inspector-local.sh`

## Historical Context

The architectural rationale for the current minimal design is documented in:

- `docs/ArchitecturalEvolution.md`
