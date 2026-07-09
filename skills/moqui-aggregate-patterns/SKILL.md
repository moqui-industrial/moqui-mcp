---
skillId: moqui.aggregate.patterns
artifactName: component://moqui-mcp/skills/moqui-aggregate-patterns/SKILL.md
skillTypeEnumId: AGSKL_PATTERN
domainName: MoquiMcp
title: Moqui Aggregate Pattern Guide
description: Parametric guidance for recognizing root-child aggregates and workflow boundaries in Moqui prompts.
statusId: SkillActive
patterns:
  - aggregate
  - root-child
  - workflow-boundary
  - multi-root
preferredServices:
  - org.moqui.agent.AgentSkillServices.search#AgentSkills
  - org.moqui.agent.AgentSkillServices.list#AgentSkills
  - org.moqui.agent.AgentSkillServices.get#AgentSkill
  - org.moqui.agent.AgentSkillServices.refresh#AgentSkills
---

# Moqui Aggregate Pattern Guide

## Purpose
Use this skill to recognize when a prompt belongs to a single Moqui aggregate, when it should be decomposed into ordered child records, and when it crosses a workflow boundary and should stop for clarification.

## When to use
Use this skill when the prompt names business nouns that look like Moqui entity trees or ordered child hierarchies, especially when the user expects the agent to create or update records through a known aggregate pattern.

## When not to use
Do not use this skill for unrelated artifact authoring, generic documentation tasks, or free-form prose.

## Core idea
Moqui prompts should first be classified as one of these shapes:

1. Single-root aggregate with ordered children.
2. Single-root aggregate with optional siblings.
3. Multi-root workflow that spans more than one aggregate or domain.

The third case is not a normal CRUD request. It should trigger a boundary check and, if necessary, a stop with a clear explanation.

## Canonical aggregate families
Common Moqui aggregate families include:

- `request` -> `requestItem`
- `orderHeader` -> `orderPart` -> `orderItem`
- `project` -> `milestone` -> `task`
- `budget` -> `budgetItem` -> `budgetItemDetail`
- `facility` -> child facilities through `parentFacilityId`
- `party` -> `organization` / `person` / role extensions
- `workEffort` -> children through `parentWorkEffortId` and `rootWorkEffortId`

These are examples of rooted hierarchies. The exact entity names must still be validated against the current Moqui model.

## Root detection rules
Prefer an entity as root when one or more of these are true:

- it is the natural create/update entry point in the UI or services
- it exposes a `relationship type="many"` to child entities
- child rows carry a foreign key back to it
- the child FK participates in a composite PK
- the child uses `parent*Id` or `root*Id`
- the prompt clearly asks for a root plus ordered descendants

If none of these are true, do not guess. Search the service registry or DataDocument layer first.

## Child ordering rules
When a prompt describes a rooted aggregate, preserve the structural order:

1. root entity
2. immediate children
3. grandchildren
4. leaf records

For example:

- `orderHeader` first, then `orderPart`, then `orderItem`
- `project` first, then `milestone`, then `task`

## Workflow boundary rule
If the prompt asks for:

- two unrelated roots in one request
- a creation chain that jumps across domains
- an action that requires business process orchestration rather than aggregate editing

then stop and tell the user the request is a workflow, not a single-root aggregate operation.

Do not silently invent a workflow.

## Service-first execution rule
Before composing custom logic:

1. search for an existing Moqui service
2. check whether the service already implements the requested aggregate
3. use the service if it exists and fits the root aggregate
4. only then consider composing child operations

## DataDocument/search rule
For lookup and interpretation, prefer the denormalized OpenSearch documents built from standard Moqui recipes.

Use the registry and the search layer to answer:

- what is the root entity
- what are the child entities
- which parameters are required
- whether the request is one aggregate or many

## Refusal and clarification policy
If the request crosses aggregate boundaries and no known Moqui workflow covers it, respond clearly that:

- the user is asking for a workflow spanning multiple domains
- the prompt cannot be treated as a single root entity operation
- the request needs decomposition or a dedicated workflow skill

## Examples
- Create an order with line items: valid single-root aggregate.
- Create a project, milestones, and tasks: valid single-root aggregate.
- Create a budget and also create an employee in one step: likely multi-root workflow, stop and clarify.
- Move an asset, create a person, and open an HR position in one request: workflow boundary, not a pure aggregate edit.
