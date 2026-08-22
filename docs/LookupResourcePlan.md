# Lookup Resource Plan

## Goal

Turn Moqui screen lookups into first-class MCP read-only resources exposed through
`lookup://...`, so a screen-derived prompt becomes an interaction contract that
binds together:

- prompt arguments
- lookup resources or resource templates used to hydrate uncertain fields
- final `tools/call` submit execution

## Mechanistic Model

1. `prompts/list`
   exposes screen-derived prompt templates, one per usable Moqui transition.

2. `prompts/get`
   returns:
   - the resolved submit binding
   - lookup bindings for fields that require controlled selection
   - `input_required` / `elicitation/create` when required inputs or unresolved
     lookup-backed fields remain

3. `resources/templates/list`
   exposes:
   - generic Moqui resource templates
   - prompt-specific `lookup://screen/...` templates derived from screen fields

4. `resources/read`
   on `lookup://...` performs read-only lookup logic and returns candidate values
   together with enough metadata to explain what was searched.

5. `tools/call`
   performs the actual transition or bound service call after the user or agent
   has resolved the required lookup-backed arguments.

## URI Design

Canonical lookup URI family:

- `lookup://screen/{promptName}/{argumentName}`
- `lookup://screen/{promptName}/{argumentName}?q={query}`
- `lookup://screen/{promptName}/{argumentName}?q={query}&<dependsOnField>={value}`

Where:

- `promptName` identifies the screen-derived interaction
- `argumentName` identifies the lookup-backed field
- `q` is the free-text search term
- extra query parameters carry dependency values already known in the prompt

## Supported Lookup Kinds in v1

- `enum`
- `enum-parent`
- `enum-group`
- `status`
- `entity-options`

## Deferred Lookup Kinds

These remain out of scope for the first `lookup://` pass and should be added only
after the base flow is stable:

- `dynamic-options`
- `status-transition`

## Prompt Binding Contract

Each lookup-backed prompt argument should export a binding with:

- `resourceUriTemplate`
- `lookupKind`
- `entityName`
- optional `enumTypeId`
- optional `statusTypeId`
- optional fixed conditions
- optional dependency fields

The prompt text should tell the client or LLM that:

- uncertain fields should be hydrated through the bound `lookup://` resource
- once values are resolved, submit happens through the bound MCP tool

## Why `lookup://` Instead of Direct Transition Exposure

Moqui transitions remain an implementation detail for UI-driven lookup behavior.
For MCP, the external contract is cleaner if read-only lookup behavior is exposed
as a resource:

- deterministic
- cacheable
- safe
- composable with completion and elicitation

This keeps write actions in `tools/call` and lookup actions in `resources/read`.
