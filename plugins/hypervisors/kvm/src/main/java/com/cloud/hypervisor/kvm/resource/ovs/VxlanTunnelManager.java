/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.resource.ovs;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.script.Script;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Production-grade auto-plumber for OVS VXLAN tunnels between data nodes.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li><b>Dynamic peer discovery</b> — every call to
 *       {@link #ensureMeshForVni(String, int, Collection)} takes the current
 *       peer set (computed by the management server from
 *       {@code HostDao.listAllHostsUpByZoneAndHypervisor}) and reconciles
 *       with what's already on the bridge. {@code agent.properties}
 *       {@code vxlan.peers} / {@code vxlan.local.ip} are kept as a fallback
 *       only and SHOULD be absent in normal operation.</li>
 *   <li><b>Ref-counted cleanup</b> — tunnels are associated with the VM
 *       names that requested them. When the last VM using a VNI on this host
 *       disappears (stop or expunge), every {@code vxlan_<vni>_<peer>} port
 *       for that VNI is removed from the bridge.</li>
 *   <li><b>Startup re-plumb</b> — on agent restart the manager re-reads
 *       {@code /var/lib/cloudstack-agent/vxlan/state.json}, prunes any
 *       {@code vmName} whose domain is no longer {@code libvirt:RUNNING},
 *       and re-issues the plumbing commands for VNIs still referenced. The
 *       mesh therefore survives agent crashes without waiting for every VM
 *       to be re-plugged.</li>
 * </ol>
 *
 * <p>Port template (one per peer per active VNI):
 * <pre>{@code
 * ovs-vsctl --may-exist add-port <bridge> vxlan_<vni>_<lastOctet>
 *   tag=<folded_vni>
 *   -- set interface vxlan_<vni>_<lastOctet>
 *        type=vxlan
 *        options:remote_ip=<peer_mgmt_ip>
 *        options:key=<raw_vni>
 *        options:dst_port=4789
 * }</pre>
 *
 * <p>Errors are logged but never thrown: a missing tunnel is a recoverable
 * data-plane issue and must not prevent a VM from plugging or unplugging.
 * The next {@code ensureMeshForVni} call will retry.
 */
public class VxlanTunnelManager {

    private static final Logger LOGGER = LogManager.getLogger(VxlanTunnelManager.class);

    private static final String DEFAULT_BRIDGE = "br-bond";
    private static final String DEFAULT_UPLINK_DEVICE = "bond1";
    private static final int VXLAN_UDP_PORT = 4789;
    private static final int OVS_TIMEOUT_MS = 15_000;

    private static final Path STATE_DIR = Paths.get("/var/lib/cloudstack-agent/vxlan");
    private static final Path STATE_FILE = STATE_DIR.resolve("state.json");

    private final ObjectMapper objectMapper = buildObjectMapper();

    private final String bridgeName;
    private final String localIpFallback;
    /**
     * Set of all local IPv4 addresses on this host (every interface: mgmt, br-bond,
     * storage, loopback aliases), used for defensive self-peer filtering. Built once at
     * construction via {@link NetworkInterface#getNetworkInterfaces()} — no live
     * re-enumeration because the IP plan on a data node is static.
     */
    private final Set<String> localIps;
    /** Fallback peer set from agent.properties; used only when the NIC detail is absent. */
    private final Set<String> fallbackPeers;

    /** In-memory state: VM name -> VNIs plumbed for that VM on this host. */
    private final Map<String, Set<Integer>> activeVnisByVm = new TreeMap<>();
    /** The last peer set observed for a given VNI, used on ref-count drop to locate ports. */
    private final Map<Integer, Set<String>> lastPeersByVni = new TreeMap<>();

    /**
     * Build a manager by reading {@code agent.properties}. The only keys
     * consumed here are the legacy fallback pair
     * ({@code vxlan.peers}, {@code vxlan.local.ip}, {@code vxlan.local.device})
     * plus the bridge-resolution keys. In production these should be empty.
     *
     * @param agentProperties contents of {@code /etc/cloudstack/agent/agent.properties};
     *                        may be {@code null} — caller still gets a working no-op manager.
     */
    public VxlanTunnelManager(Properties agentProperties) {
        Properties props = agentProperties != null ? agentProperties : new Properties();
        this.bridgeName = resolveBridge(props);
        this.localIpFallback = resolveLocalIp(props);
        this.localIps = discoverAllLocalIpv4Addresses(this.localIpFallback);
        this.fallbackPeers = resolveFallbackPeers(props, this.localIps);
        loadState();
        LOGGER.info("VxlanTunnelManager initialized: bridge={} localIpFallback={} localIps={} fallbackPeers={} activeVms={} activeVnis={}",
                bridgeName, localIpFallback, localIps, fallbackPeers,
                activeVnisByVm.keySet(), lastPeersByVni.keySet());
    }

    /**
     * Ensure the full peer mesh of VXLAN tunnels is plumbed on the local OVS
     * bridge for the given (vmName, vni) pair, using the caller-provided peer
     * list.
     *
     * <p>Idempotent: safe to call on every VM plug. The underlying
     * {@code ovs-vsctl --may-exist} is idempotent by itself; this wrapper
     * additionally persists the ref-count so cleanup is possible on stop.
     *
     * @param vmName    the libvirt instance name (e.g. {@code i-2-414-VM},
     *                  {@code r-417-VM}) — null is tolerated and treated as
     *                  a synthetic {@code "*"} bucket (legacy callers)
     * @param vni       the raw 24-bit VXLAN Network Identifier from the NIC
     *                  broadcast URI (not the folded 12-bit OVS access tag)
     * @param peers     the authoritative peer set for this zone at call time.
     *                  When {@code null} or empty, the manager falls back to
     *                  {@link #fallbackPeers}.
     */
    public synchronized void ensureMeshForVni(String vmName, int vni, Collection<String> peers) {
        if (vni <= 0) {
            LOGGER.warn("ensureMeshForVni: ignoring non-positive vni={}", vni);
            return;
        }
        Set<String> effectivePeers = effectivePeers(peers);
        if (effectivePeers.isEmpty()) {
            LOGGER.debug("ensureMeshForVni: no peers resolved (dynamic + fallback both empty), skip vni={}", vni);
            return;
        }
        String key = vmNameKey(vmName);

        Set<String> previousPeers = lastPeersByVni.get(vni);
        boolean peersDiffer = previousPeers == null || !previousPeers.equals(effectivePeers);

        if (!peersDiffer) {
            // Peers unchanged: quick registration path, skip ovs-vsctl churn.
            boolean firstRef = activeVnisByVm.computeIfAbsent(key, k -> new TreeSet<>()).add(vni);
            if (firstRef) {
                persistState();
                LOGGER.info("ensureMeshForVni: vmName={} vni={} registered (peers unchanged, mesh already in place)",
                        key, vni);
            } else {
                LOGGER.debug("ensureMeshForVni: vmName={} vni={} already registered, no-op", key, vni);
            }
            return;
        }

        // Peer set changed (new host added, dead host removed): reconcile full mesh.
        int folded = toOvsAccessTag(vni);
        int failures = 0;
        for (String peer : effectivePeers) {
            if (!plumbPeer(vni, folded, peer)) {
                failures++;
            }
        }

        // Drop ports for peers that are gone from the new set.
        if (previousPeers != null) {
            for (String stalePeer : previousPeers) {
                if (!effectivePeers.contains(stalePeer)) {
                    removePeerPort(vni, stalePeer);
                }
            }
        }

        if (failures == 0) {
            activeVnisByVm.computeIfAbsent(key, k -> new TreeSet<>()).add(vni);
            lastPeersByVni.put(vni, new LinkedHashSet<>(effectivePeers));
            persistState();
            LOGGER.info("ensureMeshForVni: vmName={} vni={} (tag={}) plumbed to {} peer(s)",
                    key, vni, folded, effectivePeers.size());
        } else {
            LOGGER.warn("ensureMeshForVni: vmName={} vni={} had {} peer failure(s); state NOT persisted (will retry)",
                    key, vni, failures);
        }
    }

    /**
     * Legacy single-arg overload kept for callers that don't know the owning
     * VM name. Internally treats the call as the {@code "*"} bucket so the
     * tunnels stay pinned until an explicit
     * {@link #releaseVmTunnels(String)} for {@code "*"} is issued (which no
     * production path ever issues — i.e., these tunnels are effectively
     * permanent). Prefer the 3-arg form from new code paths.
     *
     * @deprecated use {@link #ensureMeshForVni(String, int, Collection)}.
     */
    @Deprecated
    public synchronized void ensureMeshForVni(int vni) {
        ensureMeshForVni(null, vni, null);
    }

    /**
     * Release every VXLAN tunnel reference owned by {@code vmName} on this
     * host. If the release brings a VNI's ref-count to zero, the
     * corresponding {@code vxlan_<vni>_<peer>} ports are removed from the
     * bridge for every peer in {@link #lastPeersByVni}.
     *
     * <p>Called from {@code LibvirtStopCommandWrapper} immediately after the
     * domain is stopped (forced or clean), and from the {@code unplug} hooks
     * of OVS/VF vif drivers for hot unplug.
     *
     * @param vmName the libvirt instance name; null / blank is a no-op
     */
    public synchronized void releaseVmTunnels(String vmName) {
        String key = vmNameKey(vmName);
        Set<Integer> ownedVnis = activeVnisByVm.remove(key);
        if (ownedVnis == null || ownedVnis.isEmpty()) {
            LOGGER.debug("releaseVmTunnels: vmName={} had no active VXLAN refs", key);
            return;
        }
        Set<Integer> stillReferenced = new HashSet<>();
        for (Set<Integer> others : activeVnisByVm.values()) {
            stillReferenced.addAll(others);
        }
        int removed = 0;
        for (Integer vni : ownedVnis) {
            if (stillReferenced.contains(vni)) {
                continue;
            }
            Set<String> peers = lastPeersByVni.remove(vni);
            if (peers == null) {
                continue;
            }
            for (String peer : peers) {
                if (removePeerPort(vni, peer)) {
                    removed++;
                }
            }
        }
        persistState();
        LOGGER.info("releaseVmTunnels: vmName={} released={} vnis, removed {} port(s) from {}",
                key, ownedVnis.size(), removed, bridgeName);
    }

    /**
     * Startup hook called from {@code LibvirtComputingResource.configure()}
     * after the manager is constructed. Iterates over the persisted
     * {@code activeVnisByVm} map, prunes VMs whose libvirt domain is no
     * longer running (using the caller-supplied predicate), and re-issues
     * {@code ensureMeshForVni} for every surviving (vmName, vni) pair.
     *
     * <p>Using a predicate (instead of taking a {@code Connect} here) keeps
     * this class decoupled from libvirt — the caller supplies it.
     *
     * @param runningDomainCheck predicate returning {@code true} when the
     *                           given VM instance name is still running
     *                           (libvirt {@code VIR_DOMAIN_RUNNING}); return
     *                           {@code true} on uncertainty to avoid false
     *                           positives, or {@code null} to treat all VMs
     *                           in the state file as still alive.
     */
    public synchronized void bootstrapFromState(java.util.function.Predicate<String> runningDomainCheck) {
        LOGGER.info("bootstrapFromState: scanning {} (vms={}, vnis={})",
                STATE_FILE, activeVnisByVm.size(), lastPeersByVni.size());
        List<String> ghosts = new ArrayList<>();
        for (String vm : new ArrayList<>(activeVnisByVm.keySet())) {
            if (runningDomainCheck != null && !runningDomainCheck.test(vm) && !"*".equals(vm)) {
                ghosts.add(vm);
            }
        }
        for (String ghost : ghosts) {
            LOGGER.info("bootstrapFromState: vmName={} no longer running, purging state", ghost);
            releaseVmTunnels(ghost);
        }
        // Re-plumb what's left. Use lastPeersByVni as the authoritative peer
        // set (that's the snapshot at last plug time). Mgmt server will
        // reconcile to fresh peers on the next VM start.
        int replumbed = 0;
        for (Map.Entry<String, Set<Integer>> e : activeVnisByVm.entrySet()) {
            String vm = e.getKey();
            for (Integer vni : e.getValue()) {
                Set<String> peers = lastPeersByVni.get(vni);
                if (peers == null || peers.isEmpty()) {
                    continue;
                }
                int folded = toOvsAccessTag(vni);
                for (String peer : peers) {
                    plumbPeer(vni, folded, peer);
                }
                replumbed++;
                LOGGER.debug("bootstrapFromState: re-plumbed vmName={} vni={} peers={}", vm, vni, peers);
            }
        }
        LOGGER.info("bootstrapFromState: done — surviving vms={} replumbed-vnis={}",
                activeVnisByVm.size(), replumbed);
    }

    /**
     * Map a (potentially &gt;4094) VXLAN VNI to a valid 12-bit OVS access tag
     * (1..4094). Shared with {@code OvsVifDriver} and {@code VfPassthroughVifDriver}
     * so every host derives the same internal tag for the same VNI.
     *
     * @param segmentId the raw VNI (or VLAN id when already in range)
     * @return folded OVS access tag in [1, 4094]
     */
    public static int toOvsAccessTag(int segmentId) {
        if (segmentId >= 1 && segmentId <= 4094) {
            return segmentId;
        }
        return ((segmentId - 1) % 4094) + 1;
    }

    /** Effective OVS bridge name resolved at construction. Exposed for tests. */
    public String getBridgeName() {
        return bridgeName;
    }

    /** Unmodifiable view of the fallback peer set (from agent.properties). */
    public Set<String> getFallbackPeers() {
        return Collections.unmodifiableSet(fallbackPeers);
    }

    /** Unmodifiable snapshot of current (vmName → vnis) state for diagnostics. */
    public synchronized Map<String, Set<Integer>> snapshotActiveVnisByVm() {
        Map<String, Set<Integer>> copy = new TreeMap<>();
        for (Map.Entry<String, Set<Integer>> e : activeVnisByVm.entrySet()) {
            copy.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Programmatic override for tests: seed an entry.
     */
    synchronized void markPlumbed(String vmName, int vni, Collection<String> peers) {
        activeVnisByVm.computeIfAbsent(vmNameKey(vmName), k -> new TreeSet<>()).add(vni);
        if (peers != null && !peers.isEmpty()) {
            lastPeersByVni.put(vni, new LinkedHashSet<>(peers));
        }
    }

    private Set<String> effectivePeers(Collection<String> peers) {
        if (peers == null || peers.isEmpty()) {
            return new LinkedHashSet<>(fallbackPeers);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String p : peers) {
            if (StringUtils.isBlank(p)) {
                continue;
            }
            String ip = p.trim();
            // Primary self-filter: membership against the full set of local IPv4
            // addresses (mgmt, br-bond, storage, loopbacks). This catches the case
            // where mgmt sends the host's br-bond IP (10.181.x) as a peer while the
            // legacy bond1 probe only ever returned empty.
            if (localIps.contains(ip)) {
                continue;
            }
            // Defensive fallback: keep honoring the legacy single-IP fallback even if
            // somehow the NetworkInterface enumeration missed it (e.g. namespace-scoped
            // NIC that agent cannot see).
            if (StringUtils.isNotBlank(localIpFallback) && ip.equals(localIpFallback)) {
                continue;
            }
            out.add(ip);
        }
        return out;
    }

    private static String vmNameKey(String vmName) {
        return StringUtils.isBlank(vmName) ? "*" : vmName.trim();
    }

    private boolean plumbPeer(int vni, int folded, String peerIp) {
        String portName = buildPortName(vni, peerIp);
        String command = String.format(
                "ovs-vsctl --may-exist add-port %s %s tag=%d"
                        + " -- set interface %s type=vxlan"
                        + " options:remote_ip=%s options:key=%d options:dst_port=%d",
                bridgeName, portName, folded,
                portName, peerIp, vni, VXLAN_UDP_PORT);
        try {
            String out = Script.runSimpleBashScript(command, OVS_TIMEOUT_MS);
            if (StringUtils.isNotBlank(out)) {
                LOGGER.debug("ovs-vsctl output (port={} peer={}): {}", portName, peerIp, out.trim());
            }
            LOGGER.info("Ensured VXLAN tunnel: bridge={} port={} peer={} vni={} tag={}",
                    bridgeName, portName, peerIp, vni, folded);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to plumb VXLAN tunnel port={} peer={} vni={}: {}",
                    portName, peerIp, vni, e.getMessage());
            return false;
        }
    }

    private boolean removePeerPort(int vni, String peerIp) {
        String portName = buildPortName(vni, peerIp);
        String command = String.format("ovs-vsctl --if-exists del-port %s %s", bridgeName, portName);
        try {
            Script.runSimpleBashScript(command, OVS_TIMEOUT_MS);
            LOGGER.info("Removed VXLAN tunnel port: bridge={} port={} peer={} vni={}",
                    bridgeName, portName, peerIp, vni);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to remove VXLAN tunnel port={} peer={} vni={}: {}",
                    portName, peerIp, vni, e.getMessage());
            return false;
        }
    }

    /**
     * Port names must be &lt;= 15 chars (Linux IFNAMSIZ) and unique per
     * (vni, peer). We use {@code vxlan_<vni>_<lastOctet>}.
     */
    static String buildPortName(int vni, String peerIp) {
        String suffix = lastOctet(peerIp);
        return String.format("vxlan_%d_%s", vni, suffix);
    }

    static String lastOctet(String ipv4) {
        if (StringUtils.isBlank(ipv4)) {
            return "x";
        }
        int idx = ipv4.lastIndexOf('.');
        if (idx < 0 || idx >= ipv4.length() - 1) {
            return ipv4;
        }
        return ipv4.substring(idx + 1);
    }

    private String resolveBridge(Properties props) {
        String bridge = props.getProperty("network.bridge.name");
        if (StringUtils.isBlank(bridge)) {
            bridge = props.getProperty("guest.bridge.name");
        }
        if (StringUtils.isBlank(bridge)) {
            bridge = props.getProperty("guest.network.device");
        }
        if (StringUtils.isBlank(bridge)) {
            bridge = DEFAULT_BRIDGE;
        }
        return bridge.trim();
    }

    /**
     * Resolve the local mgmt IP used for self-filtering.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code vxlan.local.ip} — explicit override;</li>
     *   <li>first address on {@code vxlan.local.device} (default {@code bond1});</li>
     *   <li>empty string — acceptable in the dynamic-peers path because the
     *       management server already excludes the self host when it builds
     *       the NIC detail (though we still defensively filter here).</li>
     * </ol>
     */
    private String resolveLocalIp(Properties props) {
        String explicit = props.getProperty("vxlan.local.ip");
        if (StringUtils.isNotBlank(explicit)) {
            return explicit.trim();
        }
        String device = props.getProperty("vxlan.local.device", DEFAULT_UPLINK_DEVICE).trim();
        String detected = detectIpv4OnDevice(device);
        if (StringUtils.isNotBlank(detected)) {
            return detected;
        }
        LOGGER.warn("Could not detect local mgmt IP on device {}; self-peer filtering is best-effort", device);
        return "";
    }

    static String detectIpv4OnDevice(String device) {
        if (StringUtils.isBlank(device)) {
            return null;
        }
        String cmd = String.format(
                "ip -o -4 addr show dev %s scope global | awk '{print $4}' | cut -d/ -f1 | head -n1",
                device);
        try {
            String out = Script.runSimpleBashScript(cmd);
            return out != null ? out.trim() : null;
        } catch (RuntimeException e) {
            LOGGER.warn("detectIpv4OnDevice({}) failed: {}", device, e.getMessage());
            return null;
        }
    }

    private Set<String> resolveFallbackPeers(Properties props, Set<String> selfIps) {
        String raw = props.getProperty("vxlan.peers", "");
        if (StringUtils.isBlank(raw)) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String ip = token.trim();
            if (StringUtils.isBlank(ip)) {
                continue;
            }
            if (selfIps != null && selfIps.contains(ip)) {
                continue;
            }
            result.add(ip);
        }
        return result;
    }

    /**
     * Enumerate every IPv4 address bound to any {@code up} network interface on the
     * host (excluding loopback and link-local). This gives the self-peer filter a
     * complete view: mgmt (10.182.x), br-bond (10.181.x), storage/ceph (10.185.x),
     * public (dx6 LAG), etc. The returned set is unmodifiable.
     *
     * <p>Always includes the legacy single {@code localIpFallback} when non-blank,
     * so callers who rely on it keep working even if the JDK enumeration misses it.
     *
     * @param fallbackIp legacy single mgmt IP resolved from {@code vxlan.local.ip}
     *                   or the {@code bond1} probe; folded into the returned set.
     * @return unmodifiable, insertion-ordered set of local IPv4 strings.
     */
    static Set<String> discoverAllLocalIpv4Addresses(String fallbackIp) {
        Set<String> out = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(fallbackIp)) {
            out.add(fallbackIp.trim());
        }
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            if (nifs == null) {
                return Collections.unmodifiableSet(out);
            }
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                try {
                    if (!nif.isUp() || nif.isLoopback()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr == null || addr.getAddress() == null || addr.getAddress().length != 4) {
                        continue; // IPv4 only
                    }
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                        continue;
                    }
                    out.add(addr.getHostAddress());
                }
            }
        } catch (SocketException e) {
            LOGGER.warn("discoverAllLocalIpv4Addresses: NetworkInterface enumeration failed, relying on fallback ({}): {}",
                    fallbackIp, e.getMessage());
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Persisted state file. Format (Jackson-serialized):
     * <pre>
     * {
     *   "activeVnisByVm": { "i-2-414-VM": [10826], "r-417-VM": [10826,10196] },
     *   "lastPeersByVni": { "10826": ["10.182.0.21","10.182.0.22",...] }
     * }
     * </pre>
     */
    static final class State {
        public Map<String, List<Integer>> activeVnisByVm = new HashMap<>();
        public Map<String, List<String>> lastPeersByVni = new HashMap<>();
    }

    private void loadState() {
        if (!Files.exists(STATE_FILE)) {
            return;
        }
        try {
            State state = objectMapper.readValue(STATE_FILE.toFile(), State.class);
            if (state == null) {
                return;
            }
            if (state.activeVnisByVm != null) {
                for (Map.Entry<String, List<Integer>> e : state.activeVnisByVm.entrySet()) {
                    activeVnisByVm.put(e.getKey(),
                            new TreeSet<>(e.getValue() != null ? e.getValue() : Collections.emptyList()));
                }
            }
            if (state.lastPeersByVni != null) {
                for (Map.Entry<String, List<String>> e : state.lastPeersByVni.entrySet()) {
                    try {
                        int vni = Integer.parseInt(e.getKey());
                        lastPeersByVni.put(vni,
                                new LinkedHashSet<>(e.getValue() != null ? e.getValue() : Collections.emptyList()));
                    } catch (NumberFormatException nfe) {
                        LOGGER.warn("loadState: skipping non-integer vni key '{}'", e.getKey());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read VXLAN state from {}: {}", STATE_FILE, e.getMessage());
        }
    }

    private void persistState() {
        try {
            if (!Files.exists(STATE_DIR)) {
                Files.createDirectories(STATE_DIR);
            }
            State state = new State();
            for (Map.Entry<String, Set<Integer>> e : activeVnisByVm.entrySet()) {
                state.activeVnisByVm.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            for (Map.Entry<Integer, Set<String>> e : lastPeersByVni.entrySet()) {
                state.lastPeersByVni.put(String.valueOf(e.getKey()), new ArrayList<>(e.getValue()));
            }
            Path tmp = STATE_FILE.resolveSibling("state.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, STATE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not persist VXLAN state to {}: {}", STATE_FILE, e.getMessage());
        }
    }

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /** Check whether a given bridge exists on the host. Intended for diagnostics/tests. */
    static boolean bridgeExists(String bridge) {
        if (StringUtils.isBlank(bridge)) {
            return false;
        }
        try {
            Script s = new Script("/bin/sh", 5_000L);
            s.add("-c");
            s.add("ovs-vsctl br-exists " + bridge);
            String result = s.execute(null);
            return "0".equals(result);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Probe helper for tests — returns whether the state directory exists. */
    static boolean stateDirExists() {
        return new File(STATE_DIR.toString()).isDirectory();
    }
}
