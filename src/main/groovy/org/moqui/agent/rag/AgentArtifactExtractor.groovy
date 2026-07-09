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
package org.moqui.agent.rag

import groovy.xml.XmlSlurper

class AgentArtifactExtractor {

    List<Map> extractArtifacts(Map manifestItem) {
        String artifactType = (manifestItem.artifactType ?: '').toString().toLowerCase()
        File file = new File((manifestItem.filePath ?: '').toString())
        if (!file.exists()) return []

        switch (artifactType) {
            case 'entity': return extractEntityArtifacts(file)
            case 'service': return extractServiceArtifacts(file)
            case 'screen': return extractScreenArtifacts(file)
            case 'process': return extractUbplArtifacts(file)
            case 'xsd': return extractXsdArtifacts(file)
            default: return extractMarkdownArtifacts(file)
        }
    }

    List<Map> extractEntityArtifacts(File f) {
        def xml = new XmlSlurper(false, false).parse(f)
        List<Map> out = []
        xml.'entity'.each { e ->
            String entityName = e.@'entity-name'?.text()
            String packageName = e.@'package'?.text()
            String description = e.description?.text()
            def fieldNames = e.field.collect { it.@name?.text() }.findAll { it }

            out.add([
                artifactType: 'entity',
                artifactName: packageName ? "${packageName}.${entityName}" : entityName,
                packageName: packageName,
                title: entityName,
                summary: description ?: "Entity ${entityName}",
                content: "Entity ${entityName}\nFields: ${fieldNames.join(', ')}",
                entityNames: [packageName ? "${packageName}.${entityName}" : entityName],
                tags: ['entity']
            ])
        }
        xml.'extend-entity'.each { e ->
            String fullEntityName = e.@'entity-name'?.text()
            out.add([
                artifactType: 'entity',
                artifactName: fullEntityName,
                packageName: e.@'package'?.text(),
                title: fullEntityName,
                summary: "Entity extension for ${fullEntityName}",
                content: "Extend entity ${fullEntityName}",
                entityNames: [fullEntityName],
                tags: ['entity', 'extension']
            ])
        }
        out
    }

    List<Map> extractServiceArtifacts(File f) {
        def xml = new XmlSlurper(false, false).parse(f)
        List<Map> out = []
        xml.'service'.each { s ->
            String verb = s.@verb?.text()
            String noun = s.@noun?.text()
            String authenticate = s.@authenticate?.text() ?: 'true'
            String serviceName = [verb, noun].findAll { it }.join('#')
            String description = s.description?.text()

            List<String> inParamLines = []
            s.'in-parameters'.parameter.each { p ->
                String pName = p.@name?.text()
                if (!pName) return
                String pType = p.@type?.text() ?: 'String'
                boolean pRequired = p.@required?.text() == 'true'
                String pDefault = p.@'default-value'?.text() ?: p.@'default'?.text()
                String line = "${pName}:${pType}"
                if (pRequired) line += " (required)"
                if (pDefault) line += " [default: ${pDefault}]"
                inParamLines << line
            }

            List<String> outParamLines = []
            s.'out-parameters'.parameter.each { p ->
                String pName = p.@name?.text()
                if (!pName) return
                String pType = p.@type?.text() ?: 'String'
                outParamLines << "${pName}:${pType}"
            }

            List<String> contentLines = ["Service ${serviceName} (authenticate=${authenticate})"]
            if (description) contentLines << "Description: ${description}"
            if (inParamLines) contentLines << "In-parameters:\n  ${inParamLines.join('\n  ')}"
            if (outParamLines) contentLines << "Out-parameters:\n  ${outParamLines.join('\n  ')}"

            out.add([
                artifactType: 'service',
                artifactName: serviceName,
                title: serviceName,
                summary: description ?: "Service ${serviceName}",
                content: contentLines.join('\n'),
                serviceNames: [serviceName],
                tags: ['service']
            ])
        }
        out
    }

