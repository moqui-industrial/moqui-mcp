package org.moqui.mcp

import org.moqui.context.ExecutionContext

class CompositePromptProvider {
    protected final List<Object> promptProviders

    CompositePromptProvider(ExecutionContext ec) {
        promptProviders = [
                new WikiPromptProvider(ec),
                new ScreenPromptProvider(ec)
        ]
    }

    Map listPrompts(Map params) {
        List<Map> prompts = []
        promptProviders.each { provider ->
            Map result = provider.listPrompts(params)
            if (result?.prompts instanceof Collection) prompts.addAll((Collection<Map>) result.prompts)
        }
        return [
                resultType: 'complete',
                prompts   : prompts.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') },
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private'
        ]
    }

    Map getPrompt(String name, Map params) {
        if (!name) throw new IllegalArgumentException('Prompt name is required')
        for (Object provider in promptProviders) {
            if (provider.hasPrompt(name)) return provider.getPrompt(name, params)
        }
        throw new IllegalArgumentException("Unknown prompt ${name}")
    }

    Map complete(Map ref, String argumentName, String argumentValue, Map context) {
        String promptName = ref.name as String
        if (!promptName) return [resultType: 'complete', completion: [values: [], total: 0, hasMore: false]]
        for (Object provider in promptProviders) {
            if (provider.hasPrompt(promptName)) return provider.complete(promptName, argumentName, argumentValue, context)
        }
        return PromptSupport.emptyCompletion()
    }
}
