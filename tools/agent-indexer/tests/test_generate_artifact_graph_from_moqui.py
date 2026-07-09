from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SCRIPT_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from generate_artifact_graph_from_moqui import (  # noqa: E402
    add_security_graph,
    apply_xsd_relations,
    component_uri_for_path,
    merge_edge_rows,
    merge_vertex_rows,
    parse_xsd_dir,
    parse_screen_file,
    parse_view_entity_file,
)
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


def test_parse_xsd_dir_enriches_xml_actions_elements_with_semantics(tmp_path):
    xsd_file = tmp_path / "xml-actions-3.xsd"
    xsd_file.write_text(
        """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <xs:element name="service-call" substitutionGroup="AllOperations">
                <xs:complexType>
                    <xs:sequence>
                        <xs:element name="parameter" minOccurs="0" maxOccurs="unbounded"/>
                    </xs:sequence>
                    <xs:attribute name="name"/>
                    <xs:attribute name="in-map"/>
                </xs:complexType>
            </xs:element>
            <xs:element name="entity-find-one" substitutionGroup="AllOperations">
                <xs:complexType>
                    <xs:attribute name="entity-name"/>
                    <xs:attribute name="list"/>
                </xs:complexType>
            </xs:element>
        </xs:schema>""",
        encoding="utf-8",
    )

    vertices = {}
    edges = []
    parse_xsd_dir(tmp_path, vertices, edges, extract_xsd_registry(tmp_path))

    service_call = vertices["xsd-element://xml-actions-3.xsd#service-call"]
    assert service_call["xmlActionElement"] == "service-call"
    assert service_call["statementClass"] == "service_call"
    assert service_call["operation"] == "delegated_operation"
    assert service_call["grammarAttributes"] == "in-map,name"
    assert service_call["grammarChildren"] == "parameter"
    assert service_call["grammarSubstitutionGroup"] == "AllOperations"

    entity_find_one = vertices["xsd-element://xml-actions-3.xsd#entity-find-one"]
    assert entity_find_one["statementClass"] == "entity_read"
    assert entity_find_one["operation"] == "read_one"


def test_component_uri_for_path_uses_component_root(tmp_path):
    component_dir = tmp_path / "runtime" / "component" / "Example"
    screen_dir = component_dir / "screen"
    screen_dir.mkdir(parents=True)
    (component_dir / "component.xml").write_text("<component/>", encoding="utf-8")
    screen_file = screen_dir / "Example.xml"
    screen_file.write_text("<screen/>", encoding="utf-8")

    assert component_uri_for_path(screen_file) == "component://Example/screen/Example.xml"


def test_parse_screen_file_sets_security_artifact_names(tmp_path):
    component_dir = tmp_path / "Example"
    screen_dir = component_dir / "screen"
    screen_dir.mkdir(parents=True)
    (component_dir / "component.xml").write_text("<component/>", encoding="utf-8")
    screen_file = screen_dir / "Root.xml"
    screen_file.write_text(
        """<screen>
            <transition name="updateThing"/>
        </screen>""",
        encoding="utf-8",
    )

    vertices = {}
    edges = []
    parse_screen_file(screen_file, vertices, edges, {})

    screen_row = vertices[f"screen://{screen_file}"]
    transition_row = vertices[f"transition://{screen_file}#updateThing"]
    assert screen_row["securityArtifactName"] == "component://Example/screen/Root.xml"
    assert screen_row["securityArtifactTypeEnumId"] == "AT_XML_SCREEN"
    assert transition_row["securityArtifactName"] == "component://Example/screen/Root.xml/updateThing"
    assert transition_row["securityArtifactTypeEnumId"] == "AT_XML_SCREEN_TRANS"


def test_add_security_graph_links_artifact_to_authz_metadata(tmp_path):
    component_dir = tmp_path / "Example"
    service_dir = component_dir / "service"
    data_dir = component_dir / "data"
    service_dir.mkdir(parents=True)
    data_dir.mkdir(parents=True)
    (component_dir / "component.xml").write_text("<component/>", encoding="utf-8")
    (service_dir / "ExampleServices.xml").write_text(
        """<services>
            <service verb="update" noun="Example"/>
        </services>""",
        encoding="utf-8",
    )
    (data_dir / "ExampleSecurityData.xml").write_text(
        """<entity-facade-xml type="seed">
            <moqui.security.ArtifactGroup artifactGroupId="ExampleServices" description="Example secured services"/>
            <moqui.security.ArtifactGroupMember artifactGroupId="ExampleServices" artifactName="update#Example" artifactTypeEnumId="AT_SERVICE"/>
            <moqui.security.ArtifactAuthz artifactAuthzId="ExampleServicesAdmin" userGroupId="ADMIN" artifactGroupId="ExampleServices"
                    authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
            <moqui.security.EntityFilterSet entityFilterSetId="ExampleFilterSet" description="Example filters">
                <filters entityFilterId="ExampleFilter" entityName="example.Example" filterMap="[exampleId:'DEMO']"/>
            </moqui.security.EntityFilterSet>
            <moqui.security.ArtifactAuthzFilter artifactAuthzId="ExampleServicesAdmin" entityFilterSetId="ExampleFilterSet"/>
            <moqui.security.ArtifactTarpit userGroupId="ADMIN" artifactGroupId="ExampleServices" maxHitsCount="5" maxHitsDuration="60" tarpitDuration="120"/>
        </entity-facade-xml>""",
        encoding="utf-8",
    )

    vertices = {
        "service://update#Example": {
            "vertexId": "service://update#Example",
            "vertexType": "Service",
            "serviceName": "update#Example",
            "securityArtifactName": "update#Example",
            "securityArtifactTypeEnumId": "AT_SERVICE",
            "label": "update#Example",
        }
    }
    edges = []

    summary = add_security_graph(tmp_path, vertices, edges)

    assert summary["artifactGroupCount"] == 1
    assert summary["artifactAuthzCount"] == 1
    assert any(row["edgeType"] == "ARTIFACT_MATCHES_AUTHZ_MEMBER" for row in edges)
    assert any(row["edgeType"] == "ARTIFACT_GROUP_HAS_AUTHZ" for row in edges)
    assert any(row["edgeType"] == "ARTIFACT_AUTHZ_HAS_FILTER" for row in edges)
    assert any(row["edgeType"] == "ENTITY_FILTER_SET_HAS_FILTER" for row in edges)
    assert any(row["edgeType"] == "ARTIFACT_GROUP_HAS_TARPIT" for row in edges)
    security_vertices = {row.get("vertexType") for row in vertices.values()}
    assert "SecurityArtifactGroup" in security_vertices
    assert "SecurityArtifactAuthz" in security_vertices
    assert "SecurityEntityFilter" in security_vertices
