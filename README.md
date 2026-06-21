# moqui-mcp

`moqui-mcp` is a Model Context Protocol server for Moqui, designed as a thin adapter on top of native Moqui capabilities instead of a parallel runtime.

The component uses:

- Moqui artifact execution and authorization for runtime security
- Moqui `DataDocument` and `DataFeed` metadata for document governance
- Moqui Math graph entities for optional offline artifact graph materialization
- OpenSearch for lexical and optional vector retrieval
- Moqui `Visit` and `ExecutionContext` for session-aware behavior

The component adds:

- agent-oriented MCP tools
- screen-first agent document retrieval
- localization-aware embedding enrichment for screen-first prompts
- session working context
- MCP execution audit log
- guarded execution wrappers for high-risk operations

This README is the main source of truth for architecture, setup, OpenSearch preparation, validation, and release testing.

## Status

Current state:

- code-complete production candidate
- validated for Moqui runtime integration
- supports lexical retrieval out of the box
- supports vector and hybrid retrieval only when the OpenSearch node supports `knn_vector`

If OpenSearch does not support k-NN/vector search, `moqui-mcp` automatically falls back to lexical-only indexing and `bm25_fallback` runtime search behavior.

## Architecture

The primary workflow is:

`get runtime context -> get/update session context -> search agent prompts -> get agent document -> execute agent prompt`

Core responsibilities:

- `EnhancedMcpServlet`: MCP HTTP endpoint, session lifecycle, SSE transport, JSON-RPC dispatch
- `McpServices.xml`: MCP protocol services (`initialize`, `tools/list`, `tools/call`, `ping`)
- `AgentPromptServices.xml`: search and document retrieval
- `AgentExecutionServices.xml`: guarded execution and audit logging
- `AgentRuntimeServices.xml`: runtime context, session context, guarded entity lookup, artifact access checks
- `AgentDocumentServices.xml`: indexing, mapping, embedding generation, index-run tracking

Legacy/debug responsibilities:

- `RagServices.xml`: legacy artifact inspection/debug search, not the primary runtime prompt retrieval path

Persistent entities:

- `moqui.agent.AgentSessionContext`
- `moqui.agent.AgentToolExecutionLog`
- `moqui.agent.AgentDocumentIndexRun`
- `moqui.agent.ArtifactQueryEmbeddingCache`

## MCP Tools

Primary runtime tools:

- `moqui_get_runtime_context`
- `moqui_search_agent_prompts`
- `moqui_get_agent_document`
- `moqui_get_session_context`
- `moqui_update_session_context`
- `moqui_execute_agent_prompt`
- `moqui_find_records_guarded`
- `moqui_check_artifact_access`

Privileged tools (MCP_DEBUG / ADMIN only):

- `moqui_search_artifacts`: search raw artifacts (services, entities, screens, XSD schemas) in `moqui_artifacts_v1`
- `moqui_get_artifact`: inspect a specific artifact by type and name
- `moqui_search_all`: unified search across both `moqui_agent_prompts_v1` and `moqui_artifacts_v1`
- `moqui_call_service_guarded`: direct service execution with authz and confirmation gate

Note on tool selection:

- standard users: use `moqui_search_agent_prompts` → `moqui_get_agent_document` → `moqui_execute_agent_prompt`
- privileged users: may also use `moqui_search_artifacts` / `moqui_get_artifact` to access raw Moqui artifact metadata including XSD schemas, and `moqui_call_service_guarded` to call services directly

Tool visibility is filtered by user group:

- `MCP_RUNTIME`: primary runtime tools only
- `MCP_INDEX_ADMIN`: indexing services
- `MCP_DEBUG`: debug tools
- `ADMIN`: full access

## Security Model

Security rules:

- no business execution is wrapped in `disableAuthz()` in the MCP tool dispatcher
- service execution always goes through Moqui authorization
- high-risk actions require confirmation
- guarded entity lookup blocks broad reads on sensitive entities
- session context and tool logs are bound to the current Moqui user and visit

Important groups:

- `MCP_RUNTIME`
- `MCP_INDEX_ADMIN`
- `MCP_DEBUG`

Seed files:

- `data/McpSecuritySeedData.xml`: production-safe seed data
- `data/AgentDocumentSeedData.xml`: status items, `DataDocument`, `DataFeed`
- `data/AgentProjectionDocumentSeedData.xml`: draft `DataDocument` definitions for graph-aware projection
- `data/AgentArtifactGraphSeedData.xml`: root `moqui.math.Graph` seed for offline artifact graph loading
- `data/AgentMathModelSeedData.xml`: `MathModelDef`, `MathModel`, `ParameterDef`, and related governance metadata
- `data/McpDemoSeedData.xml`: optional demo-only seed data

Graph modeling decision:

- `Graph*` entities are the active structural model for artifact topology
- `Mesh*` entities are intentionally deferred for now
- see [docs/graph-vs-mesh-decision.md](docs/graph-vs-mesh-decision.md)

Artifact graph materialization:

