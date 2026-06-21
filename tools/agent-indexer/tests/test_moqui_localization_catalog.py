from pathlib import Path
import sys
import tempfile

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from moqui_localization_catalog import build_catalog  # noqa: E402


LOCALIZATION_XML = """<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml>
    <moqui.basic.LocalizedMessage original="Update" locale="it" localized="Aggiorna"/>
    <moqui.basic.LocalizedEntityField entityName="mantle.account.invoice.Invoice" fieldName="dueDate" locale="it" localized="Data scadenza"/>
</entity-facade-xml>
"""


SCREEN_XML = """<?xml version="1.0" encoding="UTF-8"?>
<screen>
    <transition name="updateInvoice" text="Update"/>
    <widgets>
        <form-single name="EditInvoiceForm" transition="updateInvoice">
            <field name="dueDate"><default-field title="Due Date"/></field>
        </form-single>
    </widgets>
</screen>
"""


def test_build_catalog_extracts_messages_entity_fields_and_screen_labels():
    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        data_file = tmp_path / "data" / "CommonL10nData.xml"
        data_file.parent.mkdir(parents=True, exist_ok=True)
        data_file.write_text(LOCALIZATION_XML, encoding="utf-8")

        screen_file = tmp_path / "component" / "screen" / "SimpleScreens" / "Accounting" / "EditInvoice.xml"
        screen_file.parent.mkdir(parents=True, exist_ok=True)
        screen_file.write_text(SCREEN_XML, encoding="utf-8")

        catalog = build_catalog([tmp_path])

    assert catalog["message://Update"]["labels"]["it"] == "Aggiorna"
    assert catalog["entityField://mantle.account.invoice.Invoice.dueDate"]["labels"]["it"] == "Data scadenza"
    assert catalog["transition://Accounting/EditInvoice.xml#updateInvoice"]["labels"]["default"] == "Update"
    assert catalog["transition://Accounting/EditInvoice.xml#updateInvoice"]["labels"]["it"] == "Aggiorna"
    assert catalog["field://Accounting/EditInvoice.xml#EditInvoiceForm.dueDate"]["labels"]["default"] == "Due Date"
