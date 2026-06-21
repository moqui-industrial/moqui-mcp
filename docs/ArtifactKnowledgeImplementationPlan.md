# Artifact Knowledge Implementation Plan

This plan defines the next implementation phase for `moqui-mcp` so the agent can reason about Moqui from both:

- the inside, through authoritative artifact knowledge
- the outside, through operational prompts, screens, seeds, and execution APIs

The main architectural goal is:

- move durable knowledge out of Python scripts and ad hoc heuristics
- keep the Moqui database and artifacts authoritative
- generate agent-facing knowledge through `Graph`, `DataDocument`, and `DataFeed`
- let the planner consume authoritative knowledge instead of guessing structure

## 1. Problem Statement

The current component already does useful operational work:

- prompt search
- prompt resolution
- execution planning
- guarded execution
- LibreChat integration
- selected aggregate pattern orchestration

But recent failures showed a structural gap:

- the planner often has to infer Moqui semantics from operational documents only
- root entity, child entity, and parameter semantics are not always available as authoritative runtime knowledge
- some prompt misunderstandings happen because the agent knows what actions are available, but not yet enough about how Moqui artifacts are internally structured

This plan introduces a complete artifact-knowledge layer to address that gap.

## 2. Layer Model

### Layer 0 - Authoritative Sources

These are the sources from which knowledge is derived.

Primary sources:

- Moqui artifact XML
  - entity definitions
  - view-entity definitions
  - service definitions
  - screen definitions
  - forms
  - transitions
  - xml-actions
- Moqui XSD files
- seed data
- test suites
- standard Moqui `DataDocument` and `DataFeed` recipes

Support sources:

- `Making Apps with Moqui`
  - found in `moqui-org/screen/moqui.org/MakingAppsWithMoqui-1.0.pdf`
- Len Silverston books
  - especially Volume 3 for universal patterns
- local Moqui skills and project notes

Source precedence:

1. runtime artifacts and active component XML
2. XSD-defined language structure
3. seed data and test suites
4. standard data-document/data-feed recipes
5. books and supporting documentation

Books are support for naming, interpretation, and pattern classification.
They must not override actual Moqui artifact structure.

### Layer A - Internal Artifact Knowledge

Purpose:

- represent Moqui as a language and executable artifact system

This layer must allow the agent to answer questions such as:

- what artifact is this?
- what entities does this service read or write?
- what transition calls what service?
- what parameters are required?
- what is the likely root entity of this flow?
- what aggregate pattern does this structure follow?

### Layer B - Operational Knowledge

Purpose:

- represent how users actually navigate and operate Moqui

There are two distinct audiences:

1. end users
   - screen-first
   - forms, buttons, links, transitions, visible workflows
2. admins / business admins
   - seed-first
   - setup-first
   - installation/configuration flows
   - test-suite-backed procedures

### Layer C - Planner / Resolver

Purpose:

- transform a user prompt into a safe, correct, explainable execution plan

Layer C must consume Layer A and Layer B.
It should not guess structure that Layer A can provide authoritatively.

### Layer D - Execution Runtime

Purpose:

- execute plans safely through services, transitions, or supported runtime APIs

### Layer E - Retrieval / Embedding Infrastructure

Purpose:

- make Layer A and Layer B searchable through OpenSearch and vector retrieval

## 3. Target Architecture

The intended flow is:

`Authoritative Sources -> Graph -> DataDocument/DataFeed -> OpenSearch -> Planner -> Execution`

In other words:

1. parse authoritative sources into graph knowledge
2. materialize denormalized technical and operational documents declaratively
3. embed and index those documents
4. let the planner retrieve grounded context
5. execute with guardrails

## 4. Canonical Role Of Each Technology

### Graph Entities

Use graph entities as the authoritative structural backbone for artifact knowledge.

They must represent:

- artifacts
- artifact parts
- calls
- references
- read/write usage
- parameters
- fields
- relationships
- lineage to derived agent documents

### DataDocument / DataFeed

Use `DataDocument` and `DataFeed` as the standard Moqui mechanism for:

- denormalized read models
- OpenSearch indexing
- agent-facing retrieval documents

Durable extraction knowledge must live here whenever possible, not in Python scripts.

### OpenSearch / Embeddings

Use OpenSearch for:

- keyword search
- filtered retrieval
- vector retrieval
- hybrid retrieval
- optional reranking

Embeddings are a projection of technical or operational documents.
They are not the authoritative knowledge themselves.

### Python

Python should be limited to:

