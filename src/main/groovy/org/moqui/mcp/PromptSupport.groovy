package org.moqui.mcp

import groovy.json.JsonOutput

class PromptSupport {
    static Map emptyCompletion() {
        return [completion: [values: [], total: 0, hasMore: false]]
    }

    static String encodeRequestState(Map state) {
        return JsonOutput.toJson(state).bytes.encodeBase64().toString()
    }

    static Map decodeRequestState(String stateText) {
        if (!stateText) return [:]
        String jsonText = new String(stateText.decodeBase64(), 'UTF-8')
        return (Map) new groovy.json.JsonSlurper().parseText(jsonText)
    }

    static Map extractInputResponseMap(Map params) {
        Map responses = params.inputResponses instanceof Map ? (Map) params.inputResponses : [:]
        if (!(responses['screen-input'] instanceof Map)) return [:]
        Map screenInput = (Map) responses['screen-input']
        if (screenInput.response instanceof Map) return (Map) screenInput.response
        if (screenInput.value instanceof Map) return (Map) screenInput.value
        return [:]
    }
}
