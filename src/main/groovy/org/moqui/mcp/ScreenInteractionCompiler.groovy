package org.moqui.mcp

import org.moqui.context.ExecutionContext
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.util.RestSchemaUtil
import org.moqui.util.MNode

class ScreenInteractionCompiler {
    protected final ExecutionContext ec
    protected final ScreenPromptRenderSupport renderSupport

    ScreenInteractionCompiler(ExecutionContext ec) {
        this.ec = ec
        this.renderSupport = new ScreenPromptRenderSupport(ec)
    }

    List<Map> compileServiceBoundPrompts() {
        ScreenFacadeImpl sfi = (ScreenFacadeImpl) ec.screenFacade
        List<Map> promptList = []
        Set<String> seenNames = new LinkedHashSet<>()

        sfi.getAllRootScreenLocations().each { String rootLocation ->
            sfi.getScreenInfoList(rootLocation, 99).each { info ->
                ScreenDefinition sd = info.sd as ScreenDefinition
                if (sd == null) return
                collectFormContexts(sd).each { Map formContext ->
                    Map descriptor = buildPromptDescriptor(info, sd, rootLocation, formContext)
                    if (descriptor != null && seenNames.add(descriptor.name as String)) promptList.add(descriptor)
                }
            }
        }

        return promptList.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') }
    }

    protected Map buildPromptDescriptor(Object info, ScreenDefinition sd, String rootLocation, Map formContext) {
        if (sd == null || formContext == null) return null

        String transitionName = formContext.transitionName as String
        String transitionMethod = formContext.transitionMethod as String ?: 'any'
        ScreenDefinition.TransitionItem ti = transitionName ? sd.getTransitionItem(transitionName, transitionMethod) : null
        if (transitionName && ti == null) ti = sd.getTransitionItem(transitionName, 'any')
        if (ti == null || !ti.hasActionsOrSingleService()) return null
        if (ti.name in ['actions', 'formSelectColumns', 'formSaveFind', 'screenDoc']) return null

        ServiceDefinition serviceDefinition = ti.singleServiceName ? ec.serviceFacade.getServiceDefinition(ti.singleServiceName) : null
        Map<String, Object> inSchema = serviceDefinition ? (RestSchemaUtil.getJsonSchemaMapIn(serviceDefinition) ?: [:]) : [:]
        Map<String, Map> properties = inSchema.properties instanceof Map ? (Map<String, Map>) inSchema.properties : [:]
        Set<String> requiredNames = ((Collection<String>) (inSchema.required ?: [])).toSet()

        List<Map> arguments = []
        properties.keySet().sort().each { String parameterName ->
            Map property = properties[parameterName] instanceof Map ? (Map) properties[parameterName] : [:]
            arguments.add([
                    name       : parameterName,
                    title      : property.title ?: parameterName,
                    description: property.description ?: "Input parameter ${parameterName}",
                    required   : requiredNames.contains(parameterName)
            ])
        }

        ti.parameterMap?.values()?.each { ScreenDefinition.ParameterItem pi ->
            if (arguments.find { it.name == pi.name }) return
            arguments.add([
                    name       : pi.name,
                    title      : pi.name,
                    description: "Transition parameter ${pi.name}",
                    required   : pi.required
            ])
        }
        (ti.pathParameterList ?: []).each { String pathParameterName ->
            if (arguments.find { it.name == pathParameterName }) return
            arguments.add([
                    name       : pathParameterName,
                    title      : pathParameterName,
                    description: "Path parameter ${pathParameterName}",
                    required   : true
            ])
        }

        augmentArgumentsFromForms(sd, ti, arguments, formContext)

        String promptName = buildPromptName(sd, formContext, ti)
        String title = buildPromptTitle(sd, formContext, ti)
        String description = buildPromptDescription(sd, formContext, ti, serviceDefinition)
        return [
                name              : promptName,
                title             : title,
                description       : description,
                arguments         : arguments.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') },
                screenLocation    : sd.location,
                rootScreenLocation: rootLocation,
                relativeScreenPath: buildRelativeScreenPath(info, sd),
                screenName        : sd.screenName,
                transitionName    : ti.name,
                transitionMethod  : ti.method,
                serviceName       : ti.singleServiceName,
                formName          : formContext.formName,
                formType          : formContext.formType,
                interactionKind   : formContext.interactionKind,
                screenInteraction : formContext.screenInteraction == true,
                executionMode     : ti.singleServiceName ? 'service' : 'transition',
                readOnly          : ti.readOnly
        ]
    }