    List<Map> extractScreenArtifacts(File f) {
        def xml = new XmlSlurper(false, false).parse(f)
        String screenName = xml.@name?.text() ?: f.name

        // Collect transitions: name + service calls they invoke
        List<String> transitionLines = []
        List<String> referencedServices = []
        xml.transition.each { t ->
            String tName = t.@name?.text()
            if (!tName) return
            List<String> calls = []
            t.'service-call'.each { sc ->
                String svc = sc.@name?.text()
                if (svc) { calls << svc; referencedServices << svc }
            }
            transitionLines << (calls ? "${tName} → ${calls.join(', ')}" : tName)
        }

        // Collect subscreens
        List<String> subscreens = []
        xml.subscreens.'subscreens-item'.each { si ->
            String sName = si.@name?.text()
            if (sName) subscreens << sName
        }

        List<String> contentLines = ["Screen: ${screenName}"]
        if (transitionLines) contentLines << "Transitions:\n  ${transitionLines.join('\n  ')}"
        if (subscreens) contentLines << "Subscreens: ${subscreens.join(', ')}"

        [[
            artifactType: 'screen',
            artifactName: screenName,
            title: screenName,
            summary: "Screen ${screenName}${transitionLines ? " with transitions: ${transitionLines.take(3).join('; ')}" : ''}",
            content: contentLines.join('\n'),
            screenPaths: [f.path],
            serviceNames: referencedServices ?: null,
            tags: ['screen']
        ]]
    }

    List<Map> extractUbplArtifacts(File f) {
        def xml = new XmlSlurper(false, false).parse(f)
        List<Map> out = []
        xml.depthFirst().findAll { it.name().toString().toLowerCase().contains('process') }.each { p ->
            String processName = p.@name?.text() ?: p.@processName?.text() ?: f.name
            out.add([
                artifactType: 'process',
                artifactName: processName,
                title: processName,
                summary: "Process ${processName}",
                content: "Process definition in ${f.name}",
                processNames: [processName],
                tags: ['process']
            ])
        }
        out ?: [[
            artifactType: 'process',
            artifactName: f.name,
            title: f.name,
            summary: "Process file ${f.name}",
            content: f.text,
            processNames: [f.name],
            tags: ['process']
        ]]
    }

    List<Map> extractXsdArtifacts(File f) {
        def xml = new XmlSlurper(false, false).parse(f)
        String schemaName = f.name.replace('.xsd', '')

        // Collect xs:element and xs:complexType names (= Moqui XML element/attribute vocabulary)
        List<String> elementNames = []
        List<String> typeNames = []
        List<String> docTexts = []

        xml.depthFirst().each { node ->
            String localName = node.name().toString()
            if (localName == 'element') {
                String n = node.@name?.text()
                if (n) elementNames << n
            } else if (localName == 'complexType' || localName == 'simpleType') {
                String n = node.@name?.text()
                if (n) typeNames << n
            } else if (localName == 'documentation') {
                String t = node.text()?.trim()
                if (t) docTexts << t
            }
        }

        String description = docTexts ? docTexts.take(3).join(' ') : "XSD schema definition for ${schemaName}"
        String content = [
            "XSD schema: ${schemaName}",
            elementNames ? "Elements: ${elementNames.take(40).join(', ')}" : null,
            typeNames ? "Types: ${typeNames.take(20).join(', ')}" : null,
            description
        ].findAll { it }.join('\n')

        [[
            artifactType: 'xsd',
            artifactName: schemaName,
            title: "${schemaName} schema",
            summary: description,
            content: content,
            tags: ['xsd', 'schema']
        ]]
    }

    List<Map> extractMarkdownArtifacts(File f) {
        [[
            artifactType: 'doc',
            artifactName: f.name,
            title: f.name,
            summary: "Documentation ${f.name}",
            content: f.text,
            tags: ['doc']
        ]]
    }
}
