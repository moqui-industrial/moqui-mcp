# MCP Smoke Test Report

- Endpoint: `http://localhost:8080/mcp/message`
- Session: `gradle-session-debug`
- Passed: `13`
- Failed: `0`

## Checks
- `PASS` `initialize`: protocolVersion=2025-06-18
- `PASS` `session_header_received`: mcpSessionId=111121
- `PASS` `notifications_initialized`: response={}
- `PASS` `tools_list_runtime_tool_visible`: tools=['moqui_get_runtime_context', 'moqui_check_runtime_configuration', 'moqui_search_agent_prompts', 'moqui_get_agent_document', 'moqui_get_session_context', 'moqui_update_session_context', 'moqui_execute_agent_prompt', 'moqui_check_artifact_access', 'moqui_find_records_guarded', 'moqui_resolve_and_execute', 'moqui_create_agent_session', 'moqui_update_agent_session_external_context']
- `PASS` `tools_list_debug_tools_visible_for_debug_user`: visible_debug_tools=['moqui_call_service_guarded', 'moqui_get_artifact', 'moqui_search_artifacts']
- `PASS` `tools_call_debug_allowed`: response={'content': [{'type': 'text', 'text': '{"searchModeUsed":"unavailable","resultList":[]}'}], 'isError': False}
- `PASS` `search_agent_prompts`: resultCount=3
- `PASS` `update_session_context`: response={'content': [{'type': 'text', 'text': '{"agentSessionContext":{"agentSessionContextId":"101225","visitId":"111121","userId":"EX_JOHN_DOE","sessionId":"111121","currentArea":"Order","currentSubArea":null,"currentScreen":null,"currentBusinessObjectsJson":"{\\"orderId\\":\\"TEST-ORDER-1\\"}","loadedDocumentsJson":null,"lastRetrievedDocumentsJson":null,"lastExecutedArtifactJson":null,"lastResultJson":null,"createdDate":"2026-05-17T13:04:49+0000","lastUpdatedDate":"2026-05-17T13:04:49+0000","partyId":null,"clientTypeEnumId":null,"clientBaseUrl":null,"clientSessionId":null,"clientContextId":null,"clientConversationId":null,"clientUrl":null,"providerEnumId":null,"providerConversationId":null,"providerThreadId":null,"providerLastResponseId":null,"modelName":null,"contextStorageModeEnumId":null,"statusId":null,"lastUpdatedStamp":"2026-05-17T13:04:49+0000","currentBusinessObjects":{"orderId":"TEST-ORDER-1"},"loadedDocuments":null,"lastRetrievedDocuments":null,"lastExecutedArtifact":null,"lastResult":null}}'}], 'isError': False}
- `PASS` `create_agent_session`: agentSessionContextId=101126
- `PASS` `find_agent_sessions`: found=3 sessions, created_in_list=True
- `PASS` `update_agent_session_external_context`: response=AgSessActive
- `PASS` `close_agent_session`: statusId=AgSessClosed
- `PASS` `find_records_guarded_privileged_access`: response={'limitUsed': 1, 'recordList': [{'invoiceId': '100000', 'invoiceTypeEnumId': 'InvoiceSales', 'fromPartyId': 'ORG_ZIZI_CORP', 'toPartyId': 'CustJqp', 'statusId': 'InvoicePmtRecvd', 'billingAccountId': None, 'invoiceDate': '2026-05-11T10:37:00+0000', 'dueDate': '2026-06-10T10:37:00+0000', 'settlementTermId': 'Net30', 'paidDate': None, 'invoiceMessage': None, 'referenceNumber': '123', 'otherPartyOrderId': None, 'description': None, 'currencyUomId': 'USD', 'overrideOrgPartyId': None, 'productStoreId': None, 'partyRelationshipId': None, 'timePeriodId': None, 'acctgTransResultEnumId': None, 'systemMessageRemoteId': None, 'externalId': None, 'originId': None, 'invoiceTotal': None, 'appliedPaymentsTotal': None, 'unpaidTotal': None, 'lastUpdatedStamp': '2026-05-11T10:39:56+0000'}]}
