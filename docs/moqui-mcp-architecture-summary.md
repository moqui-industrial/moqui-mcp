# moqui-mcp — Architettura del componente

## 1. Scopo

`moqui-mcp` è un componente Moqui che espone l'ERP a client AI esterni tramite MCP.

Non è un chatbot interno a Moqui. Serve invece per:

- cercare conoscenza operativa sugli artefatti Moqui;
- esporre tool MCP sicuri;
- guidare navigazione, lettura e azioni ERP;
- rispettare ArtifactAuthz e sicurezza Moqui;
- tracciare esecuzioni, evaluation e lineage tramite MathEntities.

Schema generale:

```text
LibreChat / OpenCode / altro client AI
        ↓
      MCP
        ↓
moqui-mcp
        ↓
Moqui ERP + OpenSearch + MathEntities
```

---

## 2. Separazione delle responsabilità

```text
Client AI esterno
  = interfaccia chat, modelli LLM, transcript/conversation context

moqui-mcp
  = server MCP, retrieval, guarded execution, audit

OpenSearch
  = indice full-text + vettoriale dei documenti agentici

Moqui
  = ERP, servizi, entity, artifact security, business execution

MathEntities
  = governance della pipeline RAG: modello, run, lineage, grafo, tensor metadata
```

Questa separazione evita di ricostruire dentro Moqui un chatbot multi-provider completo.

---

## 3. Client AI esterni

Il client AI può essere:

- LibreChat;
- OpenCode;
- ChatGPT;
- Hermes Agent;
- altro client MCP.

Il client gestisce:

- UI chat;
- conversazioni;
- modello LLM;
- transcript;
- eventuale memoria lato provider.

Moqui salva solo il minimo necessario, tramite `AgentSessionContext`.

```text
AgentSessionContext
  - agentSessionId
  - userId / partyId
  - clientTypeEnumId
  - clientBaseUrl
  - clientSessionId
  - clientUrl
  - providerConversationId
  - modelName
  - statusId
```

---

## 4. Provider LLM

Il core deve restare provider-neutral.

Strategia:

```text
openai
openai_compatible
```

`openai_compatible` copre provider come Qwen, DeepSeek, OpenRouter, Ollama compatibile, vLLM, SGLang, LM Studio e LiteLLM, se espongono API compatibili OpenAI.

Il core non deve contenere logica specifica per vendor:

```text
NO: if provider == qwen
NO: if provider == deepseek
```

Il core deve passare da una facade:

```text
AgentModelFacade
  - embeddings
  - JSON generation
  - optional LLM rerank
```

---

## 5. Corpus RAG

Il corpus non è una copia grezza degli XML Moqui.

Il componente costruisce `AgentDocument`, cioè documenti agentici derivati dagli artefatti Moqui.

Fonti principali:

```text
Screens
  → entry point operativi

Services + xml-actions
  → grammatica dichiarativa della logica ERP

Entities / fields / parameters
  → soggetti e complementi

Seed/demo/test data
  → business process story e casi d'uso

LocalizedMessage / LocalizedEntityField
  → varianti linguistiche controllate
```

L'obiettivo è trasformare gli artefatti in frasi operative.

Esempio:

```xml
<entity-find-one entity-name="mantle.account.invoice.Invoice">
    <field-map field-name="invoiceId"/>
</entity-find-one>
```

diventa:

```text
Find one Invoice by invoiceId.
Subject: Invoice
Verb: entity-find-one
Complement: invoiceId
Effect: read_one
```

---

## 6. Approccio screen-first

Gli screen Moqui sono trattati come entry point operativi.

Uno screen può contenere:

- form;
- form-list;
- transizioni;
- bottoni;
- service-call;
- entity-find;
- campi visibili o modificabili;
- navigazione.

Esempio:

```text
EditInvoice
  = entry point per aprire/modificare una Invoice
  = contiene campi aggiornabili
  = può chiamare update#Invoice
  = segue la policy navigate_then_maybe_update
```

---

## 7. Service-action extraction

I service XML sono analizzati usando la grammatica `xml-actions`.

L'XSD di `xml-actions` fornisce i verbi dichiarativi disponibili:

```text
entity-find
entity-find-one
entity-create
entity-update
entity-delete
service-call
set
if
iterate
return
script
```

Ogni statement viene normalizzato come:

```text
verb + subject + complements + conditions + effect
```

I complementi possono essere:

- entity fields;
- service parameters;
- transition parameters;
- field-map;
- econdition;
- in-map;
- out-map;
- value-field;
- statusId;
- enumId.

