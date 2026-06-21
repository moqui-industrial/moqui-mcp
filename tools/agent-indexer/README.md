# Agent Artifact Indexer

Strumenti screen-first per generare documenti agentici Moqui pronti per embedding, indexing OpenSearch ed evaluation retrieval.

## Flusso corrente

Il flusso raccomandato e':

1. generare il catalogo screen-first
2. indicizzare con `org.moqui.agent.AgentDocumentServices.index#AgentDocuments`
3. valutare il retrieval su OpenSearch reale

L'indexing runtime puo' generare automaticamente il campo `embedding` dai documenti che hanno `embeddingText`, se:

- `moqui.agent.embedding.provider != none`
- il provider/model sono configurati
- il documento non contiene gia' `embedding`

## Generazione catalogo globale

Per estendere la generazione screen-first sull'insieme degli screen SimpleScreens:

```bash
cd moqui-mcp/tools/agent-indexer
python3 generate_full_simplescreens_extension.py \
  --moqui-root /home/igor/development/projects/moqui/master \
  --output-dir ./output
```

Output attesi:

- `output/global-localization-catalog.json`
- `output/global-screen-prompt-documents.jsonl`
- `output/global-support-service-documents.jsonl`
- `output/global-task-group-documents.jsonl`
- `output/global-area-overview-documents.jsonl`
- `output/global-eval-queries.jsonl`
- `output/global-retrieval-weighted.json`
- `output/global-retrieval-weighted.md`
- `output/global-area-summary.json`
- `output/global-area-summary.md`

## Fase 1: source inventory per seed/demo/test

Per costruire l'inventario delle sorgenti non-screen da cui estrarre conoscenza procedurale:

```bash
cd moqui-mcp/tools/agent-indexer

python3 collect_seed_test_sources.py \
  --scan-root /home/igor/development/projects/moqui/master \
  --scan-root /home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component \
  --output-dir ./output
```

Output:

- `output/source-knowledge-inventory.json`
- `output/source-knowledge-inventory.md`

Questa fase non genera ancora documenti agentici finali; produce l'inventory strutturato che servira' per:

- `seed_scenario`
- `test_workflow_story`
- `generic_business_pattern`

Vedi anche:

- `docs/seed-test-knowledge-extraction.md`

## Fase 2: knowledge document generation

Per generare i primi documenti knowledge-only partendo dall'inventory:

```bash
cd moqui-mcp/tools/agent-indexer

python3 generate_seed_test_knowledge.py \
  --inventory ./output/source-knowledge-inventory.json \
  --screen-docs ./output/global-screen-prompt-documents.jsonl \
  --output-dir ./output
```

Output:

- `output/global-seed-scenario-documents.jsonl`
- `output/global-test-workflow-documents.jsonl`
- `output/global-business-pattern-documents.jsonl`
- `output/global-agent-knowledge-documents.jsonl`

## Fase 2b: service-action grammar extraction

Per generare il catalogo dei service-action statement partendo da `service/**/*.xml` e dalla grammatica `xml-actions-3.xsd`:

```bash
cd moqui-mcp/tools/agent-indexer

python3 generate_service_action_catalog.py \
  --moqui-root /home/igor/development/projects/moqui/master \
  --output-dir ./output
```

Output:

- `output/global-service-action-statements.jsonl`
- `output/global-service-action-documents.jsonl`
- `output/global-service-action-summary.json`

La mappa semantica controllata dei verbi XML action e' definita in:

- `config/xml_action_semantics.json`

La grammatica dei tag e attributi viene letta da:

- `moqui_xsd_action_grammar.py`
- `framework/xsd/xml-actions-3.xsd`

I documenti prodotti rappresentano statement e servizi come documenti agentici operativi, senza eseguire nulla a runtime.

Questi documenti sono:

- `knowledgeOnly = true`
- `runtimeExecutable = false`

## Fase 4: localization enrichment

