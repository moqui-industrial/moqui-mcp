/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.mcp

import org.moqui.context.ExecutionContext

class CompositePromptProvider {
    protected final List<Object> promptProviders

    CompositePromptProvider(ExecutionContext ec) {
        promptProviders = [
                new WikiPromptProvider(ec)
        ]
    }

    Map listPrompts(Map params) {
        List<Map> prompts = []
        promptProviders.each { provider ->
            Map result = provider.listPrompts(params)
            if (result?.prompts instanceof Collection) prompts.addAll((Collection<Map>) result.prompts)
        }
        prompts = prompts.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') }
        Map page = McpPaginationSupport.paginate(prompts, params, 'prompts')
        Map result = [
                resultType : 'complete',
                prompts : page.prompts,
                ttlMs : McpClient.CACHE_TTL_MS,
                cacheScope : 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
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
        if (!promptName) return [completion: [values: [], total: 0, hasMore: false]]
        for (Object provider in promptProviders) {
            if (provider.hasPrompt(promptName)) return provider.complete(promptName, argumentName, argumentValue, context)
        }
        return PromptSupport.emptyCompletion()
    }
}
