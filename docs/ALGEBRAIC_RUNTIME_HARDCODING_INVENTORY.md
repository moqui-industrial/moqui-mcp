# Algebraic Runtime Hardcoding Inventory

## Purpose

This document inventories the remaining domain-specific runtime logic still
present in the algebraic planner.

The goal is to migrate each item from:

```text
runtime hardcoded heuristic
```

to:

```text
generated metamodel data
```

without breaking currently passing algebraic planner tests.

## Rule

The planner runtime must not permanently encode business-domain knowledge such as:

- `budget item -> budget`
- `order item -> order`
- `gl account is allowed inside budget`
- `product implies order item`
- `warehouse implies facility`

That knowledge must come from generated metadata derived from:

- entity names
- entity relationships
- PK/FK structure
- service signatures
- required operands
- produced operands
- canonical object lexicon

## Inventory

### 1. Canonical object detection in prompt decomposition

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:658)

Current issue:

- `canonicalObject` is implemented as a manual term list
- it contains direct business nouns such as:
  - budget
  - budget item
  - order
  - order part
  - order item
  - project
  - task
  - milestone
  - request
  - facility
  - asset
  - person
  - employment position

Why this is not acceptable long-term:

- coverage depends on manually anticipated nouns
- aliases are embedded in runtime logic
- localization leaks into planner logic

Replacement target:

- generated object lexicon seed from entity and service metamodel

Priority:

- `P0`

### 2. Domain-specific target refinement

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:755)

Current issue:

- `refineTargetObject` currently contains a direct special case:
  - if target is `order` and product/quantity are present, return `order item`

Why this is not acceptable long-term:

- this is aggregate-specific knowledge
- it should emerge from service signature and object lexicon resolution

Replacement target:

- candidate morphism selection should prefer child object morphisms when:
  - verb is mutating
  - complement operands align with child service inputs
  - child morphism requires a parent identity that can be composed

Priority:

- `P0`

### 3. Parent aggregate map

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:850)

Current issue:

- `parentMap` manually encodes:
  - budget item -> budget
  - order part -> order
  - order item -> order
  - milestone -> project
  - task -> project
  - request item -> request

Why this is not acceptable long-term:

- aggregate relationships are already present in Moqui data model
- runtime should not maintain a shadow copy of them

Replacement target:

- generated aggregate pattern seed derived from entity graph

Priority:

- `P0`

### 4. Allowed reference map

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:858)

Current issue:

- `allowedReferenceMap` explicitly lists which external reference objects are
  tolerated for:
  - budget
  - budget item
  - order
  - order item
  - order part
  - asset
  - employment position
  - person

Why this is not acceptable long-term:

- this is semantic knowledge about legal references
- it should come from service signatures and reference operand typing

Observed failure linked to this:

- `AcctgTransEntry` currently becomes `workflowBoundary` because `gl account`
  is treated as a disallowed external imperative object

Replacement target:

- classify references as:
  - aggregate-produced identity
  - lookup-bound external reference
  - unsupported external imperative object

using service input metadata instead of domain tables.

Priority:

- `P0`

### 5. Object alias map

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:1048)

Current issue:

- `objectAliasMap` hardcodes synonyms for business objects

Examples:

- `budget item -> budgetitem, budget item`
- `order part -> orderpart, order part`
- `order item -> orderitem, order item`
- `facility -> facility, warehouse`
- `person -> person, party`

Why this is not acceptable long-term:

- alias generation should be systematic from artifact names
- compact forms and tokenized forms can be generated automatically

Replacement target:

- lexicon seed generated from entity names, service nouns, package paths, and
  optional localized labels

Priority:

- `P0`

### 6. Object exclusion terms

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:1064)

Current issue:

- `objectExclusionTerms` contains manual disambiguation logic

Examples:

- `budget` excludes `item`, `detail`, `subperiod`
- `order` excludes `item`, `part`, `billing`, `detail`, `message`, ...
- `project` excludes `task`, `milestone`

