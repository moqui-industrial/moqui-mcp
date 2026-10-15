# moqui-mcp

Model Context Protocol server for Moqui, targeting MCP `2026-07-28` natively.

The component is a thin MCP layer over the llm-client of the Moqui framework. Its tools are the llm-client tools, with
their names, schemas and rules, as llm-client attaches them for one profile; it adds only what llm-client does not
have: a text search over the DataDocuments indexed in OpenSearch.

The previous design (one tool per service, entity resources, Wiki prompts) is kept on the `old-mcp` branch.

## Requirements

- a `moqui-framework` checkout with llm-client (`ec.llm`, `org.moqui.llm.LlmTool`)
- Java 21
- normal Moqui authentication for remote calls
- for `find_document`, mantle-usl and an OpenSearch-compatible backend

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

`tools/call` also requires `Mcp-Name` matching the tool name. Non-ASCII or otherwise unsuitable values may use the
canonical `=?base64?<value>?=` form defined by the frozen protocol schema.

The methods are `server/discover`, `tools/list` and `tools/call`. Resources and prompts are not offered.

The endpoint accepts normal Moqui authentication such as Basic Auth, `api_key`, or `login_key`, and a Bearer token of
the configured identity provider (see `McpBearerAuthFilter`). The optional local service-account fallback is disabled
by default. It may be enabled only for trusted loopback calls with both JVM properties:

```text
moqui.mcp.serviceAccountEnabled=true
moqui.mcp.serviceAccountUserId=<userId>
```

Browser cross-origin requests are denied by default. Same-origin requests are accepted; additional exact origins may be
supplied as a comma-separated JVM property:

```text
moqui.mcp.allowedOrigins=https://agent.example.com,https://admin.example.com
```

## Tools

The llm-client tools require the `LlmGateway` permission, as the `/llm` endpoint does. A user without it sees only
`find_document`, when that is available to them.

The tools are built from the llm-client profile named by `moqui_mcp_llm_profile` (default `assist`), in the same way
`LlmGateway.attachServletTools` builds them for that profile:

| Tool | When | What it does |
|---|---|---|
| `request` | the profile has `allowed-path` entries or `allow-unprefixed-request` | GET/POST/PUT/PATCH/DELETE on a webroot path as the user: screen transitions and form-list JSON (`/qapps/.../actions/<form>`), service REST (`/rest/s1/...`) |
| `browse` | `allow-browse` | finds screens (`q`), lists the catalog of screens, REST, services and entities (including view-entities, with `isView`) |
| `run_service` | `allow-run-service` | calls a service that requires authentication, as the user, with artifact authorization on |
| `find_skill` | always | searches the llm-client skills and returns their playbook |
| `enter_sim` | `allow-enter-sim` | runs an llm-client sub-agent in a simulation overlay that does not commit |
| `find_basic` | `allowed-basic-entity` entries | options of the allow-listed configuration entities |
| `find_document` | mantle-usl and the search service are available to the user | text search over the Mantle DataDocuments in OpenSearch, inside the organizations of the user |

Not offered: `screen_use` and `write_ui`, which run in the browser of a Moqui user, and `pin`, which keeps ids on a
Moqui conversation.

The tool results are the llm-client results. A result with an `error` or a `status` of 400 or more is returned as an
MCP tool error. Screen hits and rows of `browse` get `url`, the address the user can open: it is built on
`moqui_mcp_public_url` (for example `http://localhost:8080`), or on the address of the request when that property is
not set.

Known limits that come from llm-client:

- `request` renders the call with a `WebFacadeStub`, which does not handle the entity REST API (`/rest/e1`); entity
  and view-entity rows are read through screen form-lists or service REST resources
- the skill risk gate (confirm/irreversible) runs in the llm-client agent loop, so it does not apply to MCP calls;
  writes are limited only by Moqui authorization
- `find_skill` with `select` has no conversation to activate the skill on; the playbook is still returned

### find_document

`find_document` calls `org.moqui.mcp.McpServices.search#AuthorizedDataDocuments`, which delegates to
`mantle.GeneralServices.search#MantleFiltered` with the organization filters of the user (`MANTLE_ACTIVE_ORG` or
`MANTLE_USER_ORG`). An omitted or empty query lists one bounded page. Query text is trimmed, limited to 512 characters
and escaped as one backend query clause. Arguments other than `query`, `documentType`, `pageIndex` and `pageSize` are
rejected.

## Security Model

The MCP endpoint adds transport validation; business security stays with Moqui and llm-client:

- the tools run as the authenticated user, with the authorization rules of llm-client and of Moqui
- the tool list and every call are recalculated per request; a previous `tools/list` result is not a grant
- protocol errors returned to clients do not contain stack traces

## Moqui Service Entry Points

The XML services in `org.moqui.mcp.McpServices` are thin wrappers for in-process Moqui callers. The generic dispatcher
is `org.moqui.mcp.McpServices.mcp#Handle`.

## LibreChat

LibreChat `0.8.x` uses the legacy MCP `2025-11-25` `initialize` handshake and version `0.8.7` omits its protocol header
during initialization. Requests using `X-Moqui-Mcp-Profile: librechat` receive a compatibility translation for that
handshake, `notifications/initialized`, its post-initialize `ping` probe, and subsequent standard tools calls. Native
clients should use `server/discover` with MCP `2026-07-28`.

## Development

Run the component tests from the framework root:

```bash
./gradlew :runtime:component:moqui-mcp:test
```

The tests write to the database of the runtime, so stop a Moqui server that uses the same H2 database first. The
official MCP schema is downloaded once, verified against the SHA-256 in `docs/mcp-schema-manifest.json`, and then
reused from the Gradle user cache.

Run the optional search qualification against a disposable runtime and backend:

```bash
./gradlew :runtime:component:moqui-mcp:testMcpSearchBackend \
  -PmcpTestRuntime=/path/to/moqui-runtime-with-mantle \
  -PmcpSearchUrl=http://127.0.0.1:9200
```

This task creates and removes dedicated test users, authorization records, organization records, and three disposable
indexes named `mantle`, `mantle_party`, and `mantle_product`. Do not point it at a backend or a runtime whose Mantle
data feed uses those names.

Main implementation files:

- `src/main/groovy/org/moqui/mcp/McpServlet.groovy`: HTTP and JSON-RPC boundary
- `src/main/groovy/org/moqui/mcp/McpClient.groovy`: protocol methods and the llm-client tools
- `src/main/groovy/org/moqui/mcp/McpBearerAuthFilter.groovy`: Bearer token login
- `service/org/moqui/mcp/McpServices.xml`: Moqui service wrappers and the authorized search facade