Questo consente di capire cosa fa realmente un servizio Moqui.

---

## 8. Localizzazione

Il componente usa le localizzazioni Moqui per migliorare embedding e retrieval multilingua.

Fonti:

```text
LocalizedMessage
LocalizedEntityField
Enumeration descriptions
screen labels
transition labels
field labels
button labels
```

Esempio:

```text
Invoice.dueDate
  - technical: dueDate
  - English label: Due Date
  - Italian label: Data scadenza
```

Il documento indicizzato conserva sia il nome tecnico sia le label localizzate.

---

## 9. OpenSearch

OpenSearch è il motore di retrieval.

Modalità:

```text
bm25
vector
hybrid
hybrid_rerank
hybrid_llm_rerank opzionale
```

La modalità di default consigliata è:

```text
hybrid
```

`hybrid_llm_rerank` è opzionale perché introduce costi e latenza LLM.

OpenSearch serve per:

- candidate retrieval;
- ricerca BM25;
- ricerca vettoriale;
- filtri su metadata;
- ranking ibrido.

---

## 10. Tool MCP

Il componente espone tool MCP al client AI.

Esempi:

```text
moqui_search_agent_prompts
  → cerca documenti agentici

moqui_get_agent_document
  → legge un documento agentico

moqui_execute_agent_action
  → esegue o prepara un'azione Moqui
```

Le azioni rischiose devono essere protette da:

- ArtifactAuthz;
- dry-run;
- confirmationRequired;
- needsMoreContext;
- denial per azioni non autorizzate.

---

## 11. Policy navigate/update

In Moqui, `edit` o `update` non significa sempre eseguire subito un update.

Policy:

```text
open/edit senza nuovi valori
  → navigazione

update/change/set con nuovi valori
  → navigazione
  → lettura stato corrente
  → confronto valori
  → update solo se i valori sono diversi
```

Questa policy è rappresentata come:

```text
navigate_then_maybe_update
```

---

## 12. Artifact Graph

Il componente costruisce un grafo degli artefatti Moqui.

Entità principali:

```text
Graph
GraphVertex
GraphEdge
GraphContent
```

Il grafo rappresenta:

```text
Screen → Transition → Service → Statement → Entity → Field
Service → Service
Service → Entity
Entity → Field
Seed/Test → Business Pattern
AgentDocument → Source Artifact
```

Serve per:

- explainability;
- impact analysis;
- disambiguazione;
- incremental reindex;
- validation del corpus;
- lineage tecnico.

Esempio:

```text
EditInvoice
  → update transition
  → update#Invoice service
  → Invoice entity
  → dueDate field
```

---

## 13. MathEntities governance layer

MathEntities rappresenta la pipeline AI/RAG in modo Moqui-native.

Non sostituisce OpenSearch e non sostituisce MCP. Serve per governance, tracciabilità e versionamento.

### MathModelDef

Definisce la pipeline astratta.

Può avere figli tramite `parentModelDefId`.

```text
AgentMoquiRagModelDef
  ├── ArtifactExtractionModelDef
  ├── XmlActionGrammarModelDef
  ├── ServiceActionStatementModelDef
  ├── ScreenPromptGenerationModelDef
  ├── LocalizationExtractionModelDef
  ├── ArtifactGraphGenerationModelDef
  ├── EmbeddingGenerationModelDef
  ├── OpenSearchIndexModelDef
  ├── RetrievalModelDef
  └── EvaluationModelDef
```

### MathModelDefContent

Punta agli script o alle configurazioni che implementano i pezzi del modello.

Esempi:

```text
generate_service_action_catalog.py
generate_full_simplescreens_extension.py
generate_artifact_graph.py
xml_action_semantics.json
OpenSearch mappings
```

### MathModelDefIdentification

Registra identificatori esterni.

Esempi:

```text
provider_model_id = text-embedding-3-large
opensearch_index = moqui_agent_prompts_v1
graph_id = AgentArtifactGraph
mcp_tool = moqui_search_agent_prompts
```

### ParameterDef / Parameter

Definiscono e valorizzano la configurazione del modello.

Esempi:

```text
embeddingProvider
embeddingModel
embeddingDimensions
searchMode
rerankMode
batchSize
indexName
includeLocalization
includeServiceActionStatements
includeArtifactGraph
```

### MathModel

È l'istanza concreta e versionata della pipeline.

Esempio:

```text
AgentMoquiRagModel_v1
```

### MathModelRun

Rappresenta una esecuzione concreta.

Esempi:

```text
indexing run
artifact graph load
full evaluation run
tool execution summary
embedding tensor registration
```

