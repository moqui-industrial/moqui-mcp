---
skillId: moqui.pattern.status-lifecycle
artifactName: component://moqui-mcp/skills/status-lifecycle/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Common
title: Status Lifecycle Pattern
description: Recognize current-state, lifecycle, and transition structures before create, update, or status-change execution.
statusId: SkillActive
aggregatePatternIds:
  - AggrStatusLife
patterns:
  - status
  - lifecycle
  - transition
  - state-guard
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt requests a state change that cannot be grounded to a modeled status family or allowed transition set.
---

# Status Lifecycle Pattern

## Purpose
Use this pattern skill to recognize lifecycle-bearing structures and transition constraints before execution.

## Canonical structure
- `StatusType`
- `StatusItem`
- `StatusFlow`
- `StatusFlowItem`
- `StatusFlowTransition`

## Boundary
This skill does not execute the transition by itself.
It tells the planner whether a requested state-bearing operation is structurally grounded.

## Execution meaning
This skill is structural guidance.
Use it when the planner must determine:

- current state
- allowed next state
- lifecycle family
- grouped status semantics
- whether an update is lifecycle-valid before service execution
