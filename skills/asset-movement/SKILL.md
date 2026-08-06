---
skillId: mantle.asset-movement
artifactName: component://moqui-mcp/skills/asset-movement/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_BUSINESS_PROCESS
domainName: Inventory
title: Asset Movement
description: Move an asset within its rooted inventory context and optionally update its lifecycle status.
statusId: SkillActive
aggregatePatternIds:
  - AggrFacilityTree
patternSkillIds:
  - moqui.pattern.facility-hierarchy
preferredRootServices:
  - update#mantle.product.asset.Asset
preferredLookupServices:
  - org.moqui.agent.AgentExecutionServices.find#LookupRecord
requiredInputs:
  - assetId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
  - toLocationSeqId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
legacyFallbackDocumentId: agent-prompt://inventory/findasset/updateasset
fallbackMode: prompt_search
refusalRules:
  - Refuse when source asset or target location cannot be resolved.
  - Refuse fake success when location or status did not change.
---

# Asset Movement

## Pattern specialization
This is a business specialization of the generic pattern skill `moqui.pattern.facility-hierarchy`.

## Purpose
Use this skill for requests such as moving an asset between locations or putting an asset on hold.

## Execution rules
1. Resolve the asset, facility, and location references through lookup documents first.
2. Treat movement and status update as actions on the same asset root.
3. Post-check the persisted asset state after execution.
