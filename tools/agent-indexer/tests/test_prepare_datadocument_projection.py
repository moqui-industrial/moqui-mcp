from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from prepare_datadocument_projection import build_graph_indices, project_docs  # noqa: E402


def test_project_docs_adds_graph_projection_metadata():
    vertex_rows = [
        {"vertexId": "agent-prompt://accounting/editinvoice/updateinvoice", "vertexType": "AgentDocument"},
        {"vertexId": "screen://Accounting/EditInvoice.xml", "vertexType": "Screen"},
    ]
    edge_rows = [
        {
            "edgeId": "graph-edge://1",
            "fromVertexId": "screen://Accounting/EditInvoice.xml",
            "toVertexId": "agent-prompt://accounting/editinvoice/updateinvoice",
            "edgeType": "AGENT_DOCUMENT_DERIVED_FROM",
        }
    ]
    docs = [
        {
            "documentId": "agent-prompt://accounting/editinvoice/updateinvoice",
            "documentKind": "screen_prompt",
            "sourceScreenPath": "Accounting/EditInvoice.xml",
        }
    ]

    vertices_by_id, incoming_edges, incoming_vertices = build_graph_indices(vertex_rows, edge_rows)
    projected_rows, summary = project_docs(
        docs,
        vertices_by_id,
        incoming_edges,
        incoming_vertices,
        "moqui.agent.artifactGraph.v1",
        "AgentMoquiRagModel_v1",
        "MMR1",
        "AgentPromptEmbeddingTensor_v1",
    )

    projected = projected_rows[0]
    assert projected["sourceGraphId"] == "moqui.agent.artifactGraph.v1"
    assert projected["sourceVertexId"] == "agent-prompt://accounting/editinvoice/updateinvoice"
    assert projected["sourceArtifactUri"] == "screen://Accounting/EditInvoice.xml"
    assert projected["derivedFromVertexIds"] == ["screen://Accounting/EditInvoice.xml"]
    assert projected["derivedFromEdgeIds"] == ["graph-edge://1"]
    assert projected["projectionDocumentType"] == "AgentArtifactDocument"
    assert projected["mathModelId"] == "AgentMoquiRagModel_v1"
    assert projected["mathModelRunId"] == "MMR1"
    assert projected["embeddingTensorId"] == "AgentPromptEmbeddingTensor_v1"
    assert summary["documentsWithSourceVertex"] == 1