### MathModelData

Registra input e output dei run.

Esempi:

```text
input: global-screen-prompt-documents.jsonl
input: global-service-action-statements.jsonl
output: OpenSearch index
output: Graph
output: evaluation summary
output: failure report
```

### Tensor / TensorContent

Descrivono la matrice embedding o il vector store esterno.

Non salvano tutti i float nel DB.

```text
Tensor
  = embedding matrix metadata

TensorContent
  = opensearch://moqui_agent_prompts_v1/embedding
```

---

## 14. AgentToolExecutionLog

Il componente mantiene log puntuali delle esecuzioni tool.

```text
AgentToolExecutionLog
  = evento operativo puntuale

AgentToolExecutionElasticLog
  = versione ricercabile/materializzata

MathModelRun
  = riepilogo aggregato di run o batch
```

Esempio:

```text
91 tool executions
  → summarized into one MathModelRun
```

---

## 15. Evaluation

La qualità del retrieval viene misurata con evaluation runtime.

Metriche principali:

```text
recallAt1
recallAt3
recallAt5
groupRecallAt1
groupRecallAt3
groupRecallAt5
screen_query_prompt.groupRecallAt3
```

L'ultima full evaluation ha mostrato:

```text
runtime validation: PASS
full evaluation execution: PASS
strict quality gate 0.90: FAIL
screen_query_prompt gate 0.80: PASS
```

La failure diagnosis ha mostrato che molti failure sono operativamente accettabili perché il sistema restituisce documenti fratelli utili.

Quindi conviene distinguere:

```text
strictGroupRecallAt3
  = metrica severa

operationalAdjustedRecall
  = metrica che considera sibling equivalenti o operativamente accettabili
```

---

## 16. LibreChat

LibreChat è il candidato principale come chatbot multi-provider.

Ruolo previsto:

```text
LibreChat
  = UI conversazionale multi-LLM

moqui-mcp
  = MCP server, RAG, guarded execution

Moqui
  = ERP, security, audit, session registry
```

LibreChat va testato per:

- tool discovery;
- chiamata a `moqui_search_agent_prompts`;
- dry-run;
- guarded execution;
- auth denial;
- reverse proxy;
- eventuale link o embedded screen Moqui.

La soluzione più prudente è partire da:

```text
Moqui screen → link a LibreChat via reverse proxy
```

Solo dopo verificare iframe/embedded.

---

## 17. Flusso complessivo

```text
1. Parser legge screen, services, entities, seed, test, localization
2. Vengono generati AgentDocuments
3. Viene generato Artifact Graph
4. I documenti sono indicizzati in OpenSearch
5. Gli embedding sono tracciati come Tensor/TensorContent
6. La pipeline è descritta da MathModelDef/MathModel
7. Ogni run è tracciato da MathModelRun
8. Il client AI interroga moqui-mcp via MCP
9. moqui-mcp cerca documenti e propone tool/action
10. Moqui controlla sicurezza e autorizzazioni
11. Le esecuzioni vengono loggate
12. Evaluation e failure diagnosis misurano la qualità
```

---

## 18. Cosa non fa il componente

Il componente non deve:

```text
- diventare un chatbot completo;
- salvare tutto il transcript utente/assistente;
- duplicare LibreChat;
- aggiungere provider nativi per ogni vendor;
- salvare embedding float nel database;
- usare Graph nel path caldo di ogni query;
- eseguire update senza policy/dry-run/confirmation;
- bypassare ArtifactAuthz.
```

---

## 19. Stato attuale

```text
Component RC tecnica: pronta
Runtime validation: passata
Full evaluation: completata
Strict quality gate 0.90: non passato
Failure diagnosis: completata
LibreChat integration: presente, da testare end-to-end
```

La prossima fase è:

```text
1. test LibreChat end-to-end;
2. metrica operational-adjusted;
3. micro-fix sui veri miss;
4. eventuale staging release.
```

---

## 20. Sintesi finale

`moqui-mcp` è un ponte controllato tra AI e Moqui.

La chat resta fuori.

La conoscenza Moqui viene trasformata in documenti agentici.

OpenSearch recupera i candidati.

MCP espone tool sicuri.

Moqui autorizza ed esegue.

MathEntities registra modello, run, lineage, grafo e tensor metadata.

```text
AI client
  → MCP
  → moqui-mcp
  → OpenSearch RAG
  → Moqui services/entities/security
  → logs + MathModelRun lineage
```

Questo rende il componente estendibile, tracciabile e coerente con l'architettura Moqui.
