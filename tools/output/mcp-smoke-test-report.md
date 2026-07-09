# MCP Smoke Test Report

- Endpoint: `http://localhost:8080/mcp/message`
- Session: `gradle-session`
- Passed: `13`
- Failed: `0`

## Checks
- `PASS` `initialize`: protocolVersion=2025-06-18
- `PASS` `session_header_received`: mcpSessionId=111313
- `PASS` `notifications_initialized`: response={}
- `PASS` `tools_list_runtime_tool_visible`: tools=['moqui_get_runtime_context', 'moqui_check_runtime_configuration', 'moqui_search_agent_prompts', 'moqui_get_agent_document', 'moqui_get_session_context', 'moqui_update_session_context', 'moqui_execute_agent_prompt', 'moqui_check_artifact_access', 'moqui_find_records_guarded', 'moqui_resolve_and_execute', 'moqui_create_agent_session', 'moqui_update_agent_session_external_context']
- `PASS` `tools_list_debug_tools_hidden_for_runtime_user`: leaked_debug_tools=[]
- `PASS` `tools_call_debug_denied_or_blocked`: response={'content': [{'type': 'text', 'text': 'Tool not available for current user: moqui_search_artifacts'}], 'isError': True}
- `PASS` `search_agent_prompts`: resultCount=3
- `PASS` `update_session_context`: response={'content': [{'type': 'text', 'text': '{"agentSessionContext":{"agentSessionContextId":"101326","visitId":"111313","userId":"100000","sessionId":"111313","currentArea":"Order","currentSubArea":null,"currentScreen":null,"currentBusinessObjectsJson":"{\\"orderId\\":\\"TEST-ORDER-1\\"}","loadedDocumentsJson":null,"lastRetrievedDocumentsJson":null,"lastExecutedArtifactJson":null,"lastResultJson":null,"createdDate":"2026-05-17T14:28:19+0000","lastUpdatedDate":"2026-05-17T14:28:19+0000","partyId":null,"clientTypeEnumId":null,"clientBaseUrl":null,"clientSessionId":null,"clientContextId":null,"clientConversationId":null,"clientUrl":null,"providerEnumId":null,"providerConversationId":null,"providerThreadId":null,"providerLastResponseId":null,"modelName":null,"contextStorageModeEnumId":null,"statusId":null,"lastUpdatedStamp":"2026-05-17T14:28:19+0000","currentBusinessObjects":{"orderId":"TEST-ORDER-1"},"loadedDocuments":null,"lastRetrievedDocuments":null,"lastExecutedArtifact":null,"lastResult":null}}'}], 'isError': False}
- `PASS` `create_agent_session`: agentSessionContextId=101123
- `PASS` `find_agent_sessions`: found=1 sessions, created_in_list=True
- `PASS` `update_agent_session_external_context`: response=AgSessActive
- `PASS` `close_agent_session`: statusId=AgSessClosed
- `PASS` `find_records_guarded_sensitive_denied`: response={'content': [{'type': 'text', 'text': 'Error: Query senza condizioni non consentita per entity sensibile: mantle.account.invoice.Invoice\n'}], 'isError': True}
