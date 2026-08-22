package org.moqui.mcp

import org.moqui.context.ExecutionContext

class PromptLookupResolver {
    protected final ExecutionContext ec
    protected final MoquiResourceProvider resourceProvider

    PromptLookupResolver(ExecutionContext ec) {
        this.ec = ec
        this.resourceProvider = new MoquiResourceProvider(ec)
    }

    Map resolveArguments(Map descriptor, Map<String, String> rawArguments) {
        Map<String, String> resolved = [:]
        Map<String, Map> resolutionMeta = [:]
        Map<String, Map> argumentMeta = buildArgumentMetaMap(descriptor)
        (rawArguments ?: [:]).each { String name, String rawValue ->
            if (rawValue == null) return
            Map result = resolveArgument(name, rawValue, rawArguments, descriptor, argumentMeta[name])
            resolved[name] = result.value as String
            if (result.matched) resolutionMeta[name] = result
        }
        return [arguments: resolved, resolutionMeta: resolutionMeta]
    }

    Map completeArgument(String argumentName, String prefix, Map context, Map descriptor) {
        List<String> values = []
        String valuePrefix = prefix ?: ''
        Map<String, String> argContext = [:]
        (context ?: [:]).each { k, v -> if (v != null) argContext[k as String] = v.toString() }
        Map argMeta = buildArgumentMetaMap(descriptor)[argumentName]
        boolean hasExplicitLookup = argMeta?.lookup instanceof Map

        if (hasExplicitLookup) {
            values = completeFromLookupMeta(descriptor, argMeta, (Map) argMeta.lookup, valuePrefix, argContext)
        }
        if (!values && !hasExplicitLookup) {
            switch (classifyArgument(argumentName)) {
                case 'status':
                    values = queryColumnValues('moqui.basic.StatusItem', 'description', valuePrefix, 20, null)
                    break
                case 'enum':
                    values = queryColumnValues('moqui.basic.Enumeration', 'description', valuePrefix, 20, null)
                    break
                case 'glAccount':
                    values = queryColumnValues('mantle.ledger.account.GlAccount', 'accountCode', valuePrefix, 20, null)
                    break
                case 'product':
                    values = queryProductValues(valuePrefix, 20)
                    break
                case 'facility':
                    values = queryColumnValues('mantle.facility.Facility', 'facilityName', valuePrefix, 20, null)
                    break
                case 'facilityLocation':
                    Map<String, Object> extraConditions = [:]
                    if (argContext.facilityId) extraConditions.facilityId = argContext.facilityId
                    values = queryColumnValues('mantle.facility.FacilityLocation', 'locationSeqId', valuePrefix, 20, extraConditions)
                    break
                case 'timePeriod':
                    values = queryColumnValues('mantle.party.time.TimePeriod', 'periodName', valuePrefix, 20, null)
                    break
                case 'party':
                    values = queryPartyValues(valuePrefix, 20)
                    break
            }
        }

        return [
                completion: [
                        values : values.unique(),
                        total  : values.unique().size(),
                        hasMore: false
                ]
        ]
    }

    protected Map resolveArgument(String argumentName, String rawValue, Map<String, String> rawArguments, Map descriptor, Map argMeta) {
        String trimmed = rawValue?.trim()
        if (!trimmed) return [value: rawValue, matched: false]
        boolean hasExplicitLookup = argMeta?.lookup instanceof Map
        if (argMeta?.lookup instanceof Map) {
            Map lookupResolved = resolveFromLookupMeta(descriptor, argMeta, (Map) argMeta.lookup, trimmed, rawArguments)
            if (lookupResolved?.matched) return lookupResolved
            return [value: rawValue, matched: false]
        }

        switch (classifyArgument(argumentName)) {
            case 'status':
                return resolveStatus(trimmed)
            case 'enum':
                return resolveEnumeration(trimmed)
            case 'glAccount':
                return resolveGlAccount(trimmed)
            case 'product':
                return resolveProduct(trimmed)
            case 'facility':
                return resolveFacility(trimmed)
            case 'facilityLocation':
                return resolveFacilityLocation(trimmed, rawArguments.facilityId ?: rawArguments.fromFacilityId ?: rawArguments.toFacilityId)
            case 'timePeriod':
                return resolveTimePeriod(trimmed)
            case 'party':
                return resolveParty(trimmed)
            default:
                return [value: rawValue, matched: false]
        }
    }