- `org.moqui.agent.AgentDocumentServices.load#ArtifactGraph` loads offline-generated graph JSONL into `moqui.math.GraphVertex` and `moqui.math.GraphEdge`
- the root `moqui.math.Graph` record is seeded, but vertices/edges are generated data and should be loaded from real graph output
- `org.moqui.agent.AgentMathModelServices.*` records pipeline runs, tensor metadata, and lineage summaries in `moqui.math`

MathEntities governance:

- `Graph` models artifact topology
- `MathModelDef` models the agentic RAG pipeline definition
- `MathModel` models the concrete versioned pipeline instance
- `MathModelRun` records indexing, graph loading, evaluation, and summary runs
- `Tensor` / `TensorContent` record embedding matrix metadata without storing float arrays in SQL

For the current phase:

- use `GraphVertex` / `GraphEdge` for artifact relations
- do not model artifacts with `Mesh*` entities yet
- keep mesh/state-space reasoning as a future option, not an active dependency

The MathEntities layer does not replace OpenSearch, JSONL generation, or the MCP runtime. It records the structural and computational lineage of the agentic RAG pipeline: model definition, model configuration, model runs, graph structure, embedding tensor metadata, and run data. Graph is used for artifact topology; MathModel is used for pipeline/version/run governance.

## Test User

All documented validation steps in this component use a single Moqui demo user:

- username: `john.doe`
- password: `moqui`
- userId: `EX_JOHN_DOE`

`data/McpDemoSeedData.xml` grants this user the MCP test groups:

- `MCP_RUNTIME`
- `MCP_INDEX_ADMIN`
- `MCP_DEBUG`

Because the documented test user is also a debug-capable user, `tools/list` will include debug tools and the smoke runner should be invoked with `--expect-debug-access`.

The matching Basic Authorization header is:

```text
Authorization: Basic am9obi5kb2U6bW9xdWk=
```

If demo data is not loaded in your environment, create equivalent group memberships for the user you want to use and update the commands below accordingly.

## Component Layout

Important files:

- [component.xml](component.xml)
- [MoquiConf.xml](MoquiConf.xml)
- [service/McpServices.xml](service/McpServices.xml)
- [service/org/moqui/agent/AgentPromptServices.xml](service/org/moqui/agent/AgentPromptServices.xml)
- [service/org/moqui/agent/AgentExecutionServices.xml](service/org/moqui/agent/AgentExecutionServices.xml)
- [service/org/moqui/agent/AgentRuntimeServices.xml](service/org/moqui/agent/AgentRuntimeServices.xml)
- [service/org/moqui/agent/AgentDocumentServices.xml](service/org/moqui/agent/AgentDocumentServices.xml)
- [service/org/moqui/agent/AgentMathModelServices.xml](service/org/moqui/agent/AgentMathModelServices.xml)
- [entity/AgentEntities.xml](entity/AgentEntities.xml)
- [entity/RagEntities.xml](entity/RagEntities.xml)
- [tools/mcp_smoke_test.py](tools/mcp_smoke_test.py)
- [tools/agent-indexer/README.md](tools/agent-indexer/README.md)
- [tools/agent-indexer/evaluate_opensearch_modes.py](tools/agent-indexer/evaluate_opensearch_modes.py)

## Build And Deployment

`moqui-mcp` follows the standard Moqui component build pattern and must be compiled into a component jar for servlet classes such as `EnhancedMcpServlet`.

### 1. Copy the component into the runtime

Example runtime location:

`/path/to/moqui-framework/runtime/component/moqui-mcp`

### 2. Build the component jar

From the Moqui framework root:

```bash
cd /path/to/moqui-framework
./gradlew :runtime:component:moqui-mcp:jar
```

This creates:

- `runtime/component/moqui-mcp/lib/moqui-mcp-1.0.0.jar`

### Optional Gradle tasks

The component also provides optional Gradle tasks for local validation workflows. These tasks are not wired into `load`, `load run`, or any automatic bootstrap path.

Available tasks:

- `generateAgentDocs`
- `aggregateAgentDocs`
- `prepareAgentDocs`
- `indexAgentDocs`
- `smokeTestMcp`
- `evaluateMcp`

Typical usage:

```bash
./gradlew :runtime:component:moqui-mcp:prepareAgentDocs \
  -PmoquiRoot=/path/to/moqui/root

./gradlew :runtime:component:moqui-mcp:indexAgentDocs \
  -PauthHeader="Basic am9obi5kb2U6bW9xdWk="

./gradlew :runtime:component:moqui-mcp:smokeTestMcp \
  -PauthHeader="Basic am9obi5kb2U6bW9xdWk="

./gradlew :runtime:component:moqui-mcp:evaluateMcp \
  -PauthHeader="Basic am9obi5kb2U6bW9xdWk="
```

Useful optional properties:

- `-PpythonExecutable=python3`
- `-PmoquiRoot=/path/to/moqui/root`
- `-PmcpEndpoint=http://localhost:8080/mcp`
- `-PrpcEndpoint=http://localhost:8080/rpc/json`
- `-PindexName=moqui_agent_prompts_v1`
- `-PsessionId=gradle-session`
- `-PauthHeader="Basic ..."`

