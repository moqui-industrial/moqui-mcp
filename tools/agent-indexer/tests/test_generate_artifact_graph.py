from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_artifact_graph import build_graph  # noqa: E402


def test_build_graph_creates_expected_vertices_and_edges():
    screen_docs = [
        {
            "documentId": "agent-prompt://accounting/editinvoice/updateinvoice",
            "documentKind": "screen_prompt",
            "canonicalPrompt": "update invoice",
            "area": "Accounting",
            "domainObject": "Invoice",
            "sourceScreenPath": "Accounting/EditInvoice.xml",
            "screenName": "EditInvoice",
            "transitionNames": ["updateInvoice"],
            "boundServices": ["mantle.account.InvoiceServices.update#Invoice"],
            "preferredService": "mantle.account.InvoiceServices.update#Invoice",
            "mutative": True,
            "fieldLabelDetails": [
                {
                    "name": "dueDate",
                    "entityName": "mantle.account.invoice.Invoice",
                }
            ],
            "linkedServiceStatements": [
                "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/001"
            ],
        }
    ]
    service_statement_docs = [
        {
            "statementId": "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/001",
            "serviceName": "mantle.account.InvoiceServices.update#Invoice",
            "statementVerb": "entity-find-one",
            "statementPath": "actions/001",
            "subjectKind": "entity",
            "subject": "mantle.account.invoice.Invoice",
            "complements": [{"name": "invoiceId", "role": "lookup_key"}],
        },
        {
            "statementId": "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/002",
            "serviceName": "mantle.account.InvoiceServices.update#Invoice",
            "statementVerb": "set",
            "statementPath": "actions/002",
            "subjectKind": "none",
            "subject": None,
            "complements": [{"name": "dueDate", "role": "updated_field"}],
        },
    ]
    service_docs = [
        {
            "serviceName": "mantle.account.InvoiceServices.update#Invoice",
            "sourceArtifactUri": "service://mantle.account.InvoiceServices.update#Invoice",
            "statementIds": [
                "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/001",
                "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/002",
            ],
            "readEntities": ["mantle.account.invoice.Invoice"],
            "writtenEntities": ["mantle.account.invoice.Invoice"],
            "calledServices": ["mantle.account.InvoiceServices.store#Invoice"],
            "serviceComplements": ["invoiceId", "dueDate"],
        }
    ]
    knowledge_docs = [
        {
            "documentId": "agent-scenario://accounting/demo/invoice",
            "documentKind": "seed_scenario",
            "canonicalPrompt": "understand invoice demo data",
            "area": "Accounting",
            "domainObject": "Invoice",
            "relatedEntities": ["mantle.account.invoice.Invoice"],
            "relatedAgentPrompts": ["agent-prompt://accounting/editinvoice/updateinvoice"],
        }
    ]

    vertices, edges, summary = build_graph(
        screen_docs,
        service_statement_docs,
        service_docs,
        knowledge_docs,
        "AgentArtifactGraph",
        "AgentMoquiRagModel_v1",
        "AgentArtifactGraphGenerationModelDef",
        "artifact-graph-v1",
    )

    vertex_ids = {vertex["vertexId"] for vertex in vertices}
    vertex_map = {vertex["vertexId"]: vertex for vertex in vertices}
    edge_keys = {(edge["fromVertexId"], edge["toVertexId"], edge["edgeType"]) for edge in edges}

    assert "screen://Accounting/EditInvoice.xml" in vertex_ids
    assert "transition://Accounting/EditInvoice.xml#updateInvoice" in vertex_ids
    assert "service://mantle.account.InvoiceServices.update#Invoice" in vertex_ids
    assert "entity://mantle.account.invoice.Invoice" in vertex_ids
    assert "field://mantle.account.invoice.Invoice.dueDate" in vertex_ids

    assert (
        "screen://Accounting/EditInvoice.xml",
        "transition://Accounting/EditInvoice.xml#updateInvoice",
        "SCREEN_HAS_TRANSITION",
    ) in edge_keys
    assert (
        "transition://Accounting/EditInvoice.xml#updateInvoice",
        "service://mantle.account.InvoiceServices.update#Invoice",
        "TRANSITION_CALLS_SERVICE",
    ) in edge_keys
    assert (
        "service://mantle.account.InvoiceServices.update#Invoice",
        "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/001",
        "SERVICE_HAS_STATEMENT",
    ) in edge_keys
    assert (
        "service://mantle.account.InvoiceServices.update#Invoice",
        "entity://mantle.account.invoice.Invoice",
        "SERVICE_READS_ENTITY",
    ) in edge_keys
    assert (
        "service://mantle.account.InvoiceServices.update#Invoice",
        "entity://mantle.account.invoice.Invoice",
        "SERVICE_WRITES_ENTITY",
    ) in edge_keys
    assert (
        "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/001",
        "field://mantle.account.invoice.Invoice.invoiceId",
        "STATEMENT_HAS_COMPLEMENT",
    ) in edge_keys
    statement_vertex = vertex_map["statement://service/mantle.account.InvoiceServices.update#Invoice/actions/001"]
    assert statement_vertex["xmlActionElement"] == "entity-find-one"
    assert statement_vertex["subject"] == "mantle.account.invoice.Invoice"
    assert summary["vertexCount"] >= 8
    assert summary["edgeCount"] >= 8
    assert summary["graphId"] == "AgentArtifactGraph"
    assert summary["mathModelId"] == "AgentMoquiRagModel_v1"
    assert summary["modelDefId"] == "AgentArtifactGraphGenerationModelDef"
