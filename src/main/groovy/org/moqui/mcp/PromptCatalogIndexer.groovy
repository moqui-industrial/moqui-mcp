package org.moqui.mcp

import groovy.json.JsonOutput
import org.moqui.context.ExecutionContext

class PromptCatalogIndexer {
    static final String INDEX_NAME = 'moqui_agent_prompts_v1'
    static final String DOCUMENT_TYPE = 'McpPromptCatalogDocument'

    protected final ExecutionContext ec
    protected final CompositePromptProvider promptProvider
    protected final Map<String, Map> screenDescriptorByName

    PromptCatalogIndexer(ExecutionContext ec) {
        this.ec = ec
        this.promptProvider = new CompositePromptProvider(ec)
        this.screenDescriptorByName = buildScreenDescriptorMap()
    }

    Map refresh(Map args = [:]) {
        String indexName = ((args.indexName as String) ?: INDEX_NAME).trim()
        boolean recreateIndex = Boolean.TRUE == args.recreateIndex
        boolean includeWiki = args.includeWiki == null ? true : Boolean.TRUE == args.includeWiki
        boolean includeScreen = args.includeScreen == null ? true : Boolean.TRUE == args.includeScreen

        def elasticClient = ec.factory.elastic.getDefault()
        if (elasticClient == null) throw new IllegalStateException('Elastic/OpenSearch default client is not available')

        Map mappingSpec = buildIndexSpec()
        if (recreateIndex && elasticClient.indexExists(indexName)) {
            elasticClient.deleteIndex(indexName)
        }
        if (!elasticClient.indexExists(indexName)) {
            elasticClient.createIndex(indexName, null, (Map) mappingSpec.mappings, null, (Map) mappingSpec.settings)
        } else {
            elasticClient.putMapping(indexName, (Map) mappingSpec.mappings)
        }

        List<Map> allPrompts = collectPrompts(includeWiki, includeScreen)
        List<Map> docs = allPrompts.collect { Map prompt -> makePromptDocument(indexName, prompt) }
        if (docs) elasticClient.bulkIndex(indexName, 'doc_id', docs)

        return [
                indexName   : indexName,
                documentType: DOCUMENT_TYPE,
                promptCount : docs.size(),
                wikiCount   : docs.count { it.promptSource == 'wiki' },
                screenCount : docs.count { it.promptSource == 'screen' }
        ]
    }

    protected List<Map> collectPrompts(boolean includeWiki, boolean includeScreen) {
        Map promptResult = promptProvider.listPrompts([pageSize: 10000])
        List<Map> prompts = promptResult.prompts instanceof Collection ? new ArrayList<>((Collection<Map>) promptResult.prompts) : []
        return prompts.findAll { Map prompt ->
            String source = getPromptSource(prompt)
            (includeWiki && source == 'wiki') || (includeScreen && source == 'screen')
        }
    }

