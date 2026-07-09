from pathlib import Path
import os
import tempfile
import sys

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from moqui_xsd_action_grammar import extract_action_grammar  # noqa: E402


def test_extract_action_grammar_has_service_call():
    moqui_root = os.environ.get("MOQUI_ROOT", "").strip()
    xsd_path = Path(moqui_root) / "framework/xsd/xml-actions-3.xsd" if moqui_root else None
    if not xsd_path or not xsd_path.exists():
        with tempfile.TemporaryDirectory() as temp_dir:
            xsd_path = Path(temp_dir) / "xml-actions-3.xsd"
            xsd_path.write_text(
                """<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
  <xs:element name="service-call">
    <xs:complexType>
      <xs:sequence>
        <xs:element name="field-map" minOccurs="0" maxOccurs="unbounded"/>
      </xs:sequence>
      <xs:attribute name="name" type="xs:string"/>
    </xs:complexType>
  </xs:element>
</xs:schema>
""",
                encoding="utf-8",
            )
            grammar = extract_action_grammar(xsd_path)
            assert "service-call" in grammar
            assert "name" in grammar["service-call"]["attributes"]
            assert "field-map" in grammar["service-call"]["children"]
            return
    grammar = extract_action_grammar(xsd_path)
    assert "service-call" in grammar
    assert "name" in grammar["service-call"]["attributes"]
    assert "field-map" in grammar["service-call"]["children"]
