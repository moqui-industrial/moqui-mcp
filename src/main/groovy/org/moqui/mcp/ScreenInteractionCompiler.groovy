package org.moqui.mcp

import org.moqui.context.ExecutionContext
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.util.RestSchemaUtil
import org.moqui.util.MNode

class ScreenInteractionCompiler {
    protected final ExecutionContext ec

    ScreenInteractionCompiler(ExecutionContext ec) {
        this.ec = ec
    }

    List<Map> compileServiceBoundPrompts() {
        ScreenFacadeImpl sfi = (ScreenFacadeImpl) ec.screenFacade
        List<Map> promptList = []
        Set<String> seenNames = new LinkedHashSet<>()

        sfi.getAllRootScreenLocations().each { String rootLocation ->
            sfi.getScreenInfoList(rootLocation, 99).each { info ->
                ScreenDefinition sd = info.sd as ScreenDefinition
                if (sd == null) return
                sd.getAllTransitions().each { ScreenDefinition.TransitionItem ti ->
                    Map descriptor = buildPromptDescriptor(info, sd, ti, rootLocation)
                    if (descriptor == null) return
                    if (seenNames.add(descriptor.name as String)) promptList.add(descriptor)
                }
            }
        }

        return promptList.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') }
    }

    protected Map buildPromptDescriptor(Object info, ScreenDefinition sd, ScreenDefinition.TransitionItem ti, String rootLocation) {
        if (sd == null || ti == null) return null
        if (!ti.hasActionsOrSingleService()) return null
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

        augmentArgumentsFromForms(sd, ti, arguments)

        String promptName = buildPromptName(sd, ti)
        String title = "${sd.screenName}.${ti.name}"
        String description = buildPromptDescription(sd, ti, serviceDefinition)
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

    protected static String buildPromptName(ScreenDefinition sd, ScreenDefinition.TransitionItem ti) {
        String sanitizedLocation = (sd.location ?: 'screen')
                .replace('component://', '')
                .replaceAll(/[^A-Za-z0-9]+/, '.')
                .replaceAll(/\.+/, '.')
                .replaceAll(/^\.|\.$/, '')
        String sanitizedTransition = (ti.method && ti.method != 'any') ? "${ti.name}.${ti.method}" : ti.name
        sanitizedTransition = sanitizedTransition.replaceAll(/[^A-Za-z0-9]+/, '.').replaceAll(/\.+/, '.').replaceAll(/^\.|\.$/, '')
        return "moqui.screen.${sanitizedLocation}.${sanitizedTransition}"
    }

    protected static String buildPromptDescription(ScreenDefinition sd, ScreenDefinition.TransitionItem ti, ServiceDefinition serviceDefinition) {
        StringBuilder sb = new StringBuilder()
        sb.append("Screen-derived prompt for transition ").append(ti.name)
        sb.append(" on screen ").append(sd.location).append(".")
        if (serviceDefinition != null) {
            sb.append(" Executes service ").append(serviceDefinition.serviceName).append(".")
        } else {
            sb.append(" Executes the native Moqui transition logic for this screen.")
        }
        if (ti.readOnly) sb.append(" This interaction is read-only.")
        return sb.toString()
    }

    protected void augmentArgumentsFromForms(ScreenDefinition sd, ScreenDefinition.TransitionItem ti, List<Map> arguments) {
        Map<String, Map> byName = [:]
        for (Map arg in arguments) byName[arg.name as String] = arg

        MNode screenNode = MNode.parse(ec.resourceFacade.getLocationReference(sd.location))
        if (screenNode == null) return
        Collection<MNode> formNodes = screenNode.depthFirst({ MNode it -> it.name == 'form-single' || it.name == 'form-list' })
        for (MNode formNode in formNodes) {
            if (formNode.attribute('transition') != ti.name) continue
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
        if (tooltip) sb.append(". ").append(tooltip)

        Map lookup = extractLookupHint(fieldNode)
        if (lookup?.transition) sb.append(". Uses lookup transition ").append(lookup.transition)
        if (lookup?.dependsOn) sb.append(". Depends on ").append(((Collection) lookup.dependsOn).join(', '))

        String defaultValue = extractDefaultValue(fieldNode)
        if (defaultValue != null && defaultValue != '') sb.append(". Default ").append(defaultValue)
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
        MNode dynamic = defaultField.depthFirst({ MNode it -> it.name == 'dynamic-options' })?.find()
        if (dynamic == null) return null

        List<String> dependsOn = []
        dynamic.children('depends-on').each { MNode dep ->
            String depField = dep.attribute('field')
            if (depField) dependsOn.add(depField)
        }

        Map lookup = [transition: dynamic.attribute('transition')]
        if (dependsOn) lookup.dependsOn = dependsOn
        if (dynamic.attribute('server-search')) lookup.serverSearch = dynamic.attribute('server-search')
        if (dynamic.attribute('min-length')) lookup.minLength = dynamic.attribute('min-length')
        return lookup
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
