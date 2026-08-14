# Final Standard Lookup Integration Plan

## Objective

Finish the runtime lookup layer by reusing standard Moqui and Mantle `DataDocument` and `DataFeed`
definitions already indexed into OpenSearch, instead of maintaining parallel ad hoc lookup logic.

This plan is focused on the operational lookup substrate needed by the semantic binding engine.

## Architectural Direction

1. Use standard `DataDocument` projections as the first lookup source whenever they already exist.
2. Use `org.moqui.search.SearchServices.search#DataDocuments` as the generic retrieval mechanism.
3. Use DB lookup only as confirmation or fallback when:
   - the OpenSearch document is missing
   - exact id resolution is needed after fuzzy text search
   - no standard `DataDocument` exists for the target concept
4. Keep custom runtime lookup documents only for gaps not covered by standard Mantle/Moqui documents.
5. Move lookup semantics into a stable registry so the semantic binder can resolve:
   - parties
   - people
   - products
   - facilities
   - assets
   - GL accounts
   - budgets
   - fiscal periods
   - status items
   - role types
   - work artifacts such as project/task/request
   - sales artifacts such as order part/item

## Authoritative Sources

### Framework

- `framework/service/org/moqui/search/SearchServices.xml`
- `framework/entity/EntityEntities.xml`

### Mantle DataDocuments / DataFeeds

- `runtime/component/mantle-udm/data/MantleDocumentData.xml`
- `runtime/component/mantle-udm/data/MantleDocumentAccountingData.xml`
- `runtime/component/mantle-udm/data/MantleDocumentWorkData.xml`
- `runtime/component/mantle-udm/data/MantleDocumentSalesData.xml`
- `runtime/component/mantle-udm/data/MantleDocumentInventoryData.xml`

### Mantle Services Already Using Search

- `runtime/component/mantle-usl/service/mantle/GeneralServices.xml`
- `runtime/component/mantle-usl/service/mantle/party/PartyServices.xml`
- `runtime/component/mantle-usl/service/mantle/work/ProjectServices.xml`

## Implementation Steps

### Step 1. Freeze Session Context

- persist this plan
- persist a session note under `codex-session/`
- record:
  - standard lookup sources discovered
  - current runtime files involved
  - next code entry points

### Step 2. Normalize Lookup Registry

Update `AgentToolSupport.groovy` so `DATA_DOCUMENT_LOOKUP_SPECS` is explicitly divided into:

- standard Mantle/Moqui lookup documents
- custom gap-filling lookup documents

Standard entries should include at least:

- `party -> MantleParty`
- `person -> MantleParty`
- `product -> MantleProduct`
- `facility -> MantleFacility`
- `asset -> MantleInventoryAsset`
- `glAccount -> MantleGlAccount`
- `project -> MantleProject`
- `task -> MantleTask`
- `request -> MantleRequest`
- `salesOrderPart -> MantleSalesOrderPart`
- `salesOrderItem -> MantleSalesOrderItem`

Custom entries kept only where no standard equivalent exists yet:

- `budget`
- `budgetType`
- `roleType`
- `status`
- `fiscalYearTimePeriod`
- `emplPositionClass`
- `emplPosition`

### Step 3. Improve Spec Semantics

For each lookup spec ensure:

- `idField` is correct
- exact fields include primary identifiers and canonical text keys
- canonical fields include normalization-friendly ids
- text fields include main descriptive fields
- fallback entity names match real entity names
- fallback exact fields match real persistent fields

Special rule:

- `glAccount` must continue to use `accountCodeCanonical`

### Step 4. Add Standard Work And Sales Lookup Coverage

Extend the runtime so prompts can resolve standard document identities from OpenSearch for:

- project names
- task names
- request names
- order ids / order parts / order items

This is not yet workflow composition.
This is the lookup substrate needed before composition.

### Step 5. Minimize Custom DataDocuments

Review `AgentRuntimeLookupDocumentSeedData.xml` and keep only documents that fill real gaps.

Do not replace standard Mantle documents with custom copies where standard projections already exist.

### Step 6. Wire Lookup Coverage Into Semantic Binding

Ensure semantic binding can call the lookup registry generically instead of case-specific lookup code.

Target result:

- prompt extraction decides semantic target
- lookup registry resolves identifiers
- binding fills required service operands

### Step 7. Verification

Run targeted tests for:

- party lookup
- product lookup
- facility lookup
- asset lookup
- GL account lookup by formatted and canonical code
- project lookup
- task lookup
- request lookup
- budget + fiscal period lookup

## Expected Outcome

After this phase:

- lookup behavior becomes largely standard-driven
- the semantic binding engine depends less on ad hoc patches
- the remaining hard problem is no longer lookup, but multi-step morphism composition

## Not In Scope For This Step

- full workflow planner
- automatic cross-domain orchestration
- security hardening
- complete morphism description enrichment

Those remain important, but they depend on a reliable standard lookup substrate first.