Why this is not acceptable long-term:

- disambiguation should come from candidate scoring plus explicit object lexicon
- runtime exclusions are brittle and incomplete

Replacement target:

- generated lexical overlaps
- ranking using exact noun match vs broader root noun match

Priority:

- `P1`

### 7. Candidate scoring bonuses tied to business complements

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:1182)

Current issue:

- candidate scoring has direct business bonuses/penalties

Examples:

- `accountCode` boosts budget item
- `productCode` boosts order item
- missing `productId` penalizes product-oriented candidates
- `budgetName` boosts budget
- `personName` boosts person/party

Why this is not acceptable long-term:

- complement-to-object mapping should be generic
- boosts should come from operand compatibility, not domain name checks

Replacement target:

- derive operand families from parameter metadata
- score candidate by overlap between extracted complements and input parameters

Priority:

- `P0`

### 8. inferAggregateBoundary

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:1357)

Current issue:

- `inferAggregateBoundary` still uses direct business keyword presence
- it recognizes domains through words like:
  - project
  - order
  - asset
  - facility
  - budget
  - party/person/organization/customer/supplier

Why this is not acceptable long-term:

- aggregate boundaries should be inferred from the object graph and command map,
  not from manually curated word buckets

Replacement target:

- boundary inference based on:
  - resolved command target objects
  - aggregate pattern seed
  - command dependency graph

Priority:

- `P0`

### 9. domainAlignmentScore

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:1419)

Current issue:

- `domainAlignmentScore` still uses explicit domain vocabulary

Examples:

- budget
- order
- project
- asset
- facility
- invoice
- payment
- employment
- employee
- position
- party
- person
- organization
- shipment
- request
- product

Why this is not acceptable long-term:

- candidate ranking should compare prompt objects against generated object lexicon
- not against a planner-owned vocabulary

Replacement target:

- lexicon-driven domain affinity score

Priority:

- `P0`

### 10. Operand lookup text extraction with domain-specific cue patterns

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:1482)

Current issue:

- extraction patterns are still operand-specific and partially business-specific
- current examples include:
  - organization/company/business
  - facility/warehouse
  - asset
  - product
  - position class / job class / role

What is acceptable here:

- some operand-oriented extraction logic is expected

What must still improve:

- operand families should be declared by metamodel metadata
- regex routing should be driven by operand semantics, not ad hoc domain lists

Priority:

- `P1`

### 11. Child detail penalty in producer selection

File:
[AgentAlgebraicServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml:1764)

Current issue:

- producer selection contains an explicit penalty for `budget item`

Why this is not acceptable long-term:

- child/root ordering should emerge from:
  - required parent identity operands
  - produced parent identity operands
  - aggregate pattern metadata

Replacement target:

- generic producer ordering by:
  - exact produced operand match
  - minimal unresolved required identities
  - aggregate role from metadata

Priority:

- `P0`

## Recommended migration order

### Phase A

- replace `parentMap`
- replace `allowedReferenceMap`
- replace `inferAggregateBoundary`

These three are blocking true aggregate generalization.

### Phase B

- replace `canonicalObject`
- replace `objectAliasMap`
- replace `domainAlignmentScore`

These three are blocking true object recognition generalization.

### Phase C

- replace complement scoring bonuses
- replace child detail penalties
- tighten operand-family extraction

These improve ranking quality after the structural pieces are fixed.

## Acceptance criteria for removal

An item can be considered migrated only if:

1. the runtime no longer contains the domain-specific map or rule
2. the equivalent behavior comes from generated data
3. the planner still passes:
   - `Budget -> BudgetItem`
   - `OrderHeader -> OrderPart -> OrderItem`
4. and newly passes:
   - `Shipment -> ShipmentItem`
   - `AcctgTrans -> AcctgTransEntry`

## Baseline conclusion

The planner is not blocked by lack of architecture.

It is blocked by the fact that the architecture is still partially implemented
through runtime business heuristics instead of metamodel-driven data.
