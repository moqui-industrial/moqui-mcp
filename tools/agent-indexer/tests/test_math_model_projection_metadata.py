from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from prepare_datadocument_projection import build_graph_indices, project_docs  # noqa: E402


def test_projection_docs_include_math_model_metadata():
    vertices_by_id, incoming_edges, incoming_vertices = build_graph_indices([], [])
    projected_rows, summary = project_docs(
        [{"documentId": "agent-prompt://demo/doc", "documentKind": "screen_prompt"}],
        vertices_by_id,
        incoming_edges,
        incoming_vertices,
        "AgentArtifactGraph",
        "AgentMoquiRagModel_v1",
        "MMR42",
        "AgentPromptEmbeddingTensor_v1",
    )

    projected = projected_rows[0]
    assert projected["mathModelId"] == "AgentMoquiRagModel_v1"
    assert projected["mathModelRunId"] == "MMR42"
    assert projected["embeddingTensorId"] == "AgentPromptEmbeddingTensor_v1"
    assert summary["mathModelId"] == "AgentMoquiRagModel_v1"
    assert summary["mathModelRunId"] == "MMR42"
