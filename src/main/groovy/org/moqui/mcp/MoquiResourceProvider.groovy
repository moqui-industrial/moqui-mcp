package org.moqui.mcp

import groovy.json.JsonBuilder
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl

class MoquiResourceProvider {
    protected final ExecutionContext ec
    protected final ScreenInteractionCompiler compiler

    MoquiResourceProvider(ExecutionContext ec) {
        this.ec = ec
        this.compiler = new ScreenInteractionCompiler(ec)
    }

    Map listResources(Map params) {
        List<Map> resources = []

        ((Collection<String>) ec.entity.getAllEntityNames()).toList().sort().each { String entityName ->
            if (!isEntityVisible(entityName)) return
            resources.add([
                    uri        : "moqui://entity-def/${entityName}",
                    name       : entityName,
                    description: "Moqui entity definition for ${entityName}",
                    mimeType   : 'application/json'
            ])
        }

        ec.entity.find('moqui.entity.document.DataDocument').useCache(true).list()?.each { dd ->
            if (!isDataDocumentVisible(dd as Map)) return
            resources.add([
                    uri        : "moqui://data-document/${dd.dataDocumentId}",
                    name       : dd.documentName ?: dd.dataDocumentId,
                    description: dd.documentName ?: dd.documentTitle ?: "DataDocument ${dd.dataDocumentId}",
                    mimeType   : 'application/json'
            ])
        }

        Map page = McpPaginationSupport.paginate(resources.sort { a, b -> (a.uri ?: '') <=> (b.uri ?: '') }, params, 'resources')
        Map result = [
                resultType: 'complete',
                resources : page.resources,
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    Map listResourceTemplates(Map params) {
        List<Map> templates = [
                [
                        name       : 'moqui-entity-definition',
                        title      : 'Moqui Entity Definition',
                        description: 'Read Moqui entity definition metadata by full entity name.',
                        uriTemplate: 'moqui://entity-def/{entityName}',
                        mimeType   : 'application/json'
                ],
                [
                        name       : 'moqui-entity-record',
                        title      : 'Moqui Entity Record',
                        description: 'Read a deterministic Moqui entity record by full entity name and complete primary key token.',
                        uriTemplate: 'moqui://entity/{entityName}/{primaryKeyToken}',
                        mimeType   : 'application/json'
                ],
                [
                        name       : 'moqui-data-document',
                        title      : 'Moqui Data Document',
                        description: 'Read DataDocument definition metadata by dataDocumentId.',
                        uriTemplate: 'moqui://data-document/{dataDocumentId}',
                        mimeType   : 'application/json'
                ],
                [
                        name       : 'moqui-screen-lookup',
                        title      : 'Moqui Screen Lookup',
                        description: 'Read a prompt-bound lookup resource derived from a Moqui screen field.',
                        uriTemplate: 'lookup://screen/{promptName}/{argumentName}?q={query}',
                        mimeType   : 'application/json'
                ]
        ]
        templates.addAll(buildLookupResourceTemplates())
        Map page = McpPaginationSupport.paginate(templates, params, 'resourceTemplates')
        Map result = [
                resultType       : 'complete',
                resourceTemplates: page.resourceTemplates,
                ttlMs            : McpClient.CACHE_TTL_MS,
                cacheScope       : 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    Map readResource(String uri, Map params) {
        if (!uri) throw new IllegalArgumentException('Resource URI is required')

        if (uri.startsWith('moqui://entity-def/')) {
            String entityName = uri.substring('moqui://entity-def/'.length())
            return readEntityDefinition(uri, entityName)
        }

        if (uri.startsWith('moqui://entity/')) {
            String suffix = uri.substring('moqui://entity/'.length())
            int slashIndex = suffix.indexOf('/')
            if (slashIndex < 1) throw new IllegalArgumentException("Invalid entity record URI ${uri}")
            String entityName = suffix.substring(0, slashIndex)
            String pkToken = suffix.substring(slashIndex + 1)
            return readEntityRecord(uri, entityName, pkToken)
        }

        if (uri.startsWith('moqui://data-document/')) {
            String dataDocumentId = uri.substring('moqui://data-document/'.length())
            return readDataDocument(uri, dataDocumentId)
        }

        if (uri.startsWith('lookup://screen/')) {
            return readLookupResource(uri)
        }

        if (uri.startsWith('entity://')) {
            return readEntityDefinition(uri, uri.substring('entity://'.length()))
        }

        if (uri.startsWith('datadocument://')) {
            return readDataDocument(uri, uri.substring('datadocument://'.length()))
        }

        if (uri.startsWith('record://')) {
            String withoutScheme = uri.substring('record://'.length())
            List<String> parts = withoutScheme.split('\\?', 2) as List<String>
            String entityName = parts[0]
            Map<String, Object> conditions = [:]
            if (parts.size() > 1 && parts[1]) {
                parts[1].split('&').each { String pair ->
                    List<String> kv = pair.split('=', 2) as List<String>
                    conditions[kv[0]] = kv.size() > 1 ? java.net.URLDecoder.decode(kv[1], 'UTF-8') : ''
                }
            }
            return readLegacyRecord(uri, entityName, conditions)
        }

        throw new IllegalArgumentException("Unsupported resource URI: ${uri}")
    }

    Map complete(String refUri, String argumentName, String argumentValue, Map context) {
        String valuePrefix = argumentValue ?: ''
        List<String> values = []

        if (refUri == 'moqui://entity-def/{entityName}' || refUri == 'moqui://entity/{entityName}/{primaryKeyToken}') {
            if (argumentName == 'entityName') {
                values = ((Collection<String>) ec.entity.getAllEntityNames()).toList().findAll { it.startsWith(valuePrefix) }.sort().take(100)
            }
        }

        if (refUri == 'moqui://data-document/{dataDocumentId}' && argumentName == 'dataDocumentId') {
            values = ec.entity.find('moqui.entity.document.DataDocument').useCache(true).list()
                    .collect { it.dataDocumentId as String }
                    .findAll { it.startsWith(valuePrefix) }
                    .sort()
                    .take(100)
        }

        if (refUri?.startsWith('lookup://screen/')) {
            Map lookupTemplate = parseLookupUri(refUri)
            if (argumentName == 'query' || argumentName == 'q') {
                values = queryLookupValues(lookupTemplate.promptName as String, lookupTemplate.argumentName as String,
                        [q: valuePrefix]).collect { it.value as String }.take(100)
            }
        }

        return [
                completion: [
                        values : values,
                        total  : values.size(),
                        hasMore: false
                ]
        ]
    }

    protected Map readEntityDefinition(String uri, String entityName) {
        if (!ec.entity.isEntityDefined(entityName)) throw new IllegalArgumentException("Unknown entity resource ${entityName}")
        if (!isEntityVisible(entityName)) throw new IllegalArgumentException("Entity resource is not available for ${entityName}")
        def ed = ec.entityFacade.getEntityDefinition(entityName)
        List<String> pkFieldNames = ed.getPkFieldNames()
        List<Map> fieldList = ed.getAllFieldNames().collect { String fieldName ->
            def fieldNode = ed.getFieldNode(fieldName)
            [
                    name     : fieldName,
                    type     : fieldNode?.attribute('type'),
                    isPk     : pkFieldNames.contains(fieldName),
                    notNull  : fieldNode?.attribute('not-null') == 'true',
                    encrypted: fieldNode?.attribute('encrypt') == 'true'
            ]
        }
        List<Map> relList = ed.getRelationshipsInfo(false).collect { relInfo ->
            [
                    type         : relInfo.type,
                    title        : relInfo.title,
                    relatedEntity: relInfo.relatedEntityName,
                    shortAlias   : relInfo.shortAlias
            ]
        }
        return [
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder([
                                             entityName      : entityName,
                                             packageName     : ed.getFullEntityName(),
                                             primaryKeyFields: pkFieldNames,
                                             fieldList       : fieldList,
                                             relationshipList: relList
                                     ]).toString()
                             ]]
        ]
    }

    protected Map readEntityRecord(String uri, String entityName, String pkToken) {
        if (!ec.entity.isEntityDefined(entityName)) throw new IllegalArgumentException("Unknown entity resource ${entityName}")
        if (!isEntityVisible(entityName)) throw new IllegalArgumentException("Entity resource is not available for ${entityName}")
        def ed = ec.entityFacade.getEntityDefinition(entityName)
        List<String> pkFieldNames = ed.getPkFieldNames()
        Map<String, String> pkMap = decodePrimaryKeyToken(pkToken, pkFieldNames)
        def find = ec.entity.find(entityName)
        pkMap.each { String fieldName, String value -> find.condition(fieldName, value) }
        def record = find.one()
        if (!record) throw new IllegalArgumentException("Record resource not found for ${uri}")
        return [
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder(record ?: [:]).toString()
                             ]]
        ]
    }

