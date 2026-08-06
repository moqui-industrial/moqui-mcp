# AgentSkill Execution Gap Plan

This document turns the current review into a concrete implementation checklist.

It is based on the current code in `moqui-mcp`, not on a hypothetical redesign.

## Current Assessment

`moqui-mcp` is strong on skill infrastructure and only partial on skill-driven execution.

The current state is approximately:

- `70-75%` complete versus the agreed skill-first plan
- registry/search/index foundation is now working on the correct runtime
- one real business aggregate (`budget-planning`) is verified end-to-end in direct MCP execution
- workflow-boundary refusal is now enforced for unsupported multi-domain prompts
- remaining gaps are concentrated in breadth, not in the core budget aggregate path

## What Is Already Solid

These parts are real and already present in the component:

- `AgentSkillRegister` exists and is usable as a v1 registry
- `SKILL.md` discovery and frontmatter parsing are implemented
- skill rows can be refreshed and indexed
- skill documents are searchable through standard `SearchServices`
- MCP exposes skill-first tools:
  - `moqui_resolve_agent_skill`
  - `moqui_list_agent_skills`
  - `moqui_search_agent_skills`
  - `moqui_get_agent_skill`
  - `moqui_refresh_agent_skills`
  - `moqui_index_agent_skills`
- aggregate-pattern metadata exists in `AgentAggregatePattern*`
- runtime now blocks unsupported multi-domain workflows instead of pretending success
- budget aggregate execution has been repaired and verified as a real root-plus-children aggregate
- dry-run planning for budget now returns a full aggregate plan with resolved lookup inputs and ordered child sequence ids
- stale OpenSearch skill documents can be re-aligned through:
  - `org.moqui.agent.AgentSkillServices.refresh#AgentSkills`
  - `org.moqui.agent.AgentSkillServices.index#AgentSkillDocuments`

## Main Structural Gap

The key gap is in:

- [AgentRuntimeServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentRuntimeServices.xml)

In `resolve#AndExecuteAgentPrompt`, the flow currently does this:

1. try `resolve#AgentSkill`
2. log a message that a skill was resolved
3. still continue into `search#AgentPrompts`
4. still pick a prompt/screen/service document
5. still execute mainly through the old path

This gap has been partially closed.

Today:

- `AgentSkill = operational procedure layer` for the repaired budget aggregate path
- `AgentSkill = refusal boundary layer` for unsupported multi-domain requests

Still missing:

- broader skill coverage for additional business aggregates
- richer runtime lookup coverage
- explicit post-check persistence verification on more than the current repaired flows

## Code Evidence

### Skill Resolution Exists

In [AgentRuntimeServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentRuntimeServices.xml):

- `resolve#AgentSkill` is called around lines containing:
  - `org.moqui.agent.AgentSkillServices.resolve#AgentSkill`
- `selectedSkill` only contributes a user-facing message

### Prompt-First Execution Still Dominates

In the same file, after skill resolution the code still does:

- `org.moqui.agent.AgentPromptServices.search#AgentPrompts`
- prompt candidate selection
- fallback document forcing
- `AgentExecutionServices.execute#AgentPrompt`

That is the core runtime bottleneck.

### Skill Registry Is Real

Files:

- [AgentSkillServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentSkillServices.xml)
- [AgentSkillSeedData.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/data/AgentSkillSeedData.xml)

This part is not the problem.

### Pattern Metadata Is Real But Not Activated

File:

- [AgentEntities.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/entity/AgentEntities.xml)

Pattern metadata exists, but runtime does not yet explicitly let a selected skill drive:

- pattern choice
- root placeholder resolution
- child sequencing
- refusal boundary

## Checklist

## Phase 1 - Make Skill Selection Operational

Goal:

- when a skill is selected, it must be able to govern execution instead of only annotating it

Files to change:

- [AgentRuntimeServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentRuntimeServices.xml)
- [AgentSkillServices.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/runtime/component/moqui-mcp/service/org/moqui/agent/AgentSkillServices.xml)
- likely add a new helper service file such as `AgentSkillExecutionServices.xml`

Required changes:

- load full selected skill, not only its metadata stub
- parse and use frontmatter fields such as:
  - `patterns`
  - `preferredServices`
  - `requiredContext`
  - `refusalRules`
  - `fallbackMode`
- insert a new explicit branch:
  - `selectedSkill -> executeSkillPlan(...)`
- only fall back to `search#AgentPrompts` when the skill says fallback is allowed

Exit criteria:

- a selected skill changes runtime behavior, not only messages
- logs show whether execution path was `skill-driven` or `prompt-fallback`

## Phase 2 - Add Business Skills, Not Only Infrastructure Skills

Goal:

- move from system skills to business-operational skills

Current seed data contains only infrastructure-oriented skills:

- `moqui-agent.skill.registry`
- `moqui.aggregate.patterns`
- `moqui-datadocument-datafeed`

Missing high-value business skills:

- `budget-planning`
- `order-entry`
- `project-breakdown`
- `support-request`
- `asset-movement`
- `employment-position`

