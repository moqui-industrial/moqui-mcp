---
skillId: moqui.pattern.facility-hierarchy
artifactName: component://moqui-mcp/skills/facility-hierarchy/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_PATTERN
domainName: Inventory
title: Facility Hierarchy Pattern
description: Recognize rooted inventory and facility structures organized by parent or hierarchical location relationships.
statusId: SkillActive
aggregatePatternIds:
  - AggrFacilityTree
patterns:
  - aggregate
  - hierarchy
  - recursive
  - inventory
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
refusalRules:
  - Refuse when the prompt becomes a cross-domain workflow outside the facility or inventory hierarchy.
---

# Facility Hierarchy Pattern

## Purpose
Use this pattern skill to recognize recursive facility or location hierarchies and operations anchored to that hierarchy.

## Canonical structure
- `Facility`
- child `Facility` through `parentFacilityId`
- related inventory or location references anchored to the hierarchy

## Boundary
This pattern is recursive and hierarchical.
It should not silently expand into unrelated roots such as HR or budgeting.

## Execution meaning
This skill is structural guidance.
Concrete business execution should generally be delegated to the specialization skill `mantle.asset-movement` when the request is really about moving an asset inside that hierarchy.