- evaluation
- reporting
- one-off bootstrap/migration helpers
- temporary seed draft generation when the durable recipe is not yet modeled in Moqui

Python must not remain the long-term home of artifact extraction semantics.

## 5. Graph Scope

The graph must cover all major artifact types:

- entity
- view-entity
- field
- relationship
- service
- in-parameter
- out-parameter
- screen
- form
- transition
- subscreen
- action block
- xml-action statement
- seed data artifact
- test artifact
- data document
- data feed

The graph should use standard Moqui concepts and names wherever possible.
Avoid inventing new Moqui-like terminology when the XSD or framework already defines it.

## 6. Graph Vertex Categories

Recommended vertex categories:

- `Artifact`
- `Entity`
- `ViewEntity`
- `Field`
- `Relationship`
- `Service`
- `Parameter`
- `Screen`
- `Form`
- `Transition`
- `Subscreen`
- `XmlAction`
- `DataDocument`
- `DataFeed`
- `SeedArtifact`
- `TestArtifact`
- `Pattern`

These are graph classifications, not necessarily one-to-one new business entities.
The backing storage should remain aligned with generic `Graph*` and `Parameter*` structures.

## 7. Graph Edge Types

Recommended edge families:

- `CONTAINS`
- `DEFINES`
- `CALLS_SERVICE`
- `CALLS_TRANSITION`
- `USES_ENTITY`
- `READS_ENTITY`
- `WRITES_ENTITY`
- `USES_FIELD`
- `HAS_FIELD`
- `HAS_RELATIONSHIP`
- `HAS_PARAMETER`
- `HAS_CHILD_ARTIFACT`
- `USES_XML_ACTION`
- `DERIVES_AGENT_DOCUMENT`
- `IMPLEMENTS_PATTERN`
- `SPECIALIZES`
- `REFERENCES`

Use labels and parameters instead of adding hardcoded fields to graph entities.

## 8. Pattern Taxonomy

Aggregate and structural pattern names should align with stable data-model concepts, especially Silverston-style universal structures.

Current Moqui-oriented canonical set:

- `root_seq_child`
- `self_parent_hierarchy`
- `root_parent_tree`
- `header_part_item`
- `root_seq_multilevel`
- `party_specialization`

The planner should reason in terms of these patterns instead of case-specific labels.

Examples:

- `Project -> Milestone -> Task` is an instance of `root_parent_tree`
- `Request -> RequestItem` is an instance of `root_seq_child`
- `OrderHeader -> OrderPart -> OrderItem` is an instance of `header_part_item`

## 9. Layer A Deliverables

### A1. Artifact parser coverage

Complete extraction from:

- all entity XML
- all service XML
- all screen XML
- all relevant Moqui XSD

Minimum extraction features:

- artifact identity
- namespace
- component
- file path
- noun/verb where applicable
- parameter definitions
- field definitions
- relationship definitions
- xml-action statements and their attributes
- references to entities, services, transitions, forms, screens

### A2. XSD-backed semantic registry

Build a lightweight semantic registry from XSD structure so the agent can know:

- which tags can appear where
- which attributes are meaningful
- which attributes create references
- which tags define executable statements

Examples:

- `entity-name`
- `service`
- `verb`
- `noun`
- `transition`
- `location`
- `from`
- `relationship`
- `field-name`

### A3. Native and extended verb catalog

Create two catalogs:

- native Moqui action verbs from `xml-actions`
- extended business verbs from service `verb#noun`

The planner must be able to map natural-language verbs to both catalogs.

### A4. Subject and complement catalog

Subjects:

- entity
- view-entity
- screen/business object implied by artifact references

Complements:

- service parameters
- transition parameters
- form inputs
- entity fields
- relationship fields
- enum/status/type fields

### A5. Structural pattern detection

Detect and tag structures such as:

- root-child via required FK/composite PK
- parent hierarchy via `parent*Id`
- root tree via `root*Id + parent*Id`
- specialization via shared party/specialized entities
- multilevel line-item structures

## 10. Layer B Deliverables

### B1. End-user operational corpus

Create operational documents from visible UI action paths:

- screens
- forms
- links
- buttons
- submit actions
- transitions

This corpus should answer:

- what can a normal user do from the UI?
- what business nouns appear naturally on screens?
- what prompts are likely from a user who does not know services or entities?

This is the screen-first corpus.

### B2. Admin / business-admin corpus

Create a second operational corpus from:

- seed data
- setup flows
- test suites
- installation/configuration procedures

This corpus should answer:

- how do I set up a company?
- how do I configure a chart of accounts?
- how do I create a catalog or product store?

