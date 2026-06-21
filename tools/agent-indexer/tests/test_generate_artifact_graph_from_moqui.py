from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_artifact_graph_from_moqui import apply_xsd_relations, merge_edge_rows, merge_vertex_rows, parse_view_entity_file  # noqa: E402
from moqui_xsd_artifact_relations import extract_xsd_registry  # noqa: E402


def test_merge_vertex_rows_keeps_semantic_enrichment():
    base_rows = [
        {
            "vertexId": "service://mantle.account.InvoiceServices.create#InvoiceItem",
            "vertexType": "Service",
            "label": "create#InvoiceItem",
            "sourceArtifactUri": "component://mantle-usl/service/InvoiceServices.xml",
        }
    ]
    extra_rows = [
        {
            "vertexId": "service://mantle.account.InvoiceServices.create#InvoiceItem",
            "vertexType": "Service",
            "serviceName": "mantle.account.InvoiceServices.create#InvoiceItem",
            "serviceVerb": "create",
            "serviceNoun": "InvoiceItem",
        },
        {
            "vertexId": "statement://service/mantle.account.InvoiceServices.create#InvoiceItem/actions/001",
            "vertexType": "XmlAction",
            "label": "entity-find-one",
            "serviceName": "mantle.account.InvoiceServices.create#InvoiceItem",
        },
    ]

    merged = merge_vertex_rows(base_rows, extra_rows)
    merged_map = {row["vertexId"]: row for row in merged}

    service_row = merged_map["service://mantle.account.InvoiceServices.create#InvoiceItem"]
    assert service_row["sourceArtifactUri"] == "component://mantle-usl/service/InvoiceServices.xml"
    assert service_row["serviceVerb"] == "create"
    assert service_row["serviceNoun"] == "InvoiceItem"
    assert "statement://service/mantle.account.InvoiceServices.create#InvoiceItem/actions/001" in merged_map


def test_merge_edge_rows_deduplicates_same_relation_and_keeps_metadata():
    base_rows = [
        {
            "edgeId": "screen_has_transition::1",
            "edgeType": "SCREEN_HAS_TRANSITION",
            "fromVertexId": "screen://Accounting/EditInvoice.xml",
            "toVertexId": "transition://Accounting/EditInvoice.xml#updateInvoice",
            "label": "SCREEN_HAS_TRANSITION",
        }
    ]
    extra_rows = [
        {
            "edgeId": "screen_has_transition::alt",
            "edgeType": "SCREEN_HAS_TRANSITION",
            "fromVertexId": "screen://Accounting/EditInvoice.xml",
            "toVertexId": "transition://Accounting/EditInvoice.xml#updateInvoice",
            "label": "SCREEN_HAS_TRANSITION",
            "role": "updateInvoice",
        },
        {
            "edgeId": "service_has_statement::1",
            "edgeType": "SERVICE_HAS_STATEMENT",
            "fromVertexId": "service://mantle.account.InvoiceServices.update#Invoice",
            "toVertexId": "statement://service/mantle.account.InvoiceServices.update#Invoice/actions/001",
            "label": "SERVICE_HAS_STATEMENT",
        },
    ]

    merged = merge_edge_rows(base_rows, extra_rows)
    assert len(merged) == 2
    first = [row for row in merged if row["edgeType"] == "SCREEN_HAS_TRANSITION"][0]
    assert first["role"] == "updateInvoice"


def test_extract_xsd_registry_collects_relation_attributes(tmp_path):
    xsd_file = tmp_path / "xml-actions-3.xsd"
    xsd_file.write_text(
        """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <xs:element name="service-call">
                <xs:complexType>
                    <xs:attribute name="name"/>
                </xs:complexType>
            </xs:element>
            <xs:element name="entity-find">
                <xs:complexType>
                    <xs:attribute name="entity-name"/>
                </xs:complexType>
            </xs:element>
        </xs:schema>""",
        encoding="utf-8",
    )

    registry = extract_xsd_registry(tmp_path)
    assert registry["service-call"]["relationAttributes"] == ["name"]
    assert registry["service-call"]["attributeKinds"]["name"] == "service_or_named_ref"
    assert registry["entity-find"]["relationAttributes"] == ["entity-name"]
    assert registry["entity-find"]["attributeKinds"]["entity-name"] == "entity"


def test_extract_xsd_registry_extended_relation_attributes(tmp_path):
    xsd_file = tmp_path / "xml-form-3.xsd"
    xsd_file.write_text(
        """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <xs:element name="auto-fields-service">
                <xs:complexType><xs:attribute name="service-name"/></xs:complexType>
            </xs:element>
            <xs:element name="field-entity">
                <xs:complexType><xs:attribute name="validate-entity"/></xs:complexType>
            </xs:element>
            <xs:element name="link">
                <xs:complexType><xs:attribute name="target-screen"/></xs:complexType>
            </xs:element>
        </xs:schema>""",
        encoding="utf-8",
    )
    registry = extract_xsd_registry(tmp_path)
    assert registry["auto-fields-service"]["attributeKinds"]["service-name"] == "service_or_named_ref"
    assert registry["field-entity"]["attributeKinds"]["validate-entity"] == "entity"
    assert registry["link"]["attributeKinds"]["target-screen"] == "screen"


