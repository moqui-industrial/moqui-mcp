---
skillId: moqui.algebraic.artifact-morphism-search
artifactName: component://moqui-mcp/skills/algebraic/moqui-artifact-morphism-search/SKILL.md
artifactTypeEnumId: AT_AGENT_SKILL
artifactGroupId: McpAgentSkills
skillTypeEnumId: AGSKL_SYSTEM
domainName: MoquiMcp
title: Moqui Artifact Morphism Search
description: Treat prompts as requests for registered Moqui morphisms, validate signatures, bind operands, and refuse anything outside the authorized service vocabulary.
statusId: SkillActive
patterns:
  - algebraic-catalog
  - service-morphism
  - signature-check
  - operand-binding
preferredServices:
  - org.moqui.agent.AgentAlgebraicServices.search#Morphisms
  - org.moqui.agent.AgentAlgebraicServices.get#MorphismSignature
  - org.moqui.agent.AgentAlgebraicServices.search#Objects
  - org.moqui.agent.AgentAlgebraicServices.get#ObjectSchema
  - org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism
refusalRules:
  - Refuse requests that require inventing new services, generating Groovy, or writing free SQL.
  - Refuse requests that cannot be compiled to an authorized registered Moqui morphism.
fallbackMode: none
---

# Moqui Artifact Morphism Search

Treat each user request as a request for an existing Moqui morphism.

## Operating Rules

1. Classify the request as read-only, mutating, explanatory, or unsupported.
2. Search registered Moqui service morphisms before doing anything else.
3. Use entity objects only to understand schema and bind operands.
4. Validate the selected morphism signature using authoritative service metadata.
5. Bind only explicit or resolvable operands.
6. If required operands are missing, return `needsMoreContext`.
7. If no registered morphism exists, return `refused` or `unsupported`.
8. Do not generate Groovy.
9. Do not generate SQL.
10. Do not invent services, parameters, or workflow steps.
11. Apply ArtifactAuthz before any execution planning.
12. Prefer dry-run planning until the selected morphism and operands are fully valid.

## Allowed Outcome States

- `ready`
- `needsMoreContext`
- `refused`
- `unsupported`
- `readOnlyAnswer`