    protected static List<String> buildRelativeScreenPath(Object info, ScreenDefinition sd) {
        List<String> pathSegments = []
        Object cursor = info
        while (cursor != null) {
            String currentName = cursor?.name as String
            if (currentName) pathSegments.add(0, currentName)
            cursor = cursor?.parentInfo
        }

        if (!pathSegments.isEmpty()) pathSegments.remove(0)
        if (!pathSegments.isEmpty() && pathSegments[-1] != sd.screenName) pathSegments.add(sd.screenName)
        return pathSegments
    }

    protected static String buildPromptName(ScreenDefinition sd, Map formContext, ScreenDefinition.TransitionItem ti) {
        String sanitizedLocation = (sd.location ?: 'screen')
                .replace('component://', '')
                .replaceAll(/[^A-Za-z0-9]+/, '.')
                .replaceAll(/\.+/, '.')
                .replaceAll(/^\.|\.$/, '')
        String tail = formContext.formName ?: ti.name
        if (ti?.name && ti.name != formContext.formName) tail = "${tail}.${ti.name}"
        if (ti?.method && ti.method != 'any') tail = "${tail}.${ti.method}"
        tail = tail.replaceAll(/[^A-Za-z0-9]+/, '.').replaceAll(/\.+/, '.').replaceAll(/^\.|\.$/, '')
        return "moqui.screen.${sanitizedLocation}.${tail}"
    }

    protected static String buildPromptTitle(ScreenDefinition sd, Map formContext, ScreenDefinition.TransitionItem ti) {
        String prefix = formContext.interactionKind ? "${formContext.interactionKind} " : ''
        String suffix = ti?.name && ti.name != formContext.formName ? ".${ti.name}" : ''
        return "${prefix}${sd.screenName}.${formContext.formName}${suffix}".trim()
    }

    protected static String buildPromptDescription(ScreenDefinition sd, Map formContext, ScreenDefinition.TransitionItem ti, ServiceDefinition serviceDefinition) {
        StringBuilder sb = new StringBuilder()
        sb.append('Screen-derived interaction contract for ')
                .append(formContext.formType ?: 'form')
                .append(' ')
                .append(formContext.formName)
                .append(' on screen ')
                .append(sd.location)
                .append('.')
        if (formContext.interactionKind) sb.append(' Interaction kind ').append(formContext.interactionKind).append('.')
        sb.append(' Submit transition ').append(ti.name).append('.')
        if (serviceDefinition != null) {
            sb.append(' Executes service ').append(serviceDefinition.serviceName).append('.')
        } else {
            sb.append(' Executes the native Moqui transition logic for this screen.').append(' ')
        }
        if (ti.readOnly) sb.append(' This interaction is read-only.')
        return sb.toString().trim()
    }

    protected List<Map> collectFormContexts(ScreenDefinition sd) {
        List<Map> renderedContexts = collectRenderedFormContexts(sd)
        if (renderedContexts) return renderedContexts

        MNode screenNode = MNode.parse(ec.resourceFacade.getLocationReference(sd.location))
        if (screenNode == null) return []
        List<Map> contexts = []
        Collection<MNode> formNodes = screenNode.depthFirst({ MNode it -> it.name == 'form-single' || it.name == 'form-list' })
        for (MNode formNode in formNodes) {
            String transitionName = formNode.attribute('transition')
            if (!transitionName) continue
            contexts.add([
                    formName         : formNode.attribute('name') ?: transitionName,
                    formNode         : formNode,
                    formType         : formNode.name,
                    transitionName   : transitionName,
                    transitionMethod : formNode.attribute('transition-method') ?: 'any',
                    interactionKind  : inferInteractionKind(formNode.name, transitionName),
                    screenInteraction: false
            ])
        }
        return contexts
    }

