// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.
package com.cloud.hypervisor.kvm.resource;

import java.io.StringReader;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.cloudstack.utils.security.ParserUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Structured, equality-based parsers for read-only migration observations. */
public final class MigrationObservationParser {
    public static final class TcHandle {
        private final int pref; private final int handle;
        public TcHandle(final int pref, final int handle) { this.pref = pref; this.handle = handle; }
        public int pref() { return pref; } public int handle() { return handle; }
    }
    public static final class DomainInterface {
        private final String alias; private final String target; private final String mac; private final String source;
        private final String type; private final String model; private final String bdf;
        public DomainInterface(final String alias, final String target, final String mac, final String source,
                final String type, final String model, final String bdf) {
            this.alias = alias; this.target = target; this.mac = mac; this.source = source;
            this.type = type; this.model = model; this.bdf = bdf;
        }
        public String alias() { return alias; } public String target() { return target; } public String mac() { return mac; }
        public String source() { return source; } public String type() { return type; } public String model() { return model; }
        public String bdf() { return bdf; }
    }
    public static final class VdpaDevice {
        private final String name; private final String device; private final String managementBdf;
        public VdpaDevice(final String name, final String device, final String managementBdf) {
            this.name = name; this.device = device; this.managementBdf = managementBdf;
        }
        public String name() { return name; } public String device() { return device; } public String managementBdf() { return managementBdf; }
    }
    public static final class OvsInterface {
        private final String bridge; private final String port; private final String name; private final JsonObject externalIds;
        private final String bridgeUuid; private final String portUuid; private final String interfaceUuid;
        public OvsInterface(final String bridge, final String port, final String name, final JsonObject externalIds,
                final String bridgeUuid, final String portUuid, final String interfaceUuid) {
            this.bridge = bridge; this.port = port; this.name = name; this.externalIds = externalIds;
            this.bridgeUuid = bridgeUuid; this.portUuid = portUuid; this.interfaceUuid = interfaceUuid;
        }
        public String bridge() { return bridge; } public String port() { return port; } public String name() { return name; }
        public JsonObject externalIds() { return externalIds; } public String bridgeUuid() { return bridgeUuid; }
        public String portUuid() { return portUuid; } public String interfaceUuid() { return interfaceUuid; }
    }
    public static final class VfTopology {
        private final String pf; private final Integer vfId; private final String mac; private final String vlan;
        public VfTopology(final String pf, final Integer vfId, final String mac, final String vlan) {
            this.pf = pf; this.vfId = vfId; this.mac = mac; this.vlan = vlan;
        }
        public String pf() { return pf; } public Integer vfId() { return vfId; } public String mac() { return mac; }
        public String vlan() { return vlan; }
    }

    private MigrationObservationParser() { }

    public static DomainInterface domainInterface(final String xml, final String expectedMac,
            final String expectedAlias, final String expectedTarget) {
        if (xml == null) {
            return null;
        }
        try {
            final DocumentBuilderFactory factory = ParserUtils.getSaferDocumentBuilderFactory();
            final org.w3c.dom.Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            final NodeList interfaces = document.getElementsByTagName("interface");
            DomainInterface match = null;
            for (int i = 0; i < interfaces.getLength(); i++) {
                final Element iface = (Element) interfaces.item(i);
                final Element mac = child(iface, "mac");
                final Element alias = child(iface, "alias");
                final Element target = child(iface, "target");
                final boolean identityMatch = (expectedMac != null || expectedAlias != null || expectedTarget != null)
                        && (expectedMac == null || equalsIgnoreCase(expectedMac, attr(mac, "address")))
                        && (expectedAlias == null || equalsExact(expectedAlias, attr(alias, "name")))
                        && (expectedTarget == null || equalsExact(expectedTarget, attr(target, "dev")));
                if (!identityMatch) {
                    continue;
                }
                final Element source = child(iface, "source");
                final Element model = child(iface, "model");
                final DomainInterface candidate = new DomainInterface(attr(alias, "name"), attr(target, "dev"), attr(mac, "address"),
                        attr(source, "dev") != null ? attr(source, "dev") : attr(source, "bridge"),
                        iface.getAttribute("type"), attr(model, "type"), sourceBdf(source));
                if (match != null) {
                    return null;
                }
                match = candidate;
            }
            return match;
        } catch (Exception e) {
            return null;
        }
    }

