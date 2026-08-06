---
skillId: moqui.pattern.declarative-role
artifactName: component://moqui-mcp/skills/declarative-role/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Party
title: Declarative Role Pattern
description: Recognize reusable role-definition structures that describe what a party may be or do before any specific contextual assignment.
statusId: SkillActive
aggregatePatternIds:
  - AggrPartyRoleCtx
patterns:
  - role-definition
  - classification
  - reusable-role
  - authorization-relevant
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt is really about contextual assignment, workflow execution, or unrelated aggregate creation rather than role-definition semantics.
---

# Declarative Role Pattern

## Purpose
Use this pattern skill to recognize structures that define reusable party roles before those roles are attached to a specific business context.

## Canonical structure
- `RoleType`
- `PartyRole`
- optional role hierarchies or role groupings

## Boundary
This skill is about role definition and semantic interpretation.
It does not by itself justify creating employees, assignments, approvals, or other workflow records.

## Execution meaning
This skill is structural guidance.
Use it when the planner must distinguish:

- role definition
- role specialization
- role hierarchy
- authorization-relevant role semantics

before choosing a business-process skill.