    protected Map makePromptDocument(String indexName, Map prompt) {
        String promptName = prompt.name as String
        String promptSource = getPromptSource(prompt)
        String promptTitle = (prompt.title ?: promptName) as String
        String promptDescription = (prompt.description ?: '') as String
        String canonicalPrompt = buildCanonicalPrompt(prompt)
        List<Map> arguments = prompt.arguments instanceof Collection ? ((Collection<Map>) prompt.arguments).collect { Map arg -> new LinkedHashMap(arg ?: [:]) } : []
        List<String> argumentNames = arguments.collect { Map arg -> arg.name as String }.findAll { it }
        List<String> requiredArgumentNames = arguments.findAll { Map arg -> Boolean.TRUE == arg.required }.collect { Map arg -> arg.name as String }
        List<String> boundServices = []
        List<String> relatedEntities = []
        List<String> lookupEntities = []
        List<String> lookupUriTemplates = []
        List<String> promptVariants = []
        List<Map> lookupBindings = []
        String sourceScreenPath
        String area
        String subArea
        String domainObject
        String actionKind
        String transitionName
        String executionMode

        if (promptSource == 'screen') {
            Map descriptor = screenDescriptorByName[promptName]
            if (descriptor != null) {
                sourceScreenPath = descriptor.screenLocation as String
                transitionName = descriptor.transitionName as String
                executionMode = descriptor.executionMode as String
                actionKind = inferActionKind(descriptor)
                boundServices = descriptor.serviceName ? [descriptor.serviceName as String] : []
                area = inferAreaFromScreen(descriptor.screenLocation as String)
                subArea = inferSubAreaFromScreen(descriptor.screenLocation as String)
                domainObject = inferDomainObject(descriptor)
                (descriptor.arguments instanceof Collection ? (Collection<Map>) descriptor.arguments : []).each { Map arg ->
                    Map lookup = arg.lookup instanceof Map ? (Map) arg.lookup : null
                    if (lookup) {
                        lookupBindings.add([
                                argumentName: arg.name,
                                lookupKind  : lookup.lookupKind,
                                entityName  : lookup.entityName,
                                enumTypeId  : lookup.enumTypeId,
                                statusTypeId: lookup.statusTypeId,
                                transition  : lookup.transition,
                                valueField  : lookup.valueField,
                                labelField  : lookup.labelField
                        ].findAll { it.value != null })
                        if (lookup.entityName) lookupEntities.add(lookup.entityName as String)
                        lookupUriTemplates.add("lookup://screen/${promptName}/${arg.name}?q={query}")
                    }
                }
            }
        } else {
            actionKind = inferActionKind(prompt)
            domainObject = inferDomainObjectFromWiki(promptName)
        }

        relatedEntities.addAll(lookupEntities)
        List<String> lookupArgumentNames = lookupBindings.collect { Map binding -> binding.argumentName as String }.findAll { it }
        promptVariants.addAll(buildPromptVariants(promptName, promptTitle, promptDescription, actionKind, domainObject))

        String promptUri = "prompt://${promptName}"
        String humanExplanation = buildHumanExplanation(prompt, promptSource, canonicalPrompt, lookupBindings, boundServices, sourceScreenPath)
        String body = buildPromptBody([
                promptName            : promptName,
                title                 : promptTitle,
                description           : promptDescription,
                canonicalPrompt       : canonicalPrompt,
                source                : promptSource,
                actionKind            : actionKind,
                domainObject          : domainObject,
                sourceScreenPath      : sourceScreenPath,
                transitionName        : transitionName,
                executionMode         : executionMode,
                boundServices         : boundServices,
                argumentNames         : argumentNames,
                requiredArgumentNames : requiredArgumentNames,
                lookupArgumentNames   : lookupArgumentNames,
                lookupUriTemplates    : lookupUriTemplates,
                lookupBindings        : lookupBindings,
                promptUri             : promptUri,
                humanExplanation      : humanExplanation
        ])

        return [
                doc_id              : promptName,
                documentId          : promptName,
                promptName          : promptName,
                title               : promptTitle,
                description         : promptDescription,
                canonicalPrompt     : canonicalPrompt,
                promptVariants      : promptVariants.unique(),
                humanExplanation    : humanExplanation,
                body                : body,
                promptUri           : promptUri,
                promptSource        : promptSource,
                runtimeExecutable   : promptSource == 'screen',
                sourceScreenPath    : sourceScreenPath,
                transitionNames     : transitionName ? [transitionName] : [],
                executionMode       : executionMode,
                preferredService    : boundServices ? boundServices[0] : null,
                boundServices       : boundServices.unique(),
                argumentNames       : argumentNames.unique(),
                requiredArgumentNames: requiredArgumentNames.unique(),
                lookupArgumentNames : lookupArgumentNames.unique(),
                lookupEntities      : lookupEntities.unique(),
                lookupUriTemplates  : lookupUriTemplates.unique(),
                lookupBindingsJson  : JsonOutput.toJson(lookupBindings),
                relatedEntities     : relatedEntities.unique(),
                area                : area,
                subArea             : subArea,
                domainObject        : domainObject,
                actionKind          : actionKind,
                embeddingText       : [promptTitle, canonicalPrompt, promptDescription, humanExplanation, body].findAll { it }.join('\n')
        ].findAll { it.value != null }
    }

