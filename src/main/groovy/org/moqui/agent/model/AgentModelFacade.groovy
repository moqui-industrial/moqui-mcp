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
package org.moqui.agent.model

import org.moqui.agent.AgentConfigUtil
import org.moqui.context.ExecutionContext

/**
 * Facade that selects and instantiates the configured AgentModelProvider.
 * Callers should use this instead of instantiating providers directly.
 */
class AgentModelFacade {
    protected final ExecutionContext ec

    AgentModelFacade(ExecutionContext ec) {
        this.ec = ec
    }

    Map generateJson(String systemPrompt, Map userPayload, Map options) {
        String provider = options?.purpose == 'chat' ? resolveChatProvider(options) : resolveRerankerProvider(options)
        if (!provider || provider == 'none') {
            throw new IllegalStateException(
                "AgentModelFacade.generateJson() called with provider=none. " +
                "Callers must check provider before calling (e.g. AgentPromptLlmReranker skips when provider=none)."
            )
        }
        AgentModelProvider client = makeProvider(provider)
        Map capabilities = client.capabilities() ?: [:]
        if (!Boolean.TRUE.equals(capabilities.supportsJson)) {
            throw new IllegalStateException("Provider [${provider}] does not support structured JSON generation")
        }
        return client.generateJson(systemPrompt, userPayload, options)
    }

    Map generateText(String systemPrompt, Map userPayload, Map options) {
        String provider = resolveChatProvider(options)
        if (!provider || provider == 'none') {
            throw new IllegalStateException('AgentModelFacade.generateText() called with provider=none')
        }
        AgentModelProvider client = makeProvider(provider)
        Map capabilities = client.capabilities() ?: [:]
        if (!Boolean.TRUE.equals(capabilities.supportsChat)) {
            throw new IllegalStateException("Provider [${provider}] does not support chat/text generation")
        }
        return client.generateText(systemPrompt, userPayload, options)
    }

    Map embed(String inputText, Map options) {
        String provider = resolveEmbeddingProvider(options)
        if (!provider || provider == 'none') {
            return [
                embedding     : [],
                provider      : provider ?: 'none',
                skippedReason : 'provider_none'
            ]
        }
        AgentModelProvider client = makeProvider(provider)
        Map capabilities = client.capabilities() ?: [:]
        if (!Boolean.TRUE.equals(capabilities.supportsEmbeddings)) {
            throw new IllegalStateException("Provider [${provider}] does not support embeddings")
        }
        return client.embed(inputText, options)
    }

    Map capabilities(Map options) {
        String provider = resolveAnyProvider(options)
        if (!provider || provider == 'none') return [provider: 'none']
        AgentModelProvider client = makeProvider(provider)
        return [provider: provider] + ((client.capabilities() ?: [:]) as Map)
    }

    protected AgentModelProvider makeProvider(String provider) {
        switch (provider) {
            case 'openai':
                return new OpenAiAgentModelProvider(ec)
            case 'openai_compatible':
                return new OpenAiCompatibleAgentModelProvider(ec)
            default:
                throw new IllegalArgumentException("Unsupported model provider [${provider}]")
        }
    }

    protected static String resolveRerankerProvider(Map options) {
        String fromOptions = options?.provider?.toString()?.trim()?.toLowerCase()
        if (fromOptions && fromOptions != 'none' && fromOptions != 'auto') return fromOptions
        String configured = AgentConfigUtil.getNormalizedString('moqui.agent.reranker.provider', 'none')
        if (!configured || configured == 'none') return 'none'
        if (configured == 'auto') return hasRerankerApiKey() ? 'openai' : 'none'
        return configured
    }

    protected static String resolveEmbeddingProvider(Map options) {
        String fromOptions = options?.provider?.toString()?.trim()?.toLowerCase()
        if (fromOptions && fromOptions != 'none') return fromOptions
        String configured = AgentConfigUtil.getNormalizedString('moqui.agent.embedding.provider', 'none')
        return configured ?: 'none'
    }

    protected static String resolveChatProvider(Map options) {
        String fromOptions = options?.provider?.toString()?.trim()?.toLowerCase()
        if (fromOptions && fromOptions != 'none') return fromOptions
        String configured = AgentConfigUtil.getNormalizedString('moqui.agent.chat.provider', 'none')
        return configured ?: 'none'
    }

    protected static String resolveAnyProvider(Map options) {
        String explicitProvider = options?.provider?.toString()?.trim()?.toLowerCase()
        if (explicitProvider) return explicitProvider
        if (options?.purpose == 'embedding') return resolveEmbeddingProvider(options)
        if (options?.purpose == 'chat') return resolveChatProvider(options)
        return resolveRerankerProvider(options)
    }

    protected static boolean hasRerankerApiKey() {
        String explicitKey = AgentConfigUtil.getString('moqui.agent.reranker.apiKey', '').trim()
        String envKey = AgentConfigUtil.getEnv('OPENAI_API_KEY', '').trim()
        return Boolean.TRUE.equals(explicitKey || envKey)
    }

    /** @deprecated use resolveRerankerProvider — kept for callers that used the old name */
    protected static String resolveProvider(Map options) {
        return resolveRerankerProvider(options)
    }

    /** @deprecated use hasRerankerApiKey */
    protected static boolean hasApiKey() {
        return hasRerankerApiKey()
    }
}
