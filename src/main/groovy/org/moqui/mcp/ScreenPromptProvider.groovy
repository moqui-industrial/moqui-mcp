package org.moqui.mcp

import org.moqui.context.ExecutionContext

class ScreenPromptProvider {
    protected final ExecutionContext ec
    protected final ScreenInteractionCompiler compiler
    protected final PromptLookupResolver lookupResolver

    ScreenPromptProvider(ExecutionContext ec) {
        this.ec = ec
        this.compiler = new ScreenInteractionCompiler(ec)
        this.lookupResolver = new PromptLookupResolver(ec)
    }

    boolean hasPrompt(String name) {
        return getPromptDescriptorMap().containsKey(name)
    }

    Map listPrompts(Map params) {
        return [
                prompts   : compilePromptList().collect { Map descriptor ->
                    [
                            name       : descriptor.name,
                            title      : descriptor.title,
                            description: descriptor.description,
                            arguments  : descriptor.arguments.collect { Map arg ->
                                Map lookup = arg.lookup instanceof Map ? (Map) arg.lookup : null
                                String argDescription = arg.description
                                if (lookup) {
                                    String lookupHint = "Supports MCP completion/complete and lookup://screen/${descriptor.name}/${arg.name}?q={query}"
                                    argDescription = argDescription ? "${argDescription}. ${lookupHint}" : lookupHint
                                }
                                [
                                        name       : arg.name,
                                        title      : arg.title,
                                        description: argDescription,
                                        required   : arg.required,
                                        _meta      : lookup ? [
                                                'org.moqui/lookupKind'       : lookup.lookupKind,
                                                'org.moqui/lookupEntity'     : lookup.entityName,
                                                'org.moqui/lookupEnumTypeId' : lookup.enumTypeId,
                                                'org.moqui/lookupStatusTypeId': lookup.statusTypeId,
                                                'org.moqui/lookupUriTemplate': "lookup://screen/${descriptor.name}/${arg.name}?q={query}"
                                        ].findAll { it.value != null } : null
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

        Map resolution = lookupResolver.resolveArguments(descriptor, arguments)
        Map<String, String> resolvedArguments = (Map<String, String>) resolution.arguments
        Map<String, Map> resolutionMeta = (Map<String, Map>) resolution.resolutionMeta
        List<Map> lookupBindings = buildLookupBindings(descriptor)

        List<Map> missingArguments = descriptor.arguments.findAll { Map arg -> Boolean.TRUE == arg.required && !resolvedArguments[arg.name as String] }
        List<Map> unresolvedLookupArguments = findUnresolvedLookupArguments(descriptor, arguments, resolutionMeta)
        if (missingArguments || unresolvedLookupArguments) {
            Map elicitationSpec = buildElicitationRequest(descriptor, resolvedArguments, missingArguments, unresolvedLookupArguments)
            return [
                    requestState : PromptSupport.encodeRequestState([promptName: name, arguments: resolvedArguments]),
                    inputRequests: [
                            'screen-input': [
                                    method: 'elicitation/create',
                                    params: elicitationSpec
                            ]
                    ],
                    _meta        : [
                            'org.moqui/resultType': 'input_required'
                    ]
            ]
        }

        Map promptBinding
        String bindingText
        if (descriptor.executionMode == 'transition') {
            promptBinding = [
                    toolName          : 'moqui_execute_screen_transition',
                    screenLocation    : descriptor.screenLocation,
                    rootScreenLocation: descriptor.rootScreenLocation,
                    relativeScreenPath: descriptor.relativeScreenPath,
                    transitionName    : descriptor.transitionName,
                    transitionMethod  : descriptor.transitionMethod,
                    parameters        : resolvedArguments
            ]
            bindingText = "Call tool moqui_execute_screen_transition with screenLocation=${descriptor.screenLocation}, transitionName=${descriptor.transitionName}, parameters=${resolvedArguments}."
        } else {
            promptBinding = [
                    toolName      : 'moqui_call_service',
                    serviceName   : descriptor.serviceName,
                    parameters    : resolvedArguments,
                    screenLocation: descriptor.screenLocation,
                    transitionName: descriptor.transitionName
            ]
            bindingText = "Call tool moqui_call_service with serviceName=${descriptor.serviceName} and parameters=${resolvedArguments}."
        }
        String lookupText = buildLookupInstructionText(lookupBindings)
        String submitText = buildSubmitInstructionText(promptBinding)
        String messageText = """\
Use the screen-derived interaction ${descriptor.title}.
Screen: ${descriptor.screenLocation}
Transition: ${descriptor.transitionName}
Execution mode: ${descriptor.executionMode}
${descriptor.serviceName ? "Bound service: ${descriptor.serviceName}" : ""}
Resolved arguments: ${resolvedArguments}
Resolved lookups: ${resolutionMeta}

${lookupText}
${submitText}
${bindingText}
""".stripIndent().trim()

        List<Map> promptMessages = [[
                role   : 'user',
                content: [type: 'text', text: messageText]
        ]]
        promptMessages.addAll(buildLookupResourceMessages(lookupBindings))

        return [
                description: descriptor.description,
                messages   : promptMessages,
                _meta      : [
                        'org.moqui/resultType'      : 'complete',
                        'org.moqui/promptBinding'   : promptBinding,
                        'org.moqui/resolutionMeta'  : resolutionMeta,
                        'org.moqui/lookupBindings'  : lookupBindings,
                        'org.moqui/resourceBindings': buildResourceBindings(lookupBindings),
                        'org.moqui/lookupResources' : buildLookupResourceHints(lookupBindings)
                ]
        ]
    }

    protected List<Map> findUnresolvedLookupArguments(Map descriptor, Map<String, String> arguments, Map<String, Map> resolutionMeta) {
        List<Map> unresolved = []
        (descriptor.arguments ?: []).each { Map arg ->
            Map lookup = arg.lookup instanceof Map ? (Map) arg.lookup : null
            if (!lookup) return
            String rawValue = arguments[arg.name as String]
            if (!rawValue?.trim()) return
            if (resolutionMeta[arg.name as String]?.matched) return
            unresolved.add(arg)
        }
        return unresolved
    }

    protected Map buildElicitationRequest(Map descriptor, Map<String, String> resolvedArguments, List<Map> missingArguments, List<Map> unresolvedLookupArguments) {
        Map schemaProps = [:]
        Set<String> requiredNames = new LinkedHashSet<>()
        List<String> messages = []

        if (missingArguments) {
            messages.add("Provide the remaining required inputs for ${descriptor.title}.")
            missingArguments.each { Map arg ->
                schemaProps[arg.name as String] = buildRequestedProperty(descriptor, arg, resolvedArguments, false)
                requiredNames.add(arg.name as String)
            }
        }

        if (unresolvedLookupArguments) {
            messages.add("Resolve the lookup-backed fields before submit.")
            unresolvedLookupArguments.each { Map arg ->
                schemaProps[arg.name as String] = buildRequestedProperty(descriptor, arg, resolvedArguments, true)
                requiredNames.add(arg.name as String)
            }
        }

        return [
                mode           : 'form',
                message        : messages.join(' '),
                requestedSchema: [
                        type      : 'object',
                        properties: schemaProps,
                        required  : requiredNames as List
                ]
        ]
    }

    protected Map buildRequestedProperty(Map descriptor, Map arg, Map<String, String> resolvedArguments, boolean unresolvedLookup) {
        Map property = [
                type       : 'string',
                title      : arg.title ?: arg.name,
                description: buildElicitationDescription(arg, unresolvedLookup)
        ]
        Map completion = lookupResolver.completeArgument(arg.name as String, resolvedArguments[arg.name as String] ?: '', resolvedArguments, descriptor)
        List<String> values = completion?.completion?.values instanceof Collection ? ((Collection<String>) completion.completion.values).findAll { it } as List<String> : []
        if (values && values.size() <= 20) property.enum = values.unique()
        return property
    }

    protected String buildElicitationDescription(Map arg, boolean unresolvedLookup) {
        String base = arg.description ?: (arg.title ?: arg.name)
        if (!unresolvedLookup) return base
        Map lookup = arg.lookup instanceof Map ? (Map) arg.lookup : [:]
        List<String> details = ["Current value could not be resolved uniquely."]
        if (lookup.lookupKind) details.add("Lookup kind ${lookup.lookupKind}.")
        if (lookup.entityName) details.add("Resolve against ${lookup.entityName}.")
        if (lookup.enumTypeId) details.add("Enum type ${lookup.enumTypeId}.")
        if (lookup.statusTypeId) details.add("Status type ${lookup.statusTypeId}.")
        if (lookup.conditions instanceof Collection && !((Collection) lookup.conditions).isEmpty()) {
            String condText = ((Collection<Map>) lookup.conditions).collect { Map cond ->
                String fieldName = cond.fieldName ?: '?'
                String operator = cond.operator ?: 'equals'
                cond.value != null ? "${fieldName} ${operator} ${cond.value}" : fieldName
            }.join(', ')
            details.add("Filters ${condText}.")
        }
        return "${base} ${details.join(' ')}".trim()
    }

    Map complete(String promptName, String argumentName, String argumentValue, Map context) {
        Map descriptor = getPromptDescriptorMap()[promptName]
        if (descriptor == null) return PromptSupport.emptyCompletion()
        return lookupResolver.completeArgument(argumentName, argumentValue, context, descriptor)
    }

    protected List<Map> compilePromptList() {
        return compiler.compileServiceBoundPrompts()
    }

    protected Map<String, Map> getPromptDescriptorMap() {
        Map<String, Map> promptMap = [:]
        compilePromptList().each { Map descriptor -> promptMap[descriptor.name as String] = descriptor }
        return promptMap
    }

    Map getPromptDescriptor(String name) {
        return getPromptDescriptorMap()[name]
    }

    protected List<Map> buildLookupBindings(Map descriptor) {
        List<Map> bindings = []
        (descriptor.arguments ?: []).each { Map arg ->
            Map lookup = arg.lookup instanceof Map ? (Map) arg.lookup : null
            if (!lookup) return
            Map binding = [
                    argumentName: arg.name,
                    title       : arg.title ?: arg.name,
                    lookupKind  : lookup.lookupKind,
                    entityName  : lookup.entityName
            ]
            if (lookup.transition) {
                binding.transitionName = lookup.transition
                binding.suggestedPromptName = buildSiblingPromptName(descriptor.screenLocation as String, lookup.transition as String)
            }
            if (lookup.dependsOn) binding.dependsOn = lookup.dependsOn
            if (lookup.serverSearch != null) binding.serverSearch = lookup.serverSearch
            if (lookup.minLength != null) binding.minLength = lookup.minLength
            if (lookup.enumTypeId) binding.enumTypeId = lookup.enumTypeId
            if (lookup.statusTypeId) binding.statusTypeId = lookup.statusTypeId
            if (lookup.keyField) binding.keyField = lookup.keyField
            if (lookup.textTemplate) binding.textTemplate = lookup.textTemplate
            if (lookup.conditions) binding.conditions = lookup.conditions
            binding.resourceUriTemplate = buildLookupResourceUriTemplate(descriptor, arg, lookup)
            bindings.add(binding)
        }
        return bindings
    }

    protected List<Map> buildResourceBindings(List<Map> lookupBindings) {
        return lookupBindings.collect { Map binding ->
            [
                    argumentName   : binding.argumentName,
                    title          : binding.title,
                    uriTemplate    : binding.resourceUriTemplate,
                    resourceScheme : 'lookup',
                    lookupKind     : binding.lookupKind,
                    entityName     : binding.entityName,
                    enumTypeId     : binding.enumTypeId,
                    statusTypeId   : binding.statusTypeId,
                    dependsOn      : binding.dependsOn
            ].findAll { it.value != null }
        }
    }

    protected List<Map> buildLookupResourceMessages(List<Map> lookupBindings) {
        List<Map> messages = []
        lookupBindings.each { Map binding ->
            String uri = binding.resourceUriTemplate?.replace('{query}', '')
            if (!uri) return
            Map link = [
                    type       : 'resource_link',
                    uri        : uri,
                    name       : "lookup.${binding.argumentName}",
                    title      : binding.title ?: binding.argumentName,
                    description: buildLookupResourceDescription(binding),
                    mimeType   : 'application/json'
            ].findAll { it.value != null }
            messages.add([role: 'assistant', content: link])
        }
        return messages
    }

    protected List<Map> buildLookupResourceHints(List<Map> lookupBindings) {
        return lookupBindings.collect { Map binding ->
            [
                    argumentName: binding.argumentName,
                    title       : binding.title ?: binding.argumentName,
                    uri         : binding.resourceUriTemplate?.replace('{query}', ''),
                    uriTemplate : binding.resourceUriTemplate,
                    lookupKind  : binding.lookupKind,
                    entityName  : binding.entityName,
                    enumTypeId  : binding.enumTypeId,
                    statusTypeId: binding.statusTypeId,
                    dependsOn   : binding.dependsOn
            ].findAll { it.value != null }
        }
    }

    protected static String buildLookupResourceDescription(Map binding) {
        List<String> parts = ["Lookup resource for ${binding.title ?: binding.argumentName}"]
        if (binding.lookupKind) parts.add("kind=${binding.lookupKind}")
        if (binding.entityName) parts.add("entity=${binding.entityName}")
        if (binding.enumTypeId) parts.add("enumTypeId=${binding.enumTypeId}")
        if (binding.statusTypeId) parts.add("statusTypeId=${binding.statusTypeId}")
        if (binding.dependsOn instanceof Collection && !((Collection) binding.dependsOn).isEmpty()) {
            parts.add("dependsOn=${((Collection) binding.dependsOn).join(',')}")
        }
        return parts.join('; ')
    }

    protected static String buildSiblingPromptName(String screenLocation, String transitionName) {
        String sanitizedLocation = (screenLocation ?: 'screen')
                .replace('component://', '')
                .replaceAll(/[^A-Za-z0-9]+/, '.')
                .replaceAll(/\.+/, '.')
                .replaceAll(/^\.|\.$/, '')
        String sanitizedTransition = (transitionName ?: 'transition')
                .replaceAll(/[^A-Za-z0-9]+/, '.')
                .replaceAll(/\.+/, '.')
                .replaceAll(/^\.|\.$/, '')
        return "moqui.screen.${sanitizedLocation}.${sanitizedTransition}"
    }

    protected static String buildLookupInstructionText(List<Map> lookupBindings) {
        if (!lookupBindings) {
            return 'Lookup guidance: if a field value is uncertain, resolve it before submit using completion/complete or the bound lookup:// resource. If the user already provides a valid code or identifier, pass it through unchanged.'
        }
        List<String> lines = ['Lookup guidance: before submit, resolve lookup-backed fields using completion/complete or the bound lookup:// resource. If the user already provides a valid code or identifier accepted by the lookup source, reuse it directly without another lookup.']
        lookupBindings.each { Map binding ->
            StringBuilder line = new StringBuilder()
            line.append("- ").append(binding.argumentName)
            if (binding.lookupKind) line.append(" [").append(binding.lookupKind).append("]")
            if (binding.transitionName) {
                line.append(" uses transition ").append(binding.transitionName)
                        .append(" and prompt ").append(binding.suggestedPromptName)
            } else if (binding.entityName) {
                line.append(" resolves against entity ").append(binding.entityName)
            }
            if (binding.enumTypeId) line.append("; enumTypeId=").append(binding.enumTypeId)
            if (binding.statusTypeId) line.append("; statusTypeId=").append(binding.statusTypeId)
            if (binding.keyField) line.append("; keyField=").append(binding.keyField)
            if (binding.textTemplate) line.append("; textTemplate=").append(binding.textTemplate)
            if (binding.dependsOn instanceof Collection && !((Collection) binding.dependsOn).isEmpty()) {
                line.append("; depends on ").append(((Collection) binding.dependsOn).join(', '))
            }
            if (binding.conditions instanceof Collection && !((Collection) binding.conditions).isEmpty()) {
                List<String> renderedConditions = ((Collection<Map>) binding.conditions).collect { Map cond ->
                    String fieldName = cond.fieldName ?: '?'
                    String operator = cond.operator ?: 'equals'
                    if (cond.value != null) return "${fieldName} ${operator} ${cond.value}"
                    if (cond.from != null) return "${fieldName} ${operator} from ${cond.from}"
                    return fieldName
                }
                line.append("; filters=").append(renderedConditions.join(', '))
            }
            if (binding.serverSearch != null) line.append("; serverSearch=").append(binding.serverSearch)
            if (binding.minLength != null) line.append("; minLength=").append(binding.minLength)
            if (binding.resourceUriTemplate) line.append("; resource=").append(binding.resourceUriTemplate)
            lines.add(line.toString())
        }
        return lines.join('\n')
    }

    protected static String buildSubmitInstructionText(Map promptBinding) {
        if (!promptBinding?.toolName) return 'Submit guidance: once the user confirms, invoke the bound tool using tools/call.'
        if (promptBinding.toolName == 'moqui_call_service') {
            return "Submit guidance: when the user confirms submit, invoke MCP method tools/call with tool ${promptBinding.toolName}, serviceName=${promptBinding.serviceName}, and the resolved parameters."
        }
        return "Submit guidance: when the user confirms submit, invoke MCP method tools/call with tool ${promptBinding.toolName} and the resolved parameters."
    }

    protected static String buildLookupResourceUriTemplate(Map descriptor, Map arg, Map lookup) {
        List<String> queryParts = ['q={query}']
        if (lookup.dependsOn instanceof Collection) {
            ((Collection<String>) lookup.dependsOn).findAll { it }.each { String dep ->
                queryParts.add("${dep}={${dep}}")
            }
        }
        String uriTemplate = "lookup://screen/${descriptor.name}/${arg.name}"
        if (queryParts) uriTemplate += '?' + queryParts.join('&')
        return uriTemplate
    }
}