This is the seed-first / test-first corpus.

### B3. Audience tagging

All operational documents should include audience classification:

- `end_user`
- `admin`
- `business_admin`
- `developer`

## 11. Layer E Deliverables

Create separate index families for different retrieval jobs.

Suggested separation:

- technical artifact knowledge index
- operational prompt/action index
- admin/setup index

Suggested logical names:

- `moqui_artifacts_tech_v1`
- `moqui_agent_prompts_v1`
- `moqui_admin_setup_v1`

The exact names may vary, but the separation of concerns should remain.

## 12. Planner Evolution (Layer C)

The planner should evolve in these steps.

### C1. Keep current planner working

Do not break the currently working operational flow while Layer A is being expanded.

### C2. Add retrieval-assisted decomposition

For each prompt:

1. identify likely audience
2. retrieve relevant Layer A and Layer B documents
3. decompose into subject-verb-complement units
4. choose the aggregate pattern
5. bind parameters using authoritative field/parameter knowledge

### C3. Prefer authoritative grounding over heuristics

If the graph or data documents can answer:

- root entity
- child entity ordering
- required parameters
- valid status/type values

then the planner should use that knowledge instead of heuristic guessing.

### C4. Preserve LLM role, but constrain it

The LLM remains useful for:

- decomposition
- ambiguity handling
- natural-language paraphrase
- choosing among grounded candidates

The LLM should not be treated as the authoritative source for Moqui structure.

## 13. Execution Layer Rules

Execution rules remain:

- dry-run first when risk exists
- guarded confirmation for destructive or high-impact actions
- authorization before execution
- audit and session traceability
- post-execution verification when feasible

The new knowledge layers improve execution selection.
They do not replace safety controls.

## 14. Migration Strategy Away From Python

### Phase 1

Keep current Python-based extraction/evaluation where needed, but stop adding durable semantics there.

### Phase 2

Move durable extraction logic into:

- graph seed generation
- Moqui services
- `DataDocument`
- `DataFeed`

### Phase 3

Keep Python only for:

- evaluation
- report generation
- one-time repair/backfill

## 15. Implementation Phases

### Phase 0 - Baseline and inventory

Deliverables:

- source inventory
- artifact inventory
- XSD inventory
- book/document inventory
- gap list between current graph and desired graph

### Phase 1 - Complete graph extraction

Deliverables:

- full artifact graph coverage
- graph seed generation/load process
- pattern tagging on graph artifacts

### Phase 2 - Technical DataDocuments

Deliverables:

- technical read models derived from graph
- searchable artifact knowledge documents
- lineage from graph to document

### Phase 3 - Operational DataDocuments

Deliverables:

- screen-first corpus
- seed-first/test-first corpus
- audience tagging

### Phase 4 - Indexing and embeddings

Deliverables:

- separate OpenSearch indices
- embedding generation for each corpus
- hybrid retrieval configuration

### Phase 5 - Planner integration

Deliverables:

- planner uses Layer A grounding
- planner uses pattern taxonomy
- reduced prompt-specific special cases

### Phase 6 - Validation

Deliverables:

- developer question set
- end-user prompt set
- admin/setup prompt set
- quality reports and failure diagnosis by layer

## 16. Validation Metrics

Measure at least:

- technical retrieval quality
- operational retrieval quality
- planner decomposition accuracy
- root entity detection accuracy
- child ordering accuracy
- parameter binding accuracy
- execution success rate
- clarification rate
- false clarification rate

Important distinction:

- strict retrieval metric
- operationally acceptable metric

This distinction must remain, because sibling artifacts may be operationally acceptable even when strict target matching fails.

## 17. Immediate Next Steps

1. Finalize the source inventory, including `moqui-org` and Silverston references
2. Complete graph extraction coverage for entities, services, screens, and XSD-backed references
3. Normalize graph labels and edge types to standard Moqui terminology
4. Generate technical `DataDocument` definitions from graph entities
5. Generate separate operational `DataDocument` definitions for:
   - screen-first end user flows
   - seed-first/test-first admin flows
6. Point planner decomposition at those corpora before adding new special-case execution code

## 18. Decision Summary

The component should proceed with this principle:

- Layer A provides authoritative structural knowledge
- Layer B provides audience-aware operational knowledge
- Layer C uses A and B to plan
- Layer D executes safely
- Layer E retrieves and ranks the derived documents

This is the path that best preserves:

- Moqui-standard architecture
- explainability
- maintainability
- traceability from source artifact to agent knowledge
- gradual removal of hardcoded extraction logic from Python
