package org.moqui.mcp

import org.moqui.context.ExecutionContext

class ScreenPromptProvider {
    protected final ExecutionContext ec
    protected final ScreenInteractionCompiler compiler

    ScreenPromptProvider(ExecutionContext ec) {
        this.ec = ec
        this.compiler = new ScreenInteractionCompiler(ec)
    }

    boolean hasPrompt(String name) {
        return getPromptDescriptorMap().containsKey(name)
    }

    Map listPrompts(Map params) {
        return [
                resultType: 'complete',
                prompts   : compilePromptList().collect { Map descriptor ->
                    [
                            name       : descriptor.name,
                            title      : descriptor.title,
                            description: descriptor.description,
                            arguments  : descriptor.arguments.collect { Map arg ->
                                [
                                        name       : arg.name,
                                        title      : arg.title,
                                        description: arg.description,
                                        required   : arg.required
                                ]
                            }
                    ]
                }
        ]
    }

    Map getPrompt(String name, Map params) {
        Map descriptor = getPromptDescriptorMap()[name]
        if (descriptor == null) throw new IllegalArgumentException("Unknown prompt ${name}")

        Map<String, String> arguments = [:]
        if (params.arguments instanceof Map) {
            ((Map) params.arguments).each { k, v -> if (v != null) arguments[k as String] = v.toString() }
        }
        Map requestState = params.requestState ? PromptSupport.decodeRequestState(params.requestState as String) : [:]
        if (requestState.arguments instanceof Map) {
            ((Map) requestState.arguments).each { k, v -> if (v != null && !arguments.containsKey(k)) arguments[k as String] = v.toString() }
        }
        Map responseValues = PromptSupport.extractInputResponseMap(params)
        responseValues.each { k, v -> if (v != null) arguments[k as String] = v.toString() }

        List<Map> missingArguments = descriptor.arguments.findAll { Map arg ->
            Boolean.TRUE == arg.required && !arguments[arg.name as String]
        }
        if (missingArguments) {
            Map schemaProps = [:]
            missingArguments.each { Map arg ->
                schemaProps[arg.name as String] = [
                        type       : 'string',
                        title      : arg.title ?: arg.name,
                        description: arg.description ?: arg.name
                ]
            }
            return [
                    resultType   : 'input_required',
                    requestState : PromptSupport.encodeRequestState([promptName: name, arguments: arguments]),
                    inputRequests: [
                            'screen-input': [
                                    method: 'elicitation/create',
                                    params: [
                                            mode           : 'form',
                                            message        : "Provide the remaining inputs for ${descriptor.title}.",
                                            requestedSchema: [
                                                    type      : 'object',
                                                    properties: schemaProps,
                                                    required  : missingArguments.collect { it.name }
                                            ]
                                    ]
                            ]
                    ]
            ]
        }

        String bindingText = "Call tool moqui_call_service with serviceName=${descriptor.serviceName} and parameters=${arguments}."
        String messageText = """\
Use the screen-derived interaction ${descriptor.title}.
Screen: ${descriptor.screenLocation}
Transition: ${descriptor.transitionName}
Bound service: ${descriptor.serviceName}
Resolved arguments: ${arguments}

${bindingText}
""".stripIndent().trim()

        return [
                resultType : 'complete',
                description: descriptor.description,
                messages   : [[
                                      role   : 'user',
                                      content: [type: 'text', text: messageText]
                              ]],
                _meta      : [
                        'org.moqui/promptBinding': [
                                toolName      : 'moqui_call_service',
                                serviceName   : descriptor.serviceName,
                                parameters    : arguments,
                                screenLocation: descriptor.screenLocation,
                                transitionName: descriptor.transitionName
                        ]
                ]
        ]
    }

    Map complete(String promptName, String argumentName, String argumentValue, Map context) {
        return PromptSupport.emptyCompletion()
    }

    protected List<Map> compilePromptList() {
        return compiler.compileServiceBoundPrompts()
    }

    protected Map<String, Map> getPromptDescriptorMap() {
        Map<String, Map> promptMap = [:]
        compilePromptList().each { Map descriptor -> promptMap[descriptor.name as String] = descriptor }
        return promptMap
    }
}
