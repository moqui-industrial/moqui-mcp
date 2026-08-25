## Scope

This handoff captures the repository state after the first form-centric prompt refactor in `moqui-mcp`.

Date: 2026-08-25
Branch: `master`

## Why this change was made

The previous prompt compiler model was still fundamentally:

- `transition -> prompt`

That was not aligned with the intended Moqui/MCP interaction model.

The agreed target model is:

- `1 prompt = 1 form-single or form-list`
- the form carries the interaction contract
- the transition attached to the form is the final submit binding
- lookup transitions remain auxiliary lookup resources/tools

This matches the user-driven ERP interaction model more closely than transition-centric prompt publication.

## What was implemented

### 1. Render-time extraction using Moqui screen macros

Added:

- `template/screen-macro/DefaultScreenMacros.prompt.ftl`
- `src/main/groovy/org/moqui/mcp/ScreenPromptRenderSupport.groovy`

This is the first step toward a Moqui-native prompt compiler.

Instead of parsing only raw screen XML in Groovy, the compiler now invokes the Moqui screen renderer with a custom macro template and captures:

- `form-single`
- `form-list`

as interaction units, storing them in:

- `ec.context.promptRenderInteractions`

The FTL currently extracts:

- `formName`
- `transitionName`
- `formType`
- `interactionKind`
- simple field metadata

The final MCP prompt contract is still assembled in Groovy.

### 2. Compiler refactor to form-centric prompts

Modified:

- `src/main/groovy/org/moqui/mcp/ScreenInteractionCompiler.groovy`

The compiler now works primarily as:

- `form -> prompt descriptor`

instead of:

- `transition -> prompt descriptor`

Each prompt descriptor is now built from:

- screen
- form
- submit transition

and still enriches arguments from:

- bound service schema
- transition parameters
- form fields
- lookup hints

### 3. Documentation update

Modified:

- `README.md`

The README now documents:

- the distinction between business-facing prompt discovery and technical MCP prompt contracts
- the `SKILL.md` frontmatter analogy for prompt catalog documents
- the move toward render-based form extraction

## Runtime status after refactor

### Confirmed working

- `moqui-mcp` compiles successfully
- MCP endpoint initializes on `http://localhost:8081/mcp`
- `prompts/list` returns form-centric prompts
- prompt titles/descriptions now reflect forms instead of loose transitions

Examples observed in runtime:

- `Profile.EditUser.updateUser`
- `NewProject.createProject`
- `HomeProductList.addToCart`

These are evidence that the form-centric refactor is live in runtime.

## Important limitation discovered

The current runtime catalog only returns a small visible subset of prompts for the current MCP identity.

Observed:

- `prompts/list` returned 11 prompts
- `FindProduct` was not present in the visible catalog

This means the next blocking issue is not the prompt compiler structure anymore.

The real open issue is:

- prompt visibility / authorization filtering

## Security and visibility hypothesis

Prompt visibility currently flows through:

- `src/main/groovy/org/moqui/mcp/PromptSecurityHelper.groovy`

The local MCP requests are authenticated in:

- `src/main/groovy/org/moqui/mcp/McpServlet.groovy`

For local trusted calls, the servlet logs in the configured service account:

- default `john.doe`

So the missing `FindProduct` prompt is likely caused by one of these:

1. the service account does not actually have screen/transition permissions for the relevant `SimpleScreens` prompt source
2. the prompt visibility check is too restrictive for some screen path combinations
3. the visible root screen traversal excludes some valid prompts indirectly

## Current architectural position

At this point:

- tools/resources MCP layer is already stable enough to be treated as the minimal protocol layer
- prompt compilation moved in the correct direction
- remaining prompt work is mostly:
  - visibility/security correctness
  - prompt catalog completeness
  - later, richer render-based prompt body generation

## Recommended next steps

### Immediate next step

Debug why `FindProduct.NewProductForm.createProduct` is not visible in `prompts/list` for the current authenticated MCP identity.

Start from:

- `PromptSecurityHelper.isPromptVisible(...)`
- `ScreenUrlInfo.getScreenUrlInfo(...)`
- root/relative path derivation for `SimpleScreens`
- service account `john.doe` effective permissions

### After visibility is fixed

1. verify `FindProduct.NewProductForm.createProduct`
2. verify lookup completion for:
   - `ownerPartyId`
   - `productTypeEnumId`
3. verify full flow:
   - `prompts/get`
   - `completion/complete`
   - `tools/call`

### Later refinement

Move more of the prompt body generation from Groovy into the FTL render model, keeping Groovy mainly as orchestration and MCP serialization.

## Files changed in this handoff

- `README.md`
- `src/main/groovy/org/moqui/mcp/ScreenInteractionCompiler.groovy`
- `src/main/groovy/org/moqui/mcp/ScreenPromptRenderSupport.groovy`
- `template/screen-macro/DefaultScreenMacros.prompt.ftl`

## Reproduction notes

Compile:

```bash
cd /home/igor/development/projects/moqui/tests/ai/moqui-framework
./gradlew :runtime:component:moqui-mcp:compileGroovy
```

Run:

```bash
cd /home/igor/development/projects/moqui/tests/ai/moqui-framework
./gradlew run
```

MCP endpoint:

```text
http://localhost:8081/mcp
```

## Key conclusion

The refactor proved that the correct prompt publication unit is the Moqui form, not the isolated transition.

The next problem to solve is no longer prompt shape but prompt visibility under Moqui artifact-aware security.
