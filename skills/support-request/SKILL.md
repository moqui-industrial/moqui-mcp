---
skillId: mantle.support-request
artifactName: component://moqui-mcp/skills/support-request/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_BUSINESS_PROCESS
domainName: Support
title: Support Request
description: Create a support request and its request items as a single rooted aggregate.
statusId: SkillActive
aggregatePatternIds:
  - AggrReqSeq
patternSkillIds:
  - moqui.pattern.request-sequence
preferredRootServices:
  - mantle.support.request.RequestServices.create#Request
preferredChildServices:
  - create#mantle.support.request.RequestItem
preferredLookupServices:
  - org.moqui.agent.AgentExecutionServices.find#LookupRecord
requiredInputs:
  - requestTypeEnumId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
legacyFallbackDocumentId: agent-prompt://request/findrequest/createrequest
fallbackMode: prompt_search
refusalRules:
  - Refuse when the request spans unrelated roots outside the request aggregate.
  - Refuse fake success when request items did not persist.
---

# Support Request

## Pattern specialization
This is a business specialization of the generic pattern skill `moqui.pattern.request-sequence`.

## Purpose
Use this skill when the user asks to open a request with one or more request items.

## Aggregate scope
Single rooted aggregate:

- `Request`
- `RequestItem`

## Execution rules
1. Resolve parties, categories, and references through lookup documents first.
2. Create the request root.
3. Create request items in order.
4. Post-check the persisted root and child count.