Per estrarre localizzazioni ufficiali e label esplicite degli screen:

```bash
cd moqui-mcp/tools/agent-indexer

python3 moqui_localization_catalog.py \
  --scan-root /home/igor/development/projects/moqui/master \
  --scan-root /home/igor/development/projects/moqui/tests/ai/moqui-framework \
  --output ./output/global-localization-catalog.json
```

Output:

- `output/global-localization-catalog.json`

Il catalogo combina:

- `LocalizedMessage`
- `LocalizedEntityField`
- label esplicite di screen, transition, form, field e button

Il generatore `generate_full_simplescreens_extension.py` produce automaticamente questo catalogo e lo passa a `generate_screen_prompt_catalog.py`, che arricchisce i prompt screen-first con:

- `localizedUiLabels`
- `fieldLabelDetails`
- `localizedFieldLabels`

Le localizzazioni mancanti non bloccano il pipeline e non vengono inventate traduzioni artificiali.

Quando usi `generate_full_simplescreens_extension.py`, oltre ai cataloghi per-area
viene generato anche un catalogo globale in:

- `output/.../service-actions-global/global-service-action-statements.jsonl`
- `output/.../service-actions-global/global-service-action-documents.jsonl`

## Fase 5: artifact graph offline

Per generare un grafo strutturale offline di artefatti e relazioni:

```bash
cd moqui-mcp/tools/agent-indexer

python3 generate_artifact_graph.py \
  --screen-docs ./output/global-screen-prompt-documents.jsonl \
  --service-action-statements ./output/global-service-action-statements.jsonl \
  --service-action-documents ./output/global-service-action-documents.jsonl \
  --knowledge-docs ./output/global-agent-knowledge-documents.jsonl \
  --output-dir ./output/graph
```

Output:

- `output/graph/global-artifact-graph-vertices.jsonl`
- `output/graph/global-artifact-graph-edges.jsonl`
- `output/graph/global-artifact-graph-summary.json`

Nel componente Moqui viene seminato solo il record radice `moqui.math.Graph`
con `graphId=AgentArtifactGraph`. Le righe `GraphVertex` e `GraphEdge` non sono
seed statici: devono derivare dall'output reale del generatore offline e,
quando serve, essere caricate successivamente nelle entita' `moqui.math`.

Servizio runtime per materializzazione:

```text
org.moqui.agent.AgentDocumentServices.load#ArtifactGraph
```

Input minimi:

- `verticesFilePath`
- `edgesFilePath`
- opzionale `summaryFilePath`
- opzionale `clearExisting`
- opzionale `mathModelId`
- opzionale `mathModelRunId`
- opzionale `createMathModelRun`

Il grafo e' build-time only in questa fase e non entra nel path caldo del runtime. I tipi principali oggi coperti sono:

- `Screen`
- `ScreenTransition`
- `Service`
- `ServiceActionStatement`
- `Entity`
- `EntityField`
- `AgentDocument`
- `SeedRecord`
- `TestCase`

e gli edge principali:

- `SCREEN_HAS_TRANSITION`
- `TRANSITION_CALLS_SERVICE`
- `SERVICE_HAS_STATEMENT`
- `SERVICE_CALLS_SERVICE`
- `SERVICE_READS_ENTITY`
- `SERVICE_WRITES_ENTITY`
- `SERVICE_USES_FIELD`
- `STATEMENT_HAS_COMPLEMENT`

Anche `generate_full_simplescreens_extension.py` ora puo' produrre in automatico:

