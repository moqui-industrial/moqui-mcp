---
skillId: moqui.pattern.contact-mechanism
artifactName: component://moqui-mcp/skills/contact-mechanism/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Party
title: Contact Mechanism Pattern
description: Recognize reusable contact and location structures, including party-contact linkage, typed contact payloads, and contact purpose in context.
statusId: SkillActive
aggregatePatternIds:
  - AggrPartyContact
patterns:
  - contact
  - address
  - telecom
  - geography
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt is actually a broader workflow and the only grounded evidence is contact or location structure.
---

# Contact Mechanism Pattern

## Purpose
Use this pattern skill to recognize reusable contact and location structures.

## Canonical structure
- `Party`
- `PartyContactMech`
- `ContactMech`
- optional purpose, geography, and subtype detail

## Boundary
This skill explains contact semantics and lookup structure.
It does not by itself justify unrelated fulfillment, shipment, employment, or accounting workflow execution.

## Execution meaning
This skill is structural guidance.
Use it when the planner must distinguish:

- reusable contact mechanism
- contextual party-contact link
- address vs telecom vs email semantics
- contact purpose in context