## Release Procedure

Before packaging or publishing the component:

```bash
cd moqui-mcp
./tools/clean_release_tree.sh
./tools/check_release_tree.sh
zip -r ../moqui-mcp-release.zip .
```

The release tree must not contain:

- `build/`
- `bin/`
- `lib/`
- `__pycache__/`
- `.pytest_cache/`
- `tools/output/`
- `tools/agent-indexer/output/`
- `*.pyc`
- `*.pyo`

## Evaluation Procedure

Recommended validation order:

1. `pytest -q tools/agent-indexer/tests`
2. `python3 tools/runtime_validation_test.py ...`
3. `python3 tools/mcp_smoke_test.py ...`
4. `python3 tools/agent-indexer/evaluate_opensearch_modes.py ...`

The full retrieval evaluation should be treated as a long `MathModelRun`, not as an untracked ad hoc benchmark.

### 3. Restart Moqui

After restart, confirm in `runtime/log/moqui.log` that you see:

- `Added servlet EnhancedMcpServlet on: [/mcp/*]`
- `EnhancedMcpServlet initialized`

If those lines do not appear, do not continue with MCP tests.

## Moqui Runtime Preparation

Before final testing:

1. Copy the latest component into `runtime/component/moqui-mcp`
2. Build the component jar
3. Restart Moqui
4. Verify servlet registration in `moqui.log`
5. Verify OpenSearch connectivity in `moqui.log`

For the local test environment used during development, `runtime/conf/MoquiDevConf.xml` should point to:

```xml
<default-property name="elasticsearch_url" value="http://127.0.0.1:9200"/>
```

If your local OpenSearch uses TLS and authentication instead, adjust:

- `elasticsearch_url`
- `elasticsearch_user`
- `elasticsearch_password`

## OpenSearch Preparation

The component supports two OpenSearch modes.

### Mode A: Lexical-only local validation

This is enough to validate:

- indexing
- MCP runtime
- BM25 search
- fallback behavior

In this mode, the OpenSearch node does not need the k-NN plugin.

If `index.knn` or `knn_vector` is unsupported, `moqui-mcp` automatically:

- retries index creation without vector settings
- removes the `embedding` field from the index mapping
- degrades `vector` and `hybrid` requests to `bm25_fallback`

### Mode B: True vector and hybrid validation

This is required for production-like validation of:

- `vector`
- `hybrid`
- `hybrid_rerank`

Your OpenSearch node must support the k-NN vector field type.

For `moqui-mcp`, the only additional OpenSearch plugin that is strictly required for vector and hybrid retrieval is:

- `opensearch-knn`

No extra OpenSearch plugin is required for embedding generation because `moqui-mcp` generates embeddings outside OpenSearch through its configured provider (`openai` or `openai_compatible`). Plugins such as `ml-commons` are therefore optional and not required for the documented runtime flow.

Based on the official OpenSearch plugin installation and k-NN documentation, the typical installation flow is:

```bash
cd /path/to/runtime/opensearch
bin/opensearch-plugin list
bin/opensearch-plugin install opensearch-knn
```

On some tar distributions, the short plugin name may not resolve correctly. In that case, install the version-matched ZIP explicitly.

For OpenSearch `3.4.0`, the verified command is:

```bash
cd /path/to/runtime/opensearch
bin/opensearch-plugin install --batch \
  https://repo1.maven.org/maven2/org/opensearch/plugin/opensearch-knn/3.4.0.0/opensearch-knn-3.4.0.0.zip
```

Then restart OpenSearch.

Verification options:

```bash
bin/opensearch-plugin list
curl -s http://127.0.0.1:9200/_nodes/plugins?pretty
```

What you need to verify:

- the plugin list includes `opensearch-knn`
- your node accepts index settings containing `index.knn`
- your node accepts mappings containing `knn_vector`

Recommended local workflow:

```bash
cd /path/to/moqui-framework
./gradlew stopElasticSearch
cd runtime/opensearch
bin/opensearch-plugin install --batch \
  https://repo1.maven.org/maven2/org/opensearch/plugin/opensearch-knn/3.4.0.0/opensearch-knn-3.4.0.0.zip
cd /path/to/moqui-framework
./gradlew startElasticSearch
```

Important note after plugin installation:

- if you previously indexed the catalog in lexical-only fallback mode, delete or force-recreate the MCP index
- configure a real embedding provider
- run indexing again so the new index is created with `index.knn=true` and the `embedding` field

For example, after installing the plugin:

1. set `moqui.agent.embedding.provider` to `openai` or `openai_compatible`
2. set `moqui.agent.embedding.model`
3. set `moqui.agent.embedding.dimensions`
4. rerun `org.moqui.agent.AgentDocumentServices.index#AgentDocuments` with `forceReindex=true`
5. rerun `evaluate_opensearch_modes.py`