def test_apply_xsd_relations_adds_xsd_usage_and_relation_edges(tmp_path):
    xsd_file = tmp_path / "xml-actions-3.xsd"
    xsd_file.write_text(
        """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <xs:element name="service-call">
                <xs:complexType>
                    <xs:attribute name="name"/>
                </xs:complexType>
            </xs:element>
            <xs:element name="entity-find">
                <xs:complexType>
                    <xs:attribute name="entity-name"/>
                </xs:complexType>
            </xs:element>
        </xs:schema>""",
        encoding="utf-8",
    )
    registry = extract_xsd_registry(tmp_path)

    root = ET.fromstring(
        """<service verb="update" noun="Asset">
            <actions>
                <entity-find entity-name="mantle.product.asset.Asset"/>
                <service-call name="mantle.product.asset.AssetServices.update#Asset"/>
            </actions>
        </service>"""
    )
    vertices = {}
    edges = []

    apply_xsd_relations(
        Path("AssetServices.xml"),
        root,
        "service://update#Asset",
        "service",
        vertices,
        edges,
        registry,
    )

    edge_types = {(row["edgeType"], row["toVertexId"]) for row in edges}
    assert ("ARTIFACT_USES_XSD_ELEMENT", "xsd-element://xml-actions-3.xsd#entity-find") in edge_types
    assert ("ARTIFACT_USES_XSD_ELEMENT", "xsd-element://xml-actions-3.xsd#service-call") in edge_types
    assert ("SERVICE_READS_ENTITY", "entity://mantle.product.asset.Asset") in edge_types
    assert ("SERVICE_CALLS_SERVICE", "service://mantle.product.asset.AssetServices.update#Asset") in edge_types


def test_parse_view_entity_file_adds_members_and_aliases(tmp_path):
    entity_file = tmp_path / "TestEntities.xml"
    entity_file.write_text(
        """<entities>
            <view-entity entity-name="OrderSummary" package="example.order">
                <member-entity entity-alias="OH" entity-name="mantle.order.OrderHeader"/>
                <member-entity entity-alias="OI" entity-name="mantle.order.OrderItem" join-from-alias="OH"/>
                <member-relationship entity-alias="OI" join-from-alias="OH" relationship="items"/>
                <alias entity-alias="OH" name="orderId" field="orderId"/>
                <alias entity-alias="OI" name="itemDescription" field="description"/>
            </view-entity>
        </entities>""",
        encoding="utf-8",
    )
    xsd_registry = {}
    vertices = {}
    edges = []
    parse_view_entity_file(entity_file, vertices, edges, xsd_registry)

    assert "view-entity://example.order.OrderSummary" in vertices
    assert "entity://mantle.order.OrderHeader" in vertices
    assert "entity://mantle.order.OrderItem" in vertices
    assert "field://example.order.OrderSummary.orderId" in vertices
    assert "field://mantle.order.OrderHeader.orderId" in vertices

    edge_types = {(row["edgeType"], row["toVertexId"]) for row in edges}
    assert ("VIEW_ENTITY_HAS_MEMBER", "entity://mantle.order.OrderHeader") in edge_types
    assert ("VIEW_ENTITY_HAS_MEMBER", "entity://mantle.order.OrderItem") in edge_types
    assert ("VIEW_ENTITY_USES_RELATIONSHIP", "relationship://items") in edge_types
    assert ("VIEW_ENTITY_HAS_ALIAS", "field://example.order.OrderSummary.orderId") in edge_types
    assert ("VIEW_ENTITY_ALIASES_FIELD", "field://mantle.order.OrderHeader.orderId") in edge_types


def test_apply_xsd_relations_creates_widget_vertices_for_actionables(tmp_path):
    xsd_file = tmp_path / "xml-form-3.xsd"
    xsd_file.write_text(
        """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <xs:element name="link">
                <xs:complexType><xs:attribute name="target-screen"/></xs:complexType>
            </xs:element>
            <xs:element name="submit">
                <xs:complexType></xs:complexType>
            </xs:element>
        </xs:schema>""",
        encoding="utf-8",
    )
    registry = extract_xsd_registry(tmp_path)
    root = ET.fromstring(
        """<form-single name="EditThing">
            <field name="detail">
                <default-field>
                    <link target-screen="component://example/screen/Thing.xml" text="Open Thing"/>
                    <submit text="Save"/>
                </default-field>
            </field>
        </form-single>"""
    )
    vertices = {}
    edges = []
    apply_xsd_relations(
        Path("Thing.xml"),
        root,
        "form://Thing.xml#EditThing",
        "screen",
        vertices,
        edges,
        registry,
    )

    widget_vertices = {vertex_id: row for vertex_id, row in vertices.items() if vertex_id.startswith("widget://")}
    assert any(row["vertexType"] == "LinkWidget" for row in widget_vertices.values())
    assert any(row["vertexType"] == "SubmitWidget" for row in widget_vertices.values())

    form_widget_edges = [row for row in edges if row["edgeType"] == "FORM_HAS_WIDGET"]
    assert len(form_widget_edges) == 2
    widget_to_screen_edges = [row for row in edges if row["edgeType"] == "WIDGET_USES_SCREEN"]
    assert len(widget_to_screen_edges) == 1
    assert widget_to_screen_edges[0]["toVertexId"] == "screen://component://example/screen/Thing.xml"