    Map buildIndexSpec() {
        return [
                settings: [
                        analysis: [
                                normalizer: [
                                        lowercase_keyword: [
                                                type  : 'custom',
                                                filter: ['lowercase']
                                        ]
                                ]
                        ]
                ],
                mappings: [
                        properties: [
                                doc_id               : [type: 'keyword'],
                                documentId           : [type: 'keyword'],
                                promptName           : [type: 'keyword'],
                                title                : [type: 'text'],
                                description          : [type: 'text'],
                                canonicalPrompt      : [type: 'text'],
                                promptVariants       : [type: 'text'],
                                humanExplanation     : [type: 'text'],
                                body                 : [type: 'text'],
                                promptUri            : [type: 'keyword'],
                                promptSource         : [type: 'keyword'],
                                runtimeExecutable    : [type: 'boolean'],
                                sourceScreenPath     : [type: 'keyword'],
                                transitionNames      : [type: 'keyword'],
                                executionMode        : [type: 'keyword'],
                                preferredService     : [type: 'keyword'],
                                boundServices        : [type: 'keyword'],
                                argumentNames        : [type: 'keyword'],
                                requiredArgumentNames: [type: 'keyword'],
                                lookupArgumentNames  : [type: 'keyword'],
                                lookupEntities       : [type: 'keyword'],
                                lookupUriTemplates   : [type: 'keyword'],
                                lookupBindingsJson   : [type: 'text'],
                                relatedEntities      : [type: 'keyword'],
                                area                 : [type: 'keyword'],
                                subArea              : [type: 'keyword'],
                                domainObject         : [type: 'keyword'],
                                actionKind           : [type: 'keyword'],
                                embeddingText        : [type: 'text']
                        ]
                ]
        ]
    }

    protected static String getPromptSource(Map prompt) {
        String promptName = prompt.name as String
        return promptName?.startsWith('moqui.screen.') ? 'screen' : 'wiki'
    }

    protected static String buildCanonicalPrompt(Map prompt) {
        String title = (prompt.title ?: prompt.name ?: '') as String
        return McpClient.normalizePromptSearchText(title).replace(' ', ' ').trim()
    }

    protected static String inferActionKind(Map source) {
        String text = McpClient.normalizePromptSearchText("${source?.title ?: ''} ${source?.transitionName ?: ''} ${source?.name ?: ''}")
        if (text.contains(' create ' ) || text.startsWith('create ')) return 'create'
        if (text.contains(' update ') || text.startsWith('update ')) return 'update'
        if (text.contains(' delete ') || text.startsWith('delete ')) return 'delete'
        if (text.contains(' move ') || text.startsWith('move ')) return 'move'
        if (text.contains(' find ') || text.contains(' search ') || text.contains(' list ')) return 'list'
        return 'execute'
    }

    protected static String inferAreaFromScreen(String screenLocation) {
        List<String> tokens = tokenizePath(screenLocation)
        return tokens.size() > 2 ? tokens[2] : (tokens ? tokens[-1] : null)
    }

    protected static String inferSubAreaFromScreen(String screenLocation) {
        List<String> tokens = tokenizePath(screenLocation)
        return tokens.size() > 3 ? tokens[3] : null
    }

    protected static String inferDomainObject(Map descriptor) {
        String screenName = descriptor.screenName as String
        if (screenName?.startsWith('Find') && screenName.length() > 4) return screenName.substring(4)
        if (screenName) return screenName
        List<String> tokens = tokenizePath(descriptor.screenLocation as String)
        return tokens ? tokens[-1]?.replace('.xml', '') : null
    }

