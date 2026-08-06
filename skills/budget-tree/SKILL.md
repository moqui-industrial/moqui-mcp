---
skillId: moqui.pattern.budget-tree
artifactName: component://moqui-mcp/skills/budget-tree/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Accounting
title: Budget Tree Pattern
description: Recognize and execute rooted budget aggregates composed of a budget root, ordered budget items, and optional budget item detail rows.
statusId: SkillActive
aggregatePatternIds:
  - AggrBudgetTree
patterns:
  - aggregate
  - rooted-hierarchy
  - ordered-children
  - accounting-period-bound
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the request crosses from the budget root into unrelated HR, project, or order workflow roots.
  - Refuse when organization, period, or budget type cannot be resolved.
---

# Budget Tree Pattern

## Purpose
Use this pattern skill to recognize a single budget root with ordered accounting children.

## Canonical structure
- `Budget`
- `BudgetItem`
- `BudgetItemDetail`

## Boundary
This pattern stays inside one accounting aggregate.
If the prompt also asks to create employees, jobs, orders, projects, or any unrelated root, stop and explain that it is a multi-root workflow.

## Execution meaning
This skill is structural guidance.
Concrete business execution should generally be delegated to the specialization skill `mantle.budget-planning`.