- `output/.../global-screen-prompt-documents.jsonl`
- `output/.../service-actions-global/...`
- `output/.../graph/global-artifact-graph-vertices.jsonl`
- `output/.../graph/global-artifact-graph-edges.jsonl`
- `output/.../graph/global-artifact-graph-summary.json`
- `SERVICE_HAS_STATEMENT`
- `SERVICE_CALLS_SERVICE`
- `SERVICE_READS_ENTITY`
- `SERVICE_WRITES_ENTITY`
- `SERVICE_USES_FIELD`
- `STATEMENT_HAS_COMPLEMENT`
- `SCREEN_DISPLAYS_FIELD`
- `SCREEN_EDITS_FIELD`
- `AGENT_DOCUMENT_DERIVED_FROM`

## Fase 6: DataDocument/DataFeed preparation

Per preparare i documenti agentici a una futura proiezione `DataDocument` senza cambiare il pipeline JSONL:

```bash
cd moqui-mcp/tools/agent-indexer

python3 prepare_datadocument_projection.py \
  --docs ./output/global-screen-prompt-documents.jsonl \
  --docs ./output/global-agent-knowledge-documents.jsonl \
  --graph-vertices ./output/graph/global-artifact-graph-vertices.jsonl \
  --graph-edges ./output/graph/global-artifact-graph-edges.jsonl \
  --output-dir ./output/projection
```

Output:

- `output/projection/global-screen-prompt-documents-projected.jsonl`
- `output/projection/global-agent-knowledge-documents-projected.jsonl`
- `output/projection/global-agent-documents-projected.jsonl`
- `output/projection/global-agent-documents-projection-summary.json`

I documenti projected aggiungono:

- `sourceGraphId`
- `sourceVertexId`
- `sourceArtifactUri`
- `derivedFromVertexIds`
- `derivedFromEdgeIds`
- `projectionDocumentType`
- `mathModelId`
- `mathModelRunId`
- `embeddingTensorId`

In parallelo il componente ora contiene seed draft per una futura attivazione Moqui-native:

- `AgentArtifactDocument`
- `AgentGraphVertexDocument`
- `AgentGraphEdgeDocument`

definiti in:

- `data/AgentProjectionDocumentSeedData.xml`

Questi seed sono preparatori e non sostituiscono ancora il path di indexing manuale attuale.

## Fase 3: business pattern generalization

La Fase 3 e' inclusa nello stesso orchestratore `generate_seed_test_knowledge.py` e deriva pattern ricorrenti a partire dai documenti di Fase 2:

- `generic_business_pattern`

Lo script dedicato e':

- `generate_business_patterns.py`

Output aggiuntivi:

- `output/global-business-pattern-documents.jsonl`
- `output/global-business-pattern-summary.md`

Questi pattern restano:

- `knowledgeOnly = true`
- `runtimeExecutable = false`

I documenti knowledge-only possono anche distinguere:

- `knowledgeCategory = business_configuration`
- `knowledgeCategory = technical_configuration`
- `knowledgeCategory = reference_data`
- `knowledgeCategory = business_pattern`

e i pattern tecnici ricorrenti usano:

- `documentKind = technical_configuration_pattern`

mentre i pattern di lookup/reference data usano:

- `documentKind = reference_data_pattern`

## Fase 4: evaluation e anti-regression

Per generare query di evaluation per i knowledge docs:

```bash
python3 generate_knowledge_eval_queries.py \
  --docs ./output/global-agent-knowledge-documents.jsonl \
  --output ./output/global-knowledge-eval-queries.jsonl
```

Per valutare i knowledge docs e confrontare screen-only vs combined:

```bash
python3 evaluate_seed_test_knowledge.py \
  --screen-docs ./output/global-screen-prompt-documents.jsonl \
  --knowledge-docs ./output/global-agent-knowledge-documents.jsonl \
  --screen-queries ./output/global-eval-queries.jsonl \
  --knowledge-queries ./output/global-knowledge-eval-queries.jsonl \
  --output-dir ./output
```

Output:

- `output/global-retrieval-seed-scenarios-summary.json/md`
- `output/global-retrieval-test-workflows-summary.json/md`
- `output/global-retrieval-business-patterns-summary.json/md`
- `output/global-retrieval-all-knowledge-summary.json/md`
- `output/global-retrieval-screen-vs-combined-summary.json/md`
- `output/global-agent-all-documents.jsonl`

