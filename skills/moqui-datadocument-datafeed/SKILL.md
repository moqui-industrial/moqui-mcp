---
name: moqui-datadocument-datafeed
description: Use when designing, extending, or troubleshooting Moqui DataDocument/DataFeed recipes for denormalized read models, OpenSearch indexing, or agent-facing aggregate documents. Prefer this over custom extraction scripts when the source data already lives in Moqui entities or in normalized records that can be projected declaratively.
---

# Moqui DataDocument/DataFeed

Prefer `DataDocument` and `DataFeed` when the extraction logic can be expressed from authoritative Moqui entities.

Use Python only for:
- evaluation and reports
- one-off bootstrap or migration
- generating seed drafts when the declarative recipe is already known

Do not hardcode durable extraction knowledge in scripts when the same logic can live in:
- `primaryEntityName`
- `fields fieldPath=...`
- `conditions`
- `DataFeed`

## Workflow

1. Identify the authoritative root entity.
2. Confirm child and related entities from entity relationships.
3. Define a denormalized read model with a `DataDocument`.
4. Bind it to a `DataFeed` for OpenSearch indexing.
5. Keep the extraction recipe in XML seed data, not in ad hoc scripts.

## Root Selection

Prefer a root entity when one or more of these are true:
- children reference it through required foreign keys
- children carry `parent*Id` or `root*Id`
- the entity is the natural create/update entry point in services and screens
- the aggregate is searched or executed starting from this entity

Strong signals for child ownership:
- child FK to parent participates in a composite PK
- child has a required FK and is manipulated mainly in parent context
- the parent exposes a `relationship type="many"` to the child

## Design Rules

- Keep the database authoritative.
- Use OpenSearch as a denormalized read model, not as the source of truth.
- Keep document ids and aliases stable.
- Favor a small number of high-signal fields over dumping every column.
- Include fields that help the agent with:
  - root detection
  - child ordering
  - party/role resolution
  - status and purpose interpretation

## Agent-Oriented Fields

For aggregate-oriented documents, prefer fields such as:
- root id
- parent id
- type
- status
- purpose
- owner party
- related parties and role descriptions
- names and descriptions users are likely to mention

## Moqui MCP Guidance

In `moqui-mcp`, prefer this order:

1. `DataDocument/DataFeed` for business and aggregate read models
2. `Graph/MathEntities` for topology, lineage, and explainability
3. Python for evaluation, bootstrap, or draft generation only

The agent should search denormalized OpenSearch documents built from standard Moqui recipes whenever possible.

## Relation To Generic Moqui Skills

This skill complements broader Moqui service/entity skills such as `moqui-service-writer`.
Use the broader skill for:
- service authoring
- entity authoring
- XML validation and formatting

Use this skill when the main design question is:
- how to build a denormalized read model
- how to express extraction declaratively
- how to replace script-based extraction with `DataDocument/DataFeed`
