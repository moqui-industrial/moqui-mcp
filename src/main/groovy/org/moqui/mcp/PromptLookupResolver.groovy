package org.moqui.mcp

import org.moqui.context.ExecutionContext

class PromptLookupResolver {
    protected final ExecutionContext ec

    PromptLookupResolver(ExecutionContext ec) {
        this.ec = ec
    }

    Map resolveArguments(Map descriptor, Map<String, String> rawArguments) {
        Map<String, String> resolved = [:]
        Map<String, Map> resolutionMeta = [:]
        (rawArguments ?: [:]).each { String name, String rawValue ->
            if (rawValue == null) return
            Map result = resolveArgument(name, rawValue, rawArguments, descriptor)
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

        return [
                resultType: 'complete',
                completion: [
                        values : values.unique(),
                        total  : values.unique().size(),
                        hasMore: false
                ]
        ]
    }

    protected Map resolveArgument(String argumentName, String rawValue, Map<String, String> rawArguments, Map descriptor) {
        String trimmed = rawValue?.trim()
        if (!trimmed) return [value: rawValue, matched: false]

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

    protected Map resolveStatus(String rawValue) {
        return resolveByUniqueMatch('moqui.basic.StatusItem', 'statusId', [
                [field: 'statusId', operator: 'equals'],
                [field: 'description', operator: 'equalsIgnoreCase']
        ], rawValue)
    }

    protected Map resolveEnumeration(String rawValue) {
        return resolveByUniqueMatch('moqui.basic.Enumeration', 'enumId', [
                [field: 'enumId', operator: 'equals'],
                [field: 'enumCode', operator: 'equalsIgnoreCase'],
                [field: 'description', operator: 'equalsIgnoreCase']
        ], rawValue)
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

    protected Map resolveByUniqueMatch(String entityName, String idField, List<Map> probes, String rawValue) {
        List<Map> matches = []
        for (Map probe in probes) {
            def find = ec.entity.find(entityName)
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
