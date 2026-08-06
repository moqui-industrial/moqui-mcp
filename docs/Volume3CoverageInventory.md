# Volume 3 Coverage Inventory

This document records the current state of Volume 3 knowledge inside `moqui-mcp`.

It answers four separate questions:

1. Is the chapter represented in seed data?
2. Is the chapter represented by aggregate structures?
3. Is the chapter represented by one or more agent skills?
4. Is the chapter operational at runtime in graph, retrieval, and planner behavior?

The short answer today is:

- chapter vocabulary coverage: mostly yes
- aggregate exemplars: yes for the most important Moqui structures
- skill coverage: partial but meaningful
- runtime operationalization: still incomplete

## Authoritative Sources In The Component

The current inventory is grounded in:

- [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml)
- [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentAggregatePatternSeedData.xml)
- [AgentSkillSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentSkillSeedData.xml)
- [skills](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/skills)
- [UniversalPatternGapReport.md](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/docs/UniversalPatternGapReport.md)

## Coverage Summary

| Chapter | Theme | Seeded | Aggregate Mapping | Skill Coverage | Runtime Status |
| --- | --- | --- | --- | --- | --- |
| 2 | Declarative roles | Yes | Yes | Partial | Partial |
| 3 | Contextual roles | Yes | Yes | Partial | Partial |
| 4 | Hierarchies, aggregations, peer-to-peer | Yes | Strong | Strong | Strongest but incomplete |
| 5 | Classification and types | Yes | Yes | Weak-to-partial | Partial |
| 6 | Status and lifecycle | Yes | Yes | Weak-to-partial | Partial |
| 7 | Contact mechanisms | Yes | Yes | Weak | Partial |
| 8 | Business rules | Yes | Indirect | Weak | Partial |

## Chapter By Chapter

### Chapter 2: Declarative Roles

Current coverage:

- `AgUpRoleDecl` is present with three levels in [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml:3)
- the main aggregate exemplar is `AggrPartySpec` in [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentAggregatePatternSeedData.xml:183)
- `moqui.pattern.party-specialization` is registered in [AgentSkillSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentSkillSeedData.xml:154)

What is covered well:

- party as root identity
- person and organization as specializations
- role semantics as reusable metadata

What is still missing:

- stronger runtime distinction between role definition and role participation
- more planner behavior tied directly to role-definition evidence

Assessment:

- chapter seeded: yes
- chapter operationalized: partial

### Chapter 3: Contextual Roles

Current coverage:

- `AgUpRoleCtx` is present with six variants in [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml:28)
- the main aggregate exemplar is `AggrPartyRoleCtx` in [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentAggregatePatternSeedData.xml:227)

What is covered well:

- party plus party-role plus role-type structure
- party participation as modeled context, not only as a flat foreign key

What is still missing:

- dedicated skill(s) named and framed explicitly around contextual-role reasoning
- planner rules that use this pattern robustly for assignee, manager, owner, customer, supplier, and approver interpretation

Assessment:

- chapter seeded: yes
- chapter operationalized: partial

### Chapter 4: Hierarchies, Aggregations, and Peer-to-Peer Relationships

Current coverage:

- `AgUpRecursive` is present with five levels in [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml:71)
- aggregate exemplars are the strongest family in [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentAggregatePatternSeedData.xml:3)

Strong concrete patterns already modeled:

- `AggrReqSeq` for `Request / RequestItem`
- `AggrFacilityTree` for recursive facility hierarchies
- `AggrWorkEffTree` for `WorkEffort` root and parent trees
- `AggrOrderHPI` for `OrderHeader / OrderPart / OrderItem`
- `AggrBudgetTree` for `Budget / BudgetItem / BudgetItemDetail`

Related pattern skills already present:

- `moqui.pattern.request-sequence`
- `moqui.pattern.facility-hierarchy`
- `moqui.pattern.order-header-part-item`
- `moqui.pattern.budget-tree`

Related business skills already present:

- `mantle.support-request`
- `mantle.asset-movement`
- `mantle.order-entry`
- `mantle.budget-planning`

What is covered well:

- rooted aggregates
- ordered children
- recursive hierarchies
- explicit workflow-boundary thinking

What is still missing:

