# MCP 2026-07-28 Compliance Matrix

Qualification date: 2026-10-08. LibreChat is excluded from this qualification by explicit scope decision.

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
| Legacy initialize, ping and notifications are not claimed | dispatcher capability map | `McpCoreUnitTests` | PASS |
| Notification bridge and subscriptions are absent | removed registry/tool/capabilities | core and catalog tests | PASS |
| First-page pagination, stable cursor and bounds | `McpPaginationSupport` | malformed, negative and overflow cursor tests | PASS |
| Only concrete `allow-remote="true"` services become tools | `McpClient.getServiceToolList` | real service-definition catalog test | PASS |
| Service aliases are stable, collision-resistant and MCP-safe | `McpClient.serviceToolName` | alias collision, length and determinism tests | PASS |
| Service visibility and invocation use effective Moqui authorization | `isServiceVisible`, `assertServiceCallable`, `ec.service.sync()` | list/call/revoke tests through real service engine | PASS |
| Helper and direct service tools have equivalent controls | `moqui_call_service`, metadata helper | direct/helper and non-remote denial tests | PASS |
| Business errors are tool errors without stack traces | `invokeService` | validation and search rejection tests | PASS |
| Caller messages survive nested dispatch | message snapshot/restore | real service invocation test | PASS |
| Entity and view metadata use effective runtime definitions | `MoquiResourceProvider` | entity/view aliases, members, relationships and functions | PASS |
| Record reads are deterministic and read-only | Entity Facade read path | special/composite PK and view-without-PK tests | PASS |
| Encrypted entity fields are not exported | field projection in resource provider | `SystemMessageRemote` fixture | PASS |
| DataDocument without an authorizable source fails closed | resource visibility rules | provider integration coverage | PASS |
| Wiki prompt list/get visibility is consistent | `WikiPromptProvider`, `CompositePromptProvider` | real WikiSpace/WikiPage ACL fixtures with two users | PASS |
| Prompt substitution is exact and non-evaluating | `WikiPromptProvider.makePromptResult` | missing/extra/non-recursive placeholder tests | PASS |
| Search is unavailable without Mantle | conditional builtin registration | minimal upstream runtime suite without Mantle | PASS |
| Search delegates through authorized Moqui/Mantle services | `search#AuthorizedDataDocuments` | real Moqui service engine plus OpenSearch | PASS |
| USER_ORG and ACTIVE_ORG are server-derived | Mantle setup service and entity filters | two users, two organizations, active-organization switch | PASS |
| Search query syntax cannot escape organization scope | `McpSearchSupport` and Mantle conjunction | OR, wildcard, field and `_exists_` queries on OpenSearch | PASS |
| Search parameters cannot override backend or organization filters | strict tool/service input contracts | scope/type/cluster/filter/organization negative tests | PASS |
| Counts and pagination remain in authorized scope | transformed Mantle result | any-type count/page test | PASS |
| OAuth is not falsely advertised | no OAuth capability or discovery endpoint | configuration and documentation review | PASS, optional feature not implemented |
| LibreChat integration | optional files, no active routes in `MoquiConf.xml` | not executed | EXCLUDED |

## Recorded Test Results

Minimal upstream runtime, without Mantle or LibreChat:

```text
:runtime:component:moqui-mcp:test
34 executed, 34 passed, 0 failed, 0 skipped
```

Mantle/OpenSearch security qualification:

```text
:runtime:component:moqui-mcp:testMcpSearchBackend
13 executed, 13 passed, 0 failed, 0 skipped
```

The two suites execute 47 test iterations in total. The search suite is intentionally separate because it requires a live disposable OpenSearch-compatible backend; its absence is not converted into a skipped test.

## Operational Boundary

These results qualify the component behavior in the tested configurations. A production deployment still owns TLS termination, credential lifecycle, IdP policy, rate limiting, backup, monitoring, sizing, dependency vulnerability scanning and load testing. The optional loopback service account must remain disabled unless the deployment explicitly needs and protects it.
