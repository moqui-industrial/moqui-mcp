# LibreChat E2E Test Plan

This plan assumes LibreChat is already integrated through:

- `docker/librechat-compose.yml`
- `docker/librechat/librechat.yaml`
- Moqui reverse proxy at `/librechat/*`

## Goal

Validate LibreChat as the real multi-provider chat client while `moqui-mcp` remains:

- MCP server
- RAG/retrieval layer
- guarded execution layer
- Moqui audit and session-governance backend

## Preconditions

- Moqui runtime is up on `http://localhost:8080`
- LibreChat stack is up from `docker/librechat-compose.yml`
- reverse proxy path `/librechat/` is active
- Moqui demo user with MCP access exists
- OpenAI or OpenAI-compatible provider is configured in LibreChat

## Automated proxy checks

Run:

```bash
python3 tools/librechat_proxy_smoke_test.py \
  --base-url http://localhost:8080/librechat/ \
  --out-json tools/output/librechat-proxy-smoke.json \
  --out-md tools/output/librechat-proxy-smoke.md
```

Expected:

- base URL responds with `200` or `302`
- returned HTML looks like an app shell, not a Moqui error page

## Manual end-to-end checks

### 1. UI boot

- Open `/librechat/`
- Verify login / landing page loads correctly through Moqui proxy

### 2. MCP tool discovery

- Open a conversation with the configured model
- Verify Moqui MCP tools are visible in LibreChat MCP/tool UI
- Expected tools include:
  - `moqui_search_agent_prompts`
  - `moqui_get_agent_document`
  - `moqui_execute_agent_prompt`
  - `moqui_get_runtime_context`

### 3. Retrieval

Prompt:

```text
Find how to configure a product store.
```

Expected:

- LibreChat calls `moqui_search_agent_prompts`
- returns Moqui prompt results without manual intervention

### 4. Document resolution

Prompt:

```text
Open the document for that action.
```

Expected:

- LibreChat calls `moqui_get_agent_document`
- returned payload includes context and execution metadata

### 5. Guarded dry run

Prompt:

```text
Try the action in dry run first.
```

Expected:

- LibreChat calls `moqui_execute_agent_prompt` with dry-run-safe behavior
- no unintended mutation occurs

### 6. Confirmation gate

Prompt:

```text
Execute a high-risk action.
```

Expected:

- Moqui returns `confirmationRequired=true`
- LibreChat does not bypass confirmation

### 7. Auth denial

Try a tool path or execution path the user should not access.

Expected:

- Moqui returns denial cleanly
- LibreChat shows the denial without retry loops or unsafe fallbacks

### 8. Session registry

If LibreChat is wired to register external sessions:

- create a new conversation
- verify `AgentSessionContext` row is created/updated
- verify no transcript is stored in Moqui

### 9. Reverse proxy integrity

Check:

- static assets load through `/librechat/`
- no broken path-prefix links
- no CSP or X-Frame surprises when used from a Moqui screen link

## Exit Criteria

LibreChat is staging-ready when:

- UI loads through `/librechat/`
- MCP tools are discovered
- retrieval works
- guarded dry run works
- confirmation gate works
- auth denial works
- optional external session registration works

Iframe embedding is a later step; validate reverse-proxy and new-tab flow first.
