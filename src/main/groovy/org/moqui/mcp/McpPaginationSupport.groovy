package org.moqui.mcp

class McpPaginationSupport {
    static final int DEFAULT_PAGE_SIZE = 100
    static final int MAX_PAGE_SIZE = 250

    static int normalizePageSize(Object rawPageSize) {
        int pageSize = DEFAULT_PAGE_SIZE
        if (rawPageSize instanceof Number) pageSize = ((Number) rawPageSize).intValue()
        else if (rawPageSize != null) {
            String text = rawPageSize.toString()
            pageSize = text.isInteger() ? text.toInteger() : DEFAULT_PAGE_SIZE
        }
        if (pageSize < 1) pageSize = DEFAULT_PAGE_SIZE
        if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE
        return pageSize
    }

    static int decodeCursor(Object rawCursor, String listName = 'cursor') {
        if (rawCursor == null) return 0
        String cursor = rawCursor.toString()
        if (!cursor) return 0
        if (!cursor.isInteger()) throw new IllegalArgumentException("Invalid ${listName} [${cursor}]")
        int offset = cursor.toInteger()
        return Math.max(offset, 0)
    }

    static String encodeCursor(int offset) { offset as String }

    static Map paginate(List<Map> items, Map params, String listKey) {
        int offset = decodeCursor(params?.cursor, "${listKey} cursor")
        int pageSize = normalizePageSize(params?.pageSize)
        int endIndex = Math.min(offset + pageSize, items.size())
        List<Map> page = offset < items.size() ? items.subList(offset, endIndex) : []
        Map result = [(listKey): page]
        if (endIndex < items.size()) result.nextCursor = encodeCursor(endIndex)
        return result
    }
}
