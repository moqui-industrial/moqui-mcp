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
package org.moqui.mcp

import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl

/** Test users for the llm-client tools: a member of ADMIN has the LlmGateway permission that the tools require. */
class McpTestUsers {
    static void create(ExecutionContext ec, String userId, String userGroupId = 'ADMIN') {
        ec.artifactExecution.disableAuthz()
        ec.transaction.runUseOrBegin(null, 'MCP test user') {
            delete(ec, userId)
            ec.entity.makeValue('moqui.security.UserAccount')
                    .setAll([userId: userId, username: userId, disabled: 'N']).create()
            if (userGroupId) ec.entity.makeValue('moqui.security.UserGroupMember')
                    .setAll([userGroupId: userGroupId, userId: userId, fromDate: new java.sql.Timestamp(0)]).create()
        }
        ec.cache.clearAllCaches()
    }

    static void login(ExecutionContext ec, String userId) {
        ec.user.logoutUser()
        assert ((ExecutionContextImpl) ec).userFacade.internalLoginUser(userId, false)
    }

    static void delete(ExecutionContext ec, String userId) {
        ec.transaction.runUseOrBegin(null, 'MCP test user') {
            ec.entity.find('moqui.security.UserGroupMember').condition('userId', userId).deleteAll()
            ec.entity.find('moqui.security.UserAccount').condition('userId', userId).deleteAll()
        }
    }
}
