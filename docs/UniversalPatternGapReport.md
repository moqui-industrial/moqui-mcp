# Universal Pattern Gap Report

This report measures how far `moqui-mcp` has already gone in modeling universal data patterns and what is still missing for graph construction, retrieval, and execution planning.

The purpose is not only to document the patterns, but to make them actively usable by the agent.

## Current Pattern Families In Scope

The component already seeds these universal pattern families:

- declarative roles
- contextual roles
- recursive hierarchy and aggregate structures
- classification and type systems
- status and lifecycle structures
- contact mechanism structures
- business rule structures

These are represented today in:

- [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/data/AgentUniversalPatternSeedData.xml)
- [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/data/AgentAggregatePatternSeedData.xml)
- [AgentAggregatePatterns.md](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/docs/AgentAggregatePatterns.md)

They are also now projected into the knowledge corpus through:

- `global-authoritative-reference-documents.jsonl`
- chapter-level reference documents from the universal-pattern volume
- aggregate-pattern reference documents derived from seed metadata
- `moqui-org` guide documents
- `Making Apps with Moqui` overview guidance

## Coverage By Pattern Family

### 1. Roles

Current coverage:

- declarative and contextual role families are seeded
- party specialization aggregate metadata exists
- planner work already benefits indirectly from party-oriented aggregates

Missing operationalization:

- graph tags that explicitly mark role definition artifacts versus role participation artifacts
- retrieval documents that explain role semantics in prompt-friendly terms
- planner rules that use role metadata to infer valid assignee, owner, manager, customer, supplier, and approver behavior
- stronger linkage to authorization metadata for artifact access decisions

Assessment:

- documented: good
- seeded: good
- active in retrieval: partial
- active in planner decomposition: partial

### 2. Recursive Hierarchy And Aggregate Structures

Current coverage:

- this is the strongest implemented family
- canonical aggregate patterns already exist for:
  - request plus sequenced item
  - facility parent hierarchy
  - work effort root plus parent tree
  - order header / part / item
  - budget / item / detail
  - party specialization

Missing operationalization:

- peer-to-peer relationship variants are not yet first-class in planning
- graph traversal rules are still stronger for hierarchy than for associative links
- retrieval does not yet always expose root, child, sibling, and sequence semantics explicitly enough for multi-step execution

Assessment:

- documented: strong
- seeded: strong
- active in retrieval: strong
- active in planner decomposition: strongest family so far

### 3. Classification And Type Systems

Current coverage:

- classification family is seeded
- current documents already carry some type-like fields such as artifact type, vertex type, service verb, service noun, entity name, and document type

Missing operationalization:

- graph should explicitly surface shared type systems such as enumeration, type entities, category schemes, and multi-scheme classification
- retrieval documents should normalize type semantics so prompts like "purpose", "kind", "category", "type", and "class" ground to the same modeled structure
- planner should use type metadata to reduce false ambiguity when multiple related services exist

Assessment:

- documented: good
- seeded: good
- active in retrieval: partial
- active in planner decomposition: limited

### 4. Status And Lifecycle Structures

Current coverage:

- status family is seeded
- the component already has a security-first and guarded-execution direction that conceptually fits status-aware planning

Missing operationalization:

- graph needs explicit lifecycle edges and state-bearing metadata for root entities, transitions, and status-changing services
- retrieval documents should expose allowed lifecycle changes and current-state constraints
- planner should use status structure before calling update or transition operations

Assessment:

- documented: good
- seeded: good
- active in retrieval: partial
- active in planner decomposition: limited

### 5. Contact Mechanism Structures

Current coverage:

- contact family is seeded

Missing operationalization:

- graph and retrieval still do not treat contact structures as a reusable pattern family
- planner cannot yet reliably infer when a prompt is about a reusable contact mechanism versus a direct field update
- end-user prompts involving address, telecom, email, warehouse location, or facility contact still depend too much on raw artifact matches

Assessment:

- documented: acceptable
- seeded: good
- active in retrieval: partial
- active in planner decomposition: weak

### 6. Business Rule Structures

Current coverage:

- business-rule family is seeded
- the component already contains guarded execution and authorization-aware graph enrichment

Missing operationalization:

- graph should represent rule origin more explicitly:
  - status constraints
  - role constraints
  - artifact authorization constraints
  - aggregate child-creation constraints
- retrieval should expose rule evidence instead of only returning executable artifacts
- planner should distinguish:
  - structural validity
  - authorization validity
  - lifecycle validity
  - business-policy validity

Assessment:

- documented: good
- seeded: good
- active in retrieval: partial
- active in planner decomposition: partial

## Cross-Cutting Gaps

The main remaining gap is not missing pattern vocabulary.
It is missing pattern activation.

Today the component already knows many pattern names and seeds, but not all of that knowledge is yet used in the three places that matter most:

- graph enrichment
- retrieval document construction
- execution planning

The retrieval-document side is now materially better than before because universal-pattern, aggregate-pattern, and guide references are part of the indexed knowledge corpus.
The main gap has shifted toward graph tagging and planner usage.

This means the next phase should not focus on adding many new pattern labels.
It should focus on making existing pattern knowledge operational.

## Priority Implementation Order

### Priority 1

Make universal patterns visible directly in graph metadata.

Needed outcome:

- every relevant artifact vertex can be tagged with its structural pattern role
- root, child, sibling, specialization, classifier, status-holder, and role-participation semantics are queryable

### Priority 2

Project that graph knowledge into declarative `DataDocument` views.

Needed outcome:

- OpenSearch documents carry pattern-aware fields
- retrieval can filter and rank by aggregate semantics, role semantics, type semantics, and lifecycle semantics

### Priority 3

Use pattern metadata inside the planner.

Needed outcome:

- the planner stops asking for unnecessary ids when the aggregate root and child structure can already be inferred
- multi-step prompts are decomposed by aggregate structure instead of by ad hoc heuristics

### Priority 4

Expand pattern handling for associative and peer-to-peer structures.

Needed outcome:

- not only trees, but also many-to-many and context-role structures become first-class for planning and retrieval

## Skill Recommendation

Yes, dedicated agent skills are justified.

Two skill families would be useful:

- a universal-pattern interpretation skill
  - purpose: teach the planner how to recognize root, child, sequence, specialization, hierarchy, contextual role, lifecycle, and classification semantics from graph and retrieval documents
- an xml-actions interpretation skill
  - purpose: teach the planner how to read executable behavior from Moqui DSL artifacts, not just from service names

The existing data-document/data-feed skill remains useful, but it is not sufficient by itself for universal-pattern interpretation.

## Conclusion

The component already contains the right universal pattern vocabulary.
The remaining work is to connect that vocabulary to:

- graph vertices and edges
- declarative retrieval documents
- planner decomposition logic

The strongest implemented area today is recursive aggregate structure.
The biggest opportunity is to activate the other pattern families so they become queryable evidence, not only seed metadata.
