# Runtime Lookup Gap Report

This report captures the current runtime lookup state after the latest OpenSearch and `DataDocument` fixes.

It is intentionally operational.
The goal is to distinguish:

- what is already modeled
- what is now indexed and usable
- what is still missing before lookup and execution are considered reliable

## Latest Runtime Round

Date: `2026-07-13`

This round validated the post-fix runtime directly against `mcp/message` with:

- admin/debug account `john.doe/moqui`
- pure runtime account `mcp_runtime_test/moqui`

The most important outcome is:

- lookup is now working for the tested business objects
- deterministic skill routing is now stable for budget and order prompts
- order parsing no longer misreads trailing order notes as products
- budget aggregate creation now persists the expected child rows in the tested case
- HR name normalization no longer pollutes the employee surname with technical prompt markers

The remaining gaps are now narrower and more concrete than before.

## Latest Algebraic Runtime Round

Date: `2026-08-06`

This round validated the algebraic planner and the MCP dispatcher together on `http://127.0.0.1:8081`.

What was confirmed:

- `plan#PromptAsMorphism` now resolves OpenSearch-backed operands before declaring `needsMoreContext`
- a budget prompt for fiscal year `2028` now resolves:
  - `timePeriodId=100255`
  - `budgetTypeEnumId=BudgetOperating`
- `moqui_resolve_and_execute` now imports those resolved operands into the aggregate execution path
- a dry-run budget prompt with explicit GL accounts and amounts now produces:
  - one budget root
  - four ordered budget items
  - correct `glAccountId` and `amount` extraction for every line
- a mixed HR + budget prompt is now blocked early with an explicit workflow-boundary message instead of falling into misleading legacy fallback behavior

What remains open after this round:

- the runtime still executes the selected aggregate through legacy prompt-document flows after the root is chosen
- localized prompt coverage is weaker than English prompt coverage in the new algebraic path
- lookup reuse is now wired for the planner, but not yet generalized into a full signature-driven morphism-chain executor

Additional status from the latest runtime restart:

- MCP initialization is now stable again from both `/mcp/*` and legacy root aliases `/sse` and `/message`
- the old LibreChat "Failed to initialize MCP server" symptom was traced to requests reaching the screen stub instead of the servlet
- `AgentToolExecLogFeed` was converted to manual feed mode in both source copies and in the runtime database
- after restart, no new `AgentToolExecLog` real-time feed rollback was observed during MCP smoke execution

The runtime-user MCP separation issue is now closed:

- `MCP_RUNTIME_TEST` no longer inherits debug/admin visibility
- runtime-user `tools/list` hides debug tools
- runtime-user debug tool calls are denied
- runtime-user guarded sensitive reads are denied

The active remaining runtime gaps are now:

- some real business prompts still need broader post-check coverage after service failure
- execution-log inspection is good for recent real runs but still inconsistent when queried from unrelated/new MCP sessions
- business-skill coverage is still partial outside the currently validated aggregates

## Current Coverage

### Aggregate And Structural Pattern Coverage

The aggregate pattern seed currently contains 10 modeled families:

- `AggrReqSeq`
- `AggrFacilityTree`
- `AggrWorkEffTree`
- `AggrOrderHPI`
- `AggrBudgetTree`
- `AggrPartySpec`
- `AggrPartyRoleCtx`
- `AggrEnumClass`
- `AggrStatusLife`
- `AggrPartyContact`

This means the component now has explicit metadata for:

- request plus sequenced child rows
- recursive facility hierarchies
- work effort tree structures
- order header / part / item
- budget / item / detail
- party specialization
- role context structures
- classification structures
- lifecycle structures
- contact structures

This is good structural coverage for the current phase, but not yet full operationalization.

### Authoritative Knowledge Corpus Coverage

The generated authoritative corpus currently contains 82 documents:

