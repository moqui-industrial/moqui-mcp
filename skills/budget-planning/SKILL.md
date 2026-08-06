---
skillId: mantle.budget-planning
artifactName: component://moqui-mcp/skills/budget-planning/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_BUSINESS_PROCESS
domainName: Accounting
title: Budget Planning
description: Create a budget root and its ordered budget items as a single rooted aggregate in Moqui.
statusId: SkillActive
aggregatePatternIds:
  - AggrBudgetTree
patternSkillIds:
  - moqui.pattern.budget-tree
preferredRootServices:
  - mantle.other.BudgetServices.create#Budget
preferredChildServices:
  - create#mantle.other.budget.BudgetItem
preferredLookupServices:
  - org.moqui.agent.AgentExecutionServices.find#LookupRecord
requiredInputs:
  - organizationPartyId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,sessionContext,uniqueLookup
  - timePeriodId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
  - budgetTypeEnumId|requiredFor=execute|missingPolicy=ask_user|resolution=explicitParameter,uniqueLookup
legacyFallbackDocumentId: agent-prompt://accounting/findbudget/createbudget
fallbackMode: prompt_search
refusalRules:
  - Refuse when the request spans unrelated roots outside the budget aggregate.
  - Refuse when required accounting period or organization cannot be resolved.
---

# Budget Planning

## Pattern specialization
This is a business specialization of the generic pattern skill `moqui.pattern.budget-tree`.

## Purpose
Use this skill when the user asks to create or update a budget together with its ordered budget lines.

## Aggregate scope
This skill supports a single rooted accounting aggregate:

- `Budget`
- `BudgetItem`
- `BudgetItemDetail`

It does not support unrelated workflow jumps outside that aggregate.

## When to use
Use it for prompts such as:

- create budget for year 2027
- create operating budget for ZICORP
- add budget lines with GL accounts and amounts
- create budget with multiple accounting rows

## When not to use
Do not use it when the request also asks to:

- create employees
- create positions
- create orders
- create projects
- start cross-domain workflow orchestration

Those are not part of the same budget aggregate.

## Root entity
The root is `mantle.other.budget.Budget`.

Children:

- `mantle.other.budget.BudgetItem`
- `mantle.other.budget.BudgetItemDetail`

## Required context
The aggregate requires runtime resolution of:

- budget type
- organization
- accounting period / time period

If one of these is missing or not resolvable, stop and explain which prerequisite is missing.

## Execution rules
1. Resolve the request as a single budget aggregate.
2. Create the `Budget` root first.
3. Create each `BudgetItem` with distinct sequence ids.
4. Create `BudgetItemDetail` rows only if the prompt explicitly contains line-level detail beyond the item itself.
5. Post-check that the number of persisted child rows matches the intended plan.

## Service guidance
Preferred root service:

- `mantle.other.BudgetServices.create#Budget`

Preferred child service:

- `create#mantle.other.budget.BudgetItem`

Preferred lookup service:

- `org.moqui.agent.AgentExecutionServices.find#LookupRecord`

## Refusal rules
- Refuse unsupported multi-domain workflows.
- Refuse fake success when child rows do not persist.
- Refuse to guess missing accounting context if it cannot be resolved from Moqui data.

## Post-check
After execution, verify:

- budget root exists
- expected `BudgetItem` count exists
- expected `BudgetItemDetail` count exists when applicable

## Business meaning
This is an aggregate-oriented accounting operation, not a generic screen navigation task.
