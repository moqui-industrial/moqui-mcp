/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.agent.context

import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

class AgentContextUtil {
    Map buildContext(ExecutionContext ec) {
        Set<String> userGroupIdSet = ec.user.userGroupIdSet ?: [] as Set<String>
        Set<String> permissionIdSet = [] as Set<String>

        if (userGroupIdSet) {
            try {
                def ugpList = ec.entity.find('moqui.security.UserGroupPermission')
                    .condition('userGroupId', org.moqui.entity.EntityCondition.IN, userGroupIdSet as List)
                    .useCache(true)
                    .list()
                permissionIdSet.addAll(ugpList.collect { it.userPermissionId as String }.findAll { it })
            } catch (ArtifactAuthorizationException ignored) {
                // Some runtime users can see their groups but not the underlying permission join entity.
                // Degrade gracefully and return the rest of the runtime context.
            }
        }

        Map userCtx = ec.user.context ?: [:]

        [
            userId: ec.user.userId,
            username: ec.user.username,
            tenantId: ec.context?.get('tenantId'),
            activeOrgId: userCtx.activeOrgId,
            userOrgIds: (userCtx.userOrgIds ?: []) as List,
            locale: ec.user.locale?.toString(),
            timezone: ec.user.timeZone?.getID(),
            permissionIdSet: permissionIdSet,
            userGroupIdSet: userGroupIdSet
        ]
    }
}
