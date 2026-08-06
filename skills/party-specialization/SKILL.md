---
skillId: moqui.pattern.party-specialization
artifactName: component://moqui-mcp/skills/party-specialization/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: HumanResources
title: Party Specialization Pattern
description: Recognize party-rooted aggregates where person or organization rows are specializations of a common party root.
statusId: SkillActive
aggregatePatternIds:
  - AggrPartySpec
patterns:
  - aggregate
  - specialization
  - party
  - role-aware
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt mixes party specialization with unrelated workflow roots that are not inside the same bounded context.
---

# Party Specialization Pattern

## Purpose
Use this pattern skill to recognize Moqui aggregates where `Party` is the conceptual root and `Person` or `Organization` are specializations.

## Canonical structure
- `Party`
- `Person`
- `Organization`

## Boundary
This pattern helps the agent distinguish root party creation from later contextual role or employment workflow.
It does not by itself justify cross-domain orchestration.

## Execution meaning
This skill is structural guidance.
Concrete business execution should generally be delegated to the specialization skill `mantle.employment-position-management` when the prompt is specifically about employment setup.
