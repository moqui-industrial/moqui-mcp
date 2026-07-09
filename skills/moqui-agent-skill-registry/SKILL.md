---
skillId: moqui-agent.skill.registry
artifactName: component://moqui-mcp/skills/moqui-agent-skill-registry/SKILL.md
skillTypeEnumId: AGSKL_SYSTEM
domainName: MoquiMcp
title: Moqui Agent Skill Registry
description: Discover, register, inspect, and index Moqui AgentSkill artifacts.
statusId: SkillActive
patterns:
  - registry
  - discovery
preferredServices:
  - org.moqui.agent.AgentSkillServices.refresh#AgentSkills
  - org.moqui.agent.AgentSkillServices.list#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
---

# Moqui Agent Skill Registry

## Purpose
Provide the Moqui-native registry workflow for AgentSkill artifacts.

## When to use
Use this skill when discovering skill files, registering them in Moqui, refreshing the skill index, or retrieving a skill definition.

## When not to use
Do not use this skill for end-user business execution. Use it only for skill lifecycle operations.

## Business meaning
A skill file is the procedural knowledge that teaches the agent how to interpret and operate a Moqui pattern.

## Data model semantics
The authoritative row is `moqui.agent.AgentSkillRegister`.
The file on disk remains the canonical body.
The registry row stores searchable metadata and authorization-friendly identifiers.

## Patterns involved
- registry
- discovery
- authorization-filtered retrieval
- DataDocument/DataFeed projection

## Preferred services
- `org.moqui.agent.AgentSkillServices.refresh#AgentSkills`
- `org.moqui.agent.AgentSkillServices.list#AgentSkills`
- `org.moqui.agent.AgentSkillServices.get#AgentSkill`
- `org.moqui.agent.AgentSkillServices.search#AgentSkills`

## Auto-entity fallback policy
No fallback to auto-entity execution. This skill only manages skill artifacts.

## Required inputs
- `skillId` for retrieval
- `componentNameList` for targeted refreshes
- `queryText` for search

## Defaults and forbidden assumptions
- Never assume an unregistered skill is accessible.
- Never expose an unauthorized skill body.
- Never treat discovery as execution.

## Procedure
1. Refresh the registry from component `skills/**/SKILL.md` files.
2. Persist or update `AgentSkillRegister`.
3. Keep the skill artifact linked to a Moqui artifact group.
4. Search the skill DataDocument when semantic lookup is needed.
5. Retrieve the raw skill body only when authorization permits.

## Dry-run requirements
Registry refresh should be re-runnable and idempotent.

## Confirmation requirements
Any destructive pruning of stale skill records should require explicit admin confirmation.

## Execution rules
Use Moqui services first.
Use filesystem reads only for the canonical skill body.

## Post-check
Confirm that the registry row exists and that the DataDocument can be indexed.

## Refusal rules
Refuse access if the acting user is not authorized for the skill artifact.

## Examples
- Register a new skill after adding a new `SKILL.md` file.
- Search for a skill that explains a specific aggregate pattern.
- Retrieve the body of an authorized skill for agent planning.
