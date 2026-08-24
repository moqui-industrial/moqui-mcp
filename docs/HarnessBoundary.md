# Harness Boundary

`moqui-mcp` and `moqui-harness` serve different control layers.

This split is intentional and is based on runtime validation, not only on architecture preference.

Free-form ERP prompts such as "create a full project with milestones and tasks" or "create a budget with detailed lines and inferred lookups" behave like code generation or dynamic workflow synthesis. They require:

- broad artifact injection into the LLM context
- multi-step planning
- recovery and revision
- composition of multiple services or XML Actions

Those requests are not the target of `moqui-mcp`.

`moqui-mcp` is for bounded, user-driven ERP interactions where Moqui already expresses the intent through screens, forms, transitions, services, and lookup behavior.

## `moqui-mcp`

`moqui-mcp` is the MCP-facing protocol adapter.

It exposes:

- Moqui resources
- Moqui service metadata
- Moqui service calls
- OpenSearch/DataDocument lookups
- user-driven prompts
- screen-derived prompt contracts when a screen transition maps directly to a single service

Typical examples:

- create a product from a `FindProduct` form
- create a sales order header from a `FindOrder` form
- move an asset using the `MoveAsset` screen flow
- resolve field values through screen-backed lookup transitions or deterministic entity/enum/status lookups

It does not own:

- autonomous planning
- morphism composition
- long-running workflow state
- replanning logic
- algebraic validation

## `moqui-harness`

`moqui-harness` is the agent-driven orchestration layer.

It may consume:

- `moqui-mcp` tools
- `moqui-mcp` resources
- `moqui-mcp` screen-derived prompt contracts
- Moqui service metadata

It owns:

- workflow planning
- step ordering
- validation
- approvals
- plan persistence
- execution traces
- category/morphism semantics

Typical examples:

- infer and create a project, its milestones, its tasks, and assignments from a single free prompt
- synthesize a budget header plus budget lines from partial business language
- generate or compose temporary `xml-actions` / service logic
- react to events by changing an existing plan without a new user prompt

## Practical Rule

If an interaction is already expressed by Moqui as a user-visible screen/transition and maps deterministically to a single service, it belongs in `moqui-mcp`.

If an interaction requires agent-selected composition of multiple steps, alternative branches, approvals, or plan revision, it belongs in `moqui-harness`.

## Validation Rule

Do not evaluate `moqui-mcp` by asking it to solve arbitrary open-ended prompts from `Prompt examples.md`.

Evaluate `moqui-mcp` by checking whether a single screen-derived prompt contract can:

1. be discovered,
2. expose its required inputs,
3. resolve lookup-backed fields,
4. bind to the correct Moqui tool call,
5. execute the intended transition or bound service,
6. and produce the expected state change in Moqui.
