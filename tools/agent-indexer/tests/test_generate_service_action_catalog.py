from pathlib import Path
import sys
import tempfile

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_service_action_catalog import parse_service_file, load_semantics  # noqa: E402


SERVICE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-definition-3.xsd">
    <service verb="update" noun="Invoice">
        <in-parameters>
            <parameter name="invoiceId"/>
            <parameter name="dueDate"/>
        </in-parameters>
        <actions>
            <entity-find-one entity-name="mantle.account.invoice.Invoice" value-field="invoice">
                <field-map field-name="invoiceId" from="invoiceId"/>
            </entity-find-one>
            <set field="invoice.dueDate" from="dueDate"/>
            <service-call name="mantle.account.InvoiceServices.update#Invoice" in-map="context"/>
        </actions>
    </service>
</services>
"""


def test_parse_service_file_extracts_statements():
    semantics = load_semantics()
    grammar = {
        "entity-find-one": {"attributes": ["entity-name", "value-field"], "children": ["field-map"]},
        "set": {"attributes": ["field", "from"], "children": []},
        "service-call": {"attributes": ["name", "in-map"], "children": ["field-map"]},
    }

    with tempfile.TemporaryDirectory() as tmp_dir:
        service_dir = Path(tmp_dir) / "service" / "mantle" / "account"
        service_dir.mkdir(parents=True, exist_ok=True)
        service_file = service_dir / "InvoiceServices.xml"
        service_file.write_text(SERVICE_XML, encoding="utf-8")

        statements, services = parse_service_file(service_file, grammar, semantics)

    assert len(services) == 1
    assert len(statements) == 3
    assert any(doc["statementVerb"] == "entity-find-one" for doc in statements)
    assert any(doc["calledService"] == "mantle.account.InvoiceServices.update#Invoice" for doc in statements)
    assert services[0]["readEntities"] == ["mantle.account.invoice.Invoice"]
