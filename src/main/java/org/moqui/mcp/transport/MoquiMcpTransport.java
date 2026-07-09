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
package org.moqui.mcp.transport;

import java.util.Map;
import java.util.Set;

public interface MoquiMcpTransport {
    void openSession(String sessionId, String userId);
    void closeSession(String sessionId);
    boolean isSessionActive(String sessionId);

    void sendMessage(String sessionId, Map message);
    void sendNotification(String sessionId, Map notification);
    void sendNotificationToUser(String userId, Map notification);

    void broadcastNotification(Map notification);

    int getActiveSessionCount();
    Set<String> getSessionsForUser(String userId);
}
