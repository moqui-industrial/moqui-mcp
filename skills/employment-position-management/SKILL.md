---
skillId: mantle.employment-position-management
artifactName: component://moqui-mcp/skills/employment-position-management/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_BUSINESS_PROCESS
domainName: HumanResources
title: Employment Position Management
description: Create and assign an employment position only when the request stays within a rooted HR aggregate and all required references can be resolved.
statusId: SkillActive
aggregatePatternIds:
  - AggrPartySpec
patternSkillIds:
  - moqui.pattern.party-specialization
preferredRootServices:
  - create#mantle.humanres.position.EmplPosition
preferredChildServices:
  - mantle.humanres.EmploymentServices.create#Employment
preferredLookupServices:
  - org.moqui.agent.AgentExecutionServices.find#LookupRecord
preferredServices:
  - mantle.party.PartyServices.create#Person
requiredInputs:
  - employingOrganizationPartyId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
  - statusId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
legacyFallbackDocumentId: agent-prompt://humanres/findemplposition/createemplposition
fallbackMode: prompt_search
refusalRules:
  - Refuse when the request depends on an unrelated budget, project, or workflow root that cannot be resolved.
  - Refuse fake success when the person or position was not persisted.
---

# Employment Position Management

## Pattern specialization
This is a business specialization of the generic pattern skill `moqui.pattern.party-specialization`.

## Purpose
Use this skill for HR requests that stay inside a single hiring context:

- create a person if needed
- create an employment position
- assign the person to that position

## Aggregate and boundary
This is not a free-form workflow engine.
It supports only the bounded HR operation around:

- `Person`
- `EmplPosition`
- `PartyRelationship` or equivalent employment link

If the prompt also requires unrelated roots outside this bounded HR context, stop and explain that it is a multi-domain workflow.

## Required context
Resolve before execution:

- employing organization
- position class / role
- status
- budget reference if explicitly requested

If one of these is missing, explain exactly what prerequisite is unresolved.

## Execution rules
1. Resolve lookup references through runtime lookup documents first.
2. Create the person only if it does not already exist.
3. Create the position only after the HR context is complete.
4. Create or update the employment relationship only after person and position exist.
5. Post-check persisted ids for the person, position, and assignment.