    protected static String inferDomainObjectFromWiki(String promptName) {
        List<String> tokens = (promptName ?: '').tokenize('.')
        return tokens ? tokens[-1] : null
    }

    protected static List<String> buildPromptVariants(String promptName, String promptTitle, String promptDescription, String actionKind, String domainObject) {
        List<String> variants = []
        if (promptTitle) variants.add(promptTitle)
        if (promptName) variants.add(promptName)
        if (promptDescription) variants.add(promptDescription)
        if (actionKind && domainObject) variants.add("${actionKind} ${domainObject}")
        if (domainObject) variants.add(domainObject)
        return variants.findAll { it }
    }

    protected static String buildHumanExplanation(Map prompt, String promptSource, String canonicalPrompt, List<Map> lookupBindings, List<String> boundServices, String sourceScreenPath) {
        List<String> bits = []
        bits.add("Prompt '${prompt.title ?: prompt.name}'")
        bits.add(promptSource == 'screen' ? 'is derived from a Moqui screen transition.' : 'is backed by Moqui wiki prompt content.')
        if (canonicalPrompt) bits.add("Primary intent: ${canonicalPrompt}.")
        if (sourceScreenPath) bits.add("Screen source: ${sourceScreenPath}.")
        if (boundServices) bits.add("Preferred submit service: ${boundServices[0]}.")
        if (lookupBindings) {
            String lookupText = lookupBindings.collect { Map binding ->
                String argName = binding.argumentName as String
                String kind = binding.lookupKind as String
                String target = (binding.entityName ?: binding.enumTypeId ?: binding.statusTypeId ?: binding.transition ?: '') as String
                target ? "${argName} via ${kind} on ${target}" : "${argName} via ${kind}"
            }.join('; ')
            bits.add("Lookup-backed fields: ${lookupText}.")
        }
        return bits.join(' ').trim()
    }

    protected static String buildPromptBody(Map info) {
        return """\
---
name: ${info.promptName}
title: ${info.title}
description: ${info.description ?: info.humanExplanation}
source: ${info.source}
actionKind: ${info.actionKind}
domainObject: ${info.domainObject}
promptUri: ${info.promptUri}
---
Prompt catalog entry for ${info.promptName}.

Canonical prompt: ${info.canonicalPrompt}
Human explanation: ${info.humanExplanation}
Source screen: ${info.sourceScreenPath ?: 'n/a'}
Transition: ${info.transitionName ?: 'n/a'}
Execution mode: ${info.executionMode ?: 'n/a'}
Bound services: ${(info.boundServices ?: []).join(', ')}
Arguments: ${(info.argumentNames ?: []).join(', ')}
Required arguments: ${(info.requiredArgumentNames ?: []).join(', ')}
Lookup-backed arguments: ${(info.lookupArgumentNames ?: []).join(', ')}
Lookup resource templates:
${(info.lookupUriTemplates ?: []).collect { "- ${it}" }.join('\n')}

Use prompts/get or moqui_get_prompt_contract with promptName=${info.promptName}.
When submit is confirmed, execute the bound Moqui tool described by the prompt contract.
""".stripIndent().trim()
    }

    protected static List<String> tokenizePath(String screenLocation) {
        if (!screenLocation) return []
        return screenLocation
                .replace('component://', '')
                .replace('.xml', '')
                .tokenize('/')
                .findAll { it }
    }

    protected Map<String, Map> buildScreenDescriptorMap() {
        ScreenPromptProvider screenProvider = new ScreenPromptProvider(ec)
        Map<String, Map> descriptorMap = [:]
        (screenProvider.compilePromptList() ?: []).each { Map descriptor ->
            if (descriptor?.name) descriptorMap[descriptor.name as String] = descriptor
        }
        return descriptorMap
    }
}
