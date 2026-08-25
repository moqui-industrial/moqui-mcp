# Architectural Evolution of `moqui-mcp`

## Purpose of This Document

This document records the architectural evolution of `moqui-mcp`, the motivations behind each major direction, and the reasons for the current minimal design.

It exists as a handoff artifact so that future work can restart from clear decisions instead of rediscovering the same trade-offs.

## Initial Ambition

The original goal was much broader than a conventional MCP adapter.

`moqui-mcp` was asked to bridge:

- Moqui services
- Moqui entities
- Moqui screens
- OpenSearch-backed discovery
- prompt catalogs
- lookup transitions
- screen-derived procedural guidance
- eventually agent-driven workflows

The working assumption was that Moqui screens could be transformed into MCP prompts and then used conversationally in LibreChat or other MCP-aware clients.

## Why Screen-Derived Prompts Were Attempted

The reasoning behind screen-derived prompts was strong:

- Moqui screens already encode user workflows
- transitions already encode submit and lookup logic
- forms already define input fields, required values, and UI interaction points
- Moqui already has mature security, rendering, and artifact metadata

If screen content could be transformed into MCP prompt templates, then an LLM could potentially guide a user through ERP operations without building a second application layer by hand.

This led to several experiments:

- deriving prompts from screens
- binding lookup-backed fields to parameter completion
- generating prompt catalogs
- indexing prompt metadata in OpenSearch for discovery
- exposing screen transitions as callable or lookup-like MCP surfaces

## What Worked

Several parts of the broader experiment were valid and useful:

### 1. MCP Tools for Moqui Services

Publishing Moqui services as MCP tools works well.

This is a natural mapping because:

- services are already explicit operations
- service definitions already describe parameters
- Moqui security already governs invocation
- service execution is a clean transactional boundary

### 2. MCP Resources for Entities and Data Documents

Publishing entity metadata, records, and DataDocument definitions as MCP resources also works well.

This is a natural mapping because:

- resources are read-oriented
- entity definitions are stable metadata
- entity records are deterministic when addressed by primary key
- DataDocument definitions are useful searchable metadata

### 3. Wiki-Backed Prompts

Curated prompts stored in Moqui `WikiPage` are also viable.

They are useful because:

- they are manual and intentional
- they can be business-facing or agent-facing
- they avoid trying to infer complex semantics from screens automatically

## What Did Not Hold Up

The attempt to treat rich Moqui screens as generic conversational prompts did not scale.

### 1. Complex Screens Are Not Prompts

Screens such as:

- order detail
- invoice detail
- shipment work areas
- payment screens

are not simple prompt templates.

They are operational consoles that combine:

- many sections
- many forms
- many transitions
- hidden context loading
- cross-linked updates
- high visual density
- implicit operator workflows

Trying to flatten that into a single conversational prompt produced weak behavior and poor ergonomics.

### 2. Lookup Resolution Became Too Indirect

Lookup transitions were partially mappable to MCP interaction primitives, but the resulting conversational flow became fragile:

- too many dependent lookups
- too much context had to be inferred by the model
- too many opportunities for ambiguous wording
- too much client-specific behavior in LibreChat and Inspector

This was especially problematic for ERP users who already work efficiently in graphical screens.

### 3. Prompt Discovery and Prompt Execution Were Different Problems

We found that even when a prompt template was technically complete, the user-facing problem of discovering and choosing the right prompt was separate from the execution problem.

This pushed the architecture toward:

- semantic discovery catalogs
- prompt-specific OpenSearch indexing
- prompt metadata layering

That added complexity without proving strong business value.

### 4. Rich Workflow Generation Is Closer to Code Generation

For broad free-form prompts such as project setup, budgeting, or multi-step ERP operations, the real task was not prompt filling.

It was closer to:

- generating Moqui service composition
- generating `xml-actions`
- generating procedural logic safely inside Moqui

That is a different problem from a minimal MCP adapter.

It belongs more naturally in:

- `moqui-harness`
- algebraic planning
- curated offline agent skills
- code-generation or workflow-generation layers

not inside the MCP protocol component itself.

## Resulting Architectural Split

After repeated experiments, the system was split conceptually into separate layers.

### `moqui-mcp`

This component should be:

- minimal
- standard-aligned
- stable
- protocol-oriented

Its job is to publish Moqui artifacts over MCP, not to invent workflow intelligence.

### `moqui-harness`

This is the appropriate place for:

- planning
- workflow composition
- agent-driven behavior
- morphisms and compositions
- validation and replanning

### Other Offline or Curated Layers

Additional procedural knowledge can live in:

- curated prompts
- curated skill repositories
- LLM-authored documentation
- offline-generated artifacts

## Final Design Decision for `moqui-mcp`

The current design is intentionally minimal.

### Tools

Moqui services are exposed as MCP tools.

This includes:

- helper tools such as `moqui_call_service`
- searchable helpers such as `moqui_search_data_documents`
- remote-allowed concrete Moqui services when authorized

### Resources

Moqui entities, view-entities, records, and DataDocument definitions are exposed as MCP resources.

### Prompts

Prompts come only from Moqui `WikiPage` entries in the `MCP_PROMPTS` wiki space.

This keeps prompts:

- curated
- explicit
- low-risk
- independent from screen rendering experiments

### Notifications

Notifications remain bridged through standard Moqui notification mechanisms.

## What Was Explicitly Removed

The reset to the minimal architecture intentionally removed:

- screen-derived prompt generation
- prompt catalog indexing in OpenSearch
- screen lookup resources
- screen transition execution wrappers for prompt flows
- prompt argument completion machinery tied to screen parsing
- screen prompt rendering macros

These were not removed because the experiments were meaningless.

They were removed because they belong to a different layer or require a more curated offline process than a runtime MCP adapter should carry.

## Security Rationale

Another central decision was to avoid inventing an MCP-specific security model.

`moqui-mcp` should rely on Moqui artifact-aware security:

- service visibility and invocation follow Moqui service authorization
- entity and record visibility follow Moqui artifact authorization
- prompt visibility for wiki-backed prompts follows standard Moqui access patterns

This keeps authorization consistent between:

- Moqui UI
- Moqui services
- MCP tools
- MCP resources

## Git Freeze Points

To preserve the earlier direction, the screen-prompt branch was frozen before the reset.

Frozen references:

- branch `backup/master-screen-prompts-2026-08-25`
- tag `screen-prompts-freeze-2026-08-25`

The minimal reset was then promoted on `master`.

## Current Status

`moqui-mcp` is now intended to be a minimal MCP publication layer for Moqui:

- services as tools
- entities and data definitions as resources
- curated wiki prompts only
- no runtime screen-to-prompt conversion

This is the stable baseline from which future work should proceed.

## Guidance for Future Work

If future work revisits prompts derived from ERP UI, it should do so with much stronger boundaries.

Recommended rule:

- simple prompts may be curated manually
- complex screens should remain UI
- complex procedures should become curated agent skills or harness-level workflows

That preserves the strengths of both Moqui and MCP instead of forcing all ERP interaction into chat.
