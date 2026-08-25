package org.moqui.mcp

import org.moqui.context.ExecutionContext

class ScreenPromptRenderSupport {
    static final String PROMPT_MACRO_TEMPLATE = 'component://moqui-mcp/template/screen-macro/DefaultScreenMacros.prompt.ftl'

    protected final ExecutionContext ec

    ScreenPromptRenderSupport(ExecutionContext ec) {
        this.ec = ec
    }

    List<Map> extractInteractions(String screenLocation) {
        if (!screenLocation) return []

        Object priorValue = ec.context.get('promptRenderInteractions')
        try {
            ec.context.put('promptRenderInteractions', [])
            ec.screen.makeRender()
                    .rootScreen(screenLocation)
                    .renderMode('text')
                    .macroTemplate(PROMPT_MACRO_TEMPLATE)
                    .render()
            Object renderedInteractions = ec.context.get('promptRenderInteractions')
            if (!(renderedInteractions instanceof Collection)) return []
            return ((Collection) renderedInteractions).collect { Object item ->
                item instanceof Map ? new LinkedHashMap((Map) item) : null
            }.findAll { it != null }
        } catch (Throwable t) {
            ec.logger.debug("Screen prompt render extraction failed for ${screenLocation}: ${t.message}")
            return []
        } finally {
            if (priorValue != null) {
                ec.context.put('promptRenderInteractions', priorValue)
            } else {
                ec.context.remove('promptRenderInteractions')
            }
        }
    }
}