    protected Map<String, Map> buildArgumentMetaMap(Map descriptor) {
        Map<String, Map> metaMap = [:]
        (descriptor?.arguments ?: []).each { Map arg ->
            if (arg?.name) metaMap[arg.name as String] = arg
        }
        return metaMap
    }

    protected Map resolveFromLookupMeta(Map descriptor, Map argMeta, Map lookupMeta, String rawValue, Map<String, String> rawArguments) {
        switch (lookupMeta.lookupKind) {
            case 'enum':
                return resolveEnumeration(rawValue, lookupMeta.enumTypeId as String)
            case 'enum-parent':
            case 'enum-group':
                return resolveEnumeration(rawValue, lookupMeta.enumTypeId as String)
            case 'status':
                return resolveStatus(rawValue, lookupMeta.statusTypeId as String)
            case 'dynamic-options':
                return resolveDynamicOptionsLookup(descriptor, argMeta, lookupMeta, rawValue, rawArguments)
            case 'entity-options':
                return resolveEntityOptionsLookup(lookupMeta, rawValue)
            default:
                return [value: rawValue, matched: false]
        }
    }

    protected List<String> completeFromLookupMeta(Map descriptor, Map argMeta, Map lookupMeta, String prefix, Map<String, String> argContext) {
        switch (lookupMeta.lookupKind) {
            case 'enum':
            case 'enum-parent':
            case 'enum-group':
                return queryEnumerationValues(prefix, 20, lookupMeta.enumTypeId as String)
            case 'status':
                return queryStatusValues(prefix, 20, lookupMeta.statusTypeId as String)
            case 'dynamic-options':
                return completeDynamicOptionsLookup(descriptor, argMeta, lookupMeta, prefix, argContext)
            case 'entity-options':
                return queryEntityOptionsValues(lookupMeta, prefix, 20)
            default:
                return []
        }
    }

    protected String classifyArgument(String argumentName) {
        String arg = argumentName ?: ''
        if (arg == 'statusId' || arg.endsWith('StatusId')) return 'status'
        if (arg == 'enumId' || arg.endsWith('EnumId') || arg.endsWith('TypeEnumId')) return 'enum'
        if (arg == 'glAccountId') return 'glAccount'
        if (arg == 'productId') return 'product'
        if (arg == 'facilityId') return 'facility'
        if (arg == 'locationSeqId') return 'facilityLocation'
        if (arg == 'timePeriodId') return 'timePeriod'
        if (arg == 'partyId' || arg.endsWith('PartyId') || arg == 'assignToPartyId') return 'party'
        return 'literal'
    }

    protected Map resolveStatus(String rawValue, String statusTypeId = null) {
        return resolveByUniqueMatch('moqui.basic.StatusItem', 'statusId', [
                [field: 'statusId', operator: 'equals'],
                [field: 'description', operator: 'equalsIgnoreCase']
        ], rawValue, statusTypeId ? [statusTypeId: statusTypeId] : null)
    }

    protected Map resolveEnumeration(String rawValue, String enumTypeId = null) {
        return resolveByUniqueMatch('moqui.basic.Enumeration', 'enumId', [
                [field: 'enumId', operator: 'equals'],
                [field: 'enumCode', operator: 'equalsIgnoreCase'],
                [field: 'description', operator: 'equalsIgnoreCase']
        ], rawValue, enumTypeId ? [enumTypeId: enumTypeId] : null)
    }

    protected Map resolveGlAccount(String rawValue) {
        return resolveByUniqueMatch('mantle.ledger.account.GlAccount', 'glAccountId', [
                [field: 'glAccountId', operator: 'equals'],
                [field: 'accountCode', operator: 'equals'],
                [field: 'accountName', operator: 'equalsIgnoreCase'],
                [field: 'description', operator: 'equalsIgnoreCase']
        ], rawValue)
    }

    protected Map resolveProduct(String rawValue) {
        return resolveByUniqueMatch('mantle.product.Product', 'productId', [
                [field: 'productId', operator: 'equals'],
                [field: 'pseudoId', operator: 'equalsIgnoreCase'],
                [field: 'productName', operator: 'equalsIgnoreCase']
        ], rawValue)
    }

    protected Map resolveFacility(String rawValue) {
        return resolveByUniqueMatch('mantle.facility.Facility', 'facilityId', [
                [field: 'facilityId', operator: 'equals'],
                [field: 'facilityName', operator: 'equalsIgnoreCase']
        ], rawValue)
    }