If the plugin is not available in your distribution, stay in lexical-only validation mode and treat hybrid metrics as non-authoritative.

## OpenSearch Mapping

Canonical mapping file:

- [tools/agent-indexer/opensearch-mapping-agent-area.json](tools/agent-indexer/opensearch-mapping-agent-area.json)

The runtime mapping service:

- `org.moqui.agent.AgentDocumentServices.transform#AgentPromptDocumentMapping`

loads this JSON mapping and adjusts vector dimensions from runtime configuration.

Important fields include:

- `documentId`
- `documentKind`
- `area`
- `subArea`
- `domainObject`
- `promptGroupId`
- `actionKind`
- `operationEffect`
- `executionChannel`
- `runtimeExecutable`
- `preferredService`
- `executionRequiredContext`
- `fieldNames`
- `canonicalPrompt`
- `englishPromptVariants`
- `italianPromptVariants`
- `machineVariants`
- `uiLabels`
- `embeddingText`
- `embedding`

## Agent Documents

The recommended retrieval corpus is a screen-first agent document catalog generated from Moqui screens, services, entities, and EECA references.

Key document attributes:

- `documentId`
- `documentKind`
- `area`
- `subArea`
- `domainObject`
- `canonicalPrompt`
- `preferredService`
- `executionRequiredContext`
- `runtimeExecutable`
- `executionChannel`
- `sourceScreenPath`
- `embeddingText`

Runtime policy:

- `service`, `navigation`, `screen_render`, `screen_url`, `print`, `print_export`, and `download` are handled by the MCP runtime
- `screen_transition` is not resolved yet and returns `unsupportedChannel`
- `read_query` is not resolved yet and returns `needsResolver`
- `screen_query_prompt` documents are currently generated with `runtimeExecutable=false`

## Indexing

Primary runtime indexing service:

`org.moqui.agent.AgentDocumentServices.index#AgentDocuments`

Supported inputs:

- `filePath`
- `filePathList`
- `indexName`
- `forceReindex`
- `documentSchemaVersion`
- `embeddingProvider`
- `embeddingModel`
- `embeddingDimensions`
- `batchSize`
- `reportLocation`

What the service does:

- creates the OpenSearch index if needed
- loads the mapping from the canonical JSON mapping
- falls back to lexical-only mapping if vector settings are unsupported
- reads JSONL in batches
- generates embeddings from `embeddingText` when configured and needed
- bulk indexes documents
- records the run in `AgentDocumentIndexRun`

Useful output metrics:

- `indexedCount`
- `failedCount`
- `embeddedCount`
- `embeddingSkippedCount`
- `embeddingFailedCount`
- `agentDocumentIndexRunId`

### Running indexing from the Moqui UI

Open the service run screen for:

`org.moqui.agent.AgentDocumentServices.index#AgentDocuments`

Fill at least:

- `File Path`
- `Index Name`
- `Force Reindex`

Example values:

- `File Path`
  `/path/to/runtime/component/moqui-mcp/tools/agent-indexer/output/global-screen-prompt-documents.jsonl`
- `Index Name`
  `moqui_agent_prompts_v1`
- `Force Reindex`
  `true`

### Running indexing over JSON-RPC

Moqui service endpoint:

`http://localhost:8080/rpc/json`

Example payload:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "org.moqui.agent.AgentDocumentServices.index#AgentDocuments",
  "params": {
    "filePath": "/path/to/runtime/component/moqui-mcp/tools/agent-indexer/output/global-screen-prompt-documents.jsonl",
    "indexName": "moqui_agent_prompts_v1",
    "forceReindex": true
  }
}
```

## Search Modes

Runtime search service:

`org.moqui.agent.AgentPromptServices.search#AgentPrompts`

Supported modes:

- `bm25`
- `vector`
- `hybrid`
- `hybrid_rerank`
- `hybrid_llm_rerank`

Behavior:

- `bm25`: lexical search only
- `vector`: vector search when supported, otherwise `bm25_fallback`
- `hybrid`: lexical + vector merge when supported, otherwise `bm25_fallback`
- `hybrid_rerank`: lexical + vector + structured reranking when supported, otherwise `bm25_fallback`
- `hybrid_llm_rerank`: `hybrid_rerank` + LLM-based reranking when provider is configured; falls back to `hybrid_rerank` if provider is `none` or the call fails

Search results exclude `embedding` by default to keep MCP payloads small.

## LLM Reranker Mode and Cache

`hybrid_llm_rerank` is disabled by default. The default search mode is `hybrid`.

### Enabling LLM rerank

LLM rerank requires explicit configuration of both mode and provider:

```properties
moqui.agent.search.mode=hybrid_llm_rerank
moqui.agent.reranker.provider=openai
```

or with an OpenAI-compatible endpoint (LiteLLM, vLLM, Ollama):

```properties
moqui.agent.search.mode=hybrid_llm_rerank
moqui.agent.reranker.provider=openai_compatible
moqui.agent.reranker.compat.baseUrl=http://localhost:4000/v1
```

With the default `provider=none` the component never calls any external API, regardless of the mode:

