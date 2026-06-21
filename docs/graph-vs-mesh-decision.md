# Graph Vs Mesh Decision

## Decision

For `moqui-mcp`, use `moqui.math.Graph`, `GraphVertex`, `GraphEdge`, and `GraphContent` as the active structural model.

Do **not** introduce `Mesh`, `MeshKCell`, `MeshCellVertex`, `MeshKCellEdge`, `MeshGroup`, or `MeshGroupMember` in the current implementation phase.

## Why

The current problem is to represent:

- artifact topology
- dependency structure
- service-to-service calls
- screen-to-transition-to-service paths
- entity read/write usage
- field usage
- lineage and explainability

These are best modeled as a labeled directed graph.

## Graph Scope

Use graph entities for:

- `SCREEN_HAS_TRANSITION`
- `TRANSITION_CALLS_SERVICE`
- `SERVICE_HAS_STATEMENT`
- `SERVICE_CALLS_SERVICE`
- `SERVICE_READS_ENTITY`
- `SERVICE_WRITES_ENTITY`
- `SERVICE_USES_FIELD`
- `STATEMENT_HAS_COMPLEMENT`
- `ENTITY_HAS_FIELD`
- `AGENT_DOCUMENT_DERIVED_FROM`

This graph is the canonical structural backbone for:

- artifact discovery
- impact analysis
- explainability
- planner support
- DataDocument projection lineage

## Why Not Mesh Yet

Mesh may become useful later for modeling artifact capability/state-space regions, but that is a second-order concern.

Right now it would add:

- extra modeling complexity
- more entities and loaders
- more uncertainty in planner semantics
- less immediate value than continuing to enrich the graph

The current component needs a reliable structural model first, not a full geometric state-space model.

## Future Revisit Criteria

Reconsider `Mesh*` only if all of the following become true:

1. graph-based structure is stable
2. prompt planning needs region/coverage reasoning beyond graph reachability
3. service capability overlap needs explicit k-dimensional cell modeling
4. the graph alone becomes insufficient for artifact capability matching

Until then:

- `Graph` = active topology model
- `DataDocument/DataFeed` = denormalized retrieval/read model
- `MathModel*` = governance and lineage
- `Mesh*` = deferred