- `authoritative_xml_action_element`: 50
- `authoritative_xml_actions_guide`: 1
- `authoritative_aggregate_pattern`: 10
- `authoritative_universal_pattern`: 7
- `authoritative_chapter_reference`: 10
- `authoritative_moqui_guide`: 4

The important conclusion is:

- `xml-actions` is now present as an authoritative knowledge layer
- aggregate patterns are also present as authoritative reference documents
- the gap is no longer "missing knowledge documents"
- the remaining gap is activation of that knowledge during lookup and execution planning

### Runtime Lookup Documents Added

The following runtime lookup `DataDocument` definitions were added and loaded:

- `BudgetAndTimePeriod`
- `EmplPositionClass`
- `EmplPosition`

They are grouped in:

- `AgentRuntimeLookupFeed`

### OpenSearch Index State

The runtime lookup indices now exist in OpenSearch:

- `budget_and_time_period`
- `empl_position_class`
- `empl_position`
- `mantle_gl_account`

Observed document counts at verification time:

- `budget_and_time_period`: 21
- `empl_position_class`: 10
- `empl_position`: 13
- `mantle_gl_account`: 487

This is a major improvement because earlier prompt failures were caused by missing lookup indices, not only by prompt interpretation.

### Additional Runtime Indices Verified In Use

During the latest prompt round the runtime also relied successfully on:

- `mantle_party`
- `mantle_product`
- `mantle_accounting` / `MantleGlAccount`

This matters because the generic lookup contract is no longer only theoretical.
It is being exercised by real prompt execution for:

- organization lookup
- person lookup
- product lookup
- GL account lookup by formatted code

## What Was Fixed

### 1. Missing Runtime Lookup Indices

The runtime had repeated failures caused by missing indices such as:

- `budget_and_time_period`
- `empl_position_class`

Those indices now exist and are populated.

### 2. Runtime-Safe Document Reload Path

The component now has explicit runtime-safe services to manage lookup document definitions:

- `clear#RuntimeLookupDocumentDefinitions`
- `reload#RuntimeLookupDocumentDefinitions`
- `index#RuntimeLookupDataDocuments`

This avoids depending on offline-only flows when Moqui is already running.

### 3. Execution Log Result Capture

The execution log service no longer loses the effective result summary because of a reserved-name collision on `result`.

This means inspection tooling can now report meaningful execution summaries instead of blank outcomes.

### 4. Deterministic Skill Routing For Real Business Prompts

The runtime now deterministically prefers business specialization skills for the most common tested prompt families:

- `mantle.budget-planning`
- `mantle.order-entry`
- `mantle.support-request`
- `mantle.asset-movement`
- `mantle.employment-position-management`

This removed the prior failure mode where a valid order prompt could be hijacked by an unrelated aggregate or generic knowledge skill.

The component now also contains explicit pattern skills that sit one layer above these business specializations:

- `moqui.pattern.budget-tree`
- `moqui.pattern.order-header-part-item`
- `moqui.pattern.request-sequence`
- `moqui.pattern.facility-hierarchy`
- `moqui.pattern.party-specialization`

These pattern skills are structural and should explain boundaries and aggregate shape.
The business skills remain the executable specializations.

### 5. Order Prompt Parsing No Longer Corrupts Product Lines

The runtime parser now correctly handles:

- multiple order lines in a single sentence
- Italian `quantita'`
- trailing note suffixes such as `Note ordine CODX...`

This was verified by a real order prompt that created:

- `orderId`: `100816`
- `orderItemCount`: `2`

without treating the note token as a bogus product id.

### 6. HR Person Name Normalization

The runtime now strips trailing technical markers from employee names before:

- person lookup
- optional person creation
- employment assignment planning

This directly fixes the previously observed failure where `Mario Rossi CODX...` was decomposed into:

- `firstName = Mario`
- `lastName = CODX...`

instead of resolving or creating the correct natural person.

### 7. Runtime User / Debug User Separation

