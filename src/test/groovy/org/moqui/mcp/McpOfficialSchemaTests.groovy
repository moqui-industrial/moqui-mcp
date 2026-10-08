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

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import groovy.json.JsonOutput
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class McpOfficialSchemaTests extends Specification {
    static final String SCHEMA_LOCATION = 'https://modelcontextprotocol.io/schema/2026-07-28/schema.json'

    @Shared ExecutionContext ec
    @Shared SchemaRegistry schemaRegistry

    def setupSpec() {
        File schemaFile = new File(System.getProperty('moqui.mcp.schema.path'))
        assert schemaFile.isFile()
        String schemaText = schemaFile.getText('UTF-8')
        schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemas([(SCHEMA_LOCATION): schemaText])
        }

        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
    }

    def cleanupSpec() {
        ec?.destroy()
    }

    def 'official schema accepts every complete result produced by core handlers'() {
        given:
        McpClient client = new McpClient(ec)
        Map<String, Map> resultByDefinition = [
                DiscoverResult             : client.serverDiscover(),
                ListToolsResult            : client.listTools([pageSize: 5]),
                ListResourcesResult        : client.resourceProvider.listResources([pageSize: 5]),
                ListResourceTemplatesResult: client.resourceProvider.listResourceTemplates([pageSize: 5]),
                ReadResourceResult         : client.resourceProvider.readResource(
                        'moqui://entity-def/moqui.basic.Enumeration', [:]),
                ListPromptsResult          : client.promptProvider.listPrompts([pageSize: 5]),
                GetPromptResult            : TestableWikiPromptProvider.result(),
                CallToolResult             : TestableMcpClient.completeResult(ec, [ok: true])
        ]

        expect:
        resultByDefinition.each { String definitionName, Map result ->
            Schema resultSchema = schemaRegistry.getSchema(
                    SchemaLocation.of("${SCHEMA_LOCATION}#/\$defs/${definitionName}"))
            List errors = resultSchema.validate(JsonOutput.toJson(result), InputFormat.JSON)
            assert errors.isEmpty(): "${definitionName}: ${errors.join('\n')}"
        }
    }

    def 'official schema rejects a complete result without resultType'() {
        when:
        Schema discoverSchema = schemaRegistry.getSchema(
                SchemaLocation.of("${SCHEMA_LOCATION}#/\$defs/DiscoverResult"))
        List errors = discoverSchema.validate(JsonOutput.toJson([
                supportedVersions: ['2026-07-28'], capabilities: [:], ttlMs: 1, cacheScope: 'public'
        ]), InputFormat.JSON)

        then:
        !errors.isEmpty()
    }

    static class TestableMcpClient extends McpClient {
        TestableMcpClient(ExecutionContext ec) { super(ec) }
        static Map completeResult(ExecutionContext ec, Object value) { new TestableMcpClient(ec).wrapToolResult(value) }
    }

    static class TestableWikiPromptProvider extends WikiPromptProvider {
        TestableWikiPromptProvider(ExecutionContext ec) { super(ec) }
        static Map result() { makePromptResult('schema-test', 'Hello ${name}', [name: 'Moqui']) }
    }
}
