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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer;
import com.cloud.agent.api.HostVfPurgeOrphansAnswer.TargetResult;
import com.cloud.agent.api.HostVfPurgeOrphansCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.VfPassthroughVifDriver;
import com.cloud.hypervisor.kvm.resource.VfHostLifecycleLock;
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
    private static final Pattern EXPECTED_IFACE_ID = Pattern.compile(
            "lsp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);
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
                final java.util.concurrent.locks.ReentrantLock lifecycleLock = VfHostLifecycleLock.forBdf(bdf);
                lifecycleLock.lock();
                try {
                    results.add(processTarget(cmd, bdf, vdpa, domains));
                } finally {
                    lifecycleLock.unlock();
                }
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
        final String expectedInterfaceId = cmd.getExpectedInterfaceId(bdf);
        final String expectedRepresentor = cmd.getExpectedRepresentor(bdf);
        if (!EXPECTED_IFACE_ID.matcher(StringUtils.defaultString(expectedInterfaceId)).matches()
                || StringUtils.isBlank(expectedRepresentor)) {
            return withObservation(observation, false,
                    "exact representor and lsp iface-id ownership are required for destructive cleanup");
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
        return cleanupTarget(bdf, observation, vdpa.byBdf.get(bdf), expectedRepresentor, expectedInterfaceId);
    }

    /** Executes the real target mutation path; tests inject only the environment. */
    TargetResult cleanupTarget(final String bdf, final TargetResult observation,
                               final List<String> vdpaNames, final String expectedRepresentor,
                               final String expectedInterfaceId) {
        boolean vdpaRemoved = false;
        boolean representorRemoved = false;
        boolean rebound = false;
        try {
            if (vdpaNames != null) {
                for (final String name : vdpaNames) {
                    revalidateBeforeDestructiveAction(bdf, observation.getExpectedMac(), expectedRepresentor,
                            expectedInterfaceId, "vDPA deletion", true, observation.isLifecycleAuthorizationUsed());
                    requireSuccess(environment.runner.run("/usr/sbin/vdpa", "dev", "del", name),
                            "vdpa dev del " + name);
                    vdpaRemoved = true;
                }
                final VdpaInventory postVdpa = inventoryVdpa();
                if (!postVdpa.success || postVdpa.byBdf.containsKey(bdf)) {
                    throw new IllegalStateException("vDPA delete postcondition unavailable or target still present");
                }
            }

            final String representor = StringUtils.isNotBlank(expectedRepresentor)
                    ? expectedRepresentor : VfPassthroughVifDriver.lookupRepresentor(
                            bdf, environment.pciDevices, environment.netClass);
            if (representor != null) {
                removeRepresentorChecked(bdf, observation.getExpectedMac(), representor, expectedInterfaceId,
                        observation.isLifecycleAuthorizationUsed());
                representorRemoved = true;
            }
            if (observation.isDevicePresent()) {
                revalidateBeforeDestructiveAction(bdf, observation.getExpectedMac(), expectedRepresentor,
                        expectedInterfaceId, "VF rebind", true, observation.isLifecycleAuthorizationUsed());
                final Path driver = environment.pciDevices.resolve(bdf).resolve("driver");
                if (DRV_VFIO.equals(currentDriverOf(driver))) {
                    rebindOne(bdf);
                    if (!DRV_MLX5.equals(currentDriverOf(driver))) {
                        throw new IllegalStateException("VF rebind postcondition did not observe mlx5_core");
                    }
                    rebound = true;
                }
                revalidateBeforeDestructiveAction(bdf, observation.getExpectedMac(), expectedRepresentor,
                        expectedInterfaceId, "VF identity clear", true, observation.isLifecycleAuthorizationUsed());
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

    private void removeRepresentorChecked(final String bdf, final String expectedMac,
                                          final String representor, final String expectedInterfaceId,
                                          final boolean allowUnassignedMac) {
        revalidateBeforeDestructiveAction(bdf, expectedMac, representor, expectedInterfaceId,
                "OVS deletion", false, allowUnassignedMac);
        final OvsIdentity identity = discoverOvsIdentity(representor);
        if (identity == null) {
            return;
        }
        final CommandResult transaction = environment.runner.run(
                "/usr/bin/ovsdb-client", "transact", "unix:/var/run/openvswitch/db.sock",
                buildOvsDeleteTransaction(identity, expectedInterfaceId));
        requireSuccess(transaction, "atomic OVS representor deletion " + representor);
        validateOvsTransaction(transaction.output, identity);
        if (discoverOvsIdentity(representor) != null) {
            throw new IllegalStateException("OVS representor postcondition ambiguous or row was recreated: " + representor);
        }
    }

    private OvsIdentity discoverOvsIdentity(final String representor) {
        final JsonArray interfaceWhere = whereEquals("name", stringAtom(representor));
        final JsonArray interfaceRows = ovsdbSelect("Interface", interfaceWhere,
                List.of("_uuid", "name", "external_ids"));
        final JsonArray portRows = ovsdbSelect("Port", whereEquals("name", stringAtom(representor)),
                List.of("_uuid", "name", "interfaces"));
        if (interfaceRows.size() == 0 && portRows.size() == 0) {
            return null;
        }
        if (interfaceRows.size() != 1 || portRows.size() != 1) {
            throw new IllegalStateException("OVS discovery did not identify exactly one Interface and Port");
        }
        final JsonObject iface = interfaceRows.get(0).getAsJsonObject();
        final JsonObject port = portRows.get(0).getAsJsonObject();
        final String ifaceUuid = uuid(iface.get("_uuid"));
        final String portUuid = uuid(port.get("_uuid"));
        if (!representor.equals(text(port.get("name"))) || !representor.equals(text(iface.get("name")))) {
            throw new IllegalStateException("OVS discovery name changed for " + representor);
        }
        final JsonArray interfaces = unwrapSet(port.getAsJsonArray("interfaces"));
        if (interfaces == null || interfaces.size() != 1 || !ifaceUuid.equals(uuid(interfaces.get(0)))) {
            throw new IllegalStateException("OVS Port interface set changed for " + representor);
        }
        final JsonArray bridgeRows = ovsdbSelect("Bridge", whereIncludes("ports", uuidAtom(portUuid)),
                List.of("_uuid", "name", "ports"));
        if (bridgeRows.size() != 1) {
            throw new IllegalStateException("OVS discovery did not identify exactly one Bridge");
        }
        final JsonObject bridge = bridgeRows.get(0).getAsJsonObject();
        final String bridgeUuid = uuid(bridge.get("_uuid"));
        if (StringUtils.isBlank(text(bridge.get("name")))) {
            throw new IllegalStateException("OVS Bridge name is missing");
        }
        return new OvsIdentity(bridgeUuid, text(bridge.get("name")), portUuid, ifaceUuid, representor);
    }

    private JsonArray ovsdbSelect(final String table, final JsonArray where, final List<String> columns) {
        final JsonObject operation = new JsonObject();
        operation.addProperty("op", "select");
        operation.addProperty("table", table);
        operation.add("where", where);
        final JsonArray columnArray = new JsonArray();
        columns.forEach(columnArray::add);
        operation.add("columns", columnArray);
        final JsonArray request = new JsonArray();
        request.add("Open_vSwitch");
        request.add(operation);
        final CommandResult result = environment.runner.run("/usr/bin/ovsdb-client", "transact",
                "unix:/var/run/openvswitch/db.sock", new Gson().toJson(request));
        requireSuccess(result, "read OVS " + table);
        final JsonArray response = parseArray(result.output, "OVS " + table + " discovery");
        if (response.size() != 1 || !response.get(0).isJsonObject()) {
            throw new IllegalStateException("malformed OVS " + table + " discovery response");
        }
        final JsonObject resultObject = response.get(0).getAsJsonObject();
        if (resultObject.has("error") || !resultObject.has("rows") || !resultObject.get("rows").isJsonArray()) {
            throw new IllegalStateException("OVS " + table + " discovery failed");
        }
        return resultObject.getAsJsonArray("rows");
    }

    private String buildOvsDeleteTransaction(final OvsIdentity identity, final String expectedInterfaceId) {
        final JsonArray operations = new JsonArray();
        operations.add(waitOperation("Interface", whereAll(whereEquals("_uuid", uuidAtom(identity.interfaceUuid),
                "name", stringAtom(identity.name)),
                whereIncludes("external_ids", includesMap("iface-id", expectedInterfaceId))),
                List.of("_uuid", "name", "external_ids"), new JsonArray(), "!=", "Interface"));
        operations.add(waitOperation("Port", whereAll(whereEquals("_uuid", uuidAtom(identity.portUuid),
                "name", stringAtom(identity.name), "interfaces", equalsSet(identity.interfaceUuid))),
                List.of("_uuid", "name", "interfaces"), new JsonArray(), "!=", "Port"));
        operations.add(waitOperation("Bridge", whereAll(whereEquals("_uuid", uuidAtom(identity.bridgeUuid),
                "name", stringAtom(identity.bridgeName)), whereIncludes("ports", includesUuid(identity.portUuid))),
                List.of("_uuid", "name", "ports"), new JsonArray(), "!=", "Bridge"));

        final JsonObject mutate = new JsonObject();
        mutate.addProperty("op", "mutate");
        mutate.addProperty("table", "Bridge");
        mutate.add("where", whereEquals("_uuid", uuidAtom(identity.bridgeUuid)));
        final JsonArray mutations = new JsonArray();
        final JsonArray mutation = new JsonArray();
        mutation.add("ports");
        mutation.add("delete");
        mutation.add(singletonUuid(identity.portUuid));
        mutations.add(mutation);
        mutate.add("mutations", mutations);
        operations.add(mutate);

        final JsonObject delete = new JsonObject();
        delete.addProperty("op", "delete");
        delete.addProperty("table", "Port");
        delete.add("where", whereAll(whereEquals("_uuid", uuidAtom(identity.portUuid), "name",
                stringAtom(identity.name), "interfaces", equalsSet(identity.interfaceUuid))));
        operations.add(delete);
        final JsonArray request = new JsonArray();
        request.add("Open_vSwitch");
        request.addAll(operations);
        return new Gson().toJson(request);
    }

    private JsonObject waitOperation(final String table, final JsonArray where, final List<String> columns,
                                     final JsonArray rows, final String until, final String label) {
        final JsonObject operation = new JsonObject();
        operation.addProperty("op", "wait");
        operation.addProperty("table", table);
        operation.add("where", where);
        final JsonArray columnArray = new JsonArray();
        columns.forEach(columnArray::add);
        operation.add("columns", columnArray);
        operation.addProperty("until", until);
        operation.add("rows", rows);
        operation.addProperty("timeout", "immediate");
        operation.addProperty("comment", label);
        return operation;
    }

    private JsonArray whereAll(final JsonArray... clauses) {
        final JsonArray result = new JsonArray();
        for (JsonArray clause : clauses) {
            result.add(clause);
        }
        return result;
    }

    private JsonArray whereEquals(final String field, final JsonElement value, final Object... additional) {
        final JsonArray result = new JsonArray();
        addEquals(result, field, value);
        for (int index = 0; index < additional.length; index += 2) {
            addEquals(result, (String) additional[index], (JsonElement) additional[index + 1]);
        }
        return result;
    }

    private JsonArray whereIncludes(final String field, final JsonElement value) {
        final JsonArray clause = new JsonArray();
        clause.add(field);
        clause.add("includes");
        clause.add(value);
        final JsonArray result = new JsonArray();
        result.add(clause);
        return result;
    }

    private JsonArray includesMap(final String key, final String value) {
        final JsonArray pair = new JsonArray();
        pair.add(key);
        pair.add(value);
        final JsonArray pairs = new JsonArray();
        pairs.add(pair);
        final JsonArray map = new JsonArray();
        map.add("map");
        map.add(pairs);
        return map;
    }

    private JsonArray equalsSet(final String uuid) {
        final JsonArray set = new JsonArray();
        set.add("set");
        final JsonArray values = new JsonArray();
        values.add(uuidAtom(uuid));
        set.add(values);
        return set;
    }

    private JsonArray singletonUuid(final String uuid) {
        final JsonArray set = new JsonArray();
        set.add("set");
        final JsonArray values = new JsonArray();
        values.add(uuidAtom(uuid));
        set.add(values);
        return set;
    }

    private JsonArray unwrapSet(final JsonArray value) {
        if (value == null) {
            return null;
        }
        if (value.size() == 2 && "set".equals(value.get(0).getAsString())) {
            return value.get(1).getAsJsonArray();
        }
        return value;
    }

    private JsonArray includesUuid(final String uuid) {
        return singletonUuid(uuid);
    }

    private JsonArray uuidAtom(final String uuid) {
        final JsonArray atom = new JsonArray();
        atom.add("uuid");
        atom.add(uuid);
        return atom;
    }

    private JsonArray stringAtom(final String value) {
        final JsonArray atom = new JsonArray();
        atom.add("string");
        atom.add(value);
        return atom;
    }

    private void addEquals(final JsonArray where, final String field, final JsonElement value) {
        final JsonArray clause = new JsonArray();
        clause.add(field);
        clause.add("==");
        clause.add(value);
        where.add(clause);
    }

    private JsonArray parseArray(final String output, final String operation) {
        try {
            final JsonElement parsed = new JsonParser().parse(output);
            if (!parsed.isJsonArray()) {
                throw new IllegalStateException("malformed " + operation + " response");
            }
            return parsed.getAsJsonArray();
        } catch (RuntimeException e) {
            throw new IllegalStateException("malformed " + operation + " response", e);
        }
    }

    private String uuid(final JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 2
                || !"uuid".equals(value.getAsJsonArray().get(0).getAsString())) {
            throw new IllegalStateException("OVS response did not contain a UUID");
        }
        return value.getAsJsonArray().get(1).getAsString();
    }

    private String text(final JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 2) {
            throw new IllegalStateException("OVS response did not contain a typed string");
        }
        return value.getAsJsonArray().get(1).getAsString();
    }

    private void validateOvsTransaction(final String output, final OvsIdentity identity) {
        final JsonArray response = parseArray(output, "atomic OVS deletion");
        if (response.size() != 5) {
            throw new IllegalStateException("atomic OVS deletion returned an unexpected operation count");
        }
        for (int index = 0; index < response.size(); index++) {
            final JsonElement element = response.get(index);
            if (!element.isJsonObject() || element.getAsJsonObject().has("error")) {
                throw new IllegalStateException("atomic OVS deletion operation failed");
            }
            final JsonObject result = element.getAsJsonObject();
            if (index <= 2 && (!result.has("rows") || result.getAsJsonArray("rows").size() != 1)) {
                throw new IllegalStateException("atomic OVS wait did not prove ownership");
            }
            if (index >= 3 && (!result.has("count") || result.get("count").getAsInt() != 1)) {
                throw new IllegalStateException("atomic OVS mutation did not affect exactly one row");
            }
        }
    }

    private void revalidateBeforeDestructiveAction(final String bdf, final String expectedMac,
                                                   final String expectedRepresentor, final String expectedInterfaceId,
                                                   final String action, final boolean requireMac,
                                                   final boolean allowUnassignedMac) {
        final VdpaInventory vdpa = inventoryVdpa();
        final DomainInventory domains = inventoryDomains(vdpa);
        if (!vdpa.success || !domains.success) {
            throw new IllegalStateException(action + " revalidation inventory unavailable");
        }
        if (!domains.byBdf.getOrDefault(bdf, Collections.emptyList()).isEmpty()) {
            throw new IllegalStateException(action + " blocked: domain references target BDF");
        }
        if (!EXPECTED_IFACE_ID.matcher(StringUtils.defaultString(expectedInterfaceId)).matches()
                || StringUtils.isBlank(expectedRepresentor)) {
            throw new IllegalStateException(action + " blocked: exact representor ownership evidence is missing");
        }
        final boolean present = Files.isDirectory(environment.pciDevices.resolve(bdf));
        if (requireMac && !present) {
            throw new IllegalStateException(action + " blocked: target BDF disappeared");
        }
        if (present) {
            final MacObservation mac = readVfMacExact(bdf);
            final boolean expected = matchesExpectedMac(expectedMac, mac.mac);
            final boolean authorizedUnassigned = allowUnassignedMac && MAC_UNASSIGNED_ZERO.equals(mac.status);
            if (mac.isReadError() || (!expected && !authorizedUnassigned)) {
                throw new IllegalStateException(action + " blocked: VF MAC evidence changed");
            }
            if (VfPassthroughVifDriver.lookupPfFromVf(bdf, environment.pciDevices, environment.netClass) == null
                    || VfPassthroughVifDriver.lookupVfIdFromPci(bdf, environment.pciDevices) == null) {
                throw new IllegalStateException(action + " blocked: VF topology changed");
            }
        }
        if (action.startsWith("OVS") && !hasInterfaceId(expectedRepresentor, expectedInterfaceId)) {
            throw new IllegalStateException(action + " blocked: OVS iface-id ownership changed");
        }
    }

    private boolean hasInterfaceId(final String representor, final String expectedInterfaceId) {
        final CommandResult ids = environment.runner.run("/usr/bin/ovs-vsctl", "get", "Interface", representor,
                "external_ids:iface-id");
        return ids.success && expectedInterfaceId.equals(unquote(ids.output));
    }

    private String unquote(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
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

    private static final class OvsIdentity {
        private final String bridgeUuid;
        private final String bridgeName;
        private final String portUuid;
        private final String interfaceUuid;
        private final String name;

        private OvsIdentity(final String bridgeUuid, final String bridgeName, final String portUuid,
                            final String interfaceUuid, final String name) {
            this.bridgeUuid = bridgeUuid;
            this.bridgeName = bridgeName;
            this.portUuid = portUuid;
            this.interfaceUuid = interfaceUuid;
            this.name = name;
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
