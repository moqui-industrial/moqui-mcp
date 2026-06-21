# Moqui MCP Agent

Usa questo server MCP come adapter sottile sopra security, DataDocument/SearchServices e runtime Moqui.
Non bypassare mai l'authorization di Moqui e non partire dai raw artifact se non per debug.

## Flusso primario

1. `moqui_get_runtime_context()`
2. `moqui_get_session_context()`
3. `moqui_search_agent_prompts(queryText=...)`
4. `moqui_get_agent_document(documentId=...)`
5. `moqui_execute_agent_prompt(documentId=..., parameters=...)`

Se il contesto e' incompleto:

1. usa `moqui_get_session_context()`
2. integra con `moqui_find_records_guarded(...)`
3. salva il risultato con `moqui_update_session_context(...)`
4. ripeti `moqui_execute_agent_prompt(...)`

## Regole operative

- Tratta `moqui-mcp` come MCP server + RAG + guarded execution, non come chatbot interno.
- `AgentSessionContext` e' un registro minimo di sessione esterna, non uno storage di transcript chat.
- Preferisci `DataDocument` / `DataFeed` standard Moqui per i read model denormalizzati; evita nuova logica Python di estrazione quando la stessa ricetta puo' stare in seed XML dichiarativi.
- Preferisci i documenti screen-first cercati con `moqui_search_agent_prompts`.
- Usa `moqui_get_agent_document` prima di eseguire per capire `executionRequiredContext`, `preferredService` e rischio.
- Usa `moqui_call_service_guarded` solo come tool debug o quando non esiste un document agentico adatto.
- Usa `moqui_search_artifacts` e `moqui_get_artifact` solo per troubleshooting o discovery tecnica.
- Se `confirmationRequired=true`, chiedi conferma esplicita all'utente e poi ripeti con `confirmed=true`.
- Se `needsMoreContext=true`, raccogli i campi mancanti invece di inventarli.
- Se il risultato e' `navigation` o `print_export`, guidare l'utente usando il target restituito invece di cercare di forzare una service call.
- Fidati del risultato di authz Moqui: se l'accesso e' negato, non tentare workaround.

## Pattern utili

Prompt ellittici come "stampala", "annullalo", "aggiorna questo" richiedono di leggere e aggiornare il session context.

Per query dati:

```text
moqui_find_records_guarded(...)
```

Per controllo accessi esplicito:

```text
moqui_check_artifact_access(
  artifactTypeEnumId="AT_SERVICE",
  artifactName="mantle.account.InvoiceServices.post#Invoice",
  authzActionEnumId="AUTHZA_UPDATE"
)
```

## Utenti privilegiati (MCP_DEBUG / ADMIN)

Gli utenti nei gruppi `MCP_DEBUG` o `ADMIN` hanno accesso diretto agli artefatti Moqui indicizzati in `moqui_artifacts_v1`:

- `moqui_search_artifacts`: cerca servizi, entity, screen, XSD schema e doc per nome o semantica
- `moqui_get_artifact`: ispeziona un artefatto specifico (parametri servizio, campi entity, elementi XSD)
- `moqui_search_all`: ricerca unificata su entrambi gli indici (prompt screen-first + artefatti grezzi)
- `moqui_call_service_guarded`: esecuzione diretta di un servizio Moqui con authz e confirmation gate

Flusso tipico per un utente privilegiato che vuole chiamare un servizio direttamente:

1. `moqui_search_artifacts(queryText=..., artifactTypeList=["service"])` — trova il servizio
2. `moqui_get_artifact(artifactType="service", artifactName=...)` — legge parametri e descrizione
3. `moqui_call_service_guarded(serviceName=..., parameters=...)` — esegue con authz

Per capire lo schema di un XSD Moqui:

1. `moqui_search_artifacts(queryText=..., artifactTypeList=["xsd"])` — trova lo schema
2. `moqui_get_artifact(artifactType="xsd", artifactName=...)` — legge elementi e tipi definiti

## Sicurezza

- Non usare mai tool o servizi con authz disabilitata.
- Non eseguire operazioni high-risk senza conferma.
- Non trattare i support services come prompt primari per l'utente.

## Governance Layer

- `Graph` = topologia degli artefatti
- `MathModel` = istanza concreta della pipeline RAG/agentica
- `MathModelRun` = esecuzione concreta di indexing, graph load, evaluation o summary
- `MathModelData` = lineage input/output
- `Tensor` / `TensorContent` = metadata embedding/vector store

Decisione attuale:

- usare solo le entity `Graph*` come modello strutturale degli artefatti
- non introdurre `Mesh*` per ora
- tenere eventuale reasoning geometrico/state-space come evoluzione futura, non come dipendenza del componente

Questo layer governa la pipeline; non sostituisce OpenSearch, JSONL o il runtime MCP.