```text
mode=hybrid               → no LLM call, no cost
mode=hybrid_rerank        → no LLM call, no cost
mode=hybrid_llm_rerank
  + provider=none         → fallback to hybrid_rerank, no LLM call
  + provider=openai       → LLM rerank active, external API cost
```

### Reranker properties

```properties
moqui.agent.reranker.provider=none
moqui.agent.reranker.model=gpt-5
moqui.agent.reranker.timeoutMs=30000
moqui.agent.reranker.maxCandidates=8
moqui.agent.reranker.temperature=0
moqui.agent.reranker.failOpen=true
moqui.agent.reranker.promptVersion=2026-05-llm-rerank-v1
```

### LLM rerank cache

When `hybrid_llm_rerank` is active and `provider != none`, the reranker result is cached using the standard Moqui `ec.cache` mechanism. No separate entity or cache framework is created.

Cache is enabled by default and safe to leave on even when `hybrid_llm_rerank` is not in use, because it is consulted only when the reranker actually runs.

Relevant properties:

```properties
moqui.agent.reranker.cache.enabled=true
moqui.agent.reranker.cache.name=moqui.agent.reranker.result
moqui.agent.reranker.cache.ttlSeconds=86400
```

The cache key includes:

- normalized query text
- query intent type and knowledge type
- candidate document IDs, kinds, domain objects, and scores (rounded to 4 decimal places)
- provider, model, and `promptVersion`
- `maxCandidates`

Changing any of these invalidates the cache entry naturally. To invalidate the entire cache after a prompt change:

1. Update `moqui.agent.reranker.promptVersion` to a new value, or
2. Call `org.moqui.agent.AgentRuntimeServices.clear#AgentRerankCache` (admin only)

TTL is managed inside the cached value, not at the cache layer, so entries are validated on read and removed if expired.

### Search debug fields for LLM rerank

When `moqui.agent.search.debug=true`, `searchDebug` includes:

```json
{
  "llmRerankEnabled": true,
  "llmRerankApplied": true,
  "llmRerankCacheEnabled": true,
  "llmRerankCacheHit": true,
  "llmRerankCacheKeyHash": "abc123def456",
  "rerankerProvider": "openai",
  "rerankerModel": "gpt-5",
  "rerankerLatencyMillis": 0,
  "rerankerSkippedReason": null
}
```

When the provider is `none`:

```json
{
  "llmRerankEnabled": false,
  "llmRerankApplied": false,
  "rerankerSkippedReason": "provider_none",
  "llmRerankCacheEnabled": true,
  "llmRerankCacheHit": false
}
```

## Embedding Configuration

Relevant properties:

- `moqui.agent.embedding.provider`
- `moqui.agent.embedding.model`
- `moqui.agent.embedding.dimensions`
- `moqui.agent.embedding.cacheQueryText`
- `moqui.agent.embedding.l1CacheTtlMs`
- `moqui.agent.embedding.l1CacheMaxSize`

Typical values:

```text
moqui.agent.embedding.provider=openai|openai_compatible|none
moqui.agent.embedding.model=text-embedding-3-large
moqui.agent.embedding.dimensions=3072
```

Recommended defaults:

- `moqui.agent.embedding.cacheQueryText=false`
- `moqui.agent.embedding.l1CacheTtlMs=3600000`
- `moqui.agent.embedding.l1CacheMaxSize=1000`

If `moqui.agent.embedding.provider=none`, the runtime search service degrades to lexical fallback and vector validation is not meaningful.

### Option A: OpenAI embedding provider (recommended)

This is the simplest production-like setup for `moqui-mcp`.

Start Moqui with JVM properties for OpenAI embeddings:

```bash
cd /path/to/moqui-framework

./gradlew \
  -Dmoqui.agent.embedding.provider=openai \
  -Dmoqui.agent.embedding.model=text-embedding-3-large \
  -Dmoqui.agent.embedding.dimensions=3072 \
  -Dmoqui.agent.embedding.apiKey="$OPENAI_API_KEY" \
  run
```

If you do not want to rely on an exported environment variable:

```bash
cd /path/to/moqui-framework

./gradlew \
  -Dmoqui.agent.embedding.provider=openai \
  -Dmoqui.agent.embedding.model=text-embedding-3-large \
  -Dmoqui.agent.embedding.dimensions=3072 \
  -Dmoqui.agent.embedding.apiKey="YOUR_OPENAI_API_KEY" \
  run
```

Recommended verification flow for Option A:

1. verify `opensearch-knn` is installed and visible in `_nodes/plugins`
2. restart Moqui with the OpenAI properties above
3. rerun `org.moqui.agent.AgentDocumentServices.index#AgentDocuments` with `forceReindex=true`
4. confirm:
   - `embeddedCount > 0`
   - `embeddingFailedCount = 0`
   - `embeddingProvider = openai`
5. rerun:
   - `tools/mcp_smoke_test.py`
   - `tools/agent-indexer/evaluate_opensearch_modes.py`

