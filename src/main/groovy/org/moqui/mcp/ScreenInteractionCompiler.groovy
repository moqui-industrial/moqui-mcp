package org.moqui.mcp

import org.moqui.context.ExecutionContext
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.util.RestSchemaUtil

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
                    Map descriptor = buildPromptDescriptor(sd, ti)
                    if (descriptor == null) return
                    if (seenNames.add(descriptor.name as String)) promptList.add(descriptor)
                }
            }
        }

        return promptList.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') }
    }

    protected Map buildPromptDescriptor(ScreenDefinition sd, ScreenDefinition.TransitionItem ti) {
        if (sd == null || ti == null) return null
        if (!ti.hasActionsOrSingleService()) return null
        if (!ti.singleServiceName) return null
        if (ti.name in ['actions', 'formSelectColumns', 'formSaveFind', 'screenDoc']) return null

        ServiceDefinition serviceDefinition = ec.serviceFacade.getServiceDefinition(ti.singleServiceName)
        if (serviceDefinition == null) return null

        Map<String, Object> inSchema = RestSchemaUtil.getJsonSchemaMapIn(serviceDefinition) ?: [:]
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

        String promptName = buildPromptName(sd, ti)
        String title = "${sd.screenName}.${ti.name}"
        String description = buildPromptDescription(sd, ti, serviceDefinition)
        return [
                name            : promptName,
                title           : title,
                description     : description,
                arguments       : arguments.sort { a, b -> (a.name ?: '') <=> (b.name ?: '') },
                screenLocation  : sd.location,
                screenName      : sd.screenName,
                transitionName  : ti.name,
                transitionMethod: ti.method,
                serviceName     : ti.singleServiceName,
                readOnly        : ti.readOnly
        ]
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
        sb.append(" on screen ").append(sd.location)
        sb.append(". Executes service ").append(serviceDefinition.serviceName).append(".")
        if (ti.readOnly) sb.append(" This interaction is read-only.")
        return sb.toString()
    }
}
