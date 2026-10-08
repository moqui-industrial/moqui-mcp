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

import org.moqui.util.StringUtilities

class McpSearchSupport {
    static final int MAX_QUERY_LENGTH = 512

    static String normalizePlainTextQuery(String queryString) {
        String plainQuery = queryString?.trim()
        if (!plainQuery) throw new IllegalArgumentException('queryString must not be empty')
        if (plainQuery.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("queryString must not exceed ${MAX_QUERY_LENGTH} characters")
        }
        return '(' + StringUtilities.escapeElasticQueryString(plainQuery) + ')'
    }
}