### Option B: OpenAI-compatible embedding provider

Use this when you have a local or self-hosted embedding server (LiteLLM, vLLM, Ollama with `/v1/embeddings`, LocalAI, SGLang, etc.).

```bash
./gradlew \
  -Dmoqui.agent.embedding.provider=openai_compatible \
  -Dmoqui.agent.embedding.model=text-embedding-3-large \
  -Dmoqui.agent.embedding.dimensions=3072 \
  -Dmoqui.agent.embedding.compat.baseUrl=http://localhost:4000/v1 \
  run
```

If the endpoint requires an API key:

```bash
-Dmoqui.agent.embedding.compat.apiKey="YOUR_KEY"
# or set the env variable:
export OPENAI_COMPATIBLE_API_KEY=YOUR_KEY
```

If your endpoint does not support the `dimensions` parameter (older models), set:

```text
moqui.agent.embedding.compat.includeDimensions=false
```

The `openai_compatible` provider uses the same `/embeddings` endpoint format as OpenAI.
Ollama with `--host 0.0.0.0` and `/v1/` path support works out of the box with this provider.

## Logging And Privacy

Relevant properties:

- `moqui.agent.log.parameters`
- `moqui.agent.log.results`
- `moqui.agent.search.debug`

Recommended production-oriented defaults:

- `moqui.agent.log.parameters=masked`
- `moqui.agent.log.results=summary`
- `moqui.agent.search.debug=false`

The component masks or summarizes sensitive parameters and results before persisting tool logs.

## Full Validation Runbook

### 1. Generate the screen-first catalog

```bash
cd /path/to/runtime/component/moqui-mcp/tools/agent-indexer
mkdir -p output

python3 generate_full_simplescreens_extension.py \
  --moqui-root /path/to/moqui/master \
  --output-dir ./output
```

Expected results:

- per-area directories under `output/<area>/`
- per-area `screen-prompt-documents.jsonl`
- per-area `screen-prompt-eval-queries.jsonl`
- `output/global-area-summary.json`
- `output/global-area-summary.md`

### 2. Aggregate the per-area files

```bash
cd /path/to/runtime/component/moqui-mcp/tools/agent-indexer

find ./output -mindepth 2 -maxdepth 2 -type f -name 'screen-prompt-documents.jsonl' \
  -print0 | sort -z | xargs -0 cat > ./output/global-screen-prompt-documents.jsonl

find ./output -mindepth 2 -maxdepth 2 -type f -name 'screen-prompt-eval-queries.jsonl' \
  -print0 | sort -z | xargs -0 cat > ./output/global-eval-queries.jsonl
```

### 3. Index the agent documents

Use the service UI or JSON-RPC and confirm:

- `indexedCount > 0`
- `failedCount = 0`
- `embeddingFailedCount = 0`, or clearly explain exceptions

### 4. Run MCP smoke tests

```bash
cd /path/to/runtime/component/moqui-mcp/tools
mkdir -p output

python3 mcp_smoke_test.py \
  --endpoint http://localhost:8080/mcp \
  --auth-header "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  --session-id smoke-session \
  --expect-debug-access \
  --out-json ./output/mcp-smoke-test-report.json \
  --out-md ./output/mcp-smoke-test-report.md
```

Optional arguments can be provided for:

- `--agent-document-id`
- `--dry-run-document-id`
- `--high-risk-document-id`
- `--missing-context-document-id`

Smoke pass criterion:

- `failed = 0`

### 5. Run runtime multi-mode evaluation

```bash
cd /path/to/runtime/component/moqui-mcp/tools/agent-indexer

python3 evaluate_opensearch_modes.py \
  --docs ./output/global-screen-prompt-documents.jsonl \
  --queries ./output/global-eval-queries.jsonl \
  --mcp-endpoint http://localhost:8080/mcp \
  --auth-header "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  --session-id eval-session \
  --output-dir ./output
```

Generated reports:

- `global-retrieval-opensearch-bm25.json`
- `global-retrieval-opensearch-bm25.md`
- `global-retrieval-opensearch-vector.json`
- `global-retrieval-opensearch-vector.md`
- `global-retrieval-opensearch-hybrid.json`
- `global-retrieval-opensearch-hybrid.md`
- `global-retrieval-opensearch-hybrid_rerank.json`
- `global-retrieval-opensearch-hybrid_rerank.md`
- `global-retrieval-opensearch-summary.json`
- `global-retrieval-opensearch-summary.md`

### 6. Final gates

Production-like gate:

- smoke report `failed = 0`
- `hybrid_rerank.groupRecallAt3 >= 0.90`
- `screen_query_prompt.groupRecallAt3 >= 0.80`
- no catastrophic cross-area confusion

Local lexical-only gate:

- smoke report `failed = 0`
- indexing succeeds
- BM25 report is generated
- fallback behavior is acceptable

If your OpenSearch node does not support vector search, do not treat vector or hybrid metrics as authoritative.

## Manual MCP Test Commands

Use these commands for quick manual checks against a local Moqui runtime.

