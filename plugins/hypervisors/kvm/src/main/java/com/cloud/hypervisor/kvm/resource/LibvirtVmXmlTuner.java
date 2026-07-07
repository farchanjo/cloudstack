// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.hypervisor.kvm.resource;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Applies local, Java-native tuning to a libvirt domain XML specification. This replicates the
 * behavior previously delegated to an external "libvirt-vm-xml-transformer" agent hook, but is
 * config-driven via agent.properties so it ships in the jar instead of relying on an external
 * script:
 * <ul>
 *     <li>vCPU pinning: pins every guest vCPU to a configured cpuset via {@code <vcpupin>}.</li>
 *     <li>Emulator pinning: pins the QEMU emulator thread to a configured cpuset via {@code <emulatorpin>}.</li>
 *     <li>Private bridge remap: rewrites a bridge interface's source bridge and VLAN tag.</li>
 * </ul>
 * All features default to disabled (empty configuration) and {@link #transform(String)} fails
 * open: any parsing or processing error returns the original XML unchanged.
 */
public class LibvirtVmXmlTuner {

    private static final String TAG_DOMAIN = "domain";
    private static final String TAG_VCPU = "vcpu";
    private static final String TAG_CPUTUNE = "cputune";
    private static final String TAG_VCPUPIN = "vcpupin";
    private static final String TAG_EMULATORPIN = "emulatorpin";
    private static final String TAG_DEVICES = "devices";
    private static final String TAG_INTERFACE = "interface";
    private static final String TAG_SOURCE = "source";
    private static final String TAG_VLAN = "vlan";
    private static final String TAG_TAG = "tag";
    private static final String ATTR_CPUSET = "cpuset";
    private static final String ATTR_VCPU = "vcpu";
    private static final String ATTR_BRIDGE = "bridge";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_ID = "id";
    private static final String INTERFACE_TYPE_BRIDGE = "bridge";

    protected Logger logger = LogManager.getLogger(getClass());

    private final String vcpuPool;
    private final String emulatorPool;
    private final String privateBridgeSource;
    private final String privateBridgeTarget;
    private final String privateBridgeVlan;

    public LibvirtVmXmlTuner(String vcpuPool, String emulatorPool, String privateBridgeSource,
                              String privateBridgeTarget, String privateBridgeVlan) {
        this.vcpuPool = trim(vcpuPool);
        this.emulatorPool = trim(emulatorPool);
        this.privateBridgeSource = trim(privateBridgeSource);
        this.privateBridgeTarget = trim(privateBridgeTarget);
        this.privateBridgeVlan = trim(privateBridgeVlan);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public boolean isEnabled() {
        return isCpuTuneEnabled() || isBridgeRemapEnabled();
    }

    private boolean isCpuTuneEnabled() {
        return !vcpuPool.isEmpty() || !emulatorPool.isEmpty();
    }

    private boolean isBridgeRemapEnabled() {
        return !privateBridgeSource.isEmpty() && !privateBridgeTarget.isEmpty()
                && !privateBridgeVlan.isEmpty() && !privateBridgeSource.equals(privateBridgeTarget);
    }

    /**
     * Applies the configured tuning to the given domain XML. Fails open: any exception, or a
     * root element other than {@code <domain>}, returns the original XML unchanged.
     */
    public String transform(String domainXml) {
        try {
            Document document = parse(domainXml);
            Element root = document.getDocumentElement();
            if (root == null || !TAG_DOMAIN.equals(root.getTagName())) {
                return domainXml;
            }
            if (isBridgeRemapEnabled()) {
                applyBridgeRemap(document, root);
            }
            if (isCpuTuneEnabled()) {
                applyCpuTune(document, root);
            }
            return serialize(document);
        } catch (Exception e) {
            logger.warn("Exception occurred when applying local VM XML tuning, returning original XML: {}", e);
            return domainXml;
        }
    }

    private Document parse(String domainXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(domainXml.getBytes(StandardCharsets.UTF_8)));
    }

    private String serialize(Document document) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private void applyCpuTune(Document document, Element root) {
        Element cputune = getOrCreateChild(document, root, TAG_CPUTUNE);
        if (!vcpuPool.isEmpty()) {
            int vcpuCount = readVcpuCount(root);
            for (int i = 0; i < vcpuCount; i++) {
                upsertVcpuPin(document, cputune, i);
            }
        }
        if (!emulatorPool.isEmpty()) {
            upsertEmulatorPin(document, cputune);
        }
    }

    private int readVcpuCount(Element root) {
        NodeList vcpuNodes = root.getElementsByTagName(TAG_VCPU);
        if (vcpuNodes.getLength() == 0) {
            return 0;
        }
        String text = vcpuNodes.item(0).getTextContent();
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            logger.warn("Could not parse vcpu count from '{}', skipping vcpupin injection.", text);
            return 0;
        }
    }

    private void upsertVcpuPin(Document document, Element cputune, int index) {
        Element vcpupin = findChildByAttribute(cputune, TAG_VCPUPIN, ATTR_VCPU, String.valueOf(index));
        if (vcpupin == null) {
            vcpupin = document.createElement(TAG_VCPUPIN);
            vcpupin.setAttribute(ATTR_VCPU, String.valueOf(index));
            cputune.appendChild(vcpupin);
        }
        vcpupin.setAttribute(ATTR_CPUSET, vcpuPool);
    }

    private void upsertEmulatorPin(Document document, Element cputune) {
        Element emulatorpin = getFirstChildByTag(cputune, TAG_EMULATORPIN);
        if (emulatorpin == null) {
            emulatorpin = document.createElement(TAG_EMULATORPIN);
            cputune.appendChild(emulatorpin);
        }
        emulatorpin.setAttribute(ATTR_CPUSET, emulatorPool);
    }

    private void applyBridgeRemap(Document document, Element root) {
        Element devices = getFirstChildByTag(root, TAG_DEVICES);
        if (devices == null) {
            return;
        }
        NodeList interfaces = devices.getElementsByTagName(TAG_INTERFACE);
        for (int i = 0; i < interfaces.getLength(); i++) {
            Element iface = (Element) interfaces.item(i);
            if (matchesPrivateBridgeInterface(iface)) {
                remapBridgeInterface(document, iface);
            }
        }
    }

    private boolean matchesPrivateBridgeInterface(Element iface) {
        if (!INTERFACE_TYPE_BRIDGE.equals(iface.getAttribute(ATTR_TYPE))) {
            return false;
        }
        Element source = getFirstChildByTag(iface, TAG_SOURCE);
        return source != null && privateBridgeSource.equals(source.getAttribute(ATTR_BRIDGE));
    }

    private void remapBridgeInterface(Document document, Element iface) {
        Element source = getFirstChildByTag(iface, TAG_SOURCE);
        source.setAttribute(ATTR_BRIDGE, privateBridgeTarget);
        Element vlan = getOrCreateChild(document, iface, TAG_VLAN);
        removeAllChildren(vlan);
        Element tag = document.createElement(TAG_TAG);
        tag.setAttribute(ATTR_ID, privateBridgeVlan);
        vlan.appendChild(tag);
    }

    private void removeAllChildren(Element element) {
        while (element.hasChildNodes()) {
            element.removeChild(element.getFirstChild());
        }
    }

    private Element getOrCreateChild(Document document, Element parent, String tag) {
        Element existing = getFirstChildByTag(parent, tag);
        if (existing != null) {
            return existing;
        }
        Element created = document.createElement(tag);
        parent.appendChild(created);
        return created;
    }

    private Element getFirstChildByTag(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private Element findChildByAttribute(Element parent, String tag, String attr, String value) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                Element element = (Element) node;
                if (value.equals(element.getAttribute(attr))) {
                    return element;
                }
            }
        }
        return null;
    }
}
