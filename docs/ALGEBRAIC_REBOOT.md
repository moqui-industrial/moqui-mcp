# Algebraic Reboot

This branch starts a clean experimental axis for `moqui-mcp`.

The goal is to evaluate how far an agent can go using only the formal Moqui artifact catalog:

- entities
- fields
- relationships
- view-entities
- services
- service parameters
- xml-actions
- artifact authorization
- DataDocument and DataFeed projections derived from those artifacts

## Reboot Principles

1. The frozen legacy component remains protected outside this branch.
2. The reboot branch must prefer Moqui formal structures over business narrative.
3. OpenSearch is a retrieval helper, not the source of truth.
4. Graph verifies structure; service signatures verify executability.
5. The agent must operate only on registered and authorized morphisms.
6. Requests that imply generated code, free SQL, or invented services must be refused.

## Scope of the First Algebraic Slice

The first implementation slice introduces a minimal algebraic catalog:

- `search#Morphisms`
- `get#MorphismSignature`
- `search#Objects`
- `get#ObjectSchema`
- `plan#PromptAsMorphism`

These services are intentionally read-only and planning-oriented.

## What Is Deliberately Deferred

- business-semantic support corpora
- screen-oriented retrieval
- business-specific prompt decomposition
- workflow-specific hardcoded planners
- execution over multi-step plans before dry-run planning is stable

## Immediate Goal

Prove that a natural-language request can be compiled into:

1. candidate morphisms
2. authoritative signature checks
3. operand binding
4. authorization validation
5. a dry-run execution plan

without relying on screen-first navigation or external business narrative.

## Current Status

The first algebraic slice is now materially operational:

- `xml-actions` atomic morphisms are generated as `moqui.math.ct.Morphism` seed data
- Moqui service compositions are generated as algebraic morphisms with authoritative service signatures
- entity and view-entity structure is generated as algebraic objects plus structural morphisms
- the runtime JSON-RPC services below answer directly from the formal catalog in H2:
  - `search#Morphisms`
  - `get#MorphismSignature`
  - `search#Objects`
  - `get#ObjectSchema`

Known current constraints:

- OpenSearch is still optional for this slice and may be offline without blocking the algebraic catalog
- `plan#PromptAsMorphism` now resolves part of the required operand set through the generic OpenSearch-backed lookup registry
- `resolve#AndExecuteAgentPrompt` now imports algebraic resolved operands before legacy aggregate execution and blocks cross-aggregate workflow requests earlier
- generated seed payloads intentionally summarize oversized structural metadata instead of storing unbounded raw XML-derived JSON blobs

## Latest Validation

Date: `2026-08-06`

Validated directly against the live Moqui JSON-RPC and MCP dispatcher on `http://127.0.0.1:8081`.

Confirmed:

- algebraic planning for a budget request resolves:
  - `timePeriodId`
  - `budgetTypeEnumId`
- the MCP runtime path `moqui_resolve_and_execute` now inherits those resolved operands before the legacy prompt-document path
- a budget aggregate dry-run with account codes and amounts compiles into:
  - one root `create#Budget`
  - four ordered `BudgetItem` child calls with unique `budgetItemSeqId`
  - correctly parsed `glAccountId` and `amount` fields
- a mixed HR-plus-budget request is now refused as:
  - `workflowBoundary`
  - `cross_aggregate_workflow`

Still open:

- the runtime execution path is still legacy-heavy after the root aggregate is selected
- child parsing remains stronger for explicit English prompts than for localized prompts
- the planner does not yet synthesize multi-step morphism chains directly from signatures alone