### Manual initialize

```bash
curl -i -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  -d '{
    "jsonrpc":"2.0",
    "id":1,
    "method":"initialize",
    "params":{
      "protocolVersion":"2025-06-18",
      "capabilities":{},
      "clientInfo":{"name":"manual-test","version":"1.0"},
      "sessionId":"manual-session"
    }
  }'
```

Expected results:

- HTTP `200`
- response JSON with `result.protocolVersion`
- response header `Mcp-Session-Id`

Store the returned `Mcp-Session-Id` and reuse it for all subsequent calls:

```bash
export MCP_SESSION_ID=100001
```

### Manual notifications/initialized

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}" \
  -d '{
    "jsonrpc":"2.0",
    "method":"notifications/initialized",
    "params":{}
  }'
```

### Manual tools/list

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

### Manual search

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}" \
  -d '{
    "jsonrpc":"2.0",
    "id":3,
    "method":"tools/call",
    "params":{
      "name":"moqui_search_agent_prompts",
      "arguments":{"queryText":"list orders","limit":3}
    }
  }'
```

### Manual debug-tool probe

`john.doe` is intentionally granted `MCP_DEBUG` for local validation, so a debug-tool call should succeed:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}" \
  -d '{
    "jsonrpc":"2.0",
    "id":4,
    "method":"tools/call",
    "params":{
      "name":"moqui_search_artifacts",
      "arguments":{"queryText":"order","limit":2}
    }
  }'
```

### Manual session context update

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}" \
  -d '{
    "jsonrpc":"2.0",
    "id":5,
    "method":"tools/call",
    "params":{
      "name":"moqui_update_session_context",
      "arguments":{"currentArea":"Order","currentBusinessObjects":{"orderId":"TEST-ORDER-1"}}
    }
  }'
```

### Manual guarded entity denial check

This verifies that broad reads on sensitive entities are still blocked:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Authorization: Basic am9obi5kb2U6bW9xdWk=" \
  -H "Mcp-Session-Id: ${MCP_SESSION_ID}" \
  -d '{
    "jsonrpc":"2.0",
    "id":6,
    "method":"tools/call",
    "params":{
      "name":"moqui_find_records_guarded",
      "arguments":{"entityName":"mantle.account.invoice.Invoice","limit":1}
    }
  }'
```

## OpenCode Integration

This component includes an OpenCode MCP configuration file:

- [opencode.json](opencode.json)

It is preconfigured for the local endpoint:

- URL: `http://localhost:8080/mcp`
- Authorization: `john.doe / moqui`

Current header:

```json
{
  "Authorization": "Basic am9obi5kb2U6bW9xdWk="
}
```

To use it:

1. Make sure Moqui is running on `http://localhost:8080`
2. Make sure `moqui-mcp` is loaded and `/mcp` responds to `initialize`
3. Point OpenCode at this `opencode.json` file
4. Confirm the manual MCP commands above already work in `curl`

Useful validation flow:

1. Start OpenCode with the MCP configuration
2. Ask it to call `moqui_get_runtime_context`
3. Ask it to call `moqui_search_agent_prompts` for a simple query such as `list orders`
4. Ask it to inspect the returned document with `moqui_get_agent_document`
5. Ask it to call `moqui_update_session_context` with a small test payload

### OpenCode manual validation status

Manual OpenCode validation has been completed successfully on the local `moqui-framework` runtime using:

- endpoint: `http://localhost:8080/mcp`
- user: `john.doe`
- auth header: `Authorization: Basic am9obi5kb2U6bW9xdWk=`

Validated successfully:

- `tools/list`
- `moqui_get_runtime_context`
- `moqui_search_agent_prompts`
- `moqui_get_agent_document`
- `moqui_update_session_context`
- `moqui_find_records_guarded` with expected denial on sensitive entities
- `moqui_search_artifacts`
- `moqui_get_artifact`
- `moqui_call_service_guarded`

Observed final behavior:

- runtime MCP integration: PASS
- OpenCode remote MCP integration: PASS
- runtime tools: PASS
- debug tools: PASS
- guarded denial behavior: PASS

Notes:

- `moqui_find_records_guarded` on `mantle.account.invoice.Invoice` with `john.doe` is expected to log an authorization denial; this is a correct validation outcome, not a defect.
- `moqui_get_artifact` accepts both normalized names such as `create#Order` and fully qualified names such as `mantle.order.OrderServices.create#Order`.
- local retrieval quality can still be improved separately from protocol/runtime correctness.

If OpenCode cannot connect:

- verify `/mcp` is reachable
- verify the Basic auth header
- verify the `Accept: application/json` behavior with the manual `curl` examples above

## Troubleshooting

### `/mcp` returns 404

Check:

- the component is copied into the active runtime
- the component jar was built
- `moqui.log` contains `Added servlet EnhancedMcpServlet on: [/mcp/*]`

### `ClassNotFoundException: org.moqui.mcp.EnhancedMcpServlet`

Build the component jar:

