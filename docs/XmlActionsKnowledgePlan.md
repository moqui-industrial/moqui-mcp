# Xml-Actions Knowledge Plan

This note defines the correct knowledge target for Moqui `xml-actions`.

The goal is not to treat the complete service catalog as if it were the whole language.
The goal is to teach the agent:

- the native `xml-actions` DSL
- the semantics of its built-in statement vocabulary
- how `<service-call>` extends execution through referenced services
- how to ground prompts against real executable action patterns found in Moqui components

## Core Principle

`xml-actions` is a DSL with an extensible operational vocabulary.

The base language comes from:

- element definitions in [xml-actions-3.xsd](/home/igor/development/projects/moqui/tests/ai/moqui-framework/framework/xsd/xml-actions-3.xsd)
- attribute semantics
- nesting rules
- execution structure

The extensible part comes from:

- `<service-call name="...">`

That means:

- the agent must understand the language independently of any one service
- the agent must also understand that services extend what the language can cause to happen

## What Is Already Present

The component already has important foundations:

- grammar extraction
  - [moqui_xsd_action_grammar.py](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/tools/agent-indexer/moqui_xsd_action_grammar.py)
- statement semantics mapping
  - [xml_action_semantics.json](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/tools/agent-indexer/config/xml_action_semantics.json)
- service-action catalog generation
  - [generate_service_action_catalog.py](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/tools/agent-indexer/generate_service_action_catalog.py)
- graph/document support for xml-action artifacts
  - [AgentDocumentServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/service/org/moqui/agent/AgentDocumentServices.xml)
  - [AgentArtifactDocumentData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-mcp/data/AgentArtifactDocumentData.xml)

So `xml-actions` is not absent from the knowledge base.
It is partially modeled already.

## What The Agent Must Learn

The target knowledge should be split into four layers.

### Layer 1. Grammar Knowledge

The agent must know:

- which elements exist
- where they are allowed
- which attributes they accept
- which nested statements they permit

Examples:

- `service-call`
- `entity-find`
- `entity-find-one`
- `entity-find-related`
- `set`
- `if`
- `iterate`
- `return`
- `script`
- `actions`

This is the language skeleton.

### Layer 2. Statement Semantics

The agent must know what each built-in statement means operationally.

Examples:

- `entity-find-one` means read a single record
- `entity-find` means read a list
- `set` means derive or assign a value
- `if` means conditional branch
- `iterate` means loop over a list
- `return` means exit or raise an error
- `service-call` means delegate behavior to another executable artifact

This is the execution meaning of the DSL.

### Layer 3. Real Usage Patterns

The agent must learn how the DSL is actually used in real Moqui artifacts.

This is where `mantle-udm` and `mantle-usl` become essential.

They provide working examples of patterns such as:

- read then decide then call another service
- read record then update fields
- conditional service delegation
- loop over related records and propagate changes
- status validation before continuing
- rollup and totals recalculation

Concrete examples already visible in the local source tree include:

- eeca actions that read an entity and derive fields
- seca actions that guard execution with `if`
- service orchestration patterns that chain multiple `service-call` statements

### Layer 4. Planner Grounding

The agent must be able to map prompt fragments to DSL semantics.

Examples:

- "find" often grounds to entity reads
- "create" may ground either to entity creation or to a service that encapsulates creation
- "move asset" is likely not one CRUD write, but a compound action:
  - identify asset
  - identify source and target context
  - call the correct business service
  - update status if required

This layer is what turns DSL understanding into planning quality.

## Recommended Corpus Strategy

Do not build one monolithic corpus.
Build a layered corpus.

### Corpus A. Native DSL Reference

Derived from the XSD and semantics mapping.

Each document should describe:

- statement name
- allowed parent contexts
- key attributes
- statement class
- operation effect
- common related statements

### Corpus B. Executable Usage Examples

Derived from real artifacts in:

- `moqui-framework/runtime/component/mantle-udm`
- `moqui-framework/runtime/component/mantle-usl`

Each document should describe:

- source artifact
- action sequence
- built-in statements used
- referenced entities
- referenced services
- status checks
- loops
- field derivations
- business intent of the action block

### Corpus C. Delegation Vocabulary

Derived from `<service-call>` references, but treated as extension vocabulary, not as the base DSL itself.

Each document should describe:

- called service name
- service verb
- service noun
- surrounding action context
- why that service is invoked from the action flow

### Corpus D. Prompt-To-DSL Patterns

Derived from operational prompts and validated examples.

Each document should describe:

- prompt intent
- likely aggregate root
- likely child or related entities
- likely DSL flow shape
- likely service delegation points

## Why This Matters

Without this layered `xml-actions` knowledge, the planner tends to over-index on service names alone.

That causes two recurring problems:

- it misses compound operations expressed through action flow
- it asks for unnecessary ids because it does not understand the structural read-then-decide-then-act pattern already encoded in the artifact logic

With this knowledge, the planner can reason more like:

- this prompt implies an aggregate root
- this artifact first resolves context with reads
- then it branches on status or type
- then it delegates to the right service

That is much closer to how Moqui is actually implemented.

## Is A Dedicated Embedding Worthwhile

Yes, but not as a raw dump of XSD text.

A useful embedding for `xml-actions` should be built from normalized technical documents, especially:

- DSL reference documents
- executable usage example documents
- prompt-to-DSL grounding documents

This gives the vector layer something semantically coherent to index.

## What Is Still Needed

### 1. Curated Example Extraction

Create a reusable extraction pass that selects representative `xml-actions` examples from `mantle-udm` and `mantle-usl`.

The goal is not to embed every line blindly.
The goal is to embed representative executable idioms.

### 2. Native Statement Knowledge Documents

Materialize one document per built-in statement family from the XSD and semantics mapping.

### 3. Delegation Semantics Documents

Materialize documents that explain how `<service-call>` extends the DSL without redefining the base grammar.

### 4. Planner Consumption Rules

Use these corpora in prompt decomposition so the LLM can infer:

- root entity
- child structure
- likely read path
- likely write path
- likely lifecycle guard

## Skill Recommendation

Yes, an explicit agent skill is worthwhile here.

It should teach:

- how to read `xml-actions`
- how to distinguish built-in statements from delegated service vocabulary
- how to use real artifact examples as canonical execution idioms

That skill should sit beside, not replace, the graph and retrieval layers.

## Conclusion

The right target is:

- knowledge of the `xml-actions` DSL
- knowledge that `<service-call>` extends the action vocabulary
- retrieval corpora built from real Moqui examples

The goal is understanding executable Moqui behavior, not memorizing a flat list of services.
