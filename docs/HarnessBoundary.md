# Harness Boundary

`moqui-mcp` and `moqui-harness` serve different control layers.

## `moqui-mcp`

`moqui-mcp` is the MCP-facing protocol adapter.

It exposes:

- Moqui resources
- Moqui service metadata
- Moqui service calls
- OpenSearch/DataDocument lookups
- user-driven prompts
- screen-derived prompt contracts when a screen transition maps directly to a single service

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

## Practical Rule

If an interaction is already expressed by Moqui as a user-visible screen/transition and maps deterministically to a single service, it belongs in `moqui-mcp`.

If an interaction requires agent-selected composition of multiple steps, alternative branches, approvals, or plan revision, it belongs in `moqui-harness`.
