---
skillId: moqui.pattern.order-header-part-item
artifactName: component://moqui-mcp/skills/order-header-part-item/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Order
title: Order Header Part Item Pattern
description: Recognize and execute rooted order aggregates composed of an order header, ordered parts, and ordered items.
statusId: SkillActive
aggregatePatternIds:
  - AggrOrderHPI
patterns:
  - aggregate
  - rooted-hierarchy
  - ordered-children
  - commerce
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt crosses from order capture into unrelated budgeting, HR, or project workflow roots.
---

# Order Header Part Item Pattern

## Purpose
Use this pattern skill to recognize a commerce aggregate rooted in an order header.

## Canonical structure
- `OrderHeader`
- `OrderPart`
- `OrderItem`

## Boundary
This pattern handles one order aggregate.
It does not authorize unrelated workflow jumps such as creating customers, projects, or accounting structures unless those are already resolvable prerequisites.

## Execution meaning
This skill is structural guidance.
Concrete business execution should generally be delegated to the specialization skill `mantle.order-entry`.

