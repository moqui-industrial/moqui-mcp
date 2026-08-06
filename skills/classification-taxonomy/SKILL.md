---
skillId: moqui.pattern.classification-taxonomy
artifactName: component://moqui-mcp/skills/classification-taxonomy/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Common
title: Classification Taxonomy Pattern
description: Recognize reusable type, category, scheme, and taxonomy structures that classify entities, statuses, roles, and business objects.
statusId: SkillActive
aggregatePatternIds:
  - AggrEnumClass
patterns:
  - classification
  - type-system
  - taxonomy
  - scheme
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt expects a business workflow to be executed but only classification evidence is available.
---

# Classification Taxonomy Pattern

## Purpose
Use this pattern skill to recognize shared type and category systems.

## Canonical structure
- `EnumerationType`
- `Enumeration`
- `EnumGroupMember`
- optional hierarchical or alternate classification schemes

## Boundary
This skill interprets type and category meaning.
It does not itself create the business root that uses the type.

## Execution meaning
This skill is structural guidance.
Use it when the planner must normalize prompt words such as:

- type
- category
- kind
- class
- purpose
- scheme

to a reusable modeled classification structure.