- peer-to-peer semantics are not yet first-class
- associative structures are still weaker than rooted hierarchies
- some runtime cases still degrade into fallback behavior instead of pattern-driven execution

Assessment:

- chapter seeded: yes
- chapter operationalized: strongest family, but not closed

### Chapter 5: Classification And Type Systems

Current coverage:

- `AgUpClassify` is present with four levels in [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml:110)
- the main aggregate exemplar is `AggrEnumClass` in [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentAggregatePatternSeedData.xml:271)

What is covered well:

- enumeration type
- enumeration value
- grouping / alternate scheme

What is still missing:

- no dedicated standalone classification skill yet
- runtime lookup, ranking, and planner grounding for type/category/kind/class/purpose prompts still need consolidation

Assessment:

- chapter seeded: yes
- chapter operationalized: partial

### Chapter 6: Status And Lifecycle

Current coverage:

- `AgUpStatus` is present with six levels in [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml:141)
- the main aggregate exemplar is `AggrStatusLife` in [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentAggregatePatternSeedData.xml:315)

What is covered well:

- status type
- status item
- status flow
- flow item
- transition

What is still missing:

- no dedicated lifecycle/status skill yet
- planner still does not consistently guard all create/update/transition operations through explicit lifecycle reasoning

Assessment:

- chapter seeded: yes
- chapter operationalized: partial

### Chapter 7: Contact Mechanisms

Current coverage:

- `AgUpContact` is present with six levels in [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml:184)
- the main aggregate exemplar is `AggrPartyContact` in [AgentAggregatePatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentAggregatePatternSeedData.xml:379)

What is covered well:

- party-contact linkage
- reusable contact payload
- purpose in context
- geography-aware and flexible-address variants at chapter seed level

What is still missing:

- no dedicated contact-mechanism skill yet
- runtime lookup and planning around address, telecom, email, shipping, and facility-contact behavior still rely too much on general retrieval

Assessment:

- chapter seeded: yes
- chapter operationalized: partial

### Chapter 8: Business Rules

Current coverage:

- `AgUpRule` is present with three levels in [AgentUniversalPatternSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentUniversalPatternSeedData.xml:223)

What is covered well:

- explicit rule vocabulary exists in the knowledge base
- rules are linked conceptually to status and role structures

What is still missing:

- there is no dedicated business-rule skill yet
- graph and retrieval do not yet expose rule evidence strongly enough
- planner does not yet consistently separate:
  - structural validity
  - authorization validity
  - lifecycle validity
  - business-policy validity

Assessment:

- chapter seeded: yes
- chapter operationalized: partial

## Skills Present Today

### Pattern skills

- `moqui.pattern.declarative-role`
- `moqui.pattern.contextual-role`
- `moqui.pattern.budget-tree`
- `moqui.pattern.classification-taxonomy`
- `moqui.pattern.status-lifecycle`
- `moqui.pattern.contact-mechanism`
- `moqui.pattern.business-rule`
- `moqui.pattern.order-header-part-item`
- `moqui.pattern.request-sequence`
- `moqui.pattern.facility-hierarchy`
- `moqui.pattern.party-specialization`
- `moqui.aggregate.patterns`

### Business-process skills

- `mantle.budget-planning`
- `mantle.order-entry`
- `mantle.asset-movement`
- `mantle.support-request`
- `mantle.employment-position-management`

### System/domain skills

- `moqui-agent.skill.registry`
- `moqui-datadocument-datafeed`

## What Is Not Yet True

The following statements would still be false if claimed today:

- all Volume 3 patterns are fully captured page by page
- all seeded patterns are active in graph, retrieval, and planner runtime
- the planner uses Volume 3 knowledge consistently before fallback logic
- all associative and peer-to-peer variants are first-class

## Practical Conclusion

The intervention is materially advanced but not finished.

What is already true:

- the core chapter families from Volume 3 are now represented in seed data
- the most important Moqui aggregate examples are modeled explicitly
- several pattern and business skills already exist and are wired into the component

What remains to finish:

1. operationalize seeded pattern metadata in graph enrichment and retrieval documents
2. make the planner consult that pattern evidence before old fallback prompt-search behavior
3. close associative and peer-to-peer gaps
4. validate the resulting runtime behavior with real prompts and execution logs
