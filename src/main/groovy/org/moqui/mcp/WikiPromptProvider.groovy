package org.moqui.mcp

import org.moqui.context.ExecutionContext

class WikiPromptProvider {
    protected final ExecutionContext ec

    WikiPromptProvider(ExecutionContext ec) {
        this.ec = ec
    }

    boolean hasPrompt(String name) {
        return ec.entity.find('moqui.resource.wiki.WikiPage')
                .condition('wikiSpaceId', 'MCP_PROMPTS')
                .condition('pagePath', name)
                .useCache(true)
                .count() > 0
    }

    Map listPrompts(Map params) {
        List promptList = ec.entity.find('moqui.resource.wiki.WikiPage')
                .condition('wikiSpaceId', 'MCP_PROMPTS')
                .orderBy('pagePath')
                .list()
                .findAll { wp -> (((wp.pagePath ?: '') as String).length() > 0) }
                .collect { wp ->
                    String promptName = (wp.pagePath ?: '') as String
                    [
                            name       : promptName,
                            title      : promptName,
                            description: promptName,
                            arguments  : []
                    ]
                }
        return [resultType: 'complete', prompts: promptList]
    }

    Map getPrompt(String name, Map params) {
        Map<String, String> arguments = params.arguments instanceof Map ? (Map<String, String>) params.arguments : [:]
        Map res = ec.service.sync().name('org.moqui.impl.WikiServices.get#PublishedWikiPageText')
                .parameters([wikiSpaceId: 'MCP_PROMPTS', pagePath: name]).call()
        if (!res?.pageText) throw new IllegalArgumentException("Unknown prompt ${name}")
        String text = (res.pageText ?: '') as String
        arguments.each { String key, String value ->
            text = text.replace("\${${key}}", value ?: '')
        }
        return [
                resultType : 'complete',
                description: name,
                messages   : [[
                                      role   : 'user',
                                      content: [type: 'text', text: text]
                              ]]
        ]
    }

    Map complete(String promptName, String argumentName, String argumentValue, Map context) {
        return PromptSupport.emptyCompletion()
    }
}
