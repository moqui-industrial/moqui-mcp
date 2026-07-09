package org.moqui.agent

import org.moqui.context.ExecutionContext

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AgentSkillSupport {
    static void ensureSkillLookupData(ExecutionContext ec) {
        ensureArtifactGroup(ec, 'McpAgentSkills', 'Moqui Agent Skill registry and skill file artifacts')
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
                artifactTypeEnumId: (frontmatter.artifactTypeEnumId ?: 'AT_OTHER').toString(),
                artifactGroupId: (frontmatter.artifactGroupId ?: 'McpRuntimeServices').toString(),
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