    protected Map resolveFacilityLocation(String rawValue, String facilityId) {
        List<Map> candidates = []

        def exactFind = ec.entity.find('mantle.facility.FacilityLocation').condition('locationSeqId', rawValue)
        if (facilityId) exactFind.condition('facilityId', facilityId)
        exactFind.list().each { ev -> candidates.add(ev as Map) }

        if (!candidates) {
            def descFind = ec.entity.find('mantle.facility.FacilityLocation').condition('description', rawValue)
            if (facilityId) descFind.condition('facilityId', facilityId)
            descFind.list().each { ev -> candidates.add(ev as Map) }
        }

        if (candidates.size() == 1) {
            Map match = candidates[0]
            return [value: match.locationSeqId as String, matched: true, entityName: 'mantle.facility.FacilityLocation', matchedField: 'locationSeqId']
        }
        return [value: rawValue, matched: false]
    }

    protected Map resolveTimePeriod(String rawValue) {
        return resolveByUniqueMatch('mantle.party.time.TimePeriod', 'timePeriodId', [
                [field: 'timePeriodId', operator: 'equals'],
                [field: 'periodName', operator: 'equalsIgnoreCase']
        ], rawValue)
    }

    protected Map resolveParty(String rawValue) {
        List<Map> candidates = []

        if (rawValue.contains(' ')) {
            List<String> parts = rawValue.split(/\s+/) as List<String>
            if (parts.size() >= 2) {
                def personFind = ec.entity.find('mantle.party.Person')
                        .condition('firstName', parts[0])
                        .condition('lastName', parts[-1])
                personFind.list().each { ev -> candidates.add([partyId: ev.partyId, matchedField: 'firstName/lastName', entityName: 'mantle.party.Person']) }
            }
        }

        if (!candidates) {
            def orgFind = ec.entity.find('mantle.party.Organization').condition('organizationName', rawValue)
            orgFind.list().each { ev -> candidates.add([partyId: ev.partyId, matchedField: 'organizationName', entityName: 'mantle.party.Organization']) }
        }

        if (!candidates) {
            if (ec.entity.isEntityDefined('mantle.party.Party')) {
                def partyFind = ec.entity.find('mantle.party.Party').condition('partyId', rawValue)
                partyFind.list().each { ev -> candidates.add([partyId: ev.partyId, matchedField: 'partyId', entityName: 'mantle.party.Party']) }
            }
        }

        if (candidates.size() == 1) {
            Map match = candidates[0]
            return [value: match.partyId as String, matched: true, entityName: match.entityName, matchedField: match.matchedField]
        }
        return [value: rawValue, matched: false]
    }

    protected Map resolveEntityOptionsLookup(Map lookupMeta, String rawValue) {
        String entityName = lookupMeta.entityName as String
        String keyField = lookupMeta.keyField as String
        if (!entityName || !keyField || !ec.entity.isEntityDefined(entityName)) return [value: rawValue, matched: false]

        Map<String, Object> baseConditions = [:]
        ((Collection) lookupMeta.conditions ?: []).each { Map cond ->
            if (!cond?.fieldName) return
            if (cond.value != null && cond.value != '') baseConditions[cond.fieldName as String] = cond.value
        }

        List<Map> rows = []
        def idFind = ec.entity.find(entityName)
        baseConditions.each { String k, Object v -> idFind.condition(k, v) }
        idFind.condition(keyField, rawValue)
        rows.addAll(idFind.list() as List<Map>)

        if (!rows && (lookupMeta.textTemplate?.toString()?.toLowerCase()?.contains('partyname'))) {
            def nameFind = ec.entity.find(entityName)
            baseConditions.each { String k, Object v -> nameFind.condition(k, v) }
            nameFind.list().each { Map ev ->
                String partyName = renderPartyName(ev)
                if (partyName && partyName.equalsIgnoreCase(rawValue)) rows.add(ev)
            }
        }

        if (!rows) {
            List<String> templateFields = extractTemplateFields(lookupMeta.textTemplate as String)
            if (templateFields) {
                def textFind = ec.entity.find(entityName)
                baseConditions.each { String k, Object v -> textFind.condition(k, v) }
                textFind.list().each { Map ev ->
                    List<String> probeValues = templateFields.collect { String fieldName -> ev[fieldName]?.toString() }.findAll { it }
                    if (probeValues.any { it.equalsIgnoreCase(rawValue) }) rows.add(ev)
                }
            }
        }

        List<String> uniqueIds = rows.collect { it[keyField] as String }.findAll { it }.unique()
        if (uniqueIds.size() == 1) {
            return [value: uniqueIds[0], matched: true, entityName: entityName, matchedField: keyField]
        }
        return [value: rawValue, matched: false]
    }