Preferred location:

- business skills should live in business components, especially `mantle-ubpl`, not only in `moqui-mcp`

Required changes:

- add `SKILL.md` for the first 2-3 business processes
- register them through the existing scanner
- encode:
  - supported aggregate pattern
  - subject/root entity
  - allowed child entities
  - service sequence
  - refusal conditions
  - post-check expectations

Exit criteria:

- `moqui_resolve_agent_skill` returns business skills for concrete ERP prompts
- runtime can follow those skills without screen-first guessing

## Phase 3 - Bind Skills to Aggregate Patterns Explicitly

Goal:

- connect skill execution to `AgentAggregatePattern`

Current issue:

- pattern entities exist
- the selected skill does not yet formally activate a specific pattern record

Required changes:

- frontmatter should reference a stable pattern id or pattern name
- add runtime helper that resolves:
  - skill -> aggregate pattern
  - aggregate pattern -> root member
  - aggregate pattern -> child members
  - aggregate pattern -> sequencing rules
- remove hidden coupling between prompt heuristics and aggregate selection where possible

Exit criteria:

- runtime can explain:
  - which aggregate pattern matched
  - why the prompt is in-bound or out-of-bound

## Phase 4 - Add Refusal Boundaries Based On Pattern Scope

Goal:

- reject unsupported cross-domain workflows clearly and early

This part has started, but must become skill-aware.

Examples:

- supported:
  - `Budget -> BudgetItem -> BudgetItemDetail`
  - `Project -> Milestone -> Task`
  - `OrderHeader -> OrderPart -> OrderItem`
- unsupported unless a dedicated skill exists:
  - create employee, then create HR position, then connect to budget
  - create customer, then create order, then reserve inventory, then create shipment

Required changes:

- encode boundary rules in skill frontmatter/body
- use those rules before prompt fallback
- tell the user:
  - this is a supported aggregate request
  - or this is a multi-domain workflow requiring another skill or manual step

Exit criteria:

- no fake success on unsupported workflows
- no generic “technical difficulty” when the real issue is “workflow outside supported aggregate boundary”

## Phase 5 - Introduce Service-Level Registry Binding

Goal:

- stop guessing service chains ad hoc

Important note:

- Moqui standard already has `moqui.service.ServiceRegister`

That should be used as a registry anchor whenever a skill depends on a named service flow.

Files to inspect or extend:

- [ServiceEntities.xml](/home/igor/development/projects/moqui/tests/ai/moqui-framework/framework/entity/ServiceEntities.xml)

Required changes:

- allow a skill to reference:
  - preferred service
  - ordered service list
  - service register ids where applicable
- add runtime validation that every referenced service really exists and is authorized

Exit criteria:

- business skills use stable service references
- runtime does not invent service names or sequences

## Phase 6 - Add Skill Execution Telemetry

Goal:

- prove what path executed and whether it was real

Required changes:

- extend telemetry to store:
  - selected skill id
  - pattern id
  - execution mode:
    - `skill_only`
    - `skill_plus_prompt_fallback`
    - `prompt_only`
  - refusal reason
  - post-check result

Related existing tool:

- `moqui_inspect_execution_logs`

Exit criteria:

- after any test prompt, we can inspect whether runtime really executed the intended path

## Phase 7 - Add Evaluation For Skill-First Runtime

Goal:

- stop measuring only retrieval; measure agentic correctness

Needed test families:

- aggregate success:
  - budget with lines
  - project with milestones/tasks
  - order with items
- aggregate refusal:
  - unsupported multi-domain workflow
- prerequisite refusal:
  - missing budget
  - missing root reference
  - missing person
- no-fake-success:
  - user-facing success must match database state

Exit criteria:

- a smoke suite can assert:
  - selected skill
  - selected pattern
  - executed services
  - created records
  - refusal messages

## Priority Order

Do these in this order:

1. make selected skill operational in runtime
2. add 2-3 real business skills
3. bind skill to aggregate pattern explicitly
4. enforce pattern-based refusal boundaries
5. bind skills to `ServiceRegister` or stable service references
6. improve telemetry
7. add skill-first evaluation suite

## First Concrete Milestone

The first milestone should be narrow and testable:

- implement one real business skill:
  - `budget-planning`
- make runtime execute it as a skill-driven aggregate
- verify:
  - `Budget` created
  - `BudgetItem` rows created
  - no prompt fallback required for the happy path
  - telemetry says `skill-driven`

If this milestone works, the same architecture can then be repeated for:

- `project-breakdown`
- `order-entry`

## What Not To Do Next

Avoid these until the skill execution layer is real:

- adding more generic retrieval heuristics
- adding more prompt fallback patches
- expanding screen-first behavior
- adding more infrastructure-only skills

Those would improve the surface but not solve the main runtime defect.

## Summary

The component is not failing because it lacks search infrastructure.

It is limited because:

- skills are discoverable
- patterns are modeled
- aggregates are partially executable
- but skills do not yet own the execution path

That is the next implementation boundary.