    public static VdpaDevice vdpaDevice(final String json, final String expectedName,
            final String expectedDevice) {
        final JsonArray devices = array(json);
        if (devices == null) {
            return null;
        }
        for (JsonElement element : devices) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            final String name = string(object, "name");
            final String device = string(object, "device");
            final String managementDevice = string(object, "mgmtdev");
            final String managementBdf = managementDevice != null && managementDevice.startsWith("pci/")
                    ? managementDevice.substring("pci/".length()) : managementDevice;
            if ((expectedName == null || equalsExact(expectedName, name))
                    && (expectedDevice == null || equalsExact(expectedDevice, device))) {
                return new VdpaDevice(name, device, managementBdf);
            }
        }
        return null;
    }

    public static boolean vdpaInventoryAbsent(final String json, final String expectedName,
            final String expectedBdf) {
        final JsonArray devices = array(json);
        if (devices == null) {
            return false;
        }
        for (JsonElement element : devices) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            if (equalsExact(expectedName, string(object, "name"))
                    || equalsExact("pci/" + expectedBdf, string(object, "mgmtdev"))) {
                return false;
            }
        }
        return true;
    }

    public static boolean ovnBindingExact(final String json, final String logicalPort,
            final String chassis) {
        try {
            final JsonObject table = JsonParser.parseString(json).getAsJsonObject();
            final JsonArray rows = table.getAsJsonArray("data");
            if (rows == null || rows.size() != 1) {
                return false;
            }
            final JsonArray row = rows.get(0).getAsJsonArray();
            final JsonArray headings = table.getAsJsonArray("headings");
            String actualPort = null;
            String actualChassis = null;
            for (int index = 0; index < headings.size() && index < row.size(); index++) {
                if ("logical_port".equals(headings.get(index).getAsString())) {
                    actualPort = row.get(index).getAsString();
                } else if ("chassis".equals(headings.get(index).getAsString())) {
                    actualChassis = row.get(index).getAsString();
                }
            }
            return equalsExact(logicalPort, actualPort) && (chassis == null || equalsExact(chassis, actualChassis));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static List<TcHandle> exactTcHandles(final String json) {
        final List<TcHandle> handles = new ArrayList<>();
        try {
            final JsonArray rows = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : rows) {
                final JsonObject row = element.getAsJsonObject();
                if (!row.has("pref") || !row.has("handle")) {
                    return List.of();
                }
                handles.add(new TcHandle(row.get("pref").getAsInt(), row.get("handle").getAsInt()));
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return handles;
    }

    public static boolean jsonArrayEmpty(final String json) {
        try {
            return JsonParser.parseString(json).isJsonArray()
                    && JsonParser.parseString(json).getAsJsonArray().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static OvsInterface ovsInterface(final String json, final String expectedName,
            final String expectedIfaceId, final String expectedBridge, final String expectedPort) {
        if (json != null) {
            try {
                final JsonElement parsed = JsonParser.parseString(json);
                if (parsed.isJsonArray() && parsed.getAsJsonArray().size() == 3
                        && parsed.getAsJsonArray().get(0).isJsonObject()
                        && parsed.getAsJsonArray().get(0).getAsJsonObject().has("rows")) {
                    return ovsDatabaseInterface(json, expectedName, expectedIfaceId, expectedBridge, expectedPort);
                }
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        final JsonArray interfaces = array(json);
        if (interfaces == null) {
            return null;
        }
        for (JsonElement element : interfaces) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            final String name = string(object, "name");
            final JsonObject externalIds = externalIds(object.get("external_ids"));
            final String ifaceId = externalIds == null ? null : string(externalIds, "iface-id");
            final String bridge = string(object, "bridge");
            final String port = string(object, "port");
            if ((expectedName == null || equalsExact(expectedName, name))
                    && (expectedIfaceId == null || equalsExact(expectedIfaceId, ifaceId))
                    && (expectedBridge == null || equalsExact(expectedBridge, bridge))
                    && (expectedPort == null || equalsExact(expectedPort, port))) {
                return new OvsInterface(bridge, port, name, externalIds, null, null, null);
            }
        }
        if (interfaces == null) {
            return ovsDatabaseInterface(json, expectedName, expectedIfaceId, expectedBridge, expectedPort);
        }
        return null;
    }

    private static OvsInterface ovsDatabaseInterface(final String json, final String expectedName,
            final String expectedIfaceId, final String expectedBridge, final String expectedPort) {
        try {
            final JsonArray response = JsonParser.parseString(json).getAsJsonArray();
            if (response.size() != 3) {
                return null;
            }
            final JsonArray ifaceRows = response.get(0).getAsJsonObject().getAsJsonArray("rows");
            final JsonArray portRows = response.get(1).getAsJsonObject().getAsJsonArray("rows");
            final JsonArray bridgeRows = response.get(2).getAsJsonObject().getAsJsonArray("rows");
            if (ifaceRows == null || portRows == null || bridgeRows == null
                    || ifaceRows.size() != 1 || portRows.size() != 1) {
                return null;
            }
            final JsonObject iface = ifaceRows.get(0).getAsJsonObject();
            final JsonObject port = portRows.get(0).getAsJsonObject();
            final String ifaceUuid = uuid(iface.get("_uuid"));
            final String portUuid = uuid(port.get("_uuid"));
            final JsonArray interfaces = unwrap(port.get("interfaces"));
            if ((expectedName != null && !equalsExact(expectedName, string(iface, "name")))
                    || (expectedName != null && !equalsExact(expectedName, string(port, "name")))
                    || interfaces.size() != 1 || !ifaceUuid.equals(uuid(interfaces.get(0)))) {
                return null;
            }
            for (JsonElement bridgeElement : bridgeRows) {
                final JsonObject bridge = bridgeElement.getAsJsonObject();
                if (containsUuid(unwrap(bridge.get("ports")), portUuid)) {
                    final String bridgeName = string(bridge, "name");
                    final JsonObject ids = externalIds(iface.get("external_ids"));
                    return (expectedBridge == null || equalsExact(expectedBridge, bridgeName))
                            && (expectedPort == null || equalsExact(expectedPort, string(port, "name")))
                            && (expectedIfaceId == null || equalsExact(expectedIfaceId, string(ids, "iface-id")))
                            ? new OvsInterface(bridgeName, string(port, "name"), string(iface, "name"), ids,
                            uuid(bridge.get("_uuid")), portUuid, ifaceUuid) : null;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    /** Parse the exact VF row returned by {@code ip -j -details link show}. */
    public static VfTopology vfTopology(final String json, final String expectedPf,
            final Integer expectedVfId, final String expectedMac, final String expectedVlan) {
        if (json == null || expectedPf == null) {
            return null;
        }
        try {
            final JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray() || root.getAsJsonArray().size() != 1) {
                return null;
            }
            final JsonObject link = root.getAsJsonArray().get(0).getAsJsonObject();
            if (!equalsExact(expectedPf, string(link, "ifname"))) {
                return null;
            }
            final JsonArray vfs = link.getAsJsonArray("vfinfo_list");
            if (vfs == null) {
                return null;
            }
            VfTopology match = null;
            for (JsonElement vfElement : vfs) {
                final JsonObject vf = vfElement.getAsJsonObject();
                final int vfId = intValue(vf, "vf", -1);
                if (vfId < 0 || expectedVfId != null && vfId != expectedVfId) {
                    continue;
                }
                final String mac = nestedString(vf, "mac", "address");
                final String vlan = string(vf, "vlan");
                if ((expectedMac == null || equalsIgnoreCase(expectedMac, mac))
                        && vlanMatches(expectedVlan, vlan)) {
                    if (match != null) {
                        return null;
                    }
                    match = new VfTopology(expectedPf, vfId, mac, vlan);
                }
            }
            return match;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean vlanMatches(final String expectedVlan, final String actualVlan) {
        return expectedVlan == null ? actualVlan == null || "0".equals(actualVlan)
                : equalsExact(expectedVlan, actualVlan);
    }

    public static boolean externalIdsEqual(final String expectedJson, final JsonObject observed) {
        if (expectedJson == null) {
            return observed == null;
        }
        try {
            return observed != null && JsonParser.parseString(expectedJson).equals(observed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String externalId(final JsonObject externalIds, final String key) {
        return externalIds == null ? null : string(externalIds, key);
    }

    private static JsonArray array(final String json) {
        try {
            final JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonArray() ? parsed.getAsJsonArray()
                    : parsed.isJsonObject() && parsed.getAsJsonObject().has("data")
                    ? rows(parsed.getAsJsonObject()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonArray rows(final JsonObject table) {
        final JsonArray headings = table.getAsJsonArray("headings");
        final JsonArray data = table.getAsJsonArray("data");
        final JsonArray rows = new JsonArray();
        if (headings == null || data == null) {
            return rows;
        }
        for (JsonElement rowElement : data) {
            if (!rowElement.isJsonArray()) {
                continue;
            }
            final JsonObject row = new JsonObject();
            final JsonArray values = rowElement.getAsJsonArray();
            for (int i = 0; i < headings.size() && i < values.size(); i++) {
                final String heading = headings.get(i).getAsString();
                final JsonElement value = values.get(i);
                if (value.isJsonPrimitive()) {
                    row.add(heading, value);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static Element child(final Element parent, final String name) {
        if (parent == null) {
            return null;
        }
        final NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            final Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String attr(final Element element, final String name) {
        return element == null || !element.hasAttribute(name) ? null : element.getAttribute(name);
    }

    private static String sourceBdf(final Element source) {
        final Element address = child(source, "address");
        final String domain = stripHex(attr(address, "domain"));
        final String bus = stripHex(attr(address, "bus"));
        final String slot = stripHex(attr(address, "slot"));
        final String function = stripHex(attr(address, "function"));
        return domain == null || bus == null || slot == null || function == null ? null
                : String.format("%s:%s:%s.%s", padHex(domain, 4), padHex(bus, 2), padHex(slot, 2), function);
    }

    private static String stripHex(final String value) {
        return value == null ? null : value.startsWith("0x") ? value.substring(2) : value;
    }

    private static String padHex(final String value, final int length) {
        return String.format("%" + length + "s", value).replace(' ', '0').toLowerCase(Locale.ROOT);
    }

    private static String string(final JsonObject object, final String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : null;
    }

    private static int intValue(final JsonObject object, final String name, final int fallback) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String nestedString(final JsonObject object, final String parent, final String child) {
        return object.has(parent) && object.get(parent).isJsonObject()
                ? string(object.getAsJsonObject(parent), child) : string(object, parent);
    }

    private static JsonObject externalIds(final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        if (!element.isJsonArray()) {
            return null;
        }
        final JsonObject result = new JsonObject();
        final JsonArray raw = element.getAsJsonArray();
        final JsonArray entries = raw.size() == 2 && raw.get(1).isJsonArray()
                ? raw.get(1).getAsJsonArray() : raw;
        for (JsonElement pair : entries) {
            if (pair.isJsonArray() && pair.getAsJsonArray().size() == 2) {
                result.add(pair.getAsJsonArray().get(0).getAsString(), pair.getAsJsonArray().get(1));
            }
        }
        return result;
    }

    private static String uuid(final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            final JsonElement value = element.getAsJsonObject().get("uuid");
            return value == null ? null : uuid(value);
        }
        if (element.isJsonArray()) {
            final JsonArray value = element.getAsJsonArray();
            return value.size() == 2 && value.get(0).isJsonPrimitive()
                    && "uuid".equals(value.get(0).getAsString()) ? uuid(value.get(1)) : null;
        }
        return null;
    }

    private static JsonArray unwrap(final JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return null;
        }
        final JsonArray value = element.getAsJsonArray();
        if (value.size() == 2 && value.get(0).isJsonPrimitive() && "set".equals(value.get(0).getAsString())
                && value.get(1).isJsonArray()) {
            return value.get(1).getAsJsonArray();
        }
        return value;
    }

    private static boolean containsUuid(final JsonArray values, final String wanted) {
        for (JsonElement value : values) {
            try {
                if (wanted.equals(uuid(value))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // A malformed member is not an exact match.
            }
        }
        return false;
    }

    private static boolean equalsExact(final String expected, final String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static boolean equalsIgnoreCase(final String expected, final String actual) {
        return expected == null ? actual == null : actual != null
                && expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT));
    }
}
