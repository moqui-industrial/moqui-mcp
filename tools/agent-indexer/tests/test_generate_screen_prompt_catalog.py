from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_screen_prompt_catalog import ServiceActionIndex, enrich_prompt_doc_with_service_actions  # noqa: E402


def test_enrich_prompt_doc_with_service_actions():
    doc = {
        "boundServices": ["mantle.account.InvoiceServices.update#Invoice"],
        "embeddingText": "Base embedding text.",
    }
    service_action_index = ServiceActionIndex(
        statements_by_service={
            "mantle.account.InvoiceServices.update#Invoice": [
                {
                    "statementId": "statement://invoice/001",
                    "complements": [{"name": "invoiceId"}, {"name": "dueDate"}],
                    "readEntities": ["mantle.account.invoice.Invoice"],
                    "writtenEntities": [],
                    "calledService": None,
                    "statementClass": "entity_read",
                    "operationEffect": "read_one",
                    "opaque": False,
                },
                {
                    "statementId": "statement://invoice/002",
                    "complements": [{"name": "dueDate"}],
                    "readEntities": [],
                    "writtenEntities": ["mantle.account.invoice.Invoice"],
                    "calledService": "mantle.account.InvoiceServices.store#Invoice",
                    "statementClass": "service_call",
                    "operationEffect": "delegated_operation",
                    "opaque": False,
                },
            ]
        },
        documents_by_service={
            "mantle.account.InvoiceServices.update#Invoice": {
                "serviceComplements": ["invoiceId", "dueDate", "statusId"],
                "readEntities": ["mantle.account.invoice.Invoice"],
                "writtenEntities": ["mantle.account.invoice.Invoice"],
                "calledServices": ["mantle.account.InvoiceServices.store#Invoice"],
                "statementClasses": ["entity_read", "service_call"],
                "operationEffects": ["delegated_operation", "read_one"],
            }
        },
    )

    enrich_prompt_doc_with_service_actions(doc, service_action_index)

    assert doc["linkedServiceStatements"] == ["statement://invoice/001", "statement://invoice/002"]
    assert doc["serviceComplements"] == ["dueDate", "invoiceId", "statusId"]
    assert doc["readEntities"] == ["mantle.account.invoice.Invoice"]
    assert doc["writtenEntities"] == ["mantle.account.invoice.Invoice"]
    assert doc["downstreamServices"] == ["mantle.account.InvoiceServices.store#Invoice"]
    assert doc["statementClasses"] == ["entity_read", "service_call"]
    assert doc["serviceOperationEffects"] == ["delegated_operation", "read_one"]
    assert "Service complements include dueDate, invoiceId, statusId." in doc["embeddingText"]
