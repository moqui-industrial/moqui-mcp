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

    def 'service tool alias is deterministic safe and keeps already safe names'() {
        expect:
        TestableMcpClient.alias('safe_service-name_1') == 'safe_service-name_1'

        when:
        String alias = TestableMcpClient.alias('org.moqui.impl.UserServices.create#UserAccount')

        then:
        alias == TestableMcpClient.alias('org.moqui.impl.UserServices.create#UserAccount')
        alias ==~ /[A-Za-z0-9_-]+/
        alias != 'org.moqui.impl.UserServices.create#UserAccount'

        and:
        TestableMcpClient.alias('a#b') != TestableMcpClient.alias('a.b')
        TestableMcpClient.alias('x' * 200).length() <= 96
    }

    def 'legacy handshake and ping methods are not silently accepted'() {
        when:
        new TestableMcpClient().handle(method, [:])

        then:
        thrown(IllegalArgumentException)

        where:
        method << ['initialize', 'ping', 'notifications/initialized']
    }

    def 'search input is forced into one escaped plain text clause'() {
        expect:
        McpSearchSupport.normalizePlainTextQuery('name:test OR ownerPartyId:OTHER *') ==
                '(name\\:test OR ownerPartyId\\:OTHER \\*)'

        when:
        McpSearchSupport.normalizePlainTextQuery(query)

        then:
        thrown(IllegalArgumentException)

        where:
        query << ['   ', 'x' * 513]
    }

    static class TestableMcpClient extends McpClient {
        TestableMcpClient() { super(null) }
        static String alias(String serviceName) { serviceToolName(serviceName) }
    }
}
