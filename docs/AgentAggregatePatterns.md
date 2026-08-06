# Agent Aggregate Patterns

This note maps the current Moqui aggregate patterns to the universal pattern vocabulary emphasized in the local universal-pattern reference corpus.

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
- `party_role_context`
  Example: `Party -> PartyRole -> RoleType`
- `classification_taxonomy`
  Example: `EnumerationType -> Enumeration -> EnumGroupMember`
- `status_lifecycle`
  Example: `StatusType -> StatusItem -> StatusFlow -> StatusFlowTransition`
- `party_contact_mechanism`
  Example: `Party -> PartyContactMech -> ContactMech`

These patterns are now seeded in:

- `moqui.agent.AgentAggregatePattern`
- `moqui.agent.AgentAggregatePatternMember`

The purpose is to move orchestration knowledge from hardcoded prompt-specific logic toward declarative, queryable metadata aligned with both:

- Moqui entity relationships and keys
- universal modeling patterns from the reference corpus

Most important current gaps closed by the newer patterns:

- chapter 3 contextual roles now map to a concrete `Party / PartyRole / RoleType` structure
- chapter 5 classification now maps to reusable `Enumeration` taxonomies and groupings
- chapter 6 status now maps to explicit lifecycle entities instead of only root `statusId` fields
- chapter 7 contact mechanisms now map to the actual party-contact link model used by Moqui