    protected Map readLegacyRecord(String uri, String entityName, Map<String, Object> conditions) {
        def find = ec.entity.find(entityName)
        conditions.each { String key, Object value -> find.condition(key, value) }
        def record = find.one()
        if (!record) throw new IllegalArgumentException("Record resource not found for ${uri}")
        return [
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder(record ?: [:]).toString()
                             ]]
        ]
    }

    protected Map readDataDocument(String uri, String dataDocumentId) {
        def dd = ec.entity.find('moqui.entity.document.DataDocument').condition('dataDocumentId', dataDocumentId).one()
        if (!dd) throw new IllegalArgumentException("Unknown data document resource ${dataDocumentId}")
        if (!isDataDocumentVisible(dd as Map)) throw new IllegalArgumentException("Data document resource is not available for ${dataDocumentId}")
        def fields = ec.entity.find('moqui.entity.document.DataDocumentField')
                .condition('dataDocumentId', dataDocumentId).orderBy('sequenceNum').list()
        return [
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder([
                                             dataDocument: dd,
                                             summary     : [
                                                     dataDocumentId     : dd?.dataDocumentId,
                                                     documentName       : dd?.documentName,
                                                     documentTitle      : dd?.documentTitle,
                                                     indexName          : dd?.indexName,
                                                     primaryEntityName  : dd?.primaryEntityName,
                                                     manualDataService  : dd?.manualDataServiceName,
                                                     manualMappingService: dd?.manualMappingServiceName
                                             ],
                                             fieldList   : fields
                                     ]).toString()
                             ]]
        ]
    }

    protected List<Map> buildLookupResourceTemplates() {
        List<Map> templates = []
        buildPromptDescriptorMap().each { String promptName, Map descriptor ->
            (descriptor.arguments ?: []).each { Map arg ->
                Map lookup = arg.lookup instanceof Map ? (Map) arg.lookup : null
                if (!lookup) return
                List<String> queryParts = ['q={query}']
                if (lookup.dependsOn instanceof Collection) {
                    ((Collection<String>) lookup.dependsOn).findAll { it }.each { String dep ->
                        queryParts.add("${dep}={${dep}}")
                    }
                }
                String uriTemplate = "lookup://screen/${promptName}/${arg.name}"
                if (queryParts) uriTemplate += '?' + queryParts.join('&')
                templates.add([
                        name       : "lookup.${promptName}.${arg.name}",
                        title      : "${descriptor.title}.${arg.title ?: arg.name}",
                        description: "Lookup resource for ${descriptor.title} field ${arg.name}",
                        uriTemplate: uriTemplate,
                        mimeType   : 'application/json',
                        annotations: [
                                lookupKind : lookup.lookupKind,
                                entityName : lookup.entityName,
                                enumTypeId : lookup.enumTypeId,
                                statusTypeId: lookup.statusTypeId
                        ].findAll { it.value != null }
                ])
            }
        }
        return templates.sort { a, b -> (a.uriTemplate ?: '') <=> (b.uriTemplate ?: '') }
    }

    protected Map readLookupResource(String uri) {
        Map parsed = parseLookupUri(uri)
        String promptName = parsed.promptName as String
        String argumentName = parsed.argumentName as String
        Map descriptor = buildPromptDescriptorMap()[promptName]
        if (!descriptor) throw new IllegalArgumentException("Unknown lookup prompt ${promptName}")
        Map argument = (descriptor.arguments ?: []).find { Map arg -> arg.name == argumentName } as Map
        if (!argument?.lookup) throw new IllegalArgumentException("Prompt ${promptName} does not expose lookup field ${argumentName}")

        List<Map> values = queryLookupValues(promptName, argumentName, parsed.queryParams as Map<String, String>)
        Map payload = [
                promptName  : promptName,
                argumentName: argumentName,
                title       : argument.title ?: argument.name,
                lookup      : argument.lookup,
                query       : parsed.queryParams?.q,
                values      : values
        ]

        return [
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder(payload).toString()
                             ]]
        ]
    }

    protected List<Map> queryLookupValues(String promptName, String argumentName, Map<String, String> queryParams) {
        Map descriptor = buildPromptDescriptorMap()[promptName]
        if (!descriptor) return []
        Map argument = (descriptor.arguments ?: []).find { Map arg -> arg.name == argumentName } as Map
        Map lookup = argument?.lookup instanceof Map ? (Map) argument.lookup : null
        if (!lookup) return []
        String query = queryParams?.q ?: ''

        switch (lookup.lookupKind) {
            case 'enum':
            case 'enum-parent':
            case 'enum-group':
                return queryEnumerationLookup(query, lookup.enumTypeId as String, 50)
            case 'status':
                return queryStatusLookup(query, lookup.statusTypeId as String, 50)
            case 'dynamic-options':
                return queryDynamicOptionsLookup(descriptor, argument, lookup, query, queryParams, 50)
            case 'entity-options':
                return queryEntityOptionsLookup(lookup, query, queryParams, 50)
            default:
                return []
        }
    }

    protected List<Map> queryEnumerationLookup(String query, String enumTypeId, int limit) {
        if (!ec.entity.isEntityDefined('moqui.basic.Enumeration')) return []
        def find = ec.entity.find('moqui.basic.Enumeration')
        if (enumTypeId) find.condition('enumTypeId', enumTypeId)
        List<Map> rows = find.list().take(250) as List<Map>
        String q = query?.toLowerCase()
        return rows.findAll { Map ev ->
            !q || [ev.enumId, ev.enumCode, ev.description].find { it?.toString()?.toLowerCase()?.contains(q) }
        }.collect { Map ev ->
            [
                    value      : ev.enumId as String,
                    label      : ev.description ?: ev.enumCode ?: ev.enumId,
                    description: ev.enumCode ?: ev.description,
                    metadata   : [
                            enumCode  : ev.enumCode,
                            enumTypeId: ev.enumTypeId
                    ].findAll { it.value != null }
            ]
        }.unique { it.value }.take(limit)
    }

    protected List<Map> queryStatusLookup(String query, String statusTypeId, int limit) {
        if (!ec.entity.isEntityDefined('moqui.basic.StatusItem')) return []
        def find = ec.entity.find('moqui.basic.StatusItem')
        if (statusTypeId) find.condition('statusTypeId', statusTypeId)
        List<Map> rows = find.list().take(250) as List<Map>
        String q = query?.toLowerCase()
        return rows.findAll { Map ev ->
            !q || [ev.statusId, ev.description].find { it?.toString()?.toLowerCase()?.contains(q) }
        }.collect { Map ev ->
            [
                    value      : ev.statusId as String,
                    label      : ev.description ?: ev.statusId,
                    description: ev.statusId,
                    metadata   : [statusTypeId: ev.statusTypeId].findAll { it.value != null }
            ]
        }.unique { it.value }.take(limit)
    }

    protected List<Map> queryEntityOptionsLookup(Map lookup, String query, Map<String, String> queryParams, int limit) {
        String entityName = lookup.entityName as String
        String keyField = lookup.keyField as String
        if (!entityName || !keyField || !ec.entity.isEntityDefined(entityName)) return []

        def find = ec.entity.find(entityName)
        ((Collection<Map>) lookup.conditions ?: []).each { Map cond ->
            String fieldName = cond.fieldName as String
            if (!fieldName) return
            if (cond.value != null && cond.value != '') {
                find.condition(fieldName, cond.value)
            } else if (cond.from != null && cond.from != '') {
                String fromField = cond.from as String
                if (queryParams[fromField]) find.condition(fieldName, queryParams[fromField])
            }
        }
        if (lookup.dependsOn instanceof Collection) {
            ((Collection<String>) lookup.dependsOn).findAll { it }.each { String dep ->
                if (queryParams[dep]) find.condition(dep, queryParams[dep])
            }
        }

        List<Map> rows = find.list().take(250) as List<Map>
        String q = query?.toLowerCase()
        return rows.findAll { Map ev ->
            if (!q) return true
            List<String> probeValues = [ev[keyField]?.toString(), renderLookupLabel(ev, lookup)]
            probeValues.find { it?.toLowerCase()?.contains(q) }
        }.collect { Map ev ->
            [
                    value      : ev[keyField] as String,
                    label      : renderLookupLabel(ev, lookup),
                    description: entityName,
                    metadata   : [entityName: entityName, keyField: keyField]
            ]
        }.findAll { it.value }.unique { it.value }.take(limit)
    }

    protected List<Map> queryDynamicOptionsLookup(Map descriptor, Map argument, Map lookup, String query, Map<String, String> queryParams, int limit) {
        String transitionName = lookup.transition as String
        if (!descriptor?.screenLocation || !transitionName) return []

        Map<String, Object> parameters = [:]
        Map staticParameterMap = lookup.parameterMap instanceof Map ? (Map) lookup.parameterMap : [:]
        staticParameterMap.each { k, v -> parameters[k as String] = v }
        if (query != null) parameters.term = query

        if (lookup.dependsOn instanceof Collection) {
            ((Collection<String>) lookup.dependsOn).findAll { it }.each { String dep ->
                if (queryParams.containsKey(dep)) parameters[dep] = queryParams[dep]
            }
        }

        Map result = new ScreenTransitionExecutor(ec).execute(
                descriptor.screenLocation as String,
                transitionName,
                [
                        rootScreenLocation: descriptor.rootScreenLocation,
                        relativeScreenPath: descriptor.relativeScreenPath,
                        transitionMethod  : descriptor.transitionMethod
                ] + parameters
        )
        Object jsonObject = result.jsonObject
        Collection optionRows
        if (jsonObject instanceof Map && ((Map) jsonObject).options instanceof Collection) {
            optionRows = (Collection) ((Map) jsonObject).options
        } else if (jsonObject instanceof Collection) {
            optionRows = (Collection) jsonObject
        } else {
            optionRows = []
        }

        String valueField = lookup.valueField as String
        String labelField = lookup.labelField as String
        String q = query?.toLowerCase()
        return optionRows.collect { Object row ->
            if (row instanceof Map) {
                Map rowMap = (Map) row
                String value = extractDynamicLookupValue(rowMap, valueField)
                String label = extractDynamicLookupLabel(rowMap, labelField, value)
                [
                        value      : value,
                        label      : label,
                        description: transitionName,
                        metadata   : [transition: transitionName],
                        searchText : buildDynamicLookupSearchText(rowMap, value, label, transitionName)
                ]
            } else {
                String text = row?.toString()
                [
                        value      : text,
                        label      : text,
                        description: transitionName,
                        metadata   : [transition: transitionName],
                        searchText : text
                ]
            }
        }.findAll { Map candidate ->
            if (!candidate.value) return false
            if (!q) return true
            return [candidate.value, candidate.label, candidate.description, candidate.searchText]
                    .find { it?.toString()?.toLowerCase()?.contains(q) }
        }.collect { Map candidate ->
            candidate.findAll { it.key != 'searchText' }
        }.unique { it.value }.take(limit)
    }

    protected static String extractDynamicLookupValue(Map rowMap, String valueField) {
        if (valueField && rowMap[valueField] != null) return rowMap[valueField].toString()
        for (String fallbackField in ['value', 'key', 'id', 'enumId', 'statusId']) {
            if (rowMap[fallbackField] != null) return rowMap[fallbackField].toString()
        }
        if (rowMap.size() == 1) return rowMap.values().first()?.toString()
        return null
    }

    protected static String extractDynamicLookupLabel(Map rowMap, String labelField, String defaultValue) {
        if (labelField && rowMap[labelField] != null) return rowMap[labelField].toString()
        for (String fallbackField in ['label', 'text', 'name', 'description', 'partyName', 'facilityName', 'storeName',
                                      'organizationName', 'productName', 'assetName', 'statusDesc']) {
            if (rowMap[fallbackField] != null) return rowMap[fallbackField].toString()
        }
        return defaultValue
    }

    protected static String buildDynamicLookupSearchText(Map rowMap, String value, String label, String transitionName) {
        List<String> pieces = [value, label, transitionName]
        rowMap?.values()?.each { Object raw ->
            if (raw == null) return
            if (raw instanceof Map || raw instanceof Collection) {
                pieces.add(raw.toString())
            } else {
                pieces.add(raw.toString())
            }
        }
        return pieces.findAll { it }.join(' | ')
    }

    protected String renderLookupLabel(Map entityValue, Map lookup) {
        String textTemplate = lookup.textTemplate as String
        if (!textTemplate) return entityValue[lookup.keyField as String] as String
        if (textTemplate.toLowerCase().contains('partyname')) return renderPartyName(entityValue)
        return textTemplate.replaceAll(/\$\{([^}]+)\}/) { Object[] groups ->
            entityValue[groups[1] as String]?.toString() ?: ''
        }.trim()
    }

    protected boolean isEntityVisible(String entityName) {
        if (!entityName) return false
        try {
            return ArtifactExecutionFacadeImpl.isPermitted("AT_ENTITY:AUTHZA_VIEW:${entityName}", ec)
        } catch (Throwable ignored) {
            return false
        }
    }

    protected boolean isDataDocumentVisible(Map dd) {
        if (!dd) return false
        String primaryEntityName = dd.primaryEntityName as String
        if (primaryEntityName) return isEntityVisible(primaryEntityName)
        String manualDataServiceName = dd.manualDataServiceName as String
        if (manualDataServiceName) {
            try {
                return ArtifactExecutionFacadeImpl.isPermitted("AT_SERVICE:AUTHZA_VIEW:${manualDataServiceName}", ec)
            } catch (Throwable ignored) {
                return false
            }
        }
        return true
    }

    protected static String renderPartyName(Map entityValue) {
        List<String> parts = []
        if (entityValue['firstName']) parts.add(entityValue['firstName'] as String)
        if (entityValue['middleName']) parts.add(entityValue['middleName'] as String)
        if (entityValue['lastName']) parts.add(entityValue['lastName'] as String)
        if (!parts && entityValue['organizationName']) parts.add(entityValue['organizationName'] as String)
        if (!parts && entityValue['partyName']) parts.add(entityValue['partyName'] as String)
        return parts.join(' ').trim()
    }

    protected Map<String, Map> buildPromptDescriptorMap() {
        Map<String, Map> promptMap = [:]
        compiler.compileServiceBoundPrompts().each { Map descriptor ->
            promptMap[descriptor.name as String] = descriptor
        }
        return promptMap
    }

    protected static Map parseLookupUri(String uri) {
        String withoutScheme = uri.substring('lookup://screen/'.length())
        List<String> mainParts = withoutScheme.split('\\?', 2) as List<String>
        List<String> pathParts = mainParts[0].split('/') as List<String>
        if (pathParts.size() < 2) throw new IllegalArgumentException("Invalid lookup URI ${uri}")
        Map<String, String> queryParams = [:]
        if (mainParts.size() > 1 && mainParts[1]) {
            mainParts[1].split('&').each { String pair ->
                if (!pair) return
                List<String> kv = pair.split('=', 2) as List<String>
                String key = java.net.URLDecoder.decode(kv[0], 'UTF-8')
                String value = kv.size() > 1 ? java.net.URLDecoder.decode(kv[1], 'UTF-8') : ''
                queryParams[key] = value
            }
        }
        return [
                promptName  : java.net.URLDecoder.decode(pathParts[0], 'UTF-8'),
                argumentName: java.net.URLDecoder.decode(pathParts[1], 'UTF-8'),
                queryParams : queryParams
        ]
    }

    protected static Map<String, String> decodePrimaryKeyToken(String pkToken, List<String> pkFieldNames) {
        if (!pkToken) throw new IllegalArgumentException('primaryKeyToken is required')
        if (pkFieldNames.size() == 1) {
            return [(pkFieldNames[0]): java.net.URLDecoder.decode(pkToken, 'UTF-8')]
        }

        Map<String, String> decoded = [:]
        pkToken.split(';').each { String pair ->
            List<String> kv = pair.split('=', 2) as List<String>
            if (kv.size() != 2) throw new IllegalArgumentException("Invalid composite primaryKeyToken segment ${pair}")
            decoded[kv[0]] = java.net.URLDecoder.decode(kv[1], 'UTF-8')
        }
        if (decoded.keySet() != pkFieldNames as Set) {
            throw new IllegalArgumentException("Composite primaryKeyToken must include exactly these fields: ${pkFieldNames}")
        }
        return decoded
    }
}
