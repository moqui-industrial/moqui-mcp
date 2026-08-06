---
skillId: moqui.pattern.contextual-role
artifactName: component://moqui-mcp/skills/contextual-role/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Party
title: Contextual Role Pattern
description: Recognize party participation structures where a party takes a role relative to a specific record, transaction, workflow, or business context.
statusId: SkillActive
aggregatePatternIds:
  - AggrPartyRoleCtx
patterns:
  - contextual-role
  - participation
  - assignee
  - owner
  - approver
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt tries to cross from one workflow root into another unrelated workflow root without a dedicated business skill.
---

# Contextual Role Pattern

## Purpose
Use this pattern skill to recognize when a party participates in a business object through a contextual role.

## Canonical structure
- `Party`
- contextual party-role link
- `RoleType`
- optional context attributes such as status, dates, priority, responsibility, or ownership

## Boundary
This pattern explains who is involved in a context.
It does not by itself execute the workflow that the context belongs to.

## Execution meaning
This skill is structural guidance.
Use it when the planner must infer:

- assignee
- manager
- owner
- customer
- supplier
- approver

from modeled party participation rather than from free-text guessing.