    protected List<Map> collectRenderedFormContexts(ScreenDefinition sd) {
        List<Map> interactions = renderSupport.extractInteractions(sd.location)
        if (!interactions) return []
        return interactions.collect { Map interaction ->
            String formName = interaction.formName as String
            MNode formNode = findFormNode(sd.location, formName)
            if (formNode == null) return null
            String transitionName = interaction.transitionName as String ?: formNode.attribute('transition')
            if (!transitionName) return null
            [
                    formName         : formName,
                    formNode         : formNode,
                    formType         : interaction.formType ?: formNode.name,
                    transitionName   : transitionName,
                    transitionMethod : formNode.attribute('transition-method') ?: 'any',
                    interactionKind  : interaction.interactionKind ?: inferInteractionKind(interaction.formType as String, transitionName),
                    screenInteraction: true,
                    renderedFields   : interaction.fields
            ]
        }.findAll { Map ctx -> ctx != null }
    }

    protected static String inferInteractionKind(String formType, String transitionName) {
        String normalized = (transitionName ?: '').toLowerCase()
        if ('form-list' == formType) {
            if (normalized.startsWith('get') || normalized.contains('list') || normalized.contains('find')) return 'query'
            return 'browse'
        }
        if (normalized.startsWith('create')) return 'create'
        if (normalized.startsWith('update') || normalized.startsWith('edit')) return 'update'
        if (normalized.startsWith('delete') || normalized.startsWith('remove')) return 'delete'
        if (normalized.startsWith('get') || normalized.contains('list') || normalized.contains('find')) return 'query'
        return 'action'
    }

    protected MNode findFormNode(String screenLocation, String formName) {
        if (!screenLocation || !formName) return null
        MNode screenNode = MNode.parse(ec.resourceFacade.getLocationReference(screenLocation))
        if (screenNode == null) return null
        return screenNode.depthFirst({ MNode it ->
            (it.name == 'form-single' || it.name == 'form-list') && it.attribute('name') == formName
        })?.find()
    }

    protected void augmentArgumentsFromForms(ScreenDefinition sd, ScreenDefinition.TransitionItem ti, List<Map> arguments, Map formContext = null) {
        Map<String, Map> byName = [:]
        for (Map arg in arguments) byName[arg.name as String] = arg

        MNode screenNode = MNode.parse(ec.resourceFacade.getLocationReference(sd.location))
        if (screenNode == null) return
        Collection<MNode> formNodes = formContext?.formNode ? [formContext.formNode as MNode] :
                screenNode.depthFirst({ MNode it -> it.name == 'form-single' || it.name == 'form-list' })
        for (MNode formNode in formNodes) {
            if (formContext?.formName && formNode.attribute('name') != formContext.formName) continue
            if (ti != null && formNode.attribute('transition') != ti.name) continue
            for (MNode fieldNode in formNode.children('field')) {
                String fieldName = fieldNode.attribute('name')
                if (!fieldName || fieldName == 'submitButton') continue

                Map arg = byName[fieldName]
                if (arg == null) {
                    arg = [name: fieldName, title: fieldName, description: "Screen field ${fieldName}", required: false]
                    arguments.add(arg)
                    byName[fieldName] = arg
                }

                if (!arg.title || arg.title == fieldName) {
                    String title = extractFieldTitle(fieldNode)
                    if (title) arg.title = title
                }
                String description = buildFieldDescription(fieldNode)
                if (description) arg.description = description
                if (fieldLooksRequired(fieldNode)) arg.required = true

                Map lookup = extractLookupHint(fieldNode)
                if (lookup) arg.lookup = lookup

                String defaultValue = extractDefaultValue(fieldNode)
                if (defaultValue != null && defaultValue != '') arg.defaultValue = defaultValue
            }
        }

        arguments.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') }
    }

    protected static String extractFieldTitle(MNode fieldNode) {
        MNode defaultField = fieldNode.first('default-field')
        if (defaultField?.attribute('title')) return defaultField.attribute('title')
        MNode headerField = fieldNode.first('header-field')
        if (headerField?.attribute('title')) return headerField.attribute('title')
        return null
    }

