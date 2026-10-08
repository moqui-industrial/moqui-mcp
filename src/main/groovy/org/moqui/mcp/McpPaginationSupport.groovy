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

class McpPaginationSupport {
    static final int DEFAULT_PAGE_SIZE = 100
    static final int MAX_PAGE_SIZE = 1000

    static int normalizePageSize(Object rawPageSize) {
        if (rawPageSize == null) return DEFAULT_PAGE_SIZE
        int pageSize
        String text = rawPageSize.toString()
        if (!text.isInteger()) throw new IllegalArgumentException("Invalid pageSize [${text}]")
        try { pageSize = text.toInteger() }
        catch (NumberFormatException ignored) { throw new IllegalArgumentException("Invalid pageSize [${text}]") }
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be greater than zero")
        if (pageSize > MAX_PAGE_SIZE) throw new IllegalArgumentException("pageSize must be less than or equal to ${MAX_PAGE_SIZE}")
        return pageSize
    }

    static int decodeCursor(Object rawCursor, String listName = 'cursor') {
        if (rawCursor == null) return 0
        String cursor = rawCursor.toString()
        if (!cursor) return 0
        if (!cursor.isInteger()) throw new IllegalArgumentException("Invalid ${listName} [${cursor}]")
        int offset
        try { offset = cursor.toInteger() }
        catch (NumberFormatException ignored) { throw new IllegalArgumentException("Invalid ${listName} [${cursor}]") }
        if (offset < 0) throw new IllegalArgumentException("Invalid ${listName} [${cursor}]")
        return offset
    }

    static String encodeCursor(int offset) { offset as String }

    static Map paginate(List<Map> items, Map params, String listKey) {
        int offset = decodeCursor(params?.cursor, "${listKey} cursor")
        int pageSize = normalizePageSize(params?.pageSize)
        int endIndex = (int) Math.min((long) offset + pageSize, (long) items.size())
        List<Map> page = offset < items.size() ? items.subList(offset, endIndex) : []
        Map result = [(listKey): page]
        if (endIndex < items.size()) result.nextCursor = encodeCursor(endIndex)
        return result
    }
}
