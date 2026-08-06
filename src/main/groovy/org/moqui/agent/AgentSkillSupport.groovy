package org.moqui.agent

import org.moqui.context.ExecutionContext

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AgentSkillSupport {
    static void ensureSkillLookupData(ExecutionContext ec) {
        ensureArtifactGroup(ec, 'McpAgentSkills', 'Moqui Agent Skill registry and skill file artifacts')
        ensureEnumeration(ec, 'ArtifactType', 'AT_AGENT_SKILL', 'Agent Skill')
        ensureEnumerationType(ec, 'AgentSkillType', 'Agent Skill Type')
        ensureEnumeration(ec, 'AgentSkillType', 'AGSKL_BUSINESS_PROCESS', 'Business Process Skill')
        ensureEnumeration(ec, 'AgentSkillType', 'AGSKL_PATTERN', 'Parametric Pattern Skill')
        ensureEnumeration(ec, 'AgentSkillType', 'AGSKL_DOMAIN', 'Domain Semantic Skill')
        ensureEnumeration(ec, 'AgentSkillType', 'AGSKL_SYSTEM', 'System Skill')

        ensureStatusType(ec, 'AgentSkillStatus', 'Agent Skill Status')
        ensureStatusItem(ec, 'AgentSkillStatus', 'SkillActive', 'Active', 10L)
        ensureStatusItem(ec, 'AgentSkillStatus', 'SkillDraft', 'Draft', 20L)
        ensureStatusItem(ec, 'AgentSkillStatus', 'SkillDeprecated', 'Deprecated', 90L)
    }

    protected static void ensureEnumerationType(ExecutionContext ec, String enumTypeId, String description) {
        if (ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', enumTypeId).useCache(false).one()) return
        ec.entity.makeValue('moqui.basic.EnumerationType').setAll([
                enumTypeId: enumTypeId,
                description: description
        ]).create()
    }

    protected static void ensureArtifactGroup(ExecutionContext ec, String artifactGroupId, String description) {
        if (ec.entity.find('moqui.security.ArtifactGroup').condition('artifactGroupId', artifactGroupId).useCache(false).one()) return
        ec.entity.makeValue('moqui.security.ArtifactGroup').setAll([
                artifactGroupId: artifactGroupId,
                description: description
        ]).create()
    }

    protected static void ensureEnumeration(ExecutionContext ec, String enumTypeId, String enumId, String description) {
        if (ec.entity.find('moqui.basic.Enumeration').condition('enumId', enumId).useCache(false).one()) return
        ec.entity.makeValue('moqui.basic.Enumeration').setAll([
                enumTypeId: enumTypeId,
                enumId: enumId,
                description: description
        ]).create()
    }

    protected static void ensureStatusType(ExecutionContext ec, String statusTypeId, String description) {
        if (ec.entity.find('moqui.basic.StatusType').condition('statusTypeId', statusTypeId).useCache(false).one()) return
        ec.entity.makeValue('moqui.basic.StatusType').setAll([
                statusTypeId: statusTypeId,
                description: description
        ]).create()
    }

    protected static void ensureStatusItem(ExecutionContext ec, String statusTypeId, String statusId, String description, Long sequenceNum) {
        if (ec.entity.find('moqui.basic.StatusItem').condition('statusId', statusId).useCache(false).one()) return
        ec.entity.makeValue('moqui.basic.StatusItem').setAll([
                statusTypeId: statusTypeId,
                statusId: statusId,
                description: description,
                sequenceNum: sequenceNum
        ]).create()
    }

    static List<File> listSkillFiles(ExecutionContext ec, List<String> componentNameList = null) {
        String runtimePath = System.getProperty('moqui.runtime') ?: ec?.factory?.runtimePath
        if (!runtimePath) return []

        File componentBase = new File(runtimePath, 'component')
        if (!componentBase.exists() || !componentBase.isDirectory()) return []

        Set<String> allowedComponents = componentNameList ? componentNameList.collect { it?.toString() }.findAll { it } as Set<String> : null
        List<File> skillFileList = []

        componentBase.listFiles()?.each { File compDir ->
            if (!compDir?.isDirectory()) return
            if (allowedComponents != null && !allowedComponents.contains(compDir.name)) return
            File skillsDir = new File(compDir, 'skills')
            if (!skillsDir.exists() || !skillsDir.isDirectory()) return
            skillsDir.eachFileRecurse { File f ->
                if (f.isFile() && f.name == 'SKILL.md') skillFileList << f
            }
        }

        return skillFileList.sort { File a, File b -> a.absolutePath <=> b.absolutePath }
    }

    static Map parseSkillFile(File file) {
        String text = file?.exists() ? file.getText('UTF-8') : ''
        String frontmatterText = ''
        String bodyText = text
        def matcher = (text =~ /(?s)\A---\s*\r?\n(.*?)\r?\n---\s*\r?\n(.*)\z/)
        if (matcher.matches()) {
            frontmatterText = matcher.group(1) ?: ''
            bodyText = matcher.group(2) ?: ''
        }

        Map frontmatter = parseFrontmatter(frontmatterText)
        String runtimePath = System.getProperty('moqui.runtime') ?: ''
        PathSkillInfo pathInfo = derivePathInfo(file, runtimePath)

        String skillId = (frontmatter.skillId ?: frontmatter.artifactName ?: pathInfo.defaultSkillId ?: pathInfo.skillPath)?.toString()
        String title = trimToMedium(frontmatter.title ?: humanizeName(file?.parentFile?.name ?: skillId ?: file?.name ?: 'Skill'))
        String domainName = (frontmatter.domainName ?: frontmatter.domain ?: '')?.toString()
        String description = trimToMedium(frontmatter.description ?: '')
        String skillTypeEnumId = resolveSkillTypeEnumId(frontmatter)
        String statusId = (frontmatter.statusId ?: 'SkillActive')?.toString()
        String artifactName = trimToMedium(frontmatter.artifactName ?: file?.parentFile?.name ?: skillId)
        String componentName = trimToMedium(frontmatter.componentName ?: pathInfo.componentName ?: '')
        String skillLocation = trimToMedium(frontmatter.skillLocation ?: pathInfo.skillLocation ?: artifactName)
        String skillPath = trimToMedium(frontmatter.skillPath ?: pathInfo.skillPath ?: '')
        String searchText = buildSearchText(frontmatter, bodyText, artifactName, componentName, title, description, domainName, skillTypeEnumId, skillPath)
        String contentHash = trimToMedium(sha256(text ?: ''))

        return [
                skillId: skillId,
                title: title,
                description: description,
                domainName: domainName,
                skillTypeEnumId: skillTypeEnumId,
                statusId: statusId,
                artifactName: artifactName,
                artifactTypeEnumId: (frontmatter.artifactTypeEnumId ?: 'AT_AGENT_SKILL').toString(),
                artifactGroupId: (frontmatter.artifactGroupId ?: 'McpAgentSkills').toString(),
                componentName: componentName,
                skillLocation: skillLocation,
                skillPath: skillPath,
                frontmatterJson: JsonOutput.toJson(frontmatter),
                bodyText: bodyText,
                searchText: searchText,
                contentHash: contentHash,
                rawFrontmatterText: frontmatterText
        ]
    }

    static Map parseFrontmatter(String frontmatterText) {
        Map result = [:]
        if (!frontmatterText) return result

        String currentKey = null
        frontmatterText.eachLine { String rawLine ->
            String line = rawLine?.replaceAll(/\s+$/, '') ?: ''
            String trimmed = line.trim()
            if (!trimmed || trimmed.startsWith('#')) return

            if (trimmed.startsWith('- ')) {
                if (!currentKey) return
                Object existing = result[currentKey]
                List currentList = (existing instanceof List) ? (List) existing : []
                if (!(existing instanceof List)) result[currentKey] = currentList
                currentList << parseScalar(trimmed.substring(2).trim())
                return
            }

            int colonIdx = line.indexOf(':')
            if (colonIdx < 0) return
            currentKey = line.substring(0, colonIdx).trim()
            String value = line.substring(colonIdx + 1).trim()
            if (!value) {
                result[currentKey] = []
            } else {
                result[currentKey] = parseScalar(value)
            }
        }

        return result
    }

    static String resolveSkillTypeEnumId(Map frontmatter) {
        String raw = (frontmatter?.skillTypeEnumId ?: frontmatter?.skillType ?: '').toString().trim()
        if (!raw) return 'AGSKL_SYSTEM'
        switch (raw.toLowerCase()) {
            case 'business_process':
            case 'business-process':
            case 'business process':
            case 'process':
                return 'AGSKL_BUSINESS_PROCESS'
            case 'pattern':
            case 'parametric_pattern':
            case 'parametric-pattern':
                return 'AGSKL_PATTERN'
            case 'domain':
            case 'domain_semantic':
            case 'domain semantic':
                return 'AGSKL_DOMAIN'
            case 'system':
                return 'AGSKL_SYSTEM'
            default:
                return raw
        }
    }

    static String buildSearchText(Map frontmatter, String bodyText, Object... extras) {
        List<String> parts = []
        appendValue(parts, frontmatter)
        appendValue(parts, bodyText)
        extras?.each { appendValue(parts, it) }
        return parts.findAll { it?.trim() }.join('\n')
    }

    static String humanizeName(String name) {
        if (!name) return 'Skill'
        return name.replaceAll(/[-_]+/, ' ').replaceAll(/\b\w/) { it.toUpperCase() }
    }

    protected static void appendValue(List<String> parts, Object value) {
        if (value == null) return
        if (value instanceof Map || value instanceof Collection) {
            parts << JsonOutput.toJson(value)
        } else {
            parts << value.toString()
        }
    }

    protected static String trimToMedium(Object value) {
        String text = value?.toString() ?: ''
        return text.length() > 255 ? text.substring(0, 255) : text
    }

    protected static Object parseScalar(String value) {
        String trimmed = value?.trim()
        if (trimmed == null) return null
        if (trimmed.startsWith('"') && trimmed.endsWith('"') && trimmed.length() >= 2) return trimmed.substring(1, trimmed.length() - 1)
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) return trimmed.substring(1, trimmed.length() - 1)
        if (trimmed.equalsIgnoreCase('true')) return true
        if (trimmed.equalsIgnoreCase('false')) return false
        if (trimmed ==~ /^-?\d+$/) {
            try { return Long.parseLong(trimmed) } catch (Throwable ignored) { return trimmed }
        }
        if (trimmed ==~ /^-?\d+\.\d+$/) {
            try { return new BigDecimal(trimmed) } catch (Throwable ignored) { return trimmed }
        }
        return trimmed
    }

    static String sha256(String text) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] digest = md.digest((text ?: '').getBytes(StandardCharsets.UTF_8))
        StringBuilder sb = new StringBuilder(digest.length * 2)
        for (byte b in digest) sb.append(String.format('%02x', b))
        return sb.toString()
    }

    protected static PathSkillInfo derivePathInfo(File file, String runtimePath) {
        if (!file) return new PathSkillInfo()
        try {
            File runtimeComponentDir = runtimePath ? new File(runtimePath, 'component') : null
            if (runtimeComponentDir?.exists()) {
                java.nio.file.Path root = runtimeComponentDir.toPath().toRealPath()
                java.nio.file.Path skillFile = file.toPath().toRealPath()
                if (skillFile.startsWith(root)) {
                    java.nio.file.Path rel = root.relativize(skillFile)
                    if (rel.nameCount >= 2) {
                        String componentName = rel.getName(0).toString()
                        String skillRelPath = rel.subpath(1, rel.nameCount).toString().replace(File.separatorChar, '/' as char)
                        String skillLocation = "component://${componentName}/${skillRelPath}"
                        String skillIdBase = skillRelPath.replace(File.separatorChar, '/' as char).replaceAll(/\.md$/, '').replace('/', '.')
                        return new PathSkillInfo(componentName, skillRelPath, skillLocation, "skill.${componentName}.${skillIdBase}")
                    }
                }
            }
        } catch (Throwable ignored) { }
        return new PathSkillInfo(null, file.parent ?: '', file.absolutePath, file.name.replaceAll(/\.md$/, ''))
    }

    static Map buildExecutionPolicy(Map skill) {
        Map frontmatter = [:]
        if (skill?.frontmatter instanceof Map) {
            frontmatter.putAll((Map) skill.frontmatter)
        } else if (skill?.frontmatterJson) {
            try {
                Object parsed = new groovy.json.JsonSlurper().parseText(skill.frontmatterJson as String)
                if (parsed instanceof Map) frontmatter.putAll((Map) parsed)
            } catch (Throwable ignored) { }
        }

        List<String> patterns = normalizeStringList(frontmatter.patterns)
        List<String> aggregatePatternIds = normalizeStringList(frontmatter.aggregatePatternIds)
        List<String> preferredServices = normalizeStringList(frontmatter.preferredServices)
        List<String> preferredRootServices = normalizeStringList(frontmatter.preferredRootServices)
        List<String> preferredChildServices = normalizeStringList(frontmatter.preferredChildServices)
        List<String> preferredLookupServices = normalizeStringList(frontmatter.preferredLookupServices)
        List<String> refusalRules = normalizeStringList(frontmatter.refusalRules)
        List<Map> requiredInputs = parseRequiredInputSpecs(frontmatter.requiredInputs)
        String skillTypeEnumId = (skill?.skillTypeEnumId ?: frontmatter.skillTypeEnumId ?: frontmatter.skillType ?: '') as String
        String legacyFallbackDocumentId = (frontmatter.legacyFallbackDocumentId ?: frontmatter.rootDocumentId ?: '').toString().trim()
        String fallbackMode = (frontmatter.fallbackMode ?: frontmatter.fallbackPolicyMode ?: '').toString().trim().toLowerCase()

        boolean aggregateGuidance = patterns.any { it in ['aggregate', 'root-child', 'workflow-boundary', 'multi-root'] } || !aggregatePatternIds.isEmpty()
        boolean promptFallbackAllowed = !['none', 'disabled', 'forbid_prompt_search', 'no_prompt_fallback'].contains(fallbackMode)
        if (!fallbackMode) fallbackMode = legacyFallbackDocumentId ? 'legacy_prompt_optional' : 'none'
        if (!preferredRootServices && preferredServices) preferredRootServices = preferredServices
        List<String> resolvedPatternIds = aggregatePatternIds ?: inferPatternIds(frontmatter, patterns, legacyFallbackDocumentId, skill)

        return [
                frontmatter : frontmatter,
                patterns : patterns,
                aggregatePatternIds : resolvedPatternIds,
                preferredServices : preferredServices,
                preferredRootServices : preferredRootServices,
                preferredChildServices : preferredChildServices,
                preferredLookupServices : preferredLookupServices,
                requiredInputs : requiredInputs,
                refusalRules : refusalRules,
                skillTypeEnumId : skillTypeEnumId,
                aggregateGuidance : aggregateGuidance,
                promptFallbackAllowed : promptFallbackAllowed,
                fallbackMode : fallbackMode,
                legacyFallbackDocumentId : legacyFallbackDocumentId
        ]
    }

    static String resolveAggregateRootDocumentId(String queryText, Map selectedSkill = null) {
        Map policy = buildExecutionPolicy(selectedSkill)
        String rootDocumentId = (policy.legacyFallbackDocumentId ?: '').toString().trim()
        if (rootDocumentId) return rootDocumentId
        if (AgentToolSupport.looksLikeRequestHierarchyPrompt(queryText)) return 'agent-prompt://request/findrequest/createrequest'
        if (AgentToolSupport.looksLikeBudgetHierarchyPrompt(queryText)) return 'agent-prompt://accounting/findbudget/createbudget'
        if (AgentToolSupport.looksLikeFacilityHierarchyPrompt(queryText)) return 'agent-prompt://facility/findfacility/createfacility'
        if (AgentToolSupport.looksLikeOrderHierarchyPrompt(queryText)) return 'agent-prompt://order/findorder/createorder'
        if (AgentToolSupport.looksLikeRootChildHierarchyPrompt(queryText)) return 'agent-prompt://project/findproject/createproject'
        return null
    }

    static boolean shouldUseSkillDrivenAggregatePath(Map selectedSkill, String queryText) {
        if (!(selectedSkill instanceof Map) || !queryText) return false
        Map policy = buildExecutionPolicy(selectedSkill)
        if (!policy.aggregateGuidance) return false
        return AgentToolSupport.looksLikeRootChildHierarchyPrompt(queryText)
    }

    protected static List<String> normalizeStringList(Object raw) {
        if (raw instanceof Collection) {
            return ((Collection) raw).collect { it?.toString()?.trim() }.findAll { it } as List<String>
        }
        if (raw == null) return []
        String text = raw.toString().trim()
        return text ? [text] : []
    }

    static List<Map> parseRequiredInputSpecs(Object raw) {
        List<String> items = normalizeStringList(raw)
        List<Map> result = []
        items.each { String item ->
            List<String> parts = item.split(/\|/) as List<String>
            String name = parts ? parts[0]?.trim() : null
            if (!name) return
            Map spec = [name:name, requiredFor:'execute', missingPolicy:'ask_user', resolution:['explicitParameter']]
            parts.drop(1).each { String token ->
                String trimmed = token?.trim()
                if (!trimmed) return
                int eqIdx = trimmed.indexOf('=')
                if (eqIdx < 0) return
                String key = trimmed.substring(0, eqIdx).trim()
                String value = trimmed.substring(eqIdx + 1).trim()
                if (!key) return
                if (key == 'resolution') {
                    spec.resolution = value ? value.split(/\s*,\s*/).findAll { it } as List<String> : []
                } else {
                    spec[key] = value
                }
            }
            result.add(spec)
        }
        return result
    }

    static List<String> inferPatternIds(Map frontmatter, List<String> patterns = [], String legacyFallbackDocumentId = null, Map skill = null) {
        List<String> result = []
        String skillId = (skill?.skillId ?: frontmatter?.skillId ?: '') as String
        if (patterns.contains('budget') || skillId in ['mantle.budget-planning', 'moqui.pattern.budget-tree'] || legacyFallbackDocumentId?.contains('/findbudget/')) result.add('AggrBudgetTree')
        if (patterns.contains('order') || skillId in ['mantle.order-entry', 'moqui.pattern.order-header-part-item'] || legacyFallbackDocumentId?.contains('/findorder/')) result.add('AggrOrderHPI')
        if (patterns.contains('request') || skillId in ['mantle.support-request', 'moqui.pattern.request-sequence'] || legacyFallbackDocumentId?.contains('/findrequest/')) result.add('AggrReqSeq')
        if (patterns.contains('facility') || skillId == 'moqui.pattern.facility-hierarchy' || legacyFallbackDocumentId?.contains('/findfacility/')) result.add('AggrFacilityTree')
        if (patterns.contains('party') || skillId == 'moqui.pattern.party-specialization') result.add('AggrPartySpec')
        if (patterns.contains('project') || skillId?.contains('project') || legacyFallbackDocumentId?.contains('/findproject/')) result.add('AggrWorkEffTree')
        return result.unique()
    }

    static Map buildServiceBindings(Map skill) {
        Map policy = buildExecutionPolicy(skill)
        return [
                rootServices : (policy.preferredRootServices ?: []) as List<String>,
                childServices : (policy.preferredChildServices ?: []) as List<String>,
                lookupServices : (policy.preferredLookupServices ?: []) as List<String>,
                legacyServices : (policy.preferredServices ?: []) as List<String>
        ]
    }

    static List<Map> rerankSkillCandidates(List<Map> candidates, String queryText, String domainName = null) {
        if (!(candidates instanceof List) || candidates.isEmpty()) return []
        return candidates.collect { Map skill ->
            Map copy = [:]
            copy.putAll(skill ?: [:])
            copy._skillScore = scoreSkillCandidate(copy, queryText, domainName)
            return copy
        }.sort { Map a, Map b ->
            BigDecimal scoreA = (a._skillScore ?: 0) as BigDecimal
            BigDecimal scoreB = (b._skillScore ?: 0) as BigDecimal
            scoreB <=> scoreA
        }
    }

    static String preferredSkillIdForPrompt(String queryText) {
        if (!queryText) return null
        if (AgentToolSupport.looksLikeBudgetHierarchyPrompt(queryText)) return 'mantle.budget-planning'
        if (AgentToolSupport.looksLikeOrderHierarchyPrompt(queryText)) return 'mantle.order-entry'
        if (AgentToolSupport.looksLikeSupportRequestCreatePrompt(queryText)) return 'mantle.support-request'
        if (AgentToolSupport.looksLikeAssetMoveStatusPrompt(queryText)) return 'mantle.asset-movement'
        if (AgentToolSupport.looksLikeEmploymentPositionPrompt(queryText)) return 'mantle.employment-position-management'
        if (looksLikeContactMechanismPrompt(queryText)) return 'moqui.pattern.contact-mechanism'
        if (looksLikeLifecyclePrompt(queryText)) return 'moqui.pattern.status-lifecycle'
        if (looksLikeClassificationPrompt(queryText)) return 'moqui.pattern.classification-taxonomy'
        if (looksLikeContextualRolePrompt(queryText)) return 'moqui.pattern.contextual-role'
        if (looksLikeDeclarativeRolePrompt(queryText)) return 'moqui.pattern.declarative-role'
        if (looksLikeBusinessRulePrompt(queryText)) return 'moqui.pattern.business-rule'
        return null
    }

    static BigDecimal scoreSkillCandidate(Map skill, String queryText, String domainName = null) {
        if (!(skill instanceof Map)) return 0
        BigDecimal score = 0
        String skillTypeEnumId = (skill.skillTypeEnumId ?: '') as String
        String skillId = (skill.skillId ?: '') as String
        if (skillTypeEnumId == 'AGSKL_BUSINESS_PROCESS') score += 40
        if (skillTypeEnumId == 'AGSKL_PATTERN') score += 15
        if (domainName && ((skill.domainName ?: '') as String).equalsIgnoreCase(domainName)) score += 10

        Set<String> queryTerms = tokenizeSkillText(queryText)
        Set<String> titleTerms = tokenizeSkillText((skill.title ?: '') as String)
        Set<String> descTerms = tokenizeSkillText((skill.description ?: '') as String)
        Set<String> searchTerms = tokenizeSkillText((skill.searchText ?: '') as String)

        score += queryTerms.intersect(titleTerms).size() * 8
        score += queryTerms.intersect(descTerms).size() * 4
        score += queryTerms.intersect(searchTerms).size() * 2

        String query = (queryText ?: '').toLowerCase(Locale.ROOT)
        String title = (skill.title ?: '') as String
        String searchText = (skill.searchText ?: '') as String
        String joined = "${skillId} ${title} ${searchText}".toLowerCase(Locale.ROOT)
        if (query.contains('budget') && joined.contains('budget')) score += 20
        if (query.contains('order') && joined.contains('order')) score += 20
        if (query.contains('project') && joined.contains('project')) score += 20
        if (query.contains('request') && joined.contains('request')) score += 20

        if (AgentToolSupport.looksLikeBudgetHierarchyPrompt(queryText) && skillId == 'mantle.budget-planning') score += 60
        if (AgentToolSupport.looksLikeBudgetHierarchyPrompt(queryText) && skillId == 'moqui.pattern.budget-tree') score += 35
        if (AgentToolSupport.looksLikeOrderHierarchyPrompt(queryText) && skillId == 'mantle.order-entry') score += 60
        if (AgentToolSupport.looksLikeOrderHierarchyPrompt(queryText) && skillId == 'moqui.pattern.order-header-part-item') score += 35
        if (AgentToolSupport.looksLikeSupportRequestCreatePrompt(queryText) && skillId == 'mantle.support-request') score += 60
        if (AgentToolSupport.looksLikeSupportRequestCreatePrompt(queryText) && skillId == 'moqui.pattern.request-sequence') score += 35
        if (AgentToolSupport.looksLikeAssetMoveStatusPrompt(queryText) && skillId == 'mantle.asset-movement') score += 60
        if (AgentToolSupport.looksLikeAssetMoveStatusPrompt(queryText) && skillId == 'moqui.pattern.facility-hierarchy') score += 30
        if (AgentToolSupport.looksLikeEmploymentPositionPrompt(queryText) && skillId == 'mantle.employment-position-management') score += 60
        if (AgentToolSupport.looksLikeEmploymentPositionPrompt(queryText) && skillId == 'moqui.pattern.party-specialization') score += 30
        if (looksLikeContactMechanismPrompt(queryText) && skillId == 'moqui.pattern.contact-mechanism') score += 45
        if (looksLikeLifecyclePrompt(queryText) && skillId == 'moqui.pattern.status-lifecycle') score += 45
        if (looksLikeClassificationPrompt(queryText) && skillId == 'moqui.pattern.classification-taxonomy') score += 45
        if (looksLikeContextualRolePrompt(queryText) && skillId == 'moqui.pattern.contextual-role') score += 45
        if (looksLikeDeclarativeRolePrompt(queryText) && skillId == 'moqui.pattern.declarative-role') score += 45
        if (looksLikeBusinessRulePrompt(queryText) && skillId == 'moqui.pattern.business-rule') score += 45

        return score
    }

    static boolean looksLikeDeclarativeRolePrompt(String queryText) {
        String query = (queryText ?: '').toLowerCase(Locale.ROOT)
        if (!query) return false
        return (query.contains('role type') || query.contains('tipo ruolo') ||
                query.contains('party role') || query.contains('ruolo party') ||
                query.contains('defin') && query.contains('role')) &&
                !looksLikeContextualRolePrompt(queryText)
    }

    static boolean looksLikeContextualRolePrompt(String queryText) {
        String query = (queryText ?: '').toLowerCase(Locale.ROOT)
        if (!query) return false
        return query.contains('assignee') || query.contains('owner') || query.contains('approver') ||
                query.contains('manager') || query.contains('supplier') || query.contains('customer') ||
                query.contains('assegnat') || query.contains('responsab') || query.contains('approvator') ||
                query.contains('fornitore') || query.contains('cliente')
    }

    static boolean looksLikeClassificationPrompt(String queryText) {
        String query = (queryText ?: '').toLowerCase(Locale.ROOT)
        if (!query) return false
        return query.contains('classification') || query.contains('classific') ||
                query.contains('category') || query.contains('categoria') ||
                query.contains('type') || query.contains('tipo') ||
                query.contains('kind') || query.contains('purpose') || query.contains('scopo')
    }

    static boolean looksLikeLifecyclePrompt(String queryText) {
        String query = (queryText ?: '').toLowerCase(Locale.ROOT)
        if (!query) return false
        return query.contains('status') || query.contains('state') || query.contains('lifecycle') ||
                query.contains('transition') || query.contains('stato') || query.contains('transizione')
    }

    static boolean looksLikeContactMechanismPrompt(String queryText) {
        String query = (queryText ?: '').toLowerCase(Locale.ROOT)
        if (!query) return false
        return query.contains('address') || query.contains('indirizzo') ||
                query.contains('email') || query.contains('mail') ||
                query.contains('phone') || query.contains('telefono') ||
                query.contains('telecom') || query.contains('contact') || query.contains('contatto')
    }

    static boolean looksLikeBusinessRulePrompt(String queryText) {
        String query = (queryText ?: '').toLowerCase(Locale.ROOT)
        if (!query) return false
        return query.contains('policy') || query.contains('rule') || query.contains('vincolo') ||
                query.contains('regola') || query.contains('permission') || query.contains('autorizz') ||
                query.contains('allowed') || query.contains('forbidden') || query.contains('vietat')
    }

    protected static Set<String> tokenizeSkillText(String text) {
        return ((text ?: '').toLowerCase(Locale.ROOT).split(/[^\\p{L}\\p{Nd}]+/) as List<String>)
                .findAll { it && it.length() > 2 } as Set<String>
    }

    static class PathSkillInfo {
        final String componentName
        final String skillPath
        final String skillLocation
        final String defaultSkillId

        PathSkillInfo(String componentName = null, String skillPath = '', String skillLocation = '', String defaultSkillId = '') {
            this.componentName = componentName
            this.skillPath = skillPath
            this.skillLocation = skillLocation
            this.defaultSkillId = defaultSkillId
        }
    }
}