The runtime test user is now again a real runtime user instead of an accidental admin/debug hybrid.

Validated result:

- MCP smoke with `mcp_runtime_test/moqui` passes `13/13`
- debug tools are hidden from runtime `tools/list`
- runtime calls to `moqui_search_artifacts` are blocked
- sensitive entity reads without conditions are denied for runtime profile

This closes one of the major trust gaps in the MCP surface.

## Direct Lookup Validation

The new indices were also validated with direct OpenSearch queries.

### Budget And Time Period

Querying `budget_and_time_period` with `2027` returned a real indexed record:

- `budgetId`: `100155`
- `budgetTypeEnumId`: `BudgetOperating`
- `timePeriodId`: `100003`
- `periodName`: `ZICORP FY2027`
- `partyId`: `ORG_ZIZI_CORP`

This confirms that time-period lookup is now materially available to the runtime.

### Employee Position Class

Querying `empl_position_class` with `project manager` returned:

- `emplPositionClassId`: `ProjectManager`
- `title`: `Project Manager`

This confirms that human-facing class/title lookup now works for HR position-class resolution.

### General Ledger Account

Querying `mantle_gl_account` with `612300000` and `Vehicle Rent` returned:

- `glAccountId`: `612300000`
- `accountCode`: `612300000`
- `accountName`: `Vehicle Rent`

This confirms that the standard accounting lookup path is also aligned with the new generic lookup direction.

### Formatted General Ledger Account Code

The following real prompt was executed successfully:

- `... conto contabile codice 111-600-000 ...`

It produced:

- `budgetId`: `101173`
- `budgetItemCount`: `1`

This confirms that the lookup layer now resolves human-facing formatted account codes rather than requiring raw internal ids.

## Real Runtime Validation Notes

### Unsupported Multi-Domain HR Workflow Now Stops Early

The following prompt was executed directly through MCP:

- create employment position
- connect it to `HR Plan`
- create and assign `Mario Rossi`

Current behavior:

- runtime returns `success=false`
- operation is classified as `unsupported_workflow`
- matched families include `project`, `budget`, `hr`
- user receives an explicit boundary message explaining that the request crosses multiple modeled hierarchies

This is the intended direction.
It is better than fake success.

### Order Failure No Longer Pretends Success In Direct MCP Runtime

A direct MCP runtime call was executed for a sales order with insufficient inventory on one requested line.

Observed result:

- runtime returned a real service error:
  - `Inventory insufficient, not updating order ...`
- direct database check showed:
  - no `ORDER_HEADER` row for the failed order id
  - no `ORDER_ITEM` rows for that failed order id

This indicates that, in this tested path, the runtime now surfaces the failure instead of silently persisting a partial order.

## Real Prompt Validation

### Budget Lookup By Formatted GL Code

- Prompt class:
  - budget create with formatted GL code
- Result:
  - `PASS`
- Evidence:
  - `budgetId = 101173`
  - `budgetItemCount = 1`
- Conclusion:
  - formatted accounting lookup is operational

### Order Create With Note Suffix

- Prompt class:
  - multi-line sales order with final note marker
- Result:
  - `PASS`
- Evidence:
  - `orderId = 100816`
  - `orderItemCount = 2`
  - `placed = true`
- Conclusion:
  - the parser fix for order-note suffixes is effective in runtime, not only in unit tests

### Budget Create With Four Lines

- Prompt class:
  - rooted budget aggregate with 4 child lines
- Result:
  - `PASS`
- Evidence:
  - `budgetId = 101174`
  - `budgetItemCount = 4`
- Conclusion:
  - the current budget aggregate path now persists the expected number of child rows in the tested scenario

### Employment Position Assignment

- Prompt class:
  - same-subject multi-action HR request
- Result:
  - `PASS`
- Evidence:
  - `emplPositionId = 101020`
  - `partyRelationshipId = 100102`
  - resolved `personPartyId = 100051`
  - no erroneous `createPerson` action with corrupted surname