```bash
cd /path/to/moqui-framework
./gradlew :runtime:component:moqui-mcp:jar
```

Restart Moqui afterward.

### OpenSearch SSL handshake errors

Your runtime is probably using `https://127.0.0.1:9200` against a local plaintext node.

Set:

```xml
<default-property name="elasticsearch_url" value="http://127.0.0.1:9200"/>
```

in the active runtime config.

### `unknown setting [index.knn]`

Your OpenSearch node does not have vector support enabled.

Options:

- install the `opensearch-knn` plugin and restart OpenSearch
- continue with lexical-only validation and accept `bm25_fallback`

### `sequencedIdPrimary` signature errors

The component must use standard Moqui `EntityValue.setSequencedIdPrimary()` behavior, which is already applied in the current codebase.

## Notes

- [tools/agent-indexer/README.md](tools/agent-indexer/README.md) remains useful for generator-specific details
- `Runbook.md` can remain as a short operator checklist, but this README is intended to be complete enough on its own

## Provider Strategy

`moqui-mcp` supports only two model provider types:

- `openai` — OpenAI native (Responses API + Chat Completions fallback, `/v1/embeddings`)
- `openai_compatible` — any endpoint that exposes the OpenAI API contract

All other vendors must be integrated through an OpenAI-compatible endpoint:

| Vendor | Route |
|--------|-------|
| Ollama | `openai_compatible` via `http://host:11434/v1` |
| LiteLLM Proxy | `openai_compatible` via LiteLLM base URL |
| vLLM | `openai_compatible` via vLLM OpenAI-compatible server |
| SGLang | `openai_compatible` |
| LocalAI | `openai_compatible` |
| OpenRouter | `openai_compatible` |
| Qwen, DeepSeek, etc. | `openai_compatible` via their OpenAI-compatible endpoint |

**What is NOT a provider inside `moqui-mcp`:**

- Anthropic / Claude — not implemented, not planned
- Gemini — not implemented, not planned
- Qwen / DeepSeek native — not implemented; use `openai_compatible`
- Ollama native (`/api/embeddings`) — **not supported**; use `openai_compatible` with `/v1` path

The `openai_compatible` provider uses separate config namespaces for reranker and embedding so they can point to different endpoints:

```text
# Reranker
moqui.agent.reranker.compat.baseUrl=http://localhost:4000/v1
moqui.agent.reranker.compat.apiKeyEnv=OPENAI_COMPATIBLE_API_KEY

# Embedding
moqui.agent.embedding.compat.baseUrl=http://localhost:11434/v1
moqui.agent.embedding.compat.apiKeyEnv=OPENAI_COMPATIBLE_API_KEY
```

## External Chat UI

`moqui-mcp` does not implement a chatbot UI or store chat transcripts.

**Preferred external UI candidate: LibreChat**

LibreChat is a multi-LLM chat UI that supports MCP tool calling. It connects to `moqui-mcp` as a tool server; Moqui acts as the ERP execution and retrieval backend, not as the conversation host.

Moqui stores:
- `AgentSessionContext` — external session/conversation identifiers (no transcript)
- `AgentToolExecutionLog` — ERP action audit log (service calls, artifact executions)

Provider strategy is intentionally narrow:

- `openai`
- `openai_compatible`

Native branches for Qwen, DeepSeek, Anthropic, Gemini, Mistral, Ollama-native and similar are intentionally not added to the core. If a runtime exposes an OpenAI-compatible API, configure it through `openai_compatible`.

Moqui does **not** store:
- Chat message history
- LLM conversation state
- Provider thread context (those IDs are registered in `AgentSessionContext` for reference only)

**Session registration flow (LibreChat → Moqui):**

```text
1. LibreChat starts a new conversation
2. LibreChat calls moqui_create_agent_session with:
   - clientTypeEnumId: AgClientLibreChat
   - clientSessionId: <librechat session id>
   - clientConversationId: <librechat conversation id>
   - providerEnumId: AgProviderOpenAI  (or AgProviderOpenAICompat)
   - modelName: gpt-5 (or whatever model LibreChat is using)
3. Moqui stores the identifiers in AgentSessionContext, returns agentSessionContextId
4. LibreChat passes agentSessionContextId in subsequent tool calls for correlation
5. When conversation ends, LibreChat calls moqui_close_agent_session
```

The session registry stores only:

- client session/context/conversation identifiers
- provider session/thread/conversation/response identifiers
- model, status and activity metadata

It does not store the external chat transcript.

**MCP tools for session management (all runtime-accessible):**

- `moqui_create_agent_session` — register a new external session
- `moqui_update_agent_session_external_context` — update session identifiers (e.g. new conversation)
- `moqui_find_agent_sessions` — list sessions for current user
- `moqui_close_agent_session` — mark session closed

## Sources

Official OpenSearch documentation used for plugin and vector notes:

- OpenSearch plugin installation: https://docs.opensearch.org/latest/install-and-configure/plugins/
- OpenSearch `knn_vector`: https://docs.opensearch.org/latest/mappings/supported-field-types/knn-vector/
