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
import spock.lang.Specification

class McpWikiPromptTests extends Specification {
    def 'prompt arguments are exact and replacement is not recursive'() {
        when:
        Map result = TestableWikiPromptProvider.render('test', 'Hello ${name}; literal ${second}',
                [name: '${second}', second: 'done'])

        then:
        result.resultType == 'complete'
        result.messages[0].content.text == 'Hello ${second}; literal done'

        when:
        TestableWikiPromptProvider.render('test', 'Hello ${name}', arguments)

        then:
        thrown(IllegalArgumentException)

        where:
        arguments << [[:], [name: 'ok', extra: 'denied']]
    }

    def 'placeholder extraction accepts identifiers only'() {
        expect:
        TestableWikiPromptProvider.placeholders('${good} ${also-good_2} ${bad.name} ${1bad}') ==
                ['good', 'also-good_2'] as Set
    }

    static class TestableWikiPromptProvider extends WikiPromptProvider {
        TestableWikiPromptProvider(ExecutionContext ec) { super(ec) }
        static Map render(String name, String text, Map arguments) { makePromptResult(name, text, arguments) }
        static Set<String> placeholders(String text) { extractPlaceholderNames(text) }
    }
}
