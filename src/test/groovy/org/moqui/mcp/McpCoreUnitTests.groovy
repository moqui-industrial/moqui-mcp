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

import spock.lang.Specification

class McpCoreUnitTests extends Specification {
    def 'pagination applies first page limit and rejects malformed cursors'() {
        given:
        List<Map> items = (1..5).collect { [name: "i${it}"] }

        when:
        Map first = McpPaginationSupport.paginate(items, [pageSize: 2], 'tools')

        then:
        first.tools*.name == ['i1', 'i2']
        first.nextCursor == '2'

        when:
        McpPaginationSupport.paginate(items, [cursor: '-1'], 'tools')

        then:
        thrown(IllegalArgumentException)

        when:
        McpPaginationSupport.paginate(items, [pageSize: 'abc'], 'tools')

        then:
        thrown(IllegalArgumentException)

        when:
        McpPaginationSupport.paginate(items, [cursor: '2147483648'], 'tools')

        then:
        thrown(IllegalArgumentException)

        and:
        McpPaginationSupport.paginate(items, [cursor: '5'], 'tools').tools == []
    }

    def 'a service tool is named verb_noun in snake case'() {
        expect:
        McpClient.serviceToolName(verb, noun) == name

        where:
        verb     | noun               || name
        'get'    | 'NormTree'         || 'get_norm_tree'
        'find'   | 'NormsForProduct'  || 'find_norms_for_product'
        'search' | 'Norms'            || 'search_norms'
        'get'    | 'ItemReferences'   || 'get_item_references'
        'run'    | null               || 'run'
    }

    def 'legacy handshake and ping methods are not silently accepted'() {
        when:
        new TestableMcpClient().handle(method, [:])

        then:
        thrown(IllegalArgumentException)

        where:
        method << ['initialize', 'ping', 'notifications/initialized']
    }

    def 'resources and prompts are not offered: llm-client tools replace them'() {
        when:
        new TestableMcpClient().handle(method, [:])

        then:
        IllegalArgumentException e = thrown()
        e.message.startsWith('Unsupported MCP method:')

        where:
        method << ['resources/list', 'resources/read', 'resources/templates/list', 'prompts/list', 'prompts/get']
    }

    def 'llm-client errors become MCP tool errors and plain values are wrapped'() {
        expect:
        TestableMcpClient.result([error: 'no such service']).isError
        TestableMcpClient.result([status: 403, error: 'denied']).isError
        TestableMcpClient.result([status: 404]).isError
        !TestableMcpClient.result([ok: true, result: [a: 1]]).isError
        TestableMcpClient.result('text').structuredContent == [result: 'text']
    }

    def 'search input is forced into one escaped plain text clause'() {
        expect:
        McpSearchSupport.normalizePlainTextQuery('name:test OR ownerPartyId:OTHER *') ==
                '(name\\:test OR ownerPartyId\\:OTHER \\*)'
        McpSearchSupport.normalizePlainTextQuery(null) == '*'
        McpSearchSupport.normalizePlainTextQuery('   ') == '*'

        when:
        McpSearchSupport.normalizePlainTextQuery(query)

        then:
        thrown(IllegalArgumentException)

        where:
        query << ['x' * 513]
    }

    static class TestableMcpClient extends McpClient {
        TestableMcpClient() { super(null) }
        static Map result(Object value) { toolResult(value) }
    }
}
