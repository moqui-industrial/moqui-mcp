package org.moqui.mcp

import java.util.concurrent.ConcurrentHashMap

class McpSubscriptionRegistry {
    protected static final ConcurrentHashMap<String, Set<String>> RESOURCE_SUBSCRIPTIONS = new ConcurrentHashMap<>()

    static Map subscribeResource(String principalId, String uri) {
        if (!principalId) throw new IllegalArgumentException('Authenticated principalId is required for subscription')
        if (!uri) throw new IllegalArgumentException('Resource URI is required for subscription')
        Set<String> set = RESOURCE_SUBSCRIPTIONS.computeIfAbsent(principalId) {
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>())
        }
        set.add(uri)
        return [
                uri              : uri,
                principalId      : principalId,
                subscriptionCount: set.size(),
                subscribed       : true
        ]
    }

    static Map unsubscribeResource(String principalId, String uri) {
        if (!principalId) throw new IllegalArgumentException('Authenticated principalId is required for subscription')
        if (!uri) throw new IllegalArgumentException('Resource URI is required for subscription')
        Set<String> set = RESOURCE_SUBSCRIPTIONS.get(principalId)
        boolean removed = set?.remove(uri) ?: false
        if (set != null && set.isEmpty()) RESOURCE_SUBSCRIPTIONS.remove(principalId)
        return [
                uri              : uri,
                principalId      : principalId,
                subscriptionCount: set?.size() ?: 0,
                subscribed       : false,
                removed          : removed
        ]
    }

    static List<String> listResourceSubscriptions(String principalId) {
        Set<String> set = principalId ? RESOURCE_SUBSCRIPTIONS.get(principalId) : null
        return set ? new ArrayList<>(set).sort() : []
    }
}
