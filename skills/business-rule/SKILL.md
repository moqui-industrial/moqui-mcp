---
skillId: moqui.pattern.business-rule
artifactName: component://moqui-mcp/skills/business-rule/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: MoquiMcp
title: Business Rule Pattern
description: Recognize explicit rule evidence that constrains structural validity, authorization validity, lifecycle validity, and business-policy validity.
statusId: SkillActive
aggregatePatternIds:
  - AggrStatusLife
  - AggrPartyRoleCtx
patterns:
  - rule
  - policy
  - authorization
  - guardrail
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the planner lacks enough evidence to distinguish policy refusal from lookup failure or structural invalidity.
---

# Business Rule Pattern

## Purpose
Use this pattern skill to recognize explicit rule-bearing evidence before execution.

## Canonical structure
- rule source
- constrained subject
- constrained action
- role or status dependency
- authorization or policy boundary

## Boundary
This skill is not a business workflow specialization.
It is a reasoning layer used before execution to explain why an action is:

- structurally valid
- authorization-valid
- lifecycle-valid
- policy-valid

or not.

## Execution meaning
This skill is structural guidance.
Use it when the planner must stop unsupported actions early with a precise explanation instead of falling back to vague technical failure.