## Generazione catalogo per area/sottoinsieme

Per lavorare su un singolo albero screen:

```bash
cd moqui-mcp/tools/agent-indexer
python3 generate_screen_prompt_catalog.py \
  --screens-dir /home/igor/development/projects/moqui/master/SimpleScreens/screen/SimpleScreens/Order \
  --services-dir /home/igor/development/projects/moqui/master/mantle-usl/service/mantle/order \
  --entities /home/igor/development/projects/moqui/master/mantle-udm/entity/OrderEntities.xml \
  --views /home/igor/development/projects/moqui/master/mantle-usl/entity/OrderViewEntities.xml \
  --eeca /home/igor/development/projects/moqui/master/mantle-usl/entity/Order.eecas.xml \
  --output-dir ./output
```

Output tipici:

- `output/screen-prompt-documents.jsonl`
- `output/support-service-documents.jsonl`
- `output/task-group-documents.jsonl`
- `output/area-overview-documents.jsonl`
- `output/screen-prompt-eval-queries.jsonl`
- `output/screen-prompt-catalog-report.md`
- `output/screen-prompt-catalog-metrics.json`

## Caratteristiche del catalogo

I documenti agentici includono campi come:

- `documentId`
- `documentKind`
- `area`
- `subArea`
- `domainObject`
- `promptGroupId`
- `canonicalPrompt`
- `executionRequiredContext`
- `preferredService`
- `runtimeExecutable`
- `executionChannel`
- `embeddingText`

Policy runtime attuale:

- `service`, `navigation`, `screen_render`, `screen_url`, `print`, `print_export`, `download` sono gestiti dal runtime MCP
- `screen_transition` non ha ancora un resolver dedicato
- `read_query` non ha ancora un resolver query dedicato
- i `screen_query_prompt` sono generati con `runtimeExecutable=false`

## Indexing Moqui

L'indexing operativo passa da:

```text
org.moqui.agent.AgentDocumentServices.index#AgentDocuments
```

Input principali:

- `filePath`
- `filePathList`
- `indexName`
- `forceReindex`
- `embeddingProvider`
- `embeddingModel`
- `embeddingDimensions`

Comportamento:

- crea l'indice se non esiste ancora
- legge il mapping da `opensearch-mapping-agent-area.json`
- genera `embedding` dai documenti privi di vector quando il provider e' attivo
- registra la run in `AgentDocumentIndexRun`

Metriche utili della run:

- `indexedCount`
- `failedCount`
- `embeddedCount`
- `embeddingSkippedCount`
- `embeddingFailedCount`

Per una produzione reale con vector/hybrid:

- `embeddedCount` dovrebbe essere uguale a `indexedCount`, salvo documenti senza `embeddingText`
- `embeddingFailedCount` dovrebbe essere `0`

## Evaluation retrieval

Valutazione lessicale/weighted locale:

```bash
python3 evaluate_screen_prompt_retrieval.py \
  --docs ./output/global-screen-prompt-documents.jsonl \
  --queries ./output/global-eval-queries.jsonl \
  --mode weighted \
  --out-json ./output/global-retrieval-weighted.json \
  --out-md ./output/global-retrieval-weighted.md
```

Valutazione OpenSearch reale:

```bash
python3 evaluate_screen_prompt_retrieval.py \
  --docs ./output/global-screen-prompt-documents.jsonl \
  --queries ./output/global-eval-queries.jsonl \
  --mode opensearch \
  --opensearch-url http://localhost:9200 \
  --opensearch-index moqui_agent_prompts_v1 \
  --opensearch-knn \
  --embedding-field embedding \
  --out-json ./output/global-retrieval-opensearch.json \
  --out-md ./output/global-retrieval-opensearch.md
```

