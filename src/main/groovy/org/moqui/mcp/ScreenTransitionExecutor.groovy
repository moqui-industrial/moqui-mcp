package org.moqui.mcp

import org.moqui.context.ExecutionContext
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.screen.ScreenTest

class ScreenTransitionExecutor {
    protected final ExecutionContext ec

    ScreenTransitionExecutor(ExecutionContext ec) {
        this.ec = ec
    }

    Map execute(String screenLocation, String transitionName, Map parameters) {
        if (!screenLocation) throw new IllegalArgumentException('screenLocation is required')
        if (!transitionName) throw new IllegalArgumentException('transitionName is required')

        ScreenFacadeImpl sfi = (ScreenFacadeImpl) ec.screenFacade
        ScreenDefinition targetScreen = sfi.getScreenDefinition(screenLocation)
        if (targetScreen == null) throw new IllegalArgumentException("Unknown screen location ${screenLocation}")

        Map screenInfoRef = findScreenInfo(sfi, screenLocation, parameters)
        if (!screenInfoRef) throw new IllegalArgumentException("Unable to locate screen path for ${screenLocation}")

        ScreenDefinition.TransitionItem ti = targetScreen.getTransitionItem(transitionName, (parameters?.transitionMethod ?: 'any') as String)
        if (ti == null) throw new IllegalArgumentException("Unknown transition ${transitionName} on ${screenLocation}")

        List<String> screenPath = new ArrayList<>((List<String>) screenInfoRef.relativePath)
        screenPath.add(transitionName)
        (ti.pathParameterList ?: []).each { String pathParameterName ->
            Object pathValue = parameters?.get(pathParameterName)
            if (pathValue == null || pathValue.toString().isEmpty()) {
                throw new IllegalArgumentException("Missing required path parameter ${pathParameterName} for transition ${transitionName}")
            }
            screenPath.add(pathValue.toString())
        }

        Map<String, Object> callParameters = [:]
        (parameters ?: [:]).each { k, v ->
            if (v != null && k != 'transitionMethod') callParameters[k as String] = v
        }
        String requestMethod = normalizeRequestMethod(ti)
        if (requestMethod != 'get' && !callParameters.containsKey('moquiSessionToken')) {
            callParameters.moquiSessionToken = 'TestSessionToken'
        }

        ScreenTest st = ec.screen.makeTest()
                .rootScreen(screenInfoRef.rootLocation as String)
                .webappName(screenInfoRef.webappName as String)
                .renderMode('text')
                .skipJsonSerialize(true)

        ScreenTest.ScreenTestRender str = st.render(ScreenFacadeImpl.screenPathToString(screenPath), callParameters, requestMethod)
        return [
                rootScreenLocation: screenInfoRef.rootLocation,
                screenPath        : screenPath,
                output            : str.output,
                jsonObject        : sanitizeJsonValue(str.jsonObject),
                errorMessages     : (str.errorMessages ?: []).collect { it?.toString() }
        ]
    }

    protected Map findScreenInfo(ScreenFacadeImpl sfi, String screenLocation, Map parameters) {
        List explicitRelativePath = parameters?.relativeScreenPath instanceof List ? (List) parameters.relativeScreenPath : null
        String explicitRootLocation = parameters?.rootScreenLocation as String
        if (explicitRelativePath && explicitRootLocation) {
            return [rootLocation: explicitRootLocation, relativePath: explicitRelativePath.collect { it?.toString() }, webappName: inferWebappName(explicitRootLocation)]
        }

        for (String rootLocation in sfi.getAllRootScreenLocations()) {
            List infoList = sfi.getScreenInfoList(rootLocation, 99)
            def found = infoList.find { it?.sd?.location == screenLocation }
            if (found) {
                return [
                        rootLocation: rootLocation,
                        relativePath: buildRelativeScreenPath(found, screenLocation),
                        webappName  : inferWebappName(rootLocation)
                ]
            }
        }
        return null
    }

    protected static List<String> buildRelativeScreenPath(Object info, String screenLocation) {
        List<String> pathSegments = []
        Object cursor = info
        while (cursor != null) {
            String currentName = cursor?.name as String
            if (currentName) pathSegments.add(0, currentName)
            cursor = cursor?.parentInfo
        }
        if (!pathSegments.isEmpty()) pathSegments.remove(0)
        return pathSegments
    }

    protected String inferWebappName(String rootLocation) {
        String location = rootLocation ?: ''
        if (location.contains('/webroot/screen/webroot.xml')) return 'webroot'
        if (location.contains('/SimpleScreens/screen/SimpleScreens.xml')) return 'apps'
        return 'apps'
    }

    protected static String normalizeRequestMethod(ScreenDefinition.TransitionItem ti) {
        String method = ti?.method
        if (!method || method == 'any') return ti?.readOnly ? 'get' : 'post'
        return method
    }

    protected static Object sanitizeJsonValue(Object value) {
        if (value == null) return null
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) return value
        if (value instanceof Map) {
            Map<String, Object> sanitized = [:]
            ((Map) value).each { k, v -> sanitized[k?.toString()] = sanitizeJsonValue(v) }
            return sanitized
        }
        if (value instanceof Collection) return ((Collection) value).collect { sanitizeJsonValue(it) }
        return value.toString()
    }
}
