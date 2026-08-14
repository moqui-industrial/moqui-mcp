# Standard Lookup And Search Sources

This note captures standard Moqui and Mantle sources that already define lookup-friendly search behavior and
OpenSearch-backed document projections. The agent runtime should reuse these sources instead of inventing
parallel lookup logic.

## Framework Search Services

- `framework/service/org/moqui/search/SearchServices.xml`
  - `search#DataDocuments`
    - generic OpenSearch query entry point
    - inputs: `indexName`, `documentType`, `queryString`, `nestedQueryMap`, `orderByFields`, pagination
  - `index#DataDocuments`
    - bulk indexer used by data feeds
  - `index#DataFeedDocuments`
    - reindexes all documents attached to a `DataFeed`

- `framework/service/org/moqui/search/ElasticSearchServices.xml`
  - compatibility include for delete services

## Standard DataDocument / DataFeed Projections

### Party / Product / Facility / Communication

- `runtime/component/mantle-udm/data/MantleDocumentData.xml`
  - `MantleParty`
    - includes `partyId`, `pseudoId`, organization/person names, `combinedName`, roles, ids, contact data,
      user account data, classifications, `customerStatusId`, `ownerPartyId`
  - `MantleProduct`
    - includes `productId`, `pseudoId`, name, description, categories, features, dimensions, identifications, prices
  - `MantleFacility`
    - includes `facilityId`, `pseudoId`, name, type, status, owner
  - `MantleCommunicationEvent`
  - `MantleSearch` feed indexes these documents through `org.moqui.search.SearchServices.index#DataDocuments`

### Accounting

- `runtime/component/mantle-udm/data/MantleDocumentAccountingData.xml`
  - `MantleGlAccount`
    - includes `glAccountId`, `accountCode`, `accountCodeCanonical`, `accountName`, description, class/type/resource metadata
    - `accountCodeCanonical` already removes punctuation and is ideal for tolerant lookup of values such as
      `111-600-000` vs `111600000`
  - `MantleAccountingData` feed indexes GL accounts

### Work / Request

- `runtime/component/mantle-udm/data/MantleDocumentWorkData.xml`
  - `MantleProject`
    - includes project id, name, status, type, purpose, related parties
  - `MantleTask`
    - includes task id, `rootWorkEffortId`, `parentWorkEffortId`, name, priority, status, purpose,
      related parties, facility, associations, communication events
  - `MantleEvent`
  - `MantleRequest`
    - includes request id, name, description, status, type, resolution, priority, facility/store links, parties
  - `MantleSearch` feed indexes these documents

### Sales

- `runtime/component/mantle-udm/data/MantleDocumentSalesData.xml`
  - `MantleSalesOrderPart`
    - includes `orderId`, `orderPartSeqId`, customer/vendor ids, facility, totals, shipping address, order header data
  - `MantleSalesOrderItem`
    - includes `orderId`, `orderItemSeqId`, `orderPartSeqId`, `productId`, quantity, dates, part/header/customer data,
      issuance and product feature/dimension data
  - `MantleSalesInvoice`
  - `MantleSalesInvoiceItem`
  - `MantleSales` feed indexes these documents

### Inventory

- `runtime/component/mantle-udm/data/MantleDocumentInventoryData.xml`
  - `MantleInventoryAsset`
    - includes `assetId`, `productId`, `facilityId`, `locationSeqId`, `statusId`, ATP/QOH
  - `MantleInventoryOrderItem`
  - `MantleInventoryRunConsume`
  - `MantleInventoryRunProduce`
  - `MantleInventoryProdEstimate`
  - `MantleInventoryData` feed indexes these documents

## Standard Search Consumers In Mantle USL

- `runtime/component/mantle-usl/service/mantle/GeneralServices.xml`
  - `search#MantleFiltered`
    - wraps `search#DataDocuments`
    - adds automatic wildcarding and org visibility filters

- `runtime/component/mantle-usl/service/mantle/party/PartyServices.xml`
  - uses `search#DataDocuments` against `MantleParty`
  - builds query strings for party search by term, role, and org visibility
  - also contains `search#Person`, a DB-backed fallback for user/person lookup

- `runtime/component/mantle-usl/service/mantle/work/ProjectServices.xml`
  - project team party lookup uses `search#DataDocuments` against `MantleParty`

- `runtime/component/mantle-usl/service/mantle/sales/SalesReportServices.xml`
  - uses `MantleSalesOrderItem` as OpenSearch reporting/search source

## Implications For Agent Runtime

1. Lookup morphisms should prefer standard OpenSearch projections already maintained by `DataFeed`.
2. Textual lookup should be OpenSearch-first, DB-confirm-second.
3. Canonical fields that already exist, such as `accountCodeCanonical`, should be reused instead of reimplemented.
4. Agent lookup registry should map common user-facing lookup intents to:
   - `MantleParty`
   - `MantleProduct`
   - `MantleFacility`
   - `MantleGlAccount`
   - `MantleProject`
   - `MantleTask`
   - `MantleRequest`
   - `MantleInventoryAsset`
   - `MantleSalesOrderPart`
   - `MantleSalesOrderItem`
5. UI-layer lookup transitions and widget templates remain valuable as semantic hints, but the retrieval substrate for
   the agent should be these OpenSearch-backed document projections whenever available.
