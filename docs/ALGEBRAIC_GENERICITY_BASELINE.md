# Algebraic Genericity Baseline

## Scope of this baseline

This document freezes the current state of the `moqui-mcp` algebraic planner before the next generalization pass.

The goal is to distinguish between:

- infrastructure already in place
- planner behavior already proven
- planner behavior still contaminated by domain heuristics
- acceptance tests still missing or currently failing

## Algebraic services currently present

The runtime currently exposes these core algebraic services in
[`service/org/moqui/agent/AgentAlgebraicServices.xml`](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml):

- `search#Morphisms`
- `get#MorphismSignature`
- `decompose#PromptIntoAtomicCommands`
- `classify#AtomicCommandPattern`
- `map#AtomicCommandsToMorphisms`
- `plan#PromptAsMorphism`
- `execute#MorphismChain`

These services are enough to support the target architecture:

```text
NL prompt
-> atomic command decomposition
-> candidate morphism search
-> operand binding
-> dependency planning through required/produced operands
-> ordered morphism chain
```

## Generated and loaded seeds

The component currently generates and loads these seed files:

- `data/generated/XmlActionMorphismSeed.xml`
- `data/generated/ServiceMorphismSeed.xml`
- `data/generated/EntityMorphismSeed.xml`

Generation tasks are registered in
[`build.gradle`](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/build.gradle),
and the seeds are loaded through
[`component.xml`](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/component.xml).

This proves the algebraic catalog pipeline exists.

It does **not** yet prove that:

- all relevant Moqui artifacts have been parsed exhaustively
- every generated morphism is semantically correct
- every planner decision is already driven only by metamodel data

## Current green tests

The following planner tests are currently green:

- `Budget -> BudgetItem`
- `OrderHeader -> OrderPart -> OrderItem`
- workflow boundary block for cross-aggregate prompt
- dry-run algebraic execution smoke path

These are useful but are **not yet sufficient proof of genericity**.

## Current red tests

The following genericity tests are currently red:

- `Shipment -> ShipmentItem`
- `AcctgTrans -> AcctgTransEntry`

Current observed behavior:

- shipment chain is not yet planned correctly
- accounting transaction entry is currently classified as `workflowBoundary`
  because `gl account` is still treated as an external cross-aggregate object

This means the planner is still overfitting some business references instead of
fully trusting morphism signatures plus entity relationships.

## Residual hardcoded heuristics still present

The current runtime still contains domain-aware logic that must be migrated to
metamodel-driven data.

Examples already visible in the planner:

- explicit canonical object aliases such as `budget item`
- parent/root shortcuts such as `budget item -> budget`
- allowed reference maps for domains such as budget/account
- scoring bonuses for specific business combinations
- penalties for child detail nouns such as `budget item`
- workflow boundary decisions influenced by domain vocabulary

These heuristics are concentrated mainly in:

- object classification
- candidate ranking
- aggregate root inference
- workflow boundary inference

## Why Budget and Order are not yet proof of genericity

Even if budget and order cases now work better than before, they are not yet a
formal proof of a generic planner because:

- the planner still contains domain vocabulary
- some candidate ranking uses business-specific score adjustments
- some aggregate interpretation still depends on manual noun normalization

In other words:

```text
working examples exist
!=
generic mechanism already proven
```

## Baseline conclusion

Current status is best described as:

```text
catalog and planner architecture: present
dependency-based chain composition: partially generic
full domain-independence: not achieved yet
proof across multiple aggregate families: incomplete
```

The next phase must therefore focus on:

1. moving residual domain knowledge out of runtime planner logic
2. deriving object lexicon and aggregate patterns from the Moqui metamodel
3. proving genericity on at least:
   - Shipment -> ShipmentItem
   - AcctgTrans -> AcctgTransEntry
   - existing passing Budget and Order chains