    protected Map resolveDynamicOptionsLookup(Map descriptor, Map argMeta, Map lookupMeta, String rawValue, Map<String, String> rawArguments) {
        if (!descriptor || !argMeta) return [value: rawValue, matched: false]
        Map<String, String> queryParams = [:]
        (rawArguments ?: [:]).each { k, v -> if (v != null) queryParams[k as String] = v.toString() }
        List<Map> candidates = resourceProvider.queryDynamicOptionsLookup(
                descriptor,
                argMeta,
                lookupMeta,
                rawValue,
                queryParams,
                50
        )
        List<Map> exactMatches = candidates.findAll { Map candidate ->
            String value = candidate.value as String
            String label = candidate.label as String
            return value?.equalsIgnoreCase(rawValue) || label?.equalsIgnoreCase(rawValue)
        }
        if (exactMatches.size() == 1) {
            return [
                    value       : exactMatches[0].value as String,
                    matched     : true,
                    entityName  : 'dynamic-options',
                    matchedField: lookupMeta?.transition ?: 'dynamic-options'
            ]
        }
        return [value: rawValue, matched: false]
    }

    protected List<String> completeDynamicOptionsLookup(Map descriptor, Map argMeta, Map lookupMeta, String prefix, Map<String, String> argContext) {
        if (!descriptor || !argMeta) return []
        return resourceProvider.queryDynamicOptionsLookup(
                descriptor,
                argMeta,
                lookupMeta,
                prefix,
                argContext ?: [:],
                20
        ).collect { Map candidate -> candidate.value as String }.findAll { it }
    }

    protected Map resolveByUniqueMatch(String entityName, String idField, List<Map> probes, String rawValue, Map<String, Object> fixedConditions = null) {
        List<Map> matches = []
        for (Map probe in probes) {
            def find = ec.entity.find(entityName)
            (fixedConditions ?: [:]).each { String key, Object value -> find.condition(key, value) }
            switch (probe.operator) {
                case 'equals':
                    find.condition(probe.field as String, rawValue)
                    break
                case 'equalsIgnoreCase':
                    find.condition(probe.field as String, rawValue)
                    break
                default:
                    continue
            }
            List<Map> foundList = find.list() as List<Map>
            foundList.each { Map ev ->
                String idValue = ev[idField] as String
                if (idValue) matches.add([value: idValue, entityName: entityName, matchedField: probe.field as String])
            }
            if (matches) break
        }

        List<String> uniqueValues = matches.collect { it.value as String }.unique()
        if (uniqueValues.size() == 1) {
            Map match = matches.find { (it.value as String) == uniqueValues[0] }
            return [value: uniqueValues[0], matched: true, entityName: entityName, matchedField: match?.matchedField]
        }
        return [value: rawValue, matched: false]
    }

    protected List<String> queryEnumerationValues(String prefix, int limit, String enumTypeId) {
        if (!ec.entity.isEntityDefined('moqui.basic.Enumeration')) return []
        def find = ec.entity.find('moqui.basic.Enumeration')
        if (enumTypeId) find.condition('enumTypeId', enumTypeId)
        List<Map> rows = find.list().take(250) as List<Map>
        String q = prefix?.toLowerCase() ?: ''
        return rows.findAll { Map ev ->
            if (!q) return true
            [ev.enumId, ev.enumCode, ev.description].find { it?.toString()?.toLowerCase()?.contains(q) }
        }.collect { Map ev ->
            ev.enumId as String
        }.findAll { it }
                .unique()
                .sort()
                .take(limit)
    }

