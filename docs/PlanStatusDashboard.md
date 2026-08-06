# Plan Status Dashboard

This dashboard maps the agreed plan to the current component state.

Status legend:

- `DONE`
- `PARTIAL`
- `NOT DONE`

## Executive Summary

The component is no longer in the exploratory phase.
It already has real foundations for:

- pattern seed data
- skill registry and business skills
- runtime lookup documents
- authoritative knowledge corpus
- first validated skill-routed aggregate execution

The main remaining gap is not lack of concepts.
The main remaining gap is turning those concepts into dominant planner/runtime behavior.

## Layer Status

| Layer | Goal | Status | Notes |
| --- | --- | --- | --- |
| Layer 0 | Authoritative sources inventory | DONE | Source inventory and support corpus are documented. |
| Layer 1 | Moqui grammar and xml-actions knowledge | PARTIAL | Corpus exists, but planner does not yet use it strongly enough. |
| Layer 2 | Operational business knowledge | PARTIAL | Some business aggregates are covered, breadth is still limited. |
| Layer 3 | Canonical graph | PARTIAL | Pattern metadata exists, but graph activation is incomplete. |
| Layer 4 | DataDocument / DataFeed projections | PARTIAL | Runtime lookup documents exist, but coverage is selective. |
| Layer 5 | Skill layer | PARTIAL | Infrastructure is solid, skill family breadth is still incomplete. |
| Layer 6 | Retrieval / embedding | PARTIAL | Corpus and indices exist, but grounding is not yet dominant. |
| Layer 7 | Planner / runtime enforcement | PARTIAL | Some repaired flows work, and runtime now consumes skill planning state, but prompt fallback still has too much weight. |

## Plan Checklist

### 1. Consolidate the grammar

- `DONE`
  - XSD-aware planning direction is documented.
  - xml-actions knowledge plan exists.
  - xml-actions authoritative documents are part of the corpus.
- `PARTIAL`
  - planner still uses xml-actions more as reference than as active planning substrate.
- `NEXT`
  - promote xml-actions documents into explicit planning aids and skill/rule selection inputs.

### 2. Complete the canonical graph

- `DONE`
  - aggregate pattern metadata exists
  - universal pattern metadata exists
- `PARTIAL`
  - graph tagging is not yet rich enough for all role, status, classification, and rule semantics
  - security metadata linkage is not yet fully operationalized across all artifact families
- `NEXT`
  - push pattern semantics directly into graph vertices/edges and queryable metadata.

### 3. Materialize DataDocuments

- `DONE`
  - runtime lookup DataDocuments now exist for important lookup cases
  - OpenSearch runtime indices are in use
- `PARTIAL`
  - lookup coverage is still selective
  - graph-derived pattern-aware documents are not yet complete
- `NEXT`
  - add only the next missing lookup documents proven by runtime evidence
  - project more pattern metadata into retrieval documents.

### 4. Skill layer

- `DONE`
  - `AgentSkillRegister` exists
  - skill discovery, refresh, indexing, and search exist
  - pattern and business skills already exist for several important aggregates
- `DONE`
  - direct universal-pattern skills now exist for:
    - declarative roles
    - contextual roles
    - classification taxonomy
    - status lifecycle
    - contact mechanisms
    - business rules
- `PARTIAL`
  - skill selection and skill planning now inform runtime branches, but they do not yet dominate every execution path
- `NEXT`
  - make selected skills govern execution before prompt fallback.

### 5. Retrieval and embedding

- `DONE`
  - authoritative corpus exists
  - aggregate and universal pattern references are indexed
  - runtime lookup indices are populated
- `PARTIAL`
  - retrieval ranking still does not consistently drive correct execution plans
- `NEXT`
  - tighten retrieval-to-planner grounding and reduce heuristic drift.

### 6. Planner and runtime

- `DONE`
  - unsupported multi-domain workflow refusal exists
  - repaired budget aggregate path is verified
  - order parsing and selected lookup cases were improved
- `PARTIAL`
  - prompt-first fallback still dominates too many cases
  - pattern knowledge is stronger in planning now that runtime consumes `plan#AgentSkill`, but it is still not activated strongly enough in all branches
  - telemetry inspection remains imperfect
- `NEXT`
  - make skill-first execution the default runtime path
  - enforce aggregate boundaries and post-check persistence more broadly.

## Volume 3 Pattern Status

| Pattern Family | Status | Notes |
| --- | --- | --- |
| Declarative roles | PARTIAL | Seeded and now directly represented by a pattern skill, but not fully planner-active. |
| Contextual roles | PARTIAL | Seeded and now directly represented by a pattern skill, but still needs stronger runtime use. |
| Hierarchies and aggregates | STRONG PARTIAL | Best-covered family today. |
| Classification | PARTIAL | Seeded and now directly represented by a pattern skill, but still weak in planner activation. |
| Status and lifecycle | PARTIAL | Seeded and now directly represented by a pattern skill, but lifecycle guards are not yet consistently enforced. |
| Contact mechanisms | PARTIAL | Seeded and now directly represented by a pattern skill, but lookup/planner activation is still weak. |
| Business rules | PARTIAL | Seeded and now directly represented by a pattern skill, but not yet strongly operational. |

## What Is Reliably True Today

- the component has a real pattern vocabulary
- the component has a real skill registry
- the component has a real authoritative corpus
- the component has real runtime lookup documents
- at least one business aggregate path has been repaired and verified end-to-end

## What Is Still Not Safe To Claim

- that the whole plan is complete
- that all universal-pattern knowledge is operational
- that skill-first runtime fully dominates prompt fallback
- that all lookup needs are solved generically
- that all complex business prompts are now reliable

## Recommended Next Work Order

1. bind the expanded universal-pattern skill set more directly into planner/runtime
2. extend lookup coverage only where runtime evidence proves a gap
3. add stronger telemetry and post-check verification
4. re-run real prompts and update the gap reports
