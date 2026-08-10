# Entity Template Catalog

Recognize recurring Moqui data structures by scanning entity definitions and registering reusable templates instead of hardcoding case-by-case aggregate logic.

Use this skill when:

- a new entity family appears that is not yet mapped to an existing aggregate template
- the planner needs structural guidance derived from entity PK/FK/sequence patterns
- runtime behavior suggests a Moqui aggregate exists but no template has been cataloged yet

Core idea:

- treat Moqui entity definitions as the authoritative source
- discover reusable templates such as root-sequence-child, root-sequence-multilevel, self-parent hierarchy, and root-parent tree
- persist discovered templates in the catalog so later NL-to-morphism planning can reuse them

Primary service:

- `org.moqui.agent.AgentTemplateServices.refresh#DiscoveredDataTemplates`

Expected outcome:

- previously unseen Moqui structures become explicit catalog entries
- the planner can reason over a growing template library instead of embedding domain exceptions in runtime code