    protected List<String> queryStatusValues(String prefix, int limit, String statusTypeId) {
        if (!ec.entity.isEntityDefined('moqui.basic.StatusItem')) return []
        def find = ec.entity.find('moqui.basic.StatusItem')
        if (statusTypeId) find.condition('statusTypeId', statusTypeId)
        List<Map> rows = find.list().take(250) as List<Map>
        String q = prefix?.toLowerCase() ?: ''
        return rows.findAll { Map ev ->
            if (!q) return true
            [ev.statusId, ev.description].find { it?.toString()?.toLowerCase()?.contains(q) }
        }.collect { Map ev ->
            ev.statusId as String
        }.findAll { it }
                .unique()
                .sort()
                .take(limit)
    }

    protected List<String> queryEntityOptionsValues(Map lookupMeta, String prefix, int limit) {
        String entityName = lookupMeta.entityName as String
        if (!entityName || !ec.entity.isEntityDefined(entityName)) return []
        String keyField = lookupMeta.keyField as String
        Map<String, Object> baseConditions = [:]
        ((Collection) lookupMeta.conditions ?: []).each { Map cond ->
            if (!cond?.fieldName) return
            if (cond.value != null && cond.value != '') baseConditions[cond.fieldName as String] = cond.value
        }
        List<Map> rows = ec.entity.find(entityName).list().take(250) as List<Map>
        rows = rows.findAll { Map ev -> baseConditions.every { String k, Object v -> ev[k]?.toString() == v?.toString() } }
        String q = prefix?.toLowerCase() ?: ''
        return rows.findAll { Map ev ->
            if (!q) return true
            List<String> probeValues = []
            if (keyField && ev[keyField] != null) probeValues.add(ev[keyField].toString())
            String partyName = renderPartyName(ev)
            if (partyName) probeValues.add(partyName)
            probeValues.addAll(extractTemplateFields(lookupMeta.textTemplate as String).collect { String fieldName -> ev[fieldName]?.toString() }.findAll { it })
            probeValues.find { it?.toLowerCase()?.contains(q) }
        }.collect { Map ev ->
            keyField && ev[keyField] != null ? ev[keyField].toString() : null
        }.findAll { it }
                .unique()
                .sort()
                .take(limit)
    }

    protected static List<String> extractTemplateFields(String template) {
        if (!template) return []
        def matcher = (template =~ /\$\{([^}]+)\}/)
        List<String> fields = []
        while (matcher.find()) {
            String expr = matcher.group(1)
            if (!expr) continue
            String fieldName = expr.tokenize(' ?:()[]').find { it ==~ /[A-Za-z_][A-Za-z0-9_]*/ }
            if (fieldName) fields.add(fieldName)
        }
        return fields.unique()
    }

    protected static String renderPartyName(Map ev) {
        String organizationName = ev['organizationName'] as String
        if (organizationName) return organizationName
        String firstName = ev['firstName'] as String
        String lastName = ev['lastName'] as String
        if (firstName || lastName) return [firstName, lastName].findAll { it }.join(' ')
        return null
    }

    protected List<String> queryColumnValues(String entityName, String fieldName, String prefix, int limit, Map<String, Object> conditions) {
        if (!ec.entity.isEntityDefined(entityName)) return []
        def find = ec.entity.find(entityName)
        (conditions ?: [:]).each { String key, Object value -> find.condition(key, value) }
        List<Map> rows = find.list().take(250) as List<Map>
        return rows.collect { it[fieldName] as String }
                .findAll { it && it.toLowerCase().startsWith(prefix.toLowerCase()) }
                .unique()
                .sort()
                .take(limit)
    }

    protected List<String> queryProductValues(String prefix, int limit) {
        List<Map> rows = ec.entity.find('mantle.product.Product').list().take(250) as List<Map>
        return rows.collectMany { Map ev ->
            [ev.productId as String, ev.productName as String, ev.pseudoId as String].findAll { it }
        }.findAll { it.toLowerCase().startsWith(prefix.toLowerCase()) }
                .unique()
                .sort()
                .take(limit)
    }

    protected List<String> queryPartyValues(String prefix, int limit) {
        List<String> values = []
        List<Map> people = ec.entity.find('mantle.party.Person').list().take(250) as List<Map>
        values.addAll(people.collect { Map ev -> [ev.firstName, ev.lastName].findAll { it }.join(' ') }.findAll { it })
        List<Map> orgs = ec.entity.find('mantle.party.Organization').list().take(250) as List<Map>
        values.addAll(orgs.collect { it.organizationName as String }.findAll { it })
        return values.findAll { it.toLowerCase().startsWith(prefix.toLowerCase()) }
                .unique()
                .sort()
                .take(limit)
    }
}