    protected static String buildFieldDescription(MNode fieldNode) {
        String title = extractFieldTitle(fieldNode)
        StringBuilder sb = new StringBuilder()
        if (title) sb.append(title) else sb.append(fieldNode.attribute('name'))

        MNode defaultField = fieldNode.first('default-field')
        String tooltip = defaultField?.attribute('tooltip')
        if (tooltip) sb.append('. ').append(tooltip)

        Map lookup = extractLookupHint(fieldNode)
        if (lookup?.transition) sb.append('. Uses lookup transition ').append(lookup.transition)
        if (lookup?.lookupKind) sb.append('. Lookup kind ').append(lookup.lookupKind)
        if (lookup?.entityName) sb.append('. Lookup entity ').append(lookup.entityName)
        if (lookup?.enumTypeId) sb.append('. Enum type ').append(lookup.enumTypeId)
        if (lookup?.statusTypeId) sb.append('. Status type ').append(lookup.statusTypeId)
        if (lookup?.dependsOn) sb.append('. Depends on ').append(((Collection) lookup.dependsOn).join(', '))

        String defaultValue = extractDefaultValue(fieldNode)
        if (defaultValue != null && defaultValue != '') sb.append('. Default ').append(defaultValue)
        return sb.toString()
    }

    protected static boolean fieldLooksRequired(MNode fieldNode) {
        if ('true' == fieldNode.attribute('required')) return true
        if ('true' == fieldNode.attribute('validate-required')) return true
        return false
    }

    protected static String extractDefaultValue(MNode fieldNode) {
        MNode defaultField = fieldNode.first('default-field')
        if (defaultField == null) return null
        String noCurrent = findFirstAttribute(defaultField, 'no-current-selected-key')
        if (noCurrent != null && noCurrent != '') return noCurrent
        String fixed = fieldNode.attribute('from')
        if (fixed != null && !fixed.contains('${') && !fixed.contains('context')) return fixed
        return null
    }

    protected static Map extractLookupHint(MNode fieldNode) {
        MNode defaultField = fieldNode.first('default-field')
        if (defaultField == null) return null

        MNode widgetTemplateInclude = defaultField.depthFirst({ MNode it -> it.name == 'widget-template-include' })?.find()
        if (widgetTemplateInclude != null) {
            String location = widgetTemplateInclude.attribute('location') ?: ''
            String enumTypeId = extractWidgetSetValue(widgetTemplateInclude, 'enumTypeId')
            if (location.contains('BasicWidgetTemplates.xml#enumDropDown')) {
                return [lookupKind: 'enum', entityName: 'moqui.basic.Enumeration', enumTypeId: enumTypeId]
            }
            if (location.contains('BasicWidgetTemplates.xml#enumParentDropDown')) {
                return [lookupKind: 'enum-parent', entityName: 'moqui.basic.EnumAndParent', enumTypeId: enumTypeId]
            }
            if (location.contains('BasicWidgetTemplates.xml#enumGroupDropDown')) {
                return [lookupKind: 'enum-group', entityName: 'moqui.basic.EnumAndGroup', enumTypeId: enumTypeId]
            }
            if (location.contains('BasicWidgetTemplates.xml#statusDropDown')) {
                return [lookupKind: 'status', entityName: 'moqui.basic.StatusItem', statusTypeId: extractWidgetSetValue(widgetTemplateInclude, 'statusTypeId')]
            }
            if (location.contains('BasicWidgetTemplates.xml#statusTransitionDropDown') || location.contains('BasicWidgetTemplates.xml#statusTransitionWithFlowDropDown')) {
                return [lookupKind: 'status-transition', entityName: 'moqui.basic.StatusFlowTransitionToDetail']
            }
        }

        MNode dynamic = defaultField.depthFirst({ MNode it -> it.name == 'dynamic-options' })?.find()
        if (dynamic != null) {
            List<String> dependsOn = []
            dynamic.children('depends-on').each { MNode dep ->
                String depField = dep.attribute('field')
                if (depField) dependsOn.add(depField)
            }

            Map lookup = [lookupKind: 'dynamic-options', transition: dynamic.attribute('transition')]
            if (dependsOn) lookup.dependsOn = dependsOn
            if (dynamic.attribute('server-search')) lookup.serverSearch = dynamic.attribute('server-search')
            if (dynamic.attribute('min-length')) lookup.minLength = dynamic.attribute('min-length')
            if (dynamic.attribute('value-field')) lookup.valueField = dynamic.attribute('value-field')
            if (dynamic.attribute('label-field')) lookup.labelField = dynamic.attribute('label-field')
            if (dynamic.attribute('parameter-map')) lookup.parameterMap = parseParameterMapLiteral(dynamic.attribute('parameter-map'))
            return lookup
        }

        MNode entityOptions = defaultField.depthFirst({ MNode it -> it.name == 'entity-options' })?.find()
        if (entityOptions != null) {
            MNode entityFind = entityOptions.first('entity-find')
            Map lookup = [
                    lookupKind  : 'entity-options',
                    entityName  : entityFind?.attribute('entity-name'),
                    keyField    : extractEntityOptionsKeyField(entityOptions.attribute('key')),
                    textTemplate: entityOptions.attribute('text')
            ]
            List<Map> conditions = []
            entityFind?.children('econdition')?.each { MNode cond ->
                conditions.add([
                        fieldName: cond.attribute('field-name'),
                        from     : cond.attribute('from'),
                        value    : cond.attribute('value'),
                        operator : cond.attribute('operator')
                ].findAll { it.value != null && it.value != '' })
            }
            if (conditions) lookup.conditions = conditions
            return lookup
        }

        return null
    }

