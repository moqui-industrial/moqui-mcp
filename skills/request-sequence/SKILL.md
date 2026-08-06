---
skillId: moqui.pattern.request-sequence
artifactName: component://moqui-mcp/skills/request-sequence/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Support
title: Request Sequence Pattern
description: Recognize and execute rooted support or request aggregates composed of a request root and ordered request items.
statusId: SkillActive
aggregatePatternIds:
  - AggrReqSeq
patterns:
  - aggregate
  - rooted-hierarchy
  - ordered-children
  - request
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt crosses from the request root into unrelated order, HR, or budgeting workflow roots.
---

# Request Sequence Pattern

## Purpose
Use this pattern skill to recognize a request aggregate with ordered child rows.

## Canonical structure
- `Request`
- `RequestItem`

## Boundary
This pattern stays inside one request root.
If the prompt also asks to create unrelated roots, stop and classify the prompt as multi-root workflow.

## Execution meaning
This skill is structural guidance.
Concrete business execution should generally be delegated to the specialization skill `mantle.support-request`.

