from hashlib import sha1


def normalize_graph_object_id(graph_id: str, raw_id: str) -> str:
    return sha1(f"{graph_id}|{raw_id}".encode("utf-8")).hexdigest()


def test_graph_scoped_hash_ids_are_stable_and_distinct():
    raw_id = "service://mantle.account.InvoiceServices.update#Invoice"
    first = normalize_graph_object_id("AgentArtifactGraph", raw_id)
    second = normalize_graph_object_id("AgentArtifactGraph", raw_id)
    third = normalize_graph_object_id("AnotherGraph", raw_id)

    assert first == second
    assert first != third