- Conclusion:
  - person normalization and person lookup are now behaving materially better

## Remaining Gaps

### Gap 1. Lookup Coverage Is Still Selective

The new lookup layer fixes important failures, but it is not yet complete.

The next likely lookup domains that still need explicit audit are:

- party / person / organization resolution
- contact mechanism resolution
- status and enum lookup helpers
- HR support entities around assignment and employment context
- any additional budget support entities discovered by real prompts

Status after `2026-07-12`:

- party / person / organization resolution: partially validated
- status and enum lookup: partially validated
- budget support entities: materially improved
- contact mechanism and wider HR context: still not audited enough

### Gap 2. Pattern Knowledge Is Not Yet Fully Activated In Planning

The component knows many structural patterns, but planning still does not always use them strongly enough.

The remaining planner problem is:

- knowing that a request follows `Budget / BudgetItem / BudgetItemDetail` is not yet the same as reliably creating the root and all children in one correct execution plan

This is the main reason some complex prompts still degrade into partial success.

Status after `2026-07-12`:

- rooted aggregates are now much more reliable for the tested budget and order cases
- `same_subject_multi_action` is still a permissive execution mode
- workflow-boundary enforcement is not yet the dominant planner behavior

### Gap 3. Xml-Actions Knowledge Is Present But Not Yet Driving Execution Strongly Enough

The component now has an authoritative xml-actions corpus, but that corpus is still acting more as reference than as an active planner substrate.

What remains is:

- stronger grounding from prompt intent to xml-actions flow shape
- better use of service orchestration patterns discovered in real artifacts
- stronger distinction between:
  - root creation
  - child creation
  - multi-domain workflow
  - unsupported cross-domain orchestration

Status after `2026-07-12`:

- this remains true
- however, the immediate blocking failures in the tested prompts were not caused by missing xml-actions knowledge
- they were caused more by planner selection and parser normalization

### Gap 4. Lookup Needs A Clear Generic Contract

The generic lookup mechanism should follow a stable rule:

- if the user mentions a business object by human-facing code, title, description, or label
- the agent should resolve it through OpenSearch lookup documents first
- it should not guess raw ids
- it should not hardcode per-domain patches unless the domain truly needs one

This contract is now feasible, but still needs wider coverage and repeated runtime validation.

Status after `2026-07-12`:

- the generic lookup contract is now validated for:
  - GL account by formatted code
  - budget by human-facing description
  - employment position class by natural-language title
  - person resolution in the tested HR prompt

### Gap 5. Execution Log Telemetry Is Still Not Reliably Inspectable

Two separate telemetry issues remain open:

1. `moqui_inspect_execution_logs` queried `moqui_agent_telemetry_v1` and returned:
   - `resultCount = 0`
2. Real-time `DataFeed` for `AgentToolExecLog` still fails because its OpenSearch materialization depends on a multi-entity view that requires joins.

Observed runtime error:

- `Multi-entity view entities are not supported, Elastic/OpenSearch does not support joins`

This means:

- the business execution path can succeed
- but post-execution observability is still weaker than it should be
- the telemetry/logging model needs simplification or a join-free projection

## Priority Order From Here

### Priority 1

Use the current indices in real prompt tests and verify that:

- budget prompts resolve `timePeriodId`
- HR prompts resolve `EmplPositionClass`
- lookups use OpenSearch rather than brittle guessing

Current state:

- achieved for the tested budget, order, and HR prompts

### Priority 2

Add the next lookup `DataDocument` definitions only where runtime evidence shows a real gap.

The goal is:

- generic lookup first
- domain-specific patch only if evidence proves it is unavoidable

Current state:

- still the right rule
- the latest fixes stayed generic and parser-oriented, not domain-hardcoded

### Priority 3

Tighten the planner so aggregate patterns are not only known, but enforced.

The planner should explicitly distinguish:

