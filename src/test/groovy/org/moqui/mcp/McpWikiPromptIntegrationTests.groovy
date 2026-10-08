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

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import spock.lang.Shared
import spock.lang.Specification

class McpWikiPromptIntegrationTests extends Specification {
    static final String SPACE_ID = 'MCP_TEST_PROMPTS'
    static final String USER_ALLOWED = 'mcp-prompt-allowed'
    static final String USER_DENIED = 'mcp-prompt-denied'
    static final String PAGE_PUBLIC = 'MCP_PROMPT_PUBLIC'
    static final String PAGE_RESTRICTED = 'MCP_PROMPT_RESTRICTED'

    @Shared ExecutionContext ec
    @Shared TestWikiPromptProvider provider

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        cleanupFixtures()
        [USER_ALLOWED, USER_DENIED].each { String userId ->
            ec.entity.makeValue('moqui.security.UserAccount')
                    .setAll([userId: userId, username: userId, disabled: 'N']).create()
        }
        ec.entity.makeValue('moqui.resource.wiki.WikiSpace')
                .setAll([wikiSpaceId: SPACE_ID, description: 'MCP prompt integration tests', restrictView: 'Y']).create()
        ec.entity.makeValue('moqui.resource.wiki.WikiSpaceUser')
                .setAll([wikiSpaceId: SPACE_ID, userId: USER_ALLOWED, allowView: 'Y']).create()
        ec.entity.makeValue('moqui.resource.wiki.WikiPage')
                .setAll([wikiPageId: PAGE_PUBLIC, wikiSpaceId: SPACE_ID, pagePath: 'public-prompt',
                         publishedVersionName: 'v1', restrictView: 'N']).create()
        ec.entity.makeValue('moqui.resource.wiki.WikiPage')
                .setAll([wikiPageId: PAGE_RESTRICTED, wikiSpaceId: SPACE_ID, pagePath: 'restricted-prompt',
                         publishedVersionName: 'v1', restrictView: 'Y']).create()
        ec.entity.makeValue('moqui.resource.wiki.WikiPageUser')
                .setAll([wikiPageId: PAGE_RESTRICTED, userId: USER_ALLOWED, allowView: 'Y']).create()
        ec.cache.clearAllCaches()
        provider = new TestWikiPromptProvider(ec)
    }

    def cleanupSpec() {
        ec?.user?.logoutUser()
        ec?.artifactExecution?.disableAuthz()
        cleanupFixtures()
        ec?.destroy()
    }

    def 'published prompts remain consistent across list has and get for an authorized user'() {
        given:
        login(USER_ALLOWED)

        when:
        Map listed = provider.listPrompts([:])
        Map prompt = new TestCompositePromptProvider(ec, provider).getPrompt('restricted-prompt',
                [arguments: [name: 'Moqui']])

        then:
        listed.prompts*.name == ['public-prompt', 'restricted-prompt']
        provider.hasPrompt('public-prompt')
        provider.hasPrompt('restricted-prompt')
        prompt.messages[0].content.text == 'Hello Moqui'
    }

    def 'restricted space hides prompt names and direct retrieval from another user'() {
        given:
        login(USER_DENIED)

        expect:
        provider.listPrompts([:]).prompts == []
        !provider.hasPrompt('public-prompt')
        !provider.hasPrompt('restricted-prompt')

        when:
        new TestCompositePromptProvider(ec, provider).getPrompt('public-prompt', [:])

        then:
        thrown(IllegalArgumentException)
    }

    private void login(String userId) {
        ec.user.logoutUser()
        ec.artifactExecution.disableAuthz()
        assert ((ExecutionContextImpl) ec).userFacade.internalLoginUser(userId, false)
    }

    private void cleanupFixtures() {
        if (ec == null) return
        ec.artifactExecution.disableAuthz()
        ec.entity.find('moqui.resource.wiki.WikiPageUser').condition('wikiPageId', PAGE_RESTRICTED).deleteAll()
        ec.entity.find('moqui.resource.wiki.WikiPage').condition('wikiSpaceId', SPACE_ID).deleteAll()
        ec.entity.find('moqui.resource.wiki.WikiSpaceUser').condition('wikiSpaceId', SPACE_ID).deleteAll()
        ec.entity.find('moqui.resource.wiki.WikiSpace').condition('wikiSpaceId', SPACE_ID).deleteAll()
        ec.entity.find('moqui.security.UserAccount').condition('userId', 'in', [USER_ALLOWED, USER_DENIED]).deleteAll()
    }

    static class TestWikiPromptProvider extends WikiPromptProvider {
        TestWikiPromptProvider(ExecutionContext ec) { super(ec) }

        @Override
        protected String getPromptSpaceId() { SPACE_ID }

        @Override
        protected String getPublishedPromptText(String name) { 'Hello ${name}' }
    }

    static class TestCompositePromptProvider extends CompositePromptProvider {
        TestCompositePromptProvider(ExecutionContext ec, Object provider) {
            super(ec)
            promptProviders.clear()
            promptProviders.add(provider)
        }
    }
}
