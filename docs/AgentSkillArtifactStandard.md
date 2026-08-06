# AgentSkill Artifact Standard

This component now treats `AgentSkill` as a first-class artifact, not as a thin wrapper around `AgentPrompt`.
The current model is explicitly two-layered:

- pattern skills
- business specialization skills

## Goal

An `AgentSkill` must declare, in a machine-readable way:

- what aggregate pattern it governs
- which root, child, and lookup services it prefers
- which runtime inputs are required before execution
- whether prompt-search fallback is allowed

This lets Moqui plan execution before asking the LLM to improvise.

## Artifact identity

Every skill file should declare:

```text
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
```

## Flat frontmatter format

The current parser intentionally supports a flat, line-oriented frontmatter.
Use scalar values and simple repeated lists only.

Supported business specialization example:

```text
---
skillId: mantle.order-entry
artifactName: component://moqui-mcp/skills/order-entry/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_BUSINESS_PROCESS
domainName: Order
title: Order Entry
description: Create an order header with ordered parts and items as a single rooted aggregate.
statusId: SkillActive
aggregatePatternIds:
  - AggrOrderHPI
patternSkillIds:
  - moqui.pattern.order-header-part-item
preferredRootServices:
  - mantle.order.OrderServices.create#OrderHeader
preferredChildServices:
  - mantle.order.OrderServices.add#OrderProductQuantity
preferredLookupServices:
  - org.moqui.agent.AgentExecutionServices.find#LookupRecord
requiredInputs:
  - customerPartyId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
  - productIds|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
fallbackMode: prompt_search
legacyFallbackDocumentId: agent-prompt://order/findorder/createorder
---
```

## Frontmatter fields

Core fields:

- `skillId`
- `artifactName`
- `artifactTypeEnumId`
- `artifactGroupId`
- `skillTypeEnumId`
- `domainName`
- `title`
- `description`
- `statusId`

Planning fields:

- `aggregatePatternIds`
- `preferredRootServices`
- `preferredChildServices`
- `preferredLookupServices`
- `requiredInputs`
- `fallbackMode`
- `legacyFallbackDocumentId`

Compatibility fields still recognized:

- `patterns`
- `preferredServices`
- `rootDocumentId`
- `patternSkillIds`

## Pattern-first skill layering

The preferred structure is now:

1. pattern skill
2. business specialization skill

Examples:

- `moqui.pattern.budget-tree` -> `mantle.budget-planning`
- `moqui.pattern.order-header-part-item` -> `mantle.order-entry`
- `moqui.pattern.request-sequence` -> `mantle.support-request`
- `moqui.pattern.facility-hierarchy` -> `mantle.asset-movement`
- `moqui.pattern.party-specialization` -> `mantle.employment-position-management`

Pattern skills explain structure and workflow boundaries.
Business specialization skills bind the pattern to concrete Moqui services.

## Required input encoding

`requiredInputs` is a list of compact specs parsed by `AgentSkillSupport.parseRequiredInputSpecs()`.

Format:

```text
inputName|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,sessionContext,uniqueLookup
```

Recognized keys:

- `requiredFor`
- `missingPolicy`
- `resolution`

## Service roles

The planner separates service intent into:

- `preferredRootServices`
- `preferredChildServices`
- `preferredLookupServices`
- legacy `preferredServices`

This is critical because aggregate-safe execution depends on structural ordering:

1. resolve references
2. create or update the root
3. create or update children
4. verify persistence

## Pattern binding

`aggregatePatternIds` should point to rows in:

- `moqui.agent.AgentAggregatePattern`
- `moqui.agent.AgentAggregatePatternMember`

Examples:

- `AggrBudgetTree`
- `AggrOrderHPI`
- `AggrReqSeq`
- `AggrFacilityTree`
- `AggrWorkEffTree`

## Runtime expectations

`plan#AgentSkill` should be able to answer:

- is the skill structurally applicable?
- are referenced services defined?
- which inputs are still missing?
- is execution ready?
- should prompt fallback still be allowed?

Possible states:

- `ready`
- `needsMoreContext`
- `refused`

## Migration rule

When upgrading a skill from the older format:

1. change `artifactTypeEnumId` to `AT_AGENT_SKILL`
2. replace generic `preferredServices` with root/child/lookup roles where possible
3. add `aggregatePatternIds`
4. add explicit `requiredInputs`
5. keep `legacyFallbackDocumentId` only as transitional support
