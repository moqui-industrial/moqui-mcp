# MCP 2026-07-28 Compliance Matrix

Qualification date: 2026-10-15, after the move to the llm-client tools (the previous design and its matrix are on the `old-mcp` branch). LibreChat is excluded from this qualification by explicit scope decision.

## Frozen Inputs

| Input | Frozen value |
|---|---|
| MCP specification repository | `modelcontextprotocol/modelcontextprotocol` tag `2026-07-28`, commit `5f5440bb26a62e2cf3440b92da5a667efa03b267` |
| Official JSON Schema | SHA-256 `ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203` |
| Schema validator | `com.networknt:json-schema-validator:2.0.4` |
| Minimal Moqui framework | upstream `6b197443` |
| Minimal Moqui runtime | upstream `cffc985dc782045d1b6376fe2ce5eebec7cf775e` |
| Mantle qualification | `mantle-udm` `73482e52`, `mantle-usl` `3fa07c67` |
| Search backend | OpenSearch `3.4.0` |
| Java | OpenJDK 21 |

## Requirement Matrix

| Requirement | Implementation | Automated evidence | Result |
|---|---|---|---|
| Only MCP `2026-07-28` is offered | `McpServlet`, `McpClient` | `McpCoreUnitTests`, `McpServletIntegrationTests` | PASS |
| JSON-RPC envelope, metadata, version and required headers | `McpServlet` | six real HTTP/Jetty tests, including mismatch and malformed cases | PASS |
| Content negotiation, Origin/CORS, body limit and method boundary | `McpServlet` | `McpServletIntegrationTests` | PASS |
| Standard Moqui identity is required | `McpServlet.ensureAuthenticated` | unauthenticated real HTTP request receives 401 | PASS |
| No implicit localhost user | disabled-by-default service-account properties | unauthenticated and explicit-loopback fixture tests | PASS |
| Complete results conform to the frozen official schema | all protocol providers | independent NetworkNT validation of all seven result definitions | PASS |
| Native MCP `2026-07-28` uses `server/discover`; scoped LibreChat `2025-11-25` initialize and `ping` compatibility is available | `McpServlet` legacy profile adapter | `McpServletIntegrationTests` | PASS |
| Notification bridge and subscriptions are absent | removed registry/tool/capabilities | core and catalog tests | PASS |
| First-page pagination, stable cursor and bounds | `McpPaginationSupport` | malformed, negative and overflow cursor tests | PASS |
| Tools are the server-side llm-client tools of the profile, under their llm-client names | `McpClient.llmTools` | tool catalog test on the `assist` profile | PASS |
| Browser, conversation and skill tools are not offered (`write_ui`, `pin`, `find_skill`, `enter_sim`) | `McpClient.llmTools` | catalog and unknown-tool tests | PASS |
| `screen_use` offers navigate only, checked by llm-client, and notifies the user's Moqui page | `McpClient.screenNavigate` | navigate, refused path and refused action tests | PASS |
| Resources and prompts are not offered | `McpClient.handle` | core unsupported-method tests | PASS |
| The llm-client tools require the `LlmGateway` permission | `McpClient.hasGateway` | list/call test with a user without the permission | PASS |
| Tool calls run as the user with Moqui authorization | llm-client `run_service`, `request` | real service engine and service REST tests | PASS |
| llm-client refusals are tool errors without stack traces | `McpClient.toolResult` | refusal and status tests | PASS |
| Caller messages survive nested dispatch | message snapshot/restore | real service invocation test | PASS |
| Screen hits carry a link for the user | `McpClient.addScreenLinks` | `browse` test | PASS |
| Entity REST (`/rest/e1`) through `request` | llm-client `WebFacadeStub` | test records that llm-client refuses it | KNOWN LIMIT |
| Search is unavailable without Mantle | conditional builtin registration | minimal upstream runtime suite without Mantle | PASS |
| Search delegates through authorized Moqui/Mantle services | `search#AuthorizedDataDocuments` | real Moqui service engine plus OpenSearch | NOT RE-RUN |
| USER_ORG and ACTIVE_ORG are server-derived | Mantle setup service and entity filters | two users, two organizations, active-organization switch | NOT RE-RUN |
| Search query syntax cannot escape organization scope | `McpSearchSupport` and Mantle conjunction | OR, wildcard, field and `_exists_` queries on OpenSearch | NOT RE-RUN |
| Search parameters cannot override backend or organization filters | strict `find_document`/service input contracts | scope/type/cluster/filter/organization negative tests | NOT RE-RUN |
| Counts and pagination remain in authorized scope | transformed Mantle result | any-type count/page test | NOT RE-RUN |
| OAuth is not falsely advertised | no OAuth capability or discovery endpoint | configuration and documentation review | PASS, optional feature not implemented |
| LibreChat integration | legacy MCP compatibility only; UI is provided by `webroot` | not executed | EXCLUDED |

## Recorded Test Results

Runtime with Mantle (H2), 2026-10-15:

```text
:runtime:component:moqui-mcp:test
34 executed, 34 passed, 0 failed, 0 skipped
```

The search suite (`testMcpSearchBackend`, 13 iterations, passed on 2026-10-08 with the previous tool name) was not
re-run after the rename to `find_document`: it needs a runtime whose Mantle data feed does not own the `mantle`,
`mantle_party` and `mantle_product` indexes, and the only runtime at hand has that feed. The search service it covers
did not change; the tool name and the `query` argument did.

## Operational Boundary

These results qualify the component behavior in the tested configurations. A production deployment still owns TLS termination, credential lifecycle, IdP policy, rate limiting, backup, monitoring, sizing, dependency vulnerability scanning and load testing. The optional loopback service account must remain disabled unless the deployment explicitly needs and protects it.
