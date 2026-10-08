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

class WikiPromptProvider {
    static final String PROMPT_SPACE_ID = 'MCP_PROMPTS'
    protected final ExecutionContext ec

    WikiPromptProvider(ExecutionContext ec) {
        this.ec = ec
    }

    boolean hasPrompt(String name) {
        def wikiPage = ec.entity.find('moqui.resource.wiki.WikiPage')
                .condition('wikiSpaceId', getPromptSpaceId())
                .condition('pagePath', name)
                .useCache(true)
                .one()
        return wikiPage?.publishedVersionName && isPromptVisible(wikiPage)
    }

    Map listPrompts(Map params) {
        List promptList = ec.entity.find('moqui.resource.wiki.WikiPage')
                .condition('wikiSpaceId', getPromptSpaceId())
                .orderBy('pagePath')
                .list()
                .findAll { wp -> (((wp.pagePath ?: '') as String).length() > 0) && wp.publishedVersionName && isPromptVisible(wp) }
                .collect { wp ->
                    String promptName = (wp.pagePath ?: '') as String
                    String promptText = getPublishedPromptText(promptName)
                    [
                            name : promptName,
                            title : promptName,
                            description : promptName,
                            arguments : extractPlaceholderNames(promptText).collect { String argumentName ->
                                [name : argumentName, required : true]
                            }
                    ]
                }
        return [resultType: 'complete', prompts: promptList]
    }

    Map getPrompt(String name, Map params) {
        if (params.containsKey('arguments') && params.arguments != null && !(params.arguments instanceof Map)) {
            throw new IllegalArgumentException('Prompt arguments must be an object when present')
        }
        Map arguments = params.arguments instanceof Map ? (Map) params.arguments : [:]
        if (arguments.any { key, value -> !(key instanceof String) || !(value instanceof String) }) {
            throw new IllegalArgumentException('Prompt argument names and values must be strings')
        }
        String text = getPublishedPromptText(name)
        return makePromptResult(name, text, arguments)
    }

    protected static Map makePromptResult(String name, String text, Map arguments) {
        Set<String> placeholderNames = extractPlaceholderNames(text)
        Set extraNames = arguments.keySet().findAll { !(it in placeholderNames) } as Set
        if (extraNames) throw new IllegalArgumentException("Unsupported prompt argument(s): ${extraNames.sort().join(', ')}")
        Set missingNames = placeholderNames.findAll { !arguments.containsKey(it) } as Set
        if (missingNames) throw new IllegalArgumentException("Missing prompt argument(s): ${missingNames.sort().join(', ')}")
        String renderedText = text.replaceAll(/\$\{([A-Za-z_][A-Za-z0-9_-]*)\}/) { String match, String key -> arguments[key] }
        return [
                resultType : 'complete',
                description: name,
                messages : [[
                                   role : 'user',
                                   content : [type: 'text', text: renderedText]
                           ]]
        ]
    }

    Map complete(String promptName, String argumentName, String argumentValue, Map context) {
        return PromptSupport.emptyCompletion()
    }

    protected String getPublishedPromptText(String name) {
        Map result = ec.service.sync().name('org.moqui.impl.WikiServices.get#PublishedWikiPageText')
                .parameters([wikiSpaceId: getPromptSpaceId(), pagePath: name, versionName: null]).call()
        if (!result?.pageText) throw new IllegalArgumentException("Unknown prompt ${name}")
        return result.pageText as String
    }

    protected static Set<String> extractPlaceholderNames(String text) {
        Set<String> placeholderNames = new LinkedHashSet<>()
        (text =~ /\$\{([A-Za-z_][A-Za-z0-9_-]*)\}/).each { match -> placeholderNames.add(match[1] as String) }
        return placeholderNames
    }

    protected boolean isPromptVisible(def wikiPage) {
        def wikiSpace = ec.entity.find('moqui.resource.wiki.WikiSpace')
                .condition('wikiSpaceId', getPromptSpaceId()).useCache(true).one()
        if (!wikiSpace) return false
        String userId = ec.user?.userId
        if (wikiSpace.restrictView == 'Y') {
            if (!userId || !ec.entity.find('moqui.resource.wiki.WikiSpaceUser')
                    .condition([wikiSpaceId: getPromptSpaceId(), userId: userId, allowView: 'Y']).count()) return false
        }
        if (wikiPage.restrictView == 'Y') {
            if (!userId || !ec.entity.find('moqui.resource.wiki.WikiPageUser')
                    .condition([wikiPageId: wikiPage.wikiPageId, userId: userId, allowView: 'Y']).count()) return false
        }
        return true
    }

    protected String getPromptSpaceId() { PROMPT_SPACE_ID }
}
