# moqui-mcp

Minimal Model Context Protocol server for Moqui, targeting MCP `2026-07-28` natively.

The component maps Moqui artifacts to MCP without introducing a second application or authorization model:

- concrete Moqui services become tools when the current user is artifact-authorized
- entity and view-entity definitions, records, and DataDocument definitions become resources
- published pages in the `MCP_PROMPTS` Wiki space become prompts

## Requirements

- a compatible `moqui-framework` checkout
- Java 21
- normal Moqui authentication for remote calls
- optional business components contribute their own services, entities, and DataDocuments through the standard Moqui facades

Install the component under `runtime/component/moqui-mcp`, then build from the framework root:

```bash
./gradlew :runtime:component:moqui-mcp:build
```

## Endpoint

The canonical stateless Streamable HTTP endpoint is:

```text
POST /mcp
```

Requests use JSON-RPC 2.0 with all of these transport headers:

```text
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2026-07-28
Mcp-Method: <JSON-RPC method>
```

`tools/call`, `resources/read`, and `prompts/get` also require `Mcp-Name` matching the request name or URI. Non-ASCII or otherwise unsuitable values may use the canonical `=?base64?<value>?=` form defined by the frozen protocol schema.

The endpoint accepts normal Moqui authentication such as Basic Auth, `api_key`, or `login_key`. The optional local service-account fallback is disabled by default. It may be enabled only for trusted loopback calls with both JVM properties:

```text
moqui.mcp.serviceAccountEnabled=true
moqui.mcp.serviceAccountUserId=<userId>
```

Browser cross-origin requests are denied by default. Same-origin requests are accepted; additional exact origins may be supplied as a comma-separated JVM property:

```text
moqui.mcp.allowedOrigins=https://agent.example.com,https://admin.example.com
```

The MCP OAuth extension is not implemented or advertised. Deployments requiring OAuth should terminate authorization at a tested Moqui/IdP integration; Basic Auth and Moqui login/API keys are the supported endpoint authentication mechanisms in this component.

## Tools

`tools/list` contains the built-in adapters and concrete service tools visible to the current user. Results are stable, sorted, and paginated; the default page size is 100 and the maximum is 1000.

Built-in adapters:

- `moqui_search_data_documents` calls `search#AuthorizedDataDocuments` when the deployment contributes an authorized DataDocument search implementation
- `moqui_get_service_metadata` returns the effective Moqui service schemas after applying the same publication and authorization checks as invocation
- `moqui_call_service` invokes a published service by its full Moqui service name

Direct service tools use deterministic MCP-safe aliases. The full service name remains available in `_meta.org.moqui/originalName`.

### Service publication policy

A service is published only when all of these conditions hold:

1. its effective `ServiceDefinition` is concrete, not an interface
2. it is not an internal transport service of this component
3. the current user is authorized for the service's effective Moqui action (`view`, `create`, `update`, `delete`, or `all`)

`allow-remote` is retained as service metadata but is not an MCP publication boundary. The same artifact-aware authorization that protects Moqui services decides both discovery and invocation on every request. Internal MCP transport services are excluded explicitly.

Grant users only the service artifacts and entity filters required for their role. For business search, grant the authorized search facade together with a supported entity filter set; do not grant arbitrary backend access.

### Search scope

The search tool is present only when an authorized search facade is available to the caller. Its scopes are supplied by the installed application DataDocuments. Query text is trimmed, limited to 512 characters, escaped as one backend query clause, and combined with server-derived entity filters. Client-supplied index names, cluster names, filter maps, organization IDs, and backend DSL are rejected.

## Resources

Canonical templates:

```text
moqui://entity-def/{entityName}
moqui://entity/{entityName}/{primaryKeyToken}
moqui://data-document/{dataDocumentId}
```

The provider uses effective runtime entity definitions, including view-entities and extensions. Reads use the normal Entity Facade and artifact authorization; no authorization is disabled. Fields declared with `encrypt="true"` are omitted from record payloads. A DataDocument without an authorizable primary entity or manual data service is not published.

