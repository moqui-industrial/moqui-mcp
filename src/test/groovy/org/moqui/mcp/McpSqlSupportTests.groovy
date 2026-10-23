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

class McpSqlSupportTests extends Specification {
    def 'analyzes table and column references in a read-only select'() {
        when:
        Map analysis = McpSqlSupport.analyze('''
            SELECT sales.customer_name, SUM(sales.net_amount) AS revenue
            FROM bi_sales_margin sales
            WHERE sales.order_date >= DATE '2026-01-01'
            GROUP BY sales.customer_name
            ORDER BY revenue DESC
        ''')

        then:
        analysis.statementType == 'SELECT'
        analysis.tables == ['bi_sales_margin']
        analysis.columns as Set == ['sales.customer_name', 'sales.net_amount', 'sales.order_date', 'revenue'] as Set
        analysis.analysisOnly
    }

    def 'analyzes common table expressions and nested selects'() {
        when:
        Map analysis = McpSqlSupport.analyze('''
            WITH recent_sales AS (
                SELECT sales.customer_id, sales.net_amount
                FROM bi_sales_margin sales
                WHERE sales.order_date >= DATE '2026-01-01'
            )
            SELECT customer_id, SUM(net_amount) AS revenue
            FROM recent_sales
            GROUP BY customer_id
        ''')

        then:
        analysis.tables.contains('bi_sales_margin')
        analysis.columns as Set == ['sales.customer_id', 'sales.net_amount', 'sales.order_date', 'customer_id', 'net_amount'] as Set
        analysis.hasWith
    }

    def 'reports wildcard projections for later column authorization'() {
        expect:
        McpSqlSupport.analyze('SELECT sales.*, * FROM bi_sales_margin sales').columns as Set == ['sales.*', '*'] as Set
    }

    def 'rejects writes, multiple statements, and invalid syntax before execution'() {
        when:
        McpSqlSupport.analyze(sql)

        then:
        IllegalArgumentException error = thrown()
        error.message ==~ message

        where:
        sql                                      || message
        'DELETE FROM bi_sales_margin'            || 'Only read-only SELECT statements are allowed'
        'SELECT * FROM bi_sales_margin; DELETE FROM audit_log' || 'Exactly one SQL statement is required'
        'SELECT * INTO temporary_sales FROM bi_sales_margin' || 'SELECT INTO is not allowed'
        'SELECT FROM'                            ||~ /Invalid SQL:.*/
    }
}
