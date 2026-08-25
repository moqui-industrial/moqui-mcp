package org.moqui.mcp

import org.moqui.context.ExecutionContext
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.screen.ScreenUrlInfo

class PromptSecurityHelper {
    protected final ExecutionContext ec

    PromptSecurityHelper(ExecutionContext ec) {
        this.ec = ec
    }

    boolean isPromptVisible(Map descriptor) {
        if (descriptor == null) return false
        String promptName = descriptor.name as String
        if (!promptName) return false
        if (!promptName.startsWith('moqui.screen.')) return true
        return isScreenPromptVisible(descriptor)
    }

    protected boolean isScreenPromptVisible(Map descriptor) {
        String rootScreenLocation = descriptor.rootScreenLocation as String
        String screenLocation = descriptor.screenLocation as String
        String transitionName = descriptor.transitionName as String
        String transitionMethod = (descriptor.transitionMethod as String) ?: 'any'
        List<String> relativeScreenPath = descriptor.relativeScreenPath instanceof Collection ?
                ((Collection) descriptor.relativeScreenPath).collect { it?.toString() }.findAll { it } :
                []

        if (!rootScreenLocation || !screenLocation || !transitionName) return false

        ScreenFacadeImpl sfi = (ScreenFacadeImpl) ec.screenFacade
        ScreenDefinition rootSd = sfi.getScreenDefinition(rootScreenLocation)
        ScreenDefinition targetSd = sfi.getScreenDefinition(screenLocation)
        if (rootSd == null || targetSd == null) return false

        ScreenDefinition.TransitionItem ti = targetSd.getTransitionItem(transitionName, transitionMethod)
        if (ti == null) return false

        try {
            ScreenUrlInfo sui = ScreenUrlInfo.getScreenUrlInfo(sfi, rootSd, rootSd, new ArrayList<String>(relativeScreenPath), null, 0)
            if (sui == null) return false
            return sui.isPermitted(ec, ti)
        } catch (Throwable ignored) {
            return false
        }
    }
}