    protected static String extractWidgetSetValue(MNode widgetTemplateInclude, String fieldName) {
        MNode setNode = widgetTemplateInclude.children('set')?.find { MNode set -> set.attribute('field') == fieldName }
        String value = setNode?.attribute('value')
        return value ?: setNode?.attribute('from')
    }

    protected static String extractEntityOptionsKeyField(String keyExpr) {
        if (!keyExpr) return null
        def matcher = (keyExpr =~ /\$\{([^}]+)\}/)
        if (matcher.find()) return matcher.group(1)
        return keyExpr
    }

    protected static Map<String, Object> parseParameterMapLiteral(String literal) {
        if (!literal?.trim()) return [:]
        String text = literal.trim()
        if (!text.startsWith('[') || !text.endsWith(']')) return [:]
        text = text.substring(1, text.length() - 1).trim()
        if (!text) return [:]

        Map<String, Object> parameterMap = [:]
        splitTopLevel(text).each { String entry ->
            List<String> kv = entry.split(':', 2) as List<String>
            if (kv.size() != 2) return
            String key = kv[0]?.trim()
            if (!key) return
            parameterMap[key] = parseParameterLiteralValue(kv[1]?.trim())
        }
        return parameterMap
    }

    protected static List<String> splitTopLevel(String text) {
        List<String> parts = []
        StringBuilder current = new StringBuilder()
        boolean inSingle = false
        boolean inDouble = false
        int bracketDepth = 0
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i)
            if (ch == '\'' as char && !inDouble) inSingle = !inSingle
            else if (ch == '"' as char && !inSingle) inDouble = !inDouble
            else if (!inSingle && !inDouble) {
                if (ch == '[' as char || ch == '(' as char || ch == '{' as char) bracketDepth++
                if (ch == ']' as char || ch == ')' as char || ch == '}' as char) bracketDepth--
                if (ch == ',' as char && bracketDepth == 0) {
                    parts.add(current.toString().trim())
                    current.setLength(0)
                    continue
                }
            }
            current.append(ch)
        }
        if (current.length() > 0) parts.add(current.toString().trim())
        return parts.findAll { it }
    }

    protected static Object parseParameterLiteralValue(String valueText) {
        if (valueText == null) return null
        String text = valueText.trim()
        if (!text) return ''
        if (text == 'null') return null
        if (text == 'true') return true
        if (text == 'false') return false
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith('"') && text.endsWith('"'))) {
            return text.substring(1, text.length() - 1)
        }
        if (text ==~ /-?\d+/) return text as Long
        if (text ==~ /-?\d+\.\d+/) return text as BigDecimal
        return text
    }

    protected static String findFirstAttribute(MNode parent, String attributeName) {
        if (parent == null) return null
        if (parent.attribute(attributeName)) return parent.attribute(attributeName)
        for (MNode child in parent.children) {
            String value = findFirstAttribute(child, attributeName)
            if (value != null) return value
        }
        return null
    }
}