Valutazione multi-modalita' per gate di release, misurata sul runtime reale `moqui_search_agent_prompts` via MCP:

```bash
python3 evaluate_opensearch_modes.py \
  --docs ./output/global-screen-prompt-documents.jsonl \
  --queries ./output/global-eval-queries.jsonl \
  --mcp-endpoint http://localhost:8080/mcp \
  --output-dir ./output
```

Questo runner produce:

- `global-retrieval-opensearch-bm25.json/md`
- `global-retrieval-opensearch-vector.json/md`
- `global-retrieval-opensearch-hybrid.json/md`
- `global-retrieval-opensearch-hybrid_rerank.json/md`
- `global-retrieval-opensearch-summary.json/md`
- `failure-report-top50.json/md`

Nota:

- questo runner misura il comportamento runtime di `bm25`, `vector`, `hybrid` e `hybrid_rerank`
- non interroga solo OpenSearch raw, ma il tool MCP reale del componente

Prima del go-live e' consigliato verificare almeno:

- group recall@3 `hybrid` o `hybrid_rerank`
- performance di `screen_query_prompt`
- cross-area confusion

Per tracciare la evaluation come run lungo dentro `MathModelRun`:

```bash
python3 evaluate_opensearch_modes.py \
  --docs ./output/global-screen-prompt-documents.jsonl \
  --queries ./output/global-eval-queries.jsonl \
  --mcp-endpoint http://localhost:8080/mcp \
  --rpc-endpoint http://localhost:8080/rpc/json \
  --auth-header "Basic am9obi5kb2U6bW9xdWk=" \
  --output-dir ./output/full-eval \
  --math-model-id AgentMoquiRagModel_v1 \
  --create-math-model-run \
  --run-name "Full Runtime Retrieval Evaluation"
```

Quando `--create-math-model-run` e' attivo:

- viene creato un `MathModelRun` di tipo evaluation
- il summary finale viene salvato nel run
- in caso di errore il run viene chiuso con `fail#AgentMathModelRun`
- il report top failure viene scritto anche come `failure-report-top50.json/md`

## Runtime validation

Per validare il layer MathModel/graph/tensor su un runtime Moqui attivo:

```bash
cd moqui-mcp

python3 tools/runtime_validation_test.py \
  --rpc-endpoint http://localhost:8080/rpc/json \
  --auth-header "Basic am9obi5kb2U6bW9xdWk=" \
  --vertices-file tools/agent-indexer/output/graph/global-artifact-graph-vertices.jsonl \
  --edges-file tools/agent-indexer/output/graph/global-artifact-graph-edges.jsonl \
  --summary-file tools/agent-indexer/output/graph/global-artifact-graph-summary.json \
  --out-json tools/output/runtime-validation.json \
  --out-md tools/output/runtime-validation.md
```

Il runner verifica:

- `load#ArtifactGraph`
- `get#ArtifactImpact`
- `register#AgentEmbeddingTensor`
- `summarize#AgentToolExecutionLogsToMathModelRun`
- `get#AgentModelLineage`
- opzionalmente mini `index#AgentDocuments` se si passa `--index-docs-file`

## Configurazione embedding

Configurazioni runtime tipiche:

```text
moqui.agent.embedding.provider=openai|openai_compatible|none
moqui.agent.embedding.model=text-embedding-3-large
moqui.agent.embedding.dimensions=3072
```

Nota importante:

- se `moqui.agent.embedding.provider=none`, il runtime search degrada a `bm25_fallback`
- in questo caso `vector` e `hybrid` non sono realmente attivi

## Mapping OpenSearch

Il source of truth del mapping e':

- `opensearch-mapping-agent-area.json`

Il servizio:

- `org.moqui.agent.AgentDocumentServices.transform#AgentPromptDocumentMapping`

carica quel file e ne adatta solo la dimensione del vector dalla configurazione runtime.
