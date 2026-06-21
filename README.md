# moqui-mcp

`moqui-mcp` is a Moqui component that exposes Moqui capabilities through the Model Context Protocol (MCP).

The project is still under active development. The current direction is to keep the agent layer thin and grounded in standard Moqui mechanisms:

- Moqui services, entities, screens, transitions, and authorization
- `DataDocument` and `DataFeed` for declarative knowledge projection
- OpenSearch for retrieval
- Moqui graph entities for artifact topology and knowledge lineage

## Current Focus

The component is evolving along three complementary layers:

1. Runtime MCP integration for external agent clients such as LibreChat
2. Declarative retrieval over Moqui knowledge projected into OpenSearch
3. Artifact understanding based on Moqui-native structures instead of hard-coded scripts

The long-term goal is not to create a parallel application runtime, but to let an agent understand and operate Moqui through Moqui itself.

## Current Status

Current repository status:

- usable development component
- MCP servlet and tool services integrated
- LibreChat integration available
- graph-based artifact knowledge foundation in place
- OpenSearch-backed retrieval in place
- still being refined for richer prompt decomposition and broader business coverage

This repository should be considered a development repository, not a final production release.

## Design Principles

- Moqui remains the authoritative runtime
- the database remains authoritative for business state
- OpenSearch indexes derived knowledge, not source-of-truth business data
- knowledge projection should move from custom scripts toward declarative Moqui `DataDocument` / `DataFeed` patterns
- artifact topology should be modeled with `Graph`, `GraphVertex`, and `GraphEdge`
- mesh/state-space modeling is intentionally deferred for now

## Main Functional Areas

- MCP endpoint and transport
- runtime context and session context management
- guarded execution and audit logging
- agent prompt search and document retrieval
- artifact graph extraction and graph seed loading
- OpenSearch indexing and retrieval evaluation

## Repository Layout

- [component.xml](component.xml): component descriptor
- [MoquiConf.xml](MoquiConf.xml): component configuration
- [entity](entity): component entities
- [service](service): MCP, agent, runtime, and retrieval services
- [screen](screen): Moqui app and LibreChat screens
- [src](src): Groovy and Java implementation classes
- [data](data): seed data, graph metadata, runtime metadata
- [tools](tools): validation, indexing, OpenSearch, and support scripts
- [docs](docs): architecture notes, plans, and working documentation

## Important Documents

- [docs/moqui-mcp-architecture-summary.md](docs/moqui-mcp-architecture-summary.md)
- [docs/ArtifactKnowledgeImplementationPlan.md](docs/ArtifactKnowledgeImplementationPlan.md)
- [docs/ArtifactKnowledgeSourceInventory.md](docs/ArtifactKnowledgeSourceInventory.md)
- [docs/graph-vs-mesh-decision.md](docs/graph-vs-mesh-decision.md)
- [docs/librechat-e2e-test-plan.md](docs/librechat-e2e-test-plan.md)

## Build

Build the component from the Moqui framework root:

```bash
./gradlew :runtime:component:moqui-mcp:jar
```

Some development tasks also exist in [build.gradle](build.gradle) for indexing, evaluation, and validation workflows.

## Deployment Notes

The `moqui-mcp` component contains the Moqui-side integration:

- MCP servlet and tool services
- Moqui screens for Agent Chat / LibreChat
- Moqui reverse proxy configuration for `/librechat/*`
- agent retrieval, execution, and graph knowledge logic

Docker deployment assets for the AI stack are intentionally maintained outside this component in the `moqui-deploy` repository, under the `ai/` profile. That profile is the current home for:

- LibreChat Docker Compose
- LibreChat MCP client configuration
- dedicated OpenSearch and OpenSearch Dashboards Docker files
- plugin bootstrap scripts and related local deploy assets

This keeps `moqui-mcp` focused on component logic while `moqui-deploy` owns containerized deployment concerns.

## Runtime Configuration Notes

Some settings belong to the runtime environment instead of the component itself. In particular, local OpenSearch connection properties such as:

- `elasticsearch_url`
- `elasticsearch_user`
- `elasticsearch_password`

should normally be configured in runtime environment files such as `runtime/conf/MoquiDevConf.xml`, environment variables, or deployment-specific configuration, not hard-coded as component defaults in `MoquiConf.xml`.

## OpenSearch Usage Notes

`moqui-mcp` can work with different OpenSearch setups depending on the environment:

- a local runtime node started directly from the Moqui environment
- a dedicated containerized OpenSearch stack from `moqui-deploy/ai`
- another externally managed OpenSearch service

For local development, both of these patterns are acceptable:

- direct local runtime endpoint such as `http://127.0.0.1:9200`
- containerized endpoint exposed by the AI deploy profile

The important rule is that the OpenSearch endpoint, credentials, and security mode belong to deployment configuration, not to the component source itself.

If you switch between local runtime OpenSearch and the containerized AI profile, update only the runtime/deploy configuration that provides:

- `elasticsearch_url`
- `elasticsearch_user`
- `elasticsearch_password`

The `moqui-mcp` source code and component defaults should remain unchanged.

## Development Notes

- this repository is intentionally kept aligned with the runtime copy used during local Moqui development
- generated output, build artifacts, and caches should stay out of version control
- if a behavior can be modeled with standard Moqui metadata, prefer that over adding new hard-coded extraction logic

## Near-Term Roadmap

- continue shifting artifact knowledge from custom scripts toward declarative projections
- improve prompt decomposition into entity, operation, and parameter intent
- broaden coverage of common Moqui aggregate patterns
- harden end-to-end testing across LibreChat, MCP, retrieval, and guarded execution

## License

See [LICENSE.md](LICENSE.md).
