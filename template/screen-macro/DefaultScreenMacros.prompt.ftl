<#--
    Screen interaction extractor for Moqui MCP.
    This template is not meant for human output; it traverses a rendered screen and
    stores interaction-unit metadata in ec.context.promptRenderInteractions.
-->

<#assign promptRenderInteractions = (ec.context.promptRenderInteractions![])>

<#macro @element></#macro>

<#macro screen>
    <#recurse>
    <#assign dummy = ec.context.put("promptRenderInteractions", promptRenderInteractions)!>
</#macro>

<#macro widgets><#recurse></#macro>
<#macro "fail-widgets"><#recurse></#macro>
<#macro container><#recurse></#macro>
<#macro "container-box"><#recurse></#macro>
<#macro "container-row"><#recurse></#macro>
<#macro "container-panel"><#recurse></#macro>
<#macro "container-dialog"><#recurse></#macro>
<#macro section>${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-iterate">${sri.renderSection(.node["@name"])}</#macro>
<#macro "section-include">${sri.renderSectionInclude(.node)}</#macro>

<#macro "form-single">
    <@captureInteraction "form-single"/>
</#macro>

<#macro "form-list">
    <@captureInteraction "form-list"/>
</#macro>

<#macro captureInteraction formType>
    <#assign formName = (.node["@name"]!"")>
    <#if !formName?has_content><#return></#if>
    <#assign formNode = sri.getFormNode(formName)!>
    <#if !formNode?has_content><#return></#if>
    <#assign transitionName = formNode["@transition"]!"">
    <#assign interactionKind = inferInteractionKind(formType, transitionName)>
    <#assign fieldList = []>
    <#list formNode["field"]! as fieldNode>
        <#assign fieldName = fieldNode["@name"]!"">
        <#if !fieldName?has_content || fieldName == "submitButton"><#continue></#if>
        <#assign fieldTitle = fieldName>
        <#assign defaultField = fieldNode["default-field"][0]!>
        <#assign headerField = fieldNode["header-field"][0]!>
        <#if defaultField?has_content && defaultField["@title"]?has_content>
            <#assign fieldTitle = defaultField["@title"]>
        <#elseif headerField?has_content && headerField["@title"]?has_content>
            <#assign fieldTitle = headerField["@title"]>
        </#if>
        <#assign fieldType = inferFieldType(fieldNode)>
        <#assign fieldRequired = (fieldNode["@required"]!"false") == "true" || (fieldNode["@validate-required"]!"false") == "true">
        <#assign fieldList = fieldList + [[
            "name": fieldName,
            "title": fieldTitle,
            "fieldType": fieldType,
            "required": fieldRequired
        ]]>
    </#list>
    <#assign promptRenderInteractions = promptRenderInteractions + [[
        "formName": formName,
        "transitionName": transitionName,
        "formType": formType,
        "interactionKind": interactionKind,
        "fieldCount": fieldList?size,
        "fields": fieldList
    ]]>
    <#assign dummy = ec.context.put("promptRenderInteractions", promptRenderInteractions)!>
</#macro>

<#function inferInteractionKind formType transitionName>
    <#assign normalized = (transitionName!"")?lower_case>
    <#if formType == "form-list">
        <#if normalized?starts_with("get") || normalized?contains("list") || normalized?contains("find")>
            <#return "query">
        </#if>
        <#return "browse">
    </#if>
    <#if normalized?starts_with("create")><#return "create"></#if>
    <#if normalized?starts_with("update") || normalized?starts_with("edit")><#return "update"></#if>
    <#if normalized?starts_with("delete") || normalized?starts_with("remove")><#return "delete"></#if>
    <#if normalized?starts_with("get") || normalized?contains("list") || normalized?contains("find")><#return "query"></#if>
    <#return "action">
</#function>

<#function inferFieldType fieldNode>
    <#assign defaultField = fieldNode["default-field"][0]!>
    <#assign headerField = fieldNode["header-field"][0]!>
    <#assign fieldDef = defaultField?has_content?then(defaultField, headerField)>
    <#if !fieldDef?has_content><#return "unknown"></#if>
    <#if fieldDef["text-line"]?has_content><#return "text"></#if>
    <#if fieldDef["text-area"]?has_content><#return "textarea"></#if>
    <#if fieldDef["drop-down"]?has_content><#return "dropdown"></#if>
    <#if fieldDef["date-find"]?has_content || fieldDef["date-time"]?has_content><#return "datetime"></#if>
    <#if fieldDef["text-find"]?has_content><#return "search-text"></#if>
    <#if fieldDef["submit"]?has_content><#return "submit"></#if>
    <#return "display">
</#function>
