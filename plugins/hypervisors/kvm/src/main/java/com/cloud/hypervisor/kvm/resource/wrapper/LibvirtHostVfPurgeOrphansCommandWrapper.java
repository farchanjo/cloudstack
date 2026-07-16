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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer.TargetResult;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

/**
 * Exact-BDF VF inventory and cleanup wrapper.
 *
 * <p>All observations required to authorize a target are explicit and
 * fail-closed: vDPA inventory, every libvirt domain XML/state, exact parent PF,
 * VF index, MAC, driver and representor. A command failure is never treated as
 * an empty inventory. Each target has its own exception boundary, so a failure
 * preserves the observations and results already collected for other targets.
 */
@ResourceWrapper(handles = HostVfPurgeOrphansCommand.class)
public class LibvirtHostVfPurgeOrphansCommandWrapper extends
        CommandWrapper<HostVfPurgeOrphansCommand, Answer, LibvirtComputingResource> {

    private static final Logger LOGGER = LogManager.getLogger(LibvirtHostVfPurgeOrphansCommandWrapper.class);
    private static final int MAX_TARGETS = 256;
    private static final String PCI_BDF_PATTERN = "[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\\.[0-9a-f]";
    private static final Pattern PCI_BDF = Pattern.compile(PCI_BDF_PATTERN, Pattern.CASE_INSENSITIVE);
    private static final String DRV_VFIO = "vfio-pci";
    private static final String DRV_MLX5 = "mlx5_core";
    private static final String ZERO_MAC = "00:00:00:00:00:00";
    private static final String MAC_READ_ERROR = "READ_ERROR";
    private static final String MAC_UNASSIGNED_ZERO = "UNASSIGNED_ZERO";
    private static final String MAC_NONZERO = "NONZERO";

    private final CleanupEnvironment environment;

    public LibvirtHostVfPurgeOrphansCommandWrapper() {
        this(CleanupEnvironment.system());
    }

    LibvirtHostVfPurgeOrphansCommandWrapper(final CleanupEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public Answer execute(final HostVfPurgeOrphansCommand cmd, final LibvirtComputingResource resource) {
        final Set<String> targets = normalizeTargets(cmd.getTargetPciBdfs());
        if (targets.isEmpty()) {
            return new HostVfPurgeOrphansAnswer(cmd, true, "no explicit target PCI BDFs; no-op");
        }

        final VdpaInventory vdpa = inventoryVdpa();
        final DomainInventory domains = inventoryDomains(vdpa);
        final List<TargetResult> results = new ArrayList<>();
        int processed = 0;
        for (final String bdf : targets) {
            if (processed++ >= MAX_TARGETS) {
                results.add(failure(bdf, "explicit target limit exceeded"));
                continue;
            }
            try {
                results.add(processTarget(cmd, bdf, vdpa, domains));
            } catch (RuntimeException e) {
                LOGGER.warn("Exact VF target {} failed within its exception boundary: {}", bdf, e.getMessage(), e);
                results.add(failure(bdf, "target exception: " + e.getMessage()));
            }
        }

        final boolean success = results.stream().allMatch(TargetResult::isSuccess);
        final HostVfPurgeOrphansAnswer answer = new HostVfPurgeOrphansAnswer(cmd, success,
                success ? "all explicit targets observed/cleaned" : "one or more explicit targets failed closed");
        answer.setTargetResults(results);
        answer.setVdpaDeleted((int) results.stream().filter(TargetResult::isVdpaRemoved).count());
        answer.setVfsRebound((int) results.stream().filter(TargetResult::isVfioRebound).count());
        answer.setOvsRepsFreed((int) results.stream().filter(TargetResult::isRepresentorRemoved).count());
        return answer;
    }

    private TargetResult processTarget(final HostVfPurgeOrphansCommand cmd, final String bdf,
                                       final VdpaInventory vdpa, final DomainInventory domains) {
        if (!vdpa.success) {
            return failure(bdf, "vDPA inventory unavailable: " + vdpa.details);
        }
        if (!domains.success) {
            return failure(bdf, "domain inventory unavailable: " + domains.details);
        }

        final boolean present = Files.isDirectory(environment.pciDevices.resolve(bdf));
        final String expectedMac = valueForBdf(cmd.getExpectedMacsByPciBdf(), bdf);
        final MacObservation mac = present ? readVfMacExact(bdf) : MacObservation.unassigned();
        final boolean tokenValid = cmd.hasValidOwnerToken(bdf, expectedMac);
        final List<DomainReference> references = domains.byBdf.getOrDefault(bdf, Collections.emptyList());
        final TargetResult observation = observeTarget(bdf, present, mac, vdpa.byBdf.get(bdf), references);
        observation.setExpectedMac(expectedMac);
        observation.setOwnerOperationId(cmd.getOwnerOperationId(bdf));
        observation.setOwnerPurpose(cmd.getOwnerPurpose(bdf));
        observation.setOwnerToken(cmd.getOwnerToken(bdf));

        if (cmd.isDryRun()) {
            observation.setLifecycleAuthorizationUsed(tokenValid);
            return observation;
        }
        if (!observation.isObservationComplete()) {
            return observation;
        }
        if (present && mac.isReadError()) {
            return withObservation(observation, false, "VF MAC observation failed: " + mac.details);
        }
        final boolean tokenAuthorizesUnassignedMac = tokenValid && mac.isUnassigned();
        if (present && !matchesExpectedMac(expectedMac, mac.mac) && !tokenAuthorizesUnassignedMac) {
            return withObservation(observation, false,
                    String.format("present VF MAC %s is not exact expected owner MAC %s and no valid owner token exists",
                            mac.mac, expectedMac));
        }
        final DomainReference active = firstActive(references);
        if (active != null) {
            return withObservation(observation, false,
                    String.format("domain %s state=%s references target BDF", active.name, active.state));
        }
        if (!references.isEmpty() && (!tokenValid
                || !"STAGE_ROLLBACK".equalsIgnoreCase(cmd.getOwnerPurpose(bdf)))) {
            return withObservation(observation, false,
                    "inactive persistent domain references target; STAGE_ROLLBACK authorization required");
        }
        observation.setLifecycleAuthorizationUsed(tokenValid);
        return cleanupTarget(bdf, observation, vdpa.byBdf.get(bdf));
    }

    /** Executes the real target mutation path; tests inject only the environment. */
    TargetResult cleanupTarget(final String bdf, final TargetResult observation,
                               final List<String> vdpaNames) {
        boolean vdpaRemoved = false;
        boolean representorRemoved = false;
        boolean rebound = false;
        try {
            if (vdpaNames != null) {
                for (final String name : vdpaNames) {
                    requireSuccess(environment.runner.run("/usr/sbin/vdpa", "dev", "del", name),
                            "vdpa dev del " + name);
                    vdpaRemoved = true;
                }
                final VdpaInventory postVdpa = inventoryVdpa();
                if (!postVdpa.success || postVdpa.byBdf.containsKey(bdf)) {
                    throw new IllegalStateException("vDPA delete postcondition unavailable or target still present");
                }
            }

            if (observation.isDevicePresent()) {
                final String representor = VfPassthroughVifDriver.lookupRepresentor(
                        bdf, environment.pciDevices, environment.netClass);
                if (representor != null) {
                    removeRepresentorChecked(representor);
                    representorRemoved = true;
                }
                final Path driver = environment.pciDevices.resolve(bdf).resolve("driver");
                if (DRV_VFIO.equals(currentDriverOf(driver))) {
                    rebindOne(bdf);
                    if (!DRV_MLX5.equals(currentDriverOf(driver))) {
                        throw new IllegalStateException("VF rebind postcondition did not observe mlx5_core");
                    }
                    rebound = true;
                }
                clearVfIdentityExact(bdf);
                final MacObservation postMac = readVfMacExact(bdf);
                if (!postMac.isUnassigned()) {
                    throw new IllegalStateException("VF identity clear postcondition unavailable: mac=" + postMac.mac);
                }
            }
            final TargetResult result = copyObservation(observation, true, "target cleanup and postconditions complete");
            result.setVdpaName(first(vdpaNames));
            setActions(result, representorRemoved, vdpaRemoved, rebound);
            return result;
        } catch (RuntimeException e) {
            final TargetResult result = copyObservation(observation, false, e.getMessage());
            result.setVdpaName(first(vdpaNames));
            setActions(result, representorRemoved, vdpaRemoved, rebound);
            return result;
        }
    }

    private TargetResult observeTarget(final String bdf, final boolean present, final MacObservation mac,
                                       final List<String> vdpaNames, final List<DomainReference> references) {
        String driver = null;
        String binding = "ABSENT";
        if (present) {
            driver = currentDriverOf(environment.pciDevices.resolve(bdf).resolve("driver"));
            binding = vdpaNames != null && !vdpaNames.isEmpty() ? "VDPA_BOUND"
                    : DRV_VFIO.equals(driver) ? "PASSTHROUGH_BOUND" : "FREE";
            if (VfPassthroughVifDriver.lookupPfFromVf(bdf, environment.pciDevices, environment.netClass) == null
                    || VfPassthroughVifDriver.lookupVfIdFromPci(bdf, environment.pciDevices) == null) {
                return observedResult(bdf, false, true, "exact parent PF/VF observation unavailable",
                        mac.mac, binding, driver, first(vdpaNames), references, false, mac.status);
            }
        }
        final boolean complete = !present || !mac.isReadError();
        return observedResult(bdf, complete, present, complete ? "observation complete" : mac.details,
                mac.mac, binding, driver, first(vdpaNames), references, complete, mac.status);
    }

    private void removeRepresentorChecked(final String representor) {
        requireSuccess(environment.runner.run("/usr/bin/ovs-vsctl", "--if-exists", "clear",
                "Interface", representor, "external_ids"), "clear OVS external_ids for " + representor);
        final CommandResult port = environment.runner.run("/usr/bin/ovs-vsctl", "--if-exists", "get",
                "Port", representor, "name");
        requireSuccess(port, "observe OVS port " + representor);
        if (StringUtils.isNotBlank(port.output)) {
            final CommandResult bridge = environment.runner.run("/usr/bin/ovs-vsctl", "port-to-br", representor);
            if (!bridge.success || StringUtils.isBlank(bridge.output)) {
                throw new IllegalStateException("cannot resolve exact OVS bridge for " + representor);
            }
            requireSuccess(environment.runner.run("/usr/bin/ovs-vsctl", "--if-exists", "del-port",
                    bridge.output.trim(), representor), "delete OVS representor " + representor);
        }
        final CommandResult post = environment.runner.run("/usr/bin/ovs-vsctl", "--if-exists", "get",
                "Interface", representor, "external_ids");
        if (!post.success || !(StringUtils.isBlank(post.output) || "{}".equals(post.output.trim()))) {
            throw new IllegalStateException("OVS representor removal postcondition unavailable for " + representor);
        }
        final CommandResult postPort = environment.runner.run("/usr/bin/ovs-vsctl", "--if-exists", "get",
                "Port", representor, "name");
        if (!postPort.success || StringUtils.isNotBlank(postPort.output)) {
            throw new IllegalStateException("OVS port removal postcondition unavailable for " + representor);
        }
    }

    private void clearVfIdentityExact(final String bdf) {
        final String pf = VfPassthroughVifDriver.lookupPfFromVf(bdf, environment.pciDevices, environment.netClass);
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(bdf, environment.pciDevices);
        if (pf == null || vfId == null) {
            throw new IllegalStateException("cannot resolve exact parent PF/VF for " + bdf);
        }
        requireSuccess(environment.runner.run("/sbin/ip", "link", "set", pf, "vf", String.valueOf(vfId),
                "mac", ZERO_MAC, "vlan", "0"), "clear VF identity for " + bdf);
    }

    private MacObservation readVfMacExact(final String bdf) {
        final String pf = VfPassthroughVifDriver.lookupPfFromVf(bdf, environment.pciDevices, environment.netClass);
        final Integer vfId = VfPassthroughVifDriver.lookupVfIdFromPci(bdf, environment.pciDevices);
        if (pf == null || vfId == null) {
            return MacObservation.error("cannot resolve exact parent PF/VF");
        }
        final CommandResult result = environment.runner.run("/sbin/ip", "link", "show", "dev", pf);
        if (!result.success || StringUtils.isBlank(result.output)) {
            return MacObservation.error(result.success ? "ip link output was empty" : result.error);
        }
        final String mac = parseVfMac(result.output, vfId);
        return mac == null ? MacObservation.error("VF was absent from ip link output") : MacObservation.of(mac);
    }

    private VdpaInventory inventoryVdpa() {
        final CommandResult result = environment.runner.run("/usr/sbin/vdpa", "dev", "show");
        return result.success ? VdpaInventory.success(parseVdpaDevicesByBdf(result.output))
                : VdpaInventory.failure(result.error);
    }

    private DomainInventory inventoryDomains(final VdpaInventory vdpa) {
        if (!vdpa.success) {
            return DomainInventory.failure("vDPA inventory unavailable: " + vdpa.details);
        }
        final CommandResult names = environment.runner.run("/usr/bin/virsh", "list", "--all", "--name");
        if (!names.success) {
            return DomainInventory.failure(names.error);
        }
        final Map<String, List<DomainReference>> references = new HashMap<>();
        for (final String rawName : names.output.split("\\R")) {
            final String name = rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            final CommandResult state = environment.runner.run("/usr/bin/virsh", "domstate", name);
            final CommandResult xml = environment.runner.run("/usr/bin/virsh", "dumpxml", name);
            if (!state.success || !xml.success) {
                return DomainInventory.failure("cannot inspect domain " + name + ": "
                        + (state.success ? xml.error : state.error));
            }
            final String normalizedState = state.output.trim().toLowerCase(Locale.ROOT);
            final boolean active = !(normalizedState.contains("shut off")
                    || normalizedState.contains("shutoff") || normalizedState.contains("crashed"));
            try {
                final Document document = parseDomainXml(xml.output);
                for (final String bdf : parseHostdevBdfs(document)) {
                    references.computeIfAbsent(bdf, ignored -> new ArrayList<>())
                            .add(new DomainReference(name, normalizedState, active));
                }
                for (final String bdf : parseDomainVdpaBdfs(document, vdpa.byBdf)) {
                    references.computeIfAbsent(bdf, ignored -> new ArrayList<>())
                            .add(new DomainReference(name, normalizedState, active));
                }
            } catch (Exception e) {
                return DomainInventory.failure("cannot securely parse domain " + name + " XML: " + e.getMessage());
            }
        }
        return DomainInventory.success(references);
    }

    private Set<String> parseDomainVdpaBdfs(final Document document,
                                            final Map<String, List<String>> vdpaByBdf) {
        final Set<String> bdfs = new LinkedHashSet<>();
        final NodeList interfaces = document.getElementsByTagName("interface");
        for (int index = 0; index < interfaces.getLength(); index++) {
            final Element iface = (Element) interfaces.item(index);
            if (!"vdpa".equalsIgnoreCase(iface.getAttribute("type"))) {
                continue;
            }
            final Element source = directChild(iface, "source");
            if (source == null || StringUtils.isBlank(source.getAttribute("dev"))) {
                throw new IllegalStateException("vDPA interface source is missing");
            }
            final String vhost = Paths.get(source.getAttribute("dev")).getFileName().toString();
            final String vdpaName = findVdpaNameForVhost(vhost);
            if (vdpaName == null) {
                throw new IllegalStateException("cannot map domain vDPA source " + vhost + " to a management BDF");
            }
            for (final Map.Entry<String, List<String>> entry : vdpaByBdf.entrySet()) {
                if (entry.getValue().contains(vdpaName)) {
                    bdfs.add(entry.getKey());
                }
            }
        }
        return bdfs;
    }

    private String findVdpaNameForVhost(final String vhost) {
        if (!Files.isDirectory(environment.vdpaDevices)) {
            return null;
        }
        try (java.nio.file.DirectoryStream<Path> devices = Files.newDirectoryStream(environment.vdpaDevices)) {
            for (final Path device : devices) {
                if (Files.exists(device.resolve(vhost))) {
                    return device.getFileName().toString();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot inspect vDPA sysfs: " + e.getMessage(), e);
        }
        return null;
    }

    static Set<String> parseHostdevBdfs(final String xml) {
        try {
            return parseHostdevBdfs(parseDomainXml(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot securely parse domain XML", e);
        }
    }

    private static Set<String> parseHostdevBdfs(final Document document) {
        final Set<String> bdfs = new LinkedHashSet<>();
        final NodeList hostdevs = document.getElementsByTagName("hostdev");
        for (int index = 0; index < hostdevs.getLength(); index++) {
            final Element hostdev = (Element) hostdevs.item(index);
            if (!"pci".equalsIgnoreCase(hostdev.getAttribute("type"))) {
                continue;
            }
            final Element source = directChild(hostdev, "source");
            final Element address = source == null ? null : directChild(source, "address");
            if (address == null) {
                throw new IllegalArgumentException("PCI hostdev source address is missing");
            }
            final int domain = parsePciPart(address, "domain", 0xffff);
            final int bus = parsePciPart(address, "bus", 0xff);
            final int slot = parsePciPart(address, "slot", 0x1f);
            final int function = parsePciPart(address, "function", 0x7);
            bdfs.add(String.format("%04x:%02x:%02x.%x", domain, bus, slot, function));
        }
        return bdfs;
    }

    private static Document parseDomainXml(final String xml) throws Exception {
        if (StringUtils.isBlank(xml)) {
            throw new IllegalArgumentException("domain XML is empty");
        }
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setExternalAccessLimit(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
        setExternalAccessLimit(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
        final javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("external entities are forbidden");
        });
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(final SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(final SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static void setExternalAccessLimit(final DocumentBuilderFactory factory, final String attribute) {
        try {
            factory.setAttribute(attribute, "");
        } catch (IllegalArgumentException ignored) {
            LOGGER.debug("XML parser does not expose {}; entity/DTD features remain disabled", attribute);
        }
    }

    private static Element directChild(final Element parent, final String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private static int parsePciPart(final Element address, final String name, final int maximum) {
        final String value = address.getAttribute(name);
        if (!value.matches("(?i)0x[0-9a-f]+")) {
            throw new IllegalArgumentException("invalid PCI " + name);
        }
        final long parsed = Long.parseLong(value.substring(2), 16);
        if (parsed > maximum) {
            throw new IllegalArgumentException("PCI " + name + " is out of range");
        }
        return (int) parsed;
    }

    static Set<String> normalizeTargets(final Set<String> rawTargets) {
        final Set<String> targets = new LinkedHashSet<>();
        if (rawTargets == null) {
            return targets;
        }
        for (final String raw : rawTargets) {
            final String normalized = raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
            if (normalized != null && PCI_BDF.matcher(normalized).matches()) {
                targets.add(normalized);
            }
        }
        return targets;
    }

    static String parseVfMac(final String output, final int vfId) {
        if (StringUtils.isBlank(output)) {
            return null;
        }
        final Matcher matcher = Pattern.compile("(?i)\\bvf\\s+" + vfId
                + "\\s+(?:link/ether|MAC)\\s+([0-9a-f:]{17})").matcher(output);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    static boolean matchesExpectedMac(final String expectedMac, final String observedMac) {
        return StringUtils.isNotBlank(expectedMac) && StringUtils.isNotBlank(observedMac)
                && !ZERO_MAC.equalsIgnoreCase(expectedMac) && !ZERO_MAC.equalsIgnoreCase(observedMac)
                && expectedMac.equalsIgnoreCase(observedMac);
    }

    static Map<String, List<String>> parseVdpaDevicesByBdf(final String output) {
        final Map<String, List<String>> byBdf = new HashMap<>();
        if (StringUtils.isBlank(output)) {
            return byBdf;
        }
        for (final String line : output.split("\\R")) {
            final int colon = line.indexOf(':');
            final int markerAt = line.indexOf("mgmtdev pci/");
            if (colon <= 0 || markerAt < 0) {
                continue;
            }
            final String name = line.substring(0, colon).trim();
            final String suffix = line.substring(markerAt + "mgmtdev pci/".length()).trim();
            final String bdf = suffix.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (PCI_BDF.matcher(bdf).matches()) {
                byBdf.computeIfAbsent(bdf, ignored -> new ArrayList<>()).add(name);
            }
        }
        return byBdf;
    }

    private void rebindOne(final String bdf) {
        final Path devPath = environment.pciDevices.resolve(bdf);
        final Path driverLink = devPath.resolve("driver");
        if (DRV_VFIO.equals(currentDriverOf(driverLink))) {
            environment.writer.write(environment.pciDrivers.resolve(DRV_VFIO).resolve("unbind"), bdf);
        }
        final Path override = devPath.resolve("driver_override");
        if (Files.exists(override)) {
            environment.writer.write(override, "\n");
        }
        if (!DRV_MLX5.equals(currentDriverOf(driverLink))) {
            environment.writer.write(environment.pciDrivers.resolve(DRV_MLX5).resolve("bind"), bdf);
        }
    }

    static String currentDriverOf(final Path driverLink) {
        try {
            return Files.exists(driverLink) ? driverLink.toRealPath().getFileName().toString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void requireSuccess(final CommandResult result, final String action) {
        if (!result.success) {
            throw new IllegalStateException(action + " failed: " + result.error);
        }
    }

    private static String valueForBdf(final Map<String, String> values, final String bdf) {
        if (values == null) {
            return null;
        }
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            if (bdf.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static DomainReference firstActive(final List<DomainReference> references) {
        for (final DomainReference reference : references) {
            if (reference.active) {
                return reference;
            }
        }
        return null;
    }

    private static String first(final List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static TargetResult failure(final String bdf, final String details) {
        final TargetResult result = new TargetResult(bdf, false, false, false, false, false, details);
        result.setObservationComplete(false);
        return result;
    }

    private static TargetResult observedResult(final String bdf, final boolean success, final boolean present,
                                               final String details, final String currentMac,
                                               final String bindingState, final String driver, final String vdpaName,
                                               final List<DomainReference> references,
                                                final boolean observationComplete, final String macObservation) {
        final TargetResult result = new TargetResult(bdf, success, present, false, false, false, details);
        result.setCurrentMac(currentMac);
        result.setMacObservation(macObservation);
        result.setBindingState(bindingState);
        result.setDriver(driver);
        result.setVdpaName(vdpaName);
        result.setObservationComplete(observationComplete);
        if (!references.isEmpty()) {
            result.setDomainReferenced(true);
            result.setDomainState(references.get(0).state);
        }
        return result;
    }

    private static TargetResult withObservation(final TargetResult source, final boolean success,
                                                final String details) {
        return copyObservation(source, success, details);
    }

    private static TargetResult copyObservation(final TargetResult source, final boolean success,
                                                final String details) {
        final TargetResult result = new TargetResult(source.getPciBdf(), success, source.isDevicePresent(),
                false, false, false, details);
        result.setCurrentMac(source.getCurrentMac());
        result.setMacObservation(source.getMacObservation());
        result.setExpectedMac(source.getExpectedMac());
        result.setOwnerOperationId(source.getOwnerOperationId());
        result.setOwnerPurpose(source.getOwnerPurpose());
        result.setOwnerToken(source.getOwnerToken());
        result.setBindingState(source.getBindingState());
        result.setDriver(source.getDriver());
        result.setVdpaName(source.getVdpaName());
        result.setDomainReferenced(source.isDomainReferenced());
        result.setDomainState(source.getDomainState());
        result.setLifecycleAuthorizationUsed(source.isLifecycleAuthorizationUsed());
        result.setObservationComplete(source.isObservationComplete());
        return result;
    }

    private static void setActions(final TargetResult result, final boolean representorRemoved,
                                   final boolean vdpaRemoved, final boolean rebound) {
        result.setRepresentorRemoved(representorRemoved);
        result.setVdpaRemoved(vdpaRemoved);
        result.setVfioRebound(rebound);
    }

    interface HostCommandRunner {
        CommandResult run(String... command);
    }

    private static final class MacObservation {
        private final String status;
        private final String mac;
        private final String details;

        private MacObservation(final String status, final String mac, final String details) {
            this.status = status;
            this.mac = mac;
            this.details = details;
        }

        private static MacObservation of(final String mac) {
            return ZERO_MAC.equalsIgnoreCase(mac)
                    ? new MacObservation(MAC_UNASSIGNED_ZERO, ZERO_MAC, "explicit zero MAC")
                    : new MacObservation(MAC_NONZERO, mac, "explicit nonzero MAC");
        }

        private static MacObservation unassigned() {
            return new MacObservation(MAC_UNASSIGNED_ZERO, ZERO_MAC, "device absent");
        }

        private static MacObservation error(final String details) {
            return new MacObservation(MAC_READ_ERROR, null, details);
        }

        private boolean isReadError() {
            return MAC_READ_ERROR.equals(status);
        }

        private boolean isUnassigned() {
            return MAC_UNASSIGNED_ZERO.equals(status);
        }
    }

    interface SysfsWriter {
        void write(Path path, String value);
    }

    static final class CommandResult {
        private final boolean success;
        private final String output;
        private final String error;

        CommandResult(final boolean success, final String output, final String error) {
            this.success = success;
            this.output = output == null ? "" : output;
            this.error = error == null ? "" : error;
        }

        static CommandResult success(final String output) {
            return new CommandResult(true, output, null);
        }

        static CommandResult failure(final String error) {
            return new CommandResult(false, null, error);
        }
    }

    static final class CleanupEnvironment {
        private final Path pciDevices;
        private final Path netClass;
        private final Path pciDrivers;
        private final Path vdpaDevices;
        private final HostCommandRunner runner;
        private final SysfsWriter writer;

        CleanupEnvironment(final Path pciDevices, final Path netClass, final Path pciDrivers,
                           final Path vdpaDevices, final HostCommandRunner runner,
                           final SysfsWriter writer) {
            this.pciDevices = pciDevices;
            this.netClass = netClass;
            this.pciDrivers = pciDrivers;
            this.vdpaDevices = vdpaDevices;
            this.runner = runner;
            this.writer = writer;
        }

        static CleanupEnvironment system() {
            return new CleanupEnvironment(Paths.get("/sys/bus/pci/devices"), Paths.get("/sys/class/net"),
                    Paths.get("/sys/bus/pci/drivers"), Paths.get("/sys/bus/vdpa/devices"),
                    new ScriptCommandRunner(), (path, value) -> {
                        try {
                            Files.write(path, value.getBytes(StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            throw new IllegalStateException("write to " + path + " failed: " + e.getMessage(), e);
                        }
                    });
        }
    }

    private static final class ScriptCommandRunner implements HostCommandRunner {
        @Override
        public CommandResult run(final String... command) {
            if (command == null || command.length == 0) {
                return CommandResult.failure("empty command");
            }
            final OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            final Script script = new Script(command[0], 5_000, LOGGER);
            for (int index = 1; index < command.length; index++) {
                script.add(command[index]);
            }
            final String error = script.execute(parser);
            return error == null ? CommandResult.success(parser.getLines()) : CommandResult.failure(error);
        }
    }

    private static final class VdpaInventory {
        private final boolean success;
        private final Map<String, List<String>> byBdf;
        private final String details;

        private VdpaInventory(final boolean success, final Map<String, List<String>> byBdf,
                              final String details) {
            this.success = success;
            this.byBdf = byBdf;
            this.details = details;
        }

        static VdpaInventory success(final Map<String, List<String>> byBdf) {
            return new VdpaInventory(true, byBdf, "");
        }

        static VdpaInventory failure(final String details) {
            return new VdpaInventory(false, Collections.emptyMap(), details);
        }
    }

    private static final class DomainInventory {
        private final boolean success;
        private final Map<String, List<DomainReference>> byBdf;
        private final String details;

        private DomainInventory(final boolean success, final Map<String, List<DomainReference>> byBdf,
                                final String details) {
            this.success = success;
            this.byBdf = byBdf;
            this.details = details;
        }

        static DomainInventory success(final Map<String, List<DomainReference>> byBdf) {
            return new DomainInventory(true, byBdf, "");
        }

        static DomainInventory failure(final String details) {
            return new DomainInventory(false, Collections.emptyMap(), details);
        }
    }

    private static final class DomainReference {
        private final String name;
        private final String state;
        private final boolean active;

        private DomainReference(final String name, final String state, final boolean active) {
            this.name = name;
            this.state = state;
            this.active = active;
        }
    }
}