- single aggregate operation
- aggregate plus child rows
- recursive hierarchy operation
- cross-domain workflow

Current state:

- still open
- `same_subject_multi_action` remains useful but should not silently expand into broader unsupported workflow shapes

### Priority 4

Promote xml-actions knowledge from reference corpus to planner aid:

- native statement semantics
- common orchestration shapes
- artifact-level execution patterns

Current state:

- not yet complete
- no evidence from the latest prompt round that this is the immediate blocker for the verified runtime cases

### Priority 5

Repair telemetry observability so that the agent can inspect its own last execution through OpenSearch-backed logs without relying on broken multi-entity feed joins.

## 2026-07-12 Telemetry Repair And Workflow Boundary Check

### Telemetry inspection status

`moqui_inspect_execution_logs` now returns the most recent execution correctly in runtime tests.

Validated path:

- execute prompt via `moqui_resolve_and_execute`
- immediately inspect with `moqui_inspect_execution_logs`
- result returned from `OpenSearch` with:
  - `modeUsed = opensearch`
  - `resultCount = 1`
  - correct `visitId`
  - correct `toolName`
  - correct `documentId`
  - `businessResultSummary`

Concrete validated example:

- prompt created budget `101224`
- telemetry returned `agentToolExecutionElasticLogId = 101122`
- `resultSummary` confirmed `operation = composite_aggregate_create`
- `businessResultSummary.success = true`

Root cause of the previous false-negative:

- search code defaulted to `moqui_agent_telemetry_v1`
- actual indexed telemetry was also available under `agent_tool_exec_log`
- search now resolves telemetry indices through candidate fallback:
  - explicit index
  - configured property
  - `DataDocument` metadata for `AgentToolExecLog`
  - `moqui_agent_telemetry_v1`
  - `agent_tool_exec_log`

### Remaining telemetry caveat

Real-time `DataFeed` for `AgentToolExecLog` is still broken.

Observed runtime error:

- `Error running Real-time DataFeed for DataDocument AgentToolExecLog`
- cause: multi-entity / join-style OpenSearch projection not supported

Current practical status:

- `inspect_execution_logs`: PASS
- real-time feed path: FAIL
- backfill/index-on-demand path: PASS

Conclusion:

Telemetry is now operational enough for runtime verification, but the feed design should still be simplified later to a join-free projection.

### Workflow boundary status

Prompt tested:

- create employee position
- connect to budget `HR Plan`
- assign to new employee `Mario Rossi`

Current runtime result after classifier tightening:

- tool returned `success = false`
- `operation = unsupported_workflow`
- `blocked = true`
- message explicitly explains:
  - request is leaving the root/child hierarchy
  - request is asking for a multi-domain workflow
  - user should split it into atomic requests or use a dedicated workflow

Conclusion:

- the HR/admin workflow boundary is now enforced for this class of prompt
- the previous false-success behavior is no longer present in the validated runtime test

Residual refinement:

- lexical overlap on `project manager` currently contributes to `matchedFamilies = [project, budget, hr]`
- behavior is correct, but family labeling can still be made cleaner later so role titles do not look like domain roots

## Backup Status

Recent runtime backups available:

- [backups/20260712-174852](/home/igor/development/projects/moqui/tests/ai/backups/20260712-174852)
- [backups/20260712-182458](/home/igor/development/projects/moqui/tests/ai/backups/20260712-182458)
- [backups/20260712-182706](/home/igor/development/projects/moqui/tests/ai/backups/20260712-182706)

These backups are important because the lookup layer is now partially materialized both in H2 and OpenSearch state.

## Bottom Line

The current state is materially better than the earlier failing state.

The most important change is this:

- the runtime no longer lacks the basic lookup indices for budget and HR-position-class resolution

The main unfinished work is now not "invent more theory".
It is:

- widen generic lookup coverage carefully
- validate with real prompts
- make the planner use the pattern and xml-actions knowledge more decisively
