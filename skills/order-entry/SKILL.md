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
legacyFallbackDocumentId: agent-prompt://order/findorder/createorder
fallbackMode: prompt_search
refusalRules:
  - Refuse when the request spans unrelated roots outside the order aggregate.
  - Refuse fake success when order items did not persist.
---

# Order Entry

## Pattern specialization
This is a business specialization of the generic pattern skill `moqui.pattern.order-header-part-item`.

## Purpose
Use this skill when the user asks to create a sales or purchase order with one or more lines.

## Aggregate scope
Single rooted aggregate:

- `OrderHeader`
- `OrderPart`
- `OrderItem`

## Execution rules
1. Resolve customer, product, and delivery references through lookup documents first.
2. Create the order root.
3. Add parts and items in structural order.
4. Post-check the persisted header and item count.