For a single-field primary key, `primaryKeyToken` is its URL-encoded value. Composite keys use `field=value;field=value` and must contain every primary-key field exactly once.

## Prompts

Prompts are published only from `WikiPage` records in `MCP_PROMPTS` that have a published version. `${argumentName}` placeholders are required at retrieval time; missing and unknown arguments are rejected.

No resource subscriptions or list-change notifications are advertised because this component does not currently deliver them.

## Security Model

The MCP endpoint adds transport validation but delegates business security to Moqui:

- service calls run through `ec.service.sync()` with standard authentication, authorization, validation, transactions, SECAs, and entity filters
- service visibility is recalculated for each list or call; a previous `tools/list` result is not a grant
- entity reads use standard artifact authorization and Entity Facade filters
- the search adapter does not accept arbitrary index names, organization overrides, filter maps, or raw backend configuration
- protocol errors returned to clients do not contain stack traces

There is no MCP-specific permission table. Grant the endpoint permission and the underlying service/entity permissions independently.

## Moqui Service Entry Points

The XML services in `org.moqui.mcp.McpServices` are thin wrappers for in-process Moqui callers. They are transport infrastructure and are not recursively listed as application tools.

The generic dispatcher is:

```text
org.moqui.mcp.McpServices.mcp#Handle
```

## LibreChat

The retained LibreChat screen files are optional deployment assets, not part of the MCP protocol or its authorization model. `MoquiConf.xml` does not register LibreChat routes, root-level proxy paths, or menus. A deployment that uses these screens must add narrowly scoped proxy and screen configuration in its own runtime configuration and keep credentials outside source control.

LibreChat `0.8.x` uses the legacy MCP `2025-11-25` `initialize` handshake and version `0.8.7` omits its protocol header during initialization. Requests using `X-Moqui-Mcp-Profile: librechat` receive a compatibility translation for that handshake, `notifications/initialized`, its post-initialize `ping` probe, and subsequent standard tools/resources/prompts calls. This adapter is only for the optional LibreChat test client; native clients should use `server/discover` with MCP `2026-07-28`.

## Development

Run the component tests from the framework root:

```bash
./gradlew :runtime:component:moqui-mcp:test
./gradlew :runtime:component:moqui-mcp:check
```

The official MCP schema is downloaded once, verified against the SHA-256 in `docs/mcp-schema-manifest.json`, and then reused from the Gradle user cache. After a verified first download, the regular suite can run offline.

Run the optional search security qualification against a disposable backend:

```bash
./gradlew :runtime:component:moqui-mcp:testMcpSearchBackend \
  -PmcpTestRuntime=/path/to/moqui-runtime-with-mantle \
  -PmcpSearchUrl=http://127.0.0.1:9200
```

This task creates and removes dedicated test users, authorization records, organization records, and three disposable indexes named `mantle`, `mantle_party`, and `mantle_product`. Do not point it at a backend containing indexes with those names.

Inspector instructions are in `docs/MCPInspector.md`. The local helper is `tools/inspector/run-inspector-local.sh`.
The requirement-to-test matrix and latest qualification are in `docs/McpComplianceMatrix.md`; the frozen schema and SHA-256 are in `docs/mcp-schema-manifest.json`.

Main implementation files:

- `src/main/groovy/org/moqui/mcp/McpServlet.groovy` — HTTP and JSON-RPC boundary
- `src/main/groovy/org/moqui/mcp/McpClient.groovy` — protocol dispatch and service tools
- `src/main/groovy/org/moqui/mcp/MoquiResourceProvider.groovy` — resources and templates
- `src/main/groovy/org/moqui/mcp/WikiPromptProvider.groovy` — Wiki-backed prompts
- `service/org/moqui/mcp/McpServices.xml` — Moqui service wrappers and authorized search facade
