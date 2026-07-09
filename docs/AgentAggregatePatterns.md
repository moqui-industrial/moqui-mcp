# Agent Aggregate Patterns

This note maps the current Moqui aggregate patterns to the universal pattern vocabulary emphasized in `The Data Model Resource Book, Volume 3`.

Volume 3 highlights a small set of reusable pattern families, especially:

- roles and party involvement
- hierarchies, aggregations, and peer-to-peer relationships
- classification/type structures
- status/state structures
- contact mechanisms
- business rules

For aggregate orchestration in `moqui-mcp`, the most relevant family is Chapter 4:

- recursive relationships
- multilevel aggregates
- root/child structures

Current Moqui-oriented canonical patterns:

- `root_seq_child`
  Example: `Request -> RequestItem`
- `self_parent_hierarchy`
  Example: `Facility -> parentFacilityId`
- `root_parent_tree`
  Example: `WorkEffort -> rootWorkEffortId + parentWorkEffortId`
- `header_part_item`
  Example: `OrderHeader -> OrderPart -> OrderItem`
- `root_seq_multilevel`
  Example: `Budget -> BudgetItem -> BudgetItemDetail`
- `party_specialization`
  Example: `Party -> Person / Organization`

These patterns are now seeded in:

- `moqui.agent.AgentAggregatePattern`
- `moqui.agent.AgentAggregatePatternMember`

The purpose is to move orchestration knowledge from hardcoded prompt-specific logic toward declarative, queryable metadata aligned with both:

- Moqui entity relationships and keys
- Silverston-style universal modeling patterns
