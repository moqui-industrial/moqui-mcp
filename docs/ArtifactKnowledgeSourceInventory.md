# Artifact Knowledge Source Inventory

This is the initial Phase 0 inventory for the artifact-knowledge implementation.

It lists the authoritative and support sources currently available in the workspace and establishes a clean baseline for future extraction work.

## Source Priority

Priority order:

1. active Moqui runtime/component artifacts
2. Moqui XSD language definitions
3. seed data and test suites
4. standard `DataDocument` / `DataFeed` recipes
5. books and supporting documentation

Support sources help with interpretation and naming.
They do not override live artifact structure.

## Current Workspace Sources

### Active Moqui runtime component tree

Base path:

- `moqui-framework/runtime/component`

Initial baseline counts, excluding `tools/*` generated output:

- XML/XSD files under runtime components: `767`
- entity XML files: `38`
- service XML files: `83`
- screen XML files: `451`
- data XML files: `58`

Representative entity sources:

- `moqui-framework/runtime/component/mantle-udm/entity/AccountingAccountEntities.xml`
- `moqui-framework/runtime/component/mantle-udm/entity/OrderEntities.xml`
- `moqui-framework/runtime/component/mantle-udm/entity/PartyEntities.xml`
- `moqui-framework/runtime/component/mantle-udm/entity/RequestEntities.xml`
- `moqui-framework/runtime/component/mantle-udm/entity/ShipmentEntities.xml`
- `moqui-framework/runtime/component/mantle-udm/entity/WorkEffortEntities.xml`
- `moqui-framework/runtime/component/moqui-mcp/entity/AgentEntities.xml`
- `moqui-framework/runtime/component/moqui-mcp/entity/AgentAggregatePatternEntities.xml`
- `moqui-framework/runtime/component/moqui-math/entity/MathEntities.xml`

Representative service sources:

- `moqui-framework/runtime/component/mantle-usl/service/mantle/screen/ScreenServices.xml`
- `moqui-framework/runtime/component/mantle-usl/service/mantle/work/ProjectServices.xml`
- `moqui-framework/runtime/component/mantle-usl/service/mantle/product/AssetServices.xml`
- `moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentRuntimeServices.xml`
- `moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/RagServices.xml`
- `moqui-framework/runtime/component/moqui-mcp/service/org/moqui/mcp/McpServices.xml`

Representative screen sources:

- `moqui-framework/runtime/component/SimpleScreens/screen/SimpleScreens/Accounting/Payment.xml`
- `moqui-framework/runtime/component/SimpleScreens/screen/SimpleScreens/Return/EditReturn.xml`
- `moqui-framework/runtime/component/moqui-mcp/screen/agent/LibreChat.xml`
- `moqui-framework/runtime/component/moqui-org/screen/moqui.org/framework.html.xml`
- `moqui-framework/runtime/component/moqui-org/screen/moqui.org/docs.xml`

### Moqui XSD language definitions

Base path:

- `moqui-framework/framework/xsd`

Initial baseline count:

- XSD files: `11`

These files are authoritative for:

- what tags can appear where
- what attributes define references
- what executable constructs exist in Moqui languages

### Seed data and setup material

Primary source type:

- `*/data/*.xml`

Use for:

- admin/business-admin setup flows
- initial configuration intent
- installation and bootstrap semantics

### Test-oriented sources

Current rough file count matching test-like names under runtime components:

- `2193`

This is intentionally broad and should be refined in the next pass into:

- executable tests
- demo/setup verification artifacts
- reference workflows usable by the admin corpus

## Added Support Sources

### Making Apps with Moqui

Path:

- `moqui-framework/runtime/component/moqui-org/screen/moqui.org/MakingAppsWithMoqui-1.0.pdf`

Use for:

- Moqui conceptual explanations
- developer-oriented language grounding
- examples of artifact composition

### Len Silverstone books

Paths:

- `The Data Model Resource Book, Vol. 1 A Library of Universal Data Models for All Enterprises by Len Silverston (z-lib.org).pdf`
- `The Data Model Resource Book, Vol. 2 A Library of Data Models by Industry Types (Len Silverston) (z-library.sk, 1lib.sk, z-lib.sk).pdf`
- `The Data Model Resource Book VOLume 3 Universal Patterns for Data Modeling by Silverston, LenAgnew, PaulPaul Agnew (z-lib.org).epub`

Use for:

- universal pattern naming
- aggregate structure classification
- planner taxonomy and pattern normalization

Volume 3 is especially relevant for:

- hierarchy patterns
- recursive structures
- multilevel aggregates
- specialization patterns

## Exclusions

Do not treat these as authoritative sources:

- generated files under `tools/agent-indexer/output`
- temporary evaluation reports
- ad hoc Python scripts that duplicate durable artifact semantics

These may still be useful for:

- reporting
- bootstrap validation
- migration assistance

## Next Inventory Refinements

The next pass should add:

1. per-component artifact counts
2. explicit XSD-to-artifact reference map
3. explicit list of standard `DataDocument` / `DataFeed` recipes in Mantle/Moqui
4. curated test-suite inventory for admin/setup use
5. list of graph extraction gaps by artifact family
