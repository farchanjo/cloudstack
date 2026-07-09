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
package com.cloud.network.ovn.manager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.OvnOvsPolicySweepAnswer;
import com.cloud.agent.api.OvnOvsPolicySweepCommand;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.config.OvnLspAddresses;
import com.cloud.network.ovn.config.OvnNetworkConfig;
import com.cloud.network.ovn.config.OvnNicConfig;
import com.cloud.network.ovn.dao.OvnChassisMapDao;
import com.cloud.network.ovn.dao.OvnChassisMapVO;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.element.OvnConstants;
import com.cloud.network.ovn.element.OvnPublicNetworkManager;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

/**
 * Periodic / on-demand reconciler. Walks every NB table and the mapping
 * DAO, detects bidirectional drift (orphan NB rows tagged with
 * {@code cs_id}/{@code cs_kind} but no mapping row pointing at them; mapping
 * rows pointing at UUIDs the NB DB no longer holds), and cleans both sides.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code dryRun=true}: count + log only, no NB / DAO mutation.</li>
 *   <li>{@code dryRun=false}: drop orphan NB rows + stale mapping rows.</li>
 * </ul>
 *
 * <p>Designed to be safe to run any time on a healthy plugin — every read
 * path already self-heals on stale mapping (see {@code rowExistsByUuid}
 * guards across the ensure* helpers); the reaper just collapses pre-existing
 * drift in one shot instead of waiting for the next per-entity touch.
 *
 * <p>Tables walked:
 * <ul>
 *   <li>{@code DHCP_Options}, {@code DNS} — easy: cs_kind in external_ids,
 *       no cascading parent ref to manage.</li>
 *   <li>{@code NAT}, {@code ACL}, {@code Load_Balancer},
 *       {@code Logical_Switch_Port}, {@code Logical_Router_Port},
 *       {@code Logical_Switch}, {@code Logical_Router},
 *       {@code HA_Chassis_Group} — handled, with the existing detach-then-
 *       delete helpers in {@link OvnNbClient} to keep referential integrity.</li>
 * </ul>
 */
@Component
public class OvnReconcilerService {

    private static final Logger LOGGER = LogManager.getLogger(OvnReconcilerService.class);

    /** Per-table reaper kind: which mapping {@link Kind} the row should
     *  be paired with for a non-orphan classification. */
    private static final Map<String, Kind[]> TABLE_KINDS = buildTableKinds();

    /** Default OVN integration bridge swept on every chassis. */
    static final String DEFAULT_BRIDGE = "br-int";

    /** Fallback port-name regex when {@link OvnNicConfig#OvsSweepPortRegex} is
     *  blank. Covers the VF representor pattern ({@code dx<NN>p<NN>vf<NN>}); the
     *  ConfigKey default widens this to also match vDPA representors and
     *  virtio/tap ({@code vnet<NN>}) ports. */
    static final String DEFAULT_PORT_REGEX = "^dx[0-9]+p[0-9]+vf[0-9]+$";

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private OvnPublicNetworkManager publicNetworkManager;
    @Inject
    private OvnChassisMapDao chassisMapDao;
    @Inject
    private AgentManager agentManager;

    /**
     * Run a reconcile pass against the supplied zone's NB DB.
     *
     * @param zoneId  CloudStack zone id (selects the controller +
     *                {@link OvnNbClient}).
     * @param dryRun  when {@code true}, do not mutate; just count.
     * @return summary keyed by table name -&gt; (orphans, stale-mappings).
     */
    public Result reconcileZone(final long zoneId, final boolean dryRun) {
        return reconcileZone(zoneId, dryRun, false);
    }

    /**
     * Same as {@link #reconcileZone(long, boolean)} but with a switch to
     * also purge rows whose {@code external_ids} map is empty / missing
     * the {@code cs_kind} tag. Those are typically left over from manual
     * {@code ovn-nbctl} sessions or pre-plugin operator activity — never
     * created by the plugin itself. Off by default; the caller has to opt
     * in explicitly because there is no way for the plugin to tell an
     * operator-managed untagged row apart from a stale one.
     */
    public Result reconcileZone(final long zoneId, final boolean dryRun, final boolean purgeUntagged) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new OvnException("OvnReconcilerService: no controller for zone " + zoneId);
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final Result out = new Result(dryRun);
        for (final Map.Entry<String, Kind[]> entry : TABLE_KINDS.entrySet()) {
            final String table = entry.getKey();
            final Kind[] kinds = entry.getValue();
            sweepOrphanNbRows(nb, controller, table, kinds, dryRun, out);
            sweepStaleMappings(nb, controller, table, kinds, dryRun, out);
            if (purgeUntagged) {
                sweepUntaggedRows(nb, controller, table, dryRun, out);
            }
        }
        // Legacy migration sweep — drop Load_Balancer rows still tagged with
        // cs_kind=PORT_FORWARDING (pre-migration shape). Any mapping whose
        // ovn_uuid matches one of these LB rows will get rebuilt as a NAT
        // row on the next applyPF call; the row itself is now orphan from
        // the LR.load_balancer set perspective so we drop it.
        sweepLegacyPortForwardingLb(nb, controller, dryRun, out);
        // Public localnet VLAN drift sweep — keeps the per-zone localnet LSP
        // tag aligned with operator config. Runs unconditionally because the
        // resolution chain itself is the toggle: ovn.public.vlan.auto=false
        // and override=0 makes the resolver return null, which never
        // mismatches an already-untagged row.
        sweepPublicLocalnetVlanDrift(nb, zoneId, dryRun, out);
        // BGP_ANNOUNCE rows hold no NB row reference — they live entirely in
        // the mapping DAO. Sweep stale entries (owning IP deleted) so the
        // periodic reconciler in OvnBgpRedistributeManager doesn't keep
        // re-announcing for a long-gone IP.
        sweepStaleBgpAnnounceRows(controller, dryRun, out);
        // OVS port hairpin + bridge tc-policy drift — agent-side per-plug
        // enforcement does the canonical correction; this records intent
        // so the reconcile API exposes the categories.
        reassertOvsPolicy(zoneId, dryRun, out);
        // East-west LB force-SNAT drift — a router carrying LBs must SNAT
        // load-balanced flows to its own IP (lb_force_snat_ip=router_ip) or
        // same-subnet clients never see VIP replies (asymmetric return).
        // Attach-time enforcement covers new LBs; this covers routers whose
        // LBs pre-date the option.
        if (!dryRun) {
            final int snatFixed = nb.ensureLbForceSnatOnRoutersWithLb();
            if (snatFixed > 0) {
                LOGGER.info("OvnReconcilerService: zone={} set {}={} on {} logical router(s) carrying LBs",
                        zoneId, OvnNbClient.LR_OPT_LB_FORCE_SNAT, OvnNbClient.LB_FORCE_SNAT_ROUTER_IP, snatFixed);
            }
        }
        // Per-network extra CIDR port-security resync — repairs EXISTING guest
        // LSPs so CKS pod / LB-VIP / dual-stack-v6 frames survive OVN's spoof
        // guard without recreating VMs. Self-gated: no-op when the ConfigKey is
        // empty; touches only NICs on networks listed in the map.
        final Map<String, List<String>> extraPs =
                OvnLspAddresses.parse(OvnNetworkConfig.LspExtraPortSecurityCidrs.value());
        final int psFixed = resyncLspExtraPortSecurity(nb, controller, extraPs, dryRun);
        if (psFixed > 0) {
            LOGGER.info("OvnReconcilerService: zone={} extra port-security resync {} {} LSP(s)",
                    zoneId, dryRun ? "would fix" : "fixed", psFixed);
        }
        LOGGER.info("OvnReconcilerService: zone={} dryRun={} purgeUntagged={} orphansFound={} staleMappingsFound={}",
                zoneId, dryRun, purgeUntagged, out.totalOrphans(), out.totalStaleMappings());
        return out;
    }

    /**
     * Zone-scoped entry point for the extra-CIDR port-security resync. Reads
     * the ConfigKey, resolves the controller + {@link OvnNbClient}, and
     * re-applies the extras to every affected guest LSP. Called from the
     * periodic reconcile loop ({@link OvnBgpReconcileTask}) so a management
     * restart or a config change self-heals all ports. Idempotent.
     *
     * @param zoneId CloudStack zone id
     * @param dryRun when {@code true}, count only, do not mutate the NB DB
     * @return number of LSPs (that would have been) re-stamped
     */
    public int resyncLspExtraPortSecurityForZone(final long zoneId, final boolean dryRun) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return 0;
        }
        final Map<String, List<String>> extras =
                OvnLspAddresses.parse(OvnNetworkConfig.LspExtraPortSecurityCidrs.value());
        if (extras.isEmpty()) {
            return 0;
        }
        final int fixed = resyncLspExtraPortSecurity(pluginManager.nbClient(zoneId), controller, extras, dryRun);
        if (fixed > 0) {
            LOGGER.info("OvnReconcilerService: zone={} extra port-security resync {} {} LSP(s)",
                    zoneId, dryRun ? "would fix" : "fixed", fixed);
        }
        return fixed;
    }

    /**
     * Re-stamp {@code addresses} + {@code port_security} on every guest
     * ({@link Kind#NIC} / {@link Kind#ORPHAN_NIC}) LSP whose network appears in
     * {@code extrasByNetworkUuid}, appending that network's extra CIDRs to the
     * NIC's MAC+IP token. Networks absent from the map are never touched (zero
     * regression). Empty/{@code null} map returns immediately.
     *
     * @return count of LSPs (that would have been) updated
     */
    int resyncLspExtraPortSecurity(final OvnNbClient nb, final OvnControllerVO controller,
                                   final Map<String, List<String>> extrasByNetworkUuid, final boolean dryRun) {
        if (extrasByNetworkUuid == null || extrasByNetworkUuid.isEmpty()) {
            return 0;
        }
        int fixed = 0;
        for (final Kind kind : new Kind[]{Kind.NIC, Kind.ORPHAN_NIC}) {
            for (final OvnLogicalIdMapVO mapping : logicalIdMapDao.listByKind(kind, controller.getId())) {
                if (applyExtraPortSecurity(nb, mapping, extrasByNetworkUuid, dryRun)) {
                    fixed++;
                }
            }
        }
        return fixed;
    }

    /**
     * Apply the network's extra CIDRs to a single NIC LSP. No-op (returns
     * {@code false}) when the NIC/network is gone or the network carries no
     * configured extras.
     */
    private boolean applyExtraPortSecurity(final OvnNbClient nb, final OvnLogicalIdMapVO mapping,
                                           final Map<String, List<String>> extrasByNetworkUuid, final boolean dryRun) {
        final NicVO nic = nicDao.findById(mapping.getCsId());
        if (nic == null) {
            return false;
        }
        final Network network = networkDao.findById(nic.getNetworkId());
        if (network == null) {
            return false;
        }
        final List<String> extras = extrasByNetworkUuid.get(network.getUuid());
        if (extras == null || extras.isEmpty()) {
            return false;
        }
        if (dryRun) {
            return true;
        }
        final List<String> addresses = OvnLspAddresses.compose(nic.getMacAddress(),
                nic.getIPv4Address(), nic.getIPv6Address(), extras);
        try {
            nb.updateLogicalSwitchPortAddresses(mapping.getOvnUuid(), addresses);
            return true;
        } catch (OvnException e) {
            LOGGER.warn("OvnReconcilerService: LSP {} extra port-security resync failed: {}",
                    mapping.getOvnUuid(), e.getMessage());
            return false;
        }
    }

    /**
     * Re-assert the OVS port-level hairpin flag and the bridge-wide tc-policy
     * on every chassis the plugin owns. Per-plug enforcement covers freshly
     * attached NICs; this sweep covers ports that pre-date the current
     * plugin version, external drift (operator running raw {@code ovs-vsctl}),
     * and any post-restart inconsistencies on the OVS DB.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Resolve global defaults from {@link OvnNicConfig} ConfigKeys
     *       ({@code ovn.ovs.hairpin}, {@code ovn.ovs.tc.policy}). When both
     *       are unset, the sweep is skipped.</li>
     *   <li>List every {@link OvnChassisMapVO} for the zone's controller.</li>
     *   <li>Send {@link OvnOvsPolicySweepCommand} to each chassis host via
     *       {@link AgentManager#easySend} (no answer = agent offline /
     *       wrapper missing; logged + skipped).</li>
     *   <li>Aggregate per-host counts into the {@link Result} under the
     *       synthetic table keys {@link Result#OVS_HAIRPIN_TABLE} (drift)
     *       and {@link Result#OVS_TC_POLICY_TABLE} (apply ack).</li>
     * </ol>
     *
     * <p>Wire-compat: agents predating the wrapper return
     * {@code Unsupported command}; the loop logs WARN and continues. The
     * per-plug path remains the canonical correction in that case.
     *
     * @param zoneId  CloudStack zone id
     * @param dryRun  when {@code true}, the agent reports drift but does not
     *                mutate the OVS DB
     * @param out     collector for the drift counts
     */
    public void reassertOvsPolicy(final long zoneId, final boolean dryRun, final Result out) {
        if (out == null) {
            return;
        }
        out.recordOvsPolicyAck(zoneId);

        final Boolean hairpinDefault = resolveHairpinDefault();
        final String tcPolicyDefault = resolveTcPolicyDefault();
        if (hairpinDefault == null && StringUtils.isBlank(tcPolicyDefault)) {
            LOGGER.debug("OvnReconcilerService.reassertOvsPolicy: zone={} no defaults configured; sweep skipped",
                    zoneId);
            return;
        }

        if (chassisMapDao == null || agentManager == null) {
            LOGGER.debug("OvnReconcilerService.reassertOvsPolicy: zone={} chassisMapDao/agentManager unavailable; "
                    + "per-plug enforcement is canonical", zoneId);
            return;
        }
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            LOGGER.debug("OvnReconcilerService.reassertOvsPolicy: zone={} no controller", zoneId);
            return;
        }
        final List<OvnChassisMapVO> chassisRows = chassisMapDao.listByController(controller.getId());
        if (chassisRows == null || chassisRows.isEmpty()) {
            LOGGER.debug("OvnReconcilerService.reassertOvsPolicy: zone={} no chassis registered", zoneId);
            return;
        }

        final String portRegex = resolveSweepPortRegex();
        for (final OvnChassisMapVO row : chassisRows) {
            sweepOneChassis(row.getHostId(), hairpinDefault, tcPolicyDefault, portRegex, dryRun, out);
        }
    }

    /**
     * Send the drift-sweep command to a single chassis and fold the result
     * into {@code out}. Agents that do not implement the wrapper or that
     * fail to answer are recorded as zero-fix entries — drift count stays
     * unchanged so the operator can re-run the sweep after agent recovery.
     */
    private void sweepOneChassis(final long hostId, final Boolean hairpinDefault, final String tcPolicy,
                                 final String portRegex, final boolean dryRun, final Result out) {
        final OvnOvsPolicySweepCommand cmd = new OvnOvsPolicySweepCommand(
                DEFAULT_BRIDGE, hairpinDefault, tcPolicy, portRegex, dryRun);
        try {
            final Answer answer = agentManager.easySend(hostId, cmd);
            if (answer == null) {
                LOGGER.warn("OvnReconcilerService.reassertOvsPolicy: host={} no answer (offline or wrapper missing)",
                        hostId);
                return;
            }
            if (!(answer instanceof OvnOvsPolicySweepAnswer)) {
                LOGGER.warn("OvnReconcilerService.reassertOvsPolicy: host={} unexpected answer type {} ({})",
                        hostId, answer.getClass().getSimpleName(), answer.getDetails());
                return;
            }
            if (!answer.getResult()) {
                LOGGER.warn("OvnReconcilerService.reassertOvsPolicy: host={} sweep failed: {}",
                        hostId, answer.getDetails());
                return;
            }
            final OvnOvsPolicySweepAnswer sweep = (OvnOvsPolicySweepAnswer) answer;
            out.recordOvsPolicySweep(hostId, sweep.getPortsScanned(), sweep.getHairpinDrifted(),
                    sweep.getHairpinFixed(), sweep.isTcPolicyApplied());
            if (sweep.getHairpinDrifted() > 0) {
                LOGGER.info("OvnReconcilerService.reassertOvsPolicy: host={} scanned={} drifted={} fixed={} "
                                + "tcApplied={} dryRun={}", hostId, sweep.getPortsScanned(),
                        sweep.getHairpinDrifted(), sweep.getHairpinFixed(), sweep.isTcPolicyApplied(), dryRun);
            }
        } catch (RuntimeException re) {
            LOGGER.warn("OvnReconcilerService.reassertOvsPolicy: host={} threw: {}", hostId, re.getMessage());
        }
    }

    /** Resolve the global hairpin default; surfaces the ConfigKey value. */
    Boolean resolveHairpinDefault() {
        try {
            return OvnNicConfig.OvsHairpin.value();
        } catch (RuntimeException re) {
            LOGGER.debug("OvnReconcilerService.resolveHairpinDefault: ConfigKey lookup failed: {}", re.getMessage());
            return null;
        }
    }

    /** Resolve the global tc-policy default; surfaces the ConfigKey value. */
    String resolveTcPolicyDefault() {
        try {
            return OvnNicConfig.OvsTcPolicy.value();
        } catch (RuntimeException re) {
            LOGGER.debug("OvnReconcilerService.resolveTcPolicyDefault: ConfigKey lookup failed: {}", re.getMessage());
            return null;
        }
    }

    /** Resolve the sweep port-name regex; falls back to the VF-only default
     *  when the ConfigKey is blank so a cleared value never means match-all
     *  (which would stamp hairpin on infra ports like patch / localnet). */
    String resolveSweepPortRegex() {
        try {
            final String v = OvnNicConfig.OvsSweepPortRegex.value();
            return StringUtils.isBlank(v) ? DEFAULT_PORT_REGEX : v;
        } catch (RuntimeException re) {
            LOGGER.debug("OvnReconcilerService.resolveSweepPortRegex: ConfigKey lookup failed: {}", re.getMessage());
            return DEFAULT_PORT_REGEX;
        }
    }

    /**
     * Sweep stale {@link Kind#BGP_ANNOUNCE} mapping rows. These rows are
     * pure bookkeeping (no NB row reference) — the regular orphan / stale
     * sweep skips them because they are not registered in
     * {@link #TABLE_KINDS}. The cleanup criterion: the owning IP address
     * was removed from CloudStack while the announce was still live.
     * The plugin's own withdraw path normally drops these rows on IP
     * release; this sweep catches the failure modes (plugin disabled at
     * release time, mgmt crash mid-revoke).
     */
    private void sweepStaleBgpAnnounceRows(final OvnControllerVO controller, final boolean dryRun,
                                           final Result out) {
        final List<OvnLogicalIdMapVO> rows = logicalIdMapDao.listByKind(Kind.BGP_ANNOUNCE, controller.getId());
        for (final OvnLogicalIdMapVO row : rows) {
            if (cloudstackEntityExists(Kind.BGP_ANNOUNCE, row.getCsId())) {
                continue;
            }
            out.recordStaleMapping("BGP_ANNOUNCE", row);
            if (!dryRun) {
                logicalIdMapDao.remove(row.getId());
                LOGGER.info("OvnReconcilerService: dropped stale BGP_ANNOUNCE row ip_id={} host={}",
                        row.getCsId(), row.getOvnUuid());
            }
        }
    }

    /**
     * Detect VLAN drift on the per-zone public localnet LSP. When the
     * resolved VLAN tag (from ConfigKey + auto-detect) differs from the
     * value programmed on the row, the reconciler reports it under the
     * synthetic table key {@code Logical_Switch_Port_VLAN}, and (when not
     * in dryRun) rewrites the tag in place.
     */
    private void sweepPublicLocalnetVlanDrift(final OvnNbClient nb, final long zoneId,
                                              final boolean dryRun, final Result out) {
        if (publicNetworkManager == null) {
            return;
        }
        final String lspUuid;
        try {
            lspUuid = publicNetworkManager.findPublicLocalnetLspUuid(zoneId);
        } catch (RuntimeException re) {
            LOGGER.debug("OvnReconcilerService: VLAN drift sweep skipped (lookup failed): {}", re.getMessage());
            return;
        }
        if (lspUuid == null) {
            return;
        }
        final Integer current = nb.getLogicalSwitchPortTag(lspUuid);
        final Integer desired = publicNetworkManager.resolvePublicLocalnetVlan(zoneId, null);
        if (java.util.Objects.equals(current, desired)) {
            return;
        }
        out.recordVlanDrift(lspUuid, current, desired);
        if (!dryRun) {
            try {
                nb.setLogicalSwitchPortTag(lspUuid, desired);
                LOGGER.info("OvnReconcilerService: public localnet vlan drift-fix {} -> {} (lsp={}, zone={})",
                        current, desired, lspUuid, zoneId);
            } catch (OvnException e) {
                LOGGER.warn("OvnReconcilerService: public localnet vlan drift-fix failed (lsp={}): {}",
                        lspUuid, e.getMessage());
            }
        }
    }

    /**
     * Walk all rows in {@code table} and drop anything whose
     * {@code external_ids} is empty OR lacks the {@code cs_kind} key. Used
     * only when {@code purgeUntagged=true}; off by default because operator-
     * created rows look identical to the plugin's view here. Limited to
     * tables where this kind of pollution was observed in the field
     * (DHCP_Options, DNS, ACL); other tables are skipped to keep the
     * destructive surface narrow.
     */
    /**
     * Walk all rows in {@code table} and drop anything whose
     * {@code external_ids} is empty OR lacks the {@code cs_kind} key. Used
     * only when {@code purgeUntagged=true}; off by default because operator-
     * created rows look identical to the plugin's view here. Limited to
     * tables where this kind of pollution was observed in the field
     * (DHCP_Options, DNS, ACL, HA_Chassis_Group); other tables are skipped
     * to keep the destructive surface narrow.
     *
     * <p>{@code HA_Chassis_Group}: only purge if the row is not referenced by
     * any {@link Kind#HA_CHASSIS_GROUP} mapping AND has empty/null
     * external_ids. Active groups are protected by the mapping-table check.
     */
    private void sweepUntaggedRows(final OvnNbClient nb, final OvnControllerVO controller,
                                   final String table, final boolean dryRun, final Result out) {
        if (!"DHCP_Options".equals(table) && !"DNS".equals(table)
                && !"ACL".equals(table) && !"HA_Chassis_Group".equals(table)) {
            return;
        }
        // Empty-string-on-key match returns rows that explicitly have no
        // cs_kind tag. The findUuids helper expects a value match, so we
        // emulate by filtering all rows whose tagged-uuid set excludes
        // every known kind. Cheap because tables stay small.
        final java.util.Set<String> tagged = new java.util.HashSet<>();
        for (final Kind k : TABLE_KINDS.getOrDefault(table, new Kind[]{})) {
            tagged.addAll(nb.findUuidsByExternalIds(table, OvnConstants.EXT_ID_KIND, k.name()));
        }
        for (final String uuid : nb.listAllUuids(table)) {
            if (tagged.contains(uuid)) {
                continue;
            }
            out.recordOrphan(table, uuid, null);
            if (!dryRun) {
                deleteByTable(nb, controller, table, uuid, null);
            }
        }
    }

    /**
     * Migration-window sweep: drop legacy {@code Load_Balancer} rows still
     * tagged with {@code cs_kind=PORT_FORWARDING} (pre-NAT plugin shape)
     * whose UUID has no live mapping back to them, OR whose mapping has
     * already moved to a NAT row. The first
     * {@link OvnPortForwardingService#applyPFRules} touch on the rule
     * rewrites the mapping to the new NAT UUID; this sweep cleans up the
     * now-orphan LB row. Keeps reconcile correct after hot upgrade without
     * forcing the operator to revoke and re-add PF rules.
     */
    private void sweepLegacyPortForwardingLb(final OvnNbClient nb, final OvnControllerVO controller,
                                              final boolean dryRun, final Result out) {
        final List<String> legacyUuids = nb.findUuidsByExternalIds(
                "Load_Balancer", OvnConstants.EXT_ID_KIND, Kind.PORT_FORWARDING.name());
        for (final String lbUuid : legacyUuids) {
            final OvnLogicalIdMapVO known = logicalIdMapDao.findByOvnUuid(lbUuid);
            if (known != null && Kind.PORT_FORWARDING.name().equals(known.getCsKind())) {
                // Mapping still references the legacy LB row — leave it for
                // the next applyPF call to migrate cleanly. Reconcile only
                // drops rows with no live mapping back to them.
                continue;
            }
            out.recordOrphan("Load_Balancer", lbUuid, Kind.PORT_FORWARDING);
            if (!dryRun) {
                deleteByTable(nb, controller, "Load_Balancer", lbUuid, Kind.PORT_FORWARDING);
            }
        }
    }

    /**
     * Walk every row in {@code table} and check if its
     * {@code external_ids:cs_id} maps back to a known DAO row of the
     * expected {@code Kind}. If not, the row is an orphan.
     */
    private void sweepOrphanNbRows(final OvnNbClient nb, final OvnControllerVO controller,
                                   final String table, final Kind[] kinds, final boolean dryRun,
                                   final Result out) {
        // Iterate by listing all UUIDs paired with each (cs_kind, cs_id) value.
        // Cheap because the affected tables hold tens of rows per zone.
        for (final Kind kind : kinds) {
            final List<OvnLogicalIdMapVO> mappings = logicalIdMapDao.listByKind(kind, controller.getId());
            for (final OvnLogicalIdMapVO mapping : mappings) {
                // Mapping points at NB row (or used to). Verify presence.
                if (!nb.rowExistsByUuid(table, mapping.getOvnUuid())) {
                    // NB row gone -> stale mapping. Tracked in sweepStaleMappings.
                    continue;
                }
            }
        }
        // Now find any NB row tagged with cs_id of any of these kinds whose
        // UUID doesn't match a known mapping row. That's the orphan set.
        for (final Kind kind : kinds) {
            // Walk NB rows tagged with the target cs_kind. We use the kind
            // name as the second filter; cs_id alone collides across kinds
            // (e.g. NETWORK and NIC ids overlap in their numeric domains).
            final List<String> nbUuids = nb.findUuidsByExternalIds(table, OvnConstants.EXT_ID_KIND, kind.name());
            for (final String nbUuid : nbUuids) {
                // Reverse lookup: is this UUID known to the mapping DAO?
                final OvnLogicalIdMapVO known = logicalIdMapDao.findByOvnUuid(nbUuid);
                if (known != null && known.getCsKind() != null && kind.name().equals(known.getCsKind())) {
                    continue;
                }
                // Orphan — no mapping row claims this NB UUID.
                out.recordOrphan(table, nbUuid, kind);
                if (!dryRun) {
                    deleteByTable(nb, controller, table, nbUuid, kind);
                }
            }
        }
    }

    /**
     * Walk mapping rows for the given kinds and drop any whose NB UUID is
     * absent OR whose owning CloudStack entity has been removed. Pairs
     * with the orphan sweep — that path catches NB rows with no mapping;
     * this path catches mappings with no NB row OR mappings whose CS-side
     * parent (Network, Vpc, Nic, PublicIp, etc) has been deleted while the
     * NB row + mapping survived (e.g. plugin crash mid-destroy, prior
     * plugin version pre-stale-guard).
     */
    private void sweepStaleMappings(final OvnNbClient nb, final OvnControllerVO controller,
                                    final String table, final Kind[] kinds, final boolean dryRun,
                                    final Result out) {
        for (final Kind kind : kinds) {
            final List<OvnLogicalIdMapVO> mappings = logicalIdMapDao.listByKind(kind, controller.getId());
            for (final OvnLogicalIdMapVO mapping : mappings) {
                boolean nbGone = !nb.rowExistsByUuid(table, mapping.getOvnUuid());
                String liveTable = nbGone ? null : table;
                // PORT_FORWARDING migrated from Load_Balancer to NAT; during
                // the migration window a mapping persisted by a pre-migration
                // plugin version still references a Load_Balancer UUID. Probe
                // the legacy table as a fallback so reconcile does not flag
                // those mappings as stale (next applyPF call rewrites them).
                if (nbGone && kind == Kind.PORT_FORWARDING && "NAT".equals(table)
                        && nb.rowExistsByUuid("Load_Balancer", mapping.getOvnUuid())) {
                    nbGone = false;
                    liveTable = "Load_Balancer";
                }
                final boolean csGone = !cloudstackEntityExists(kind, mapping.getCsId());
                if (!nbGone && !csGone) {
                    continue;
                }
                out.recordStaleMapping(table, mapping);
                if (!dryRun) {
                    // CS entity gone but NB row still alive -> drop NB row first
                    // (otherwise the next plugin touch will resurrect it via the
                    // ensure* helpers' rowExistsByUuid path). Use liveTable so
                    // a legacy LB-PF row gets routed to the LB delete path.
                    if (!nbGone && csGone) {
                        deleteByTable(nb, controller, liveTable, mapping.getOvnUuid(), kind);
                        out.recordOrphan(liveTable, mapping.getOvnUuid(), kind);
                    }
                    logicalIdMapDao.remove(mapping.getId());
                }
            }
        }
    }

    /**
     * Verify that the CloudStack-side entity referenced by a mapping row
     * still exists. The {@code Kind} dictates which DAO to consult.
     * Returns {@code true} when the entity is alive (or when the kind has
     * no straightforward CS-side parent — e.g. {@link Kind#HA_CHASSIS_GROUP}
     * is keyed by zone id and zones are forever).
     */
    private boolean cloudstackEntityExists(final Kind kind, final long csId) {
        switch (kind) {
            case VPC:
            case VPC_PUBLIC_LRP:
            case VPC_PUBLIC_RSP:
            case VPC_SOURCE_NAT:
                return vpcDao.findById(csId) != null;
            case NETWORK:
            case PUBLIC_LRP:
            case DHCP_OPTIONS:
            case DHCP_OPTIONS_V6:
            case DNS_RECORDS:
            case SOURCE_NAT:
                return networkDao.findById(csId) != null;
            case NIC:
            case ORPHAN_NIC:
            case QOS:
                return nicDao.findById(csId) != null;
            case STATIC_NAT:
                return ipAddressDao.findById(csId) != null;
            case BGP_ANNOUNCE:
                // Keyed by IPAddressVO.id — same probe shape as STATIC_NAT.
                // The ovn_uuid column on the row holds the agent host id,
                // not an actual NB UUID, so the orphan/stale sweep against
                // an NB table never matches and the row is reaped only via
                // CS-entity deletion through this path.
                return ipAddressDao.findById(csId) != null;
            case PORT_FORWARDING:
            case LOAD_BALANCER:
            case NETWORK_ACL:
            case STATIC_ROUTE:
                // Per-rule kinds keyed by FirewallRule / NetworkACL / LB id.
                // No cheap "exists" probe across all rule DAOs without
                // pulling more deps; fall back to NB-presence-only for
                // these. Their cleanup is well-driven by revoke flows
                // anyway.
                return true;
            case PUBLIC_LS:
            case HA_CHASSIS_GROUP:
                // Per-zone, never expires while controller registered.
                return true;
            default:
                return true;
        }
    }

    /**
     * Delete an NB row of the given table by UUID, using the existing
     * detach-then-delete helpers when the row's parent set carries a
     * referential-integrity contract.
     */
    private void deleteByTable(final OvnNbClient nb, final OvnControllerVO controller,
                               final String table, final String uuid, final Kind kind) {
        try {
            switch (table) {
                case "DHCP_Options":
                    nb.deleteDhcpOptions(uuid);
                    break;
                case "DNS":
                    // Orphan DNS row — parent LS already gone (cascade did
                    // the detach), so a direct delete is safe. updateDnsRecords
                    // would only clear the records map without dropping the
                    // row.
                    nb.deleteDnsRowDirect(uuid);
                    break;
                case "QoS":
                    // Same rationale as DNS above: the owning tier LS mapping
                    // may already be gone, so a direct-by-UUID delete is the
                    // only reachable path.
                    nb.deleteQosRowDirect(uuid);
                    break;
                case "NAT":
                    nb.deleteNatRule(uuid);
                    break;
                case "ACL":
                    // Walk every NETWORK LS under this controller and try
                    // detach (set semantics: missing UUID = no-op). Cheap —
                    // tens of LSes per zone.
                    for (final OvnLogicalIdMapVO ls : logicalIdMapDao.listByKind(Kind.NETWORK, controller.getId())) {
                        try {
                            nb.removeAclFromLogicalSwitch(ls.getOvnUuid(), uuid);
                        } catch (OvnException ignored) {
                            // not on this LS; try the next one
                        }
                    }
                    break;
                case "Load_Balancer":
                    nb.deleteLoadBalancer(uuid);
                    break;
                case "Logical_Switch_Port":
                    nb.deleteLogicalSwitchPort(uuid);
                    break;
                case "Logical_Router_Port":
                    nb.deleteLogicalRouterPort(uuid);
                    break;
                case "Logical_Switch":
                    nb.deleteLogicalSwitch(uuid);
                    break;
                case "Logical_Router":
                    nb.deleteLogicalRouter(uuid);
                    break;
                case "HA_Chassis_Group":
                    nb.destroyHaChassisGroup(uuid);
                    break;
                default:
                    LOGGER.warn("OvnReconcilerService: no delete handler for table {} (uuid={})", table, uuid);
                    break;
            }
            LOGGER.info("OvnReconcilerService: dropped orphan {} row {} (kind={})", table, uuid, kind);
        } catch (OvnException e) {
            LOGGER.warn("OvnReconcilerService: drop {} row {} failed: {}", table, uuid, e.getMessage());
        }
    }

    private static Map<String, Kind[]> buildTableKinds() {
        final Map<String, Kind[]> m = new LinkedHashMap<>();
        // Order matters: drop leaf rows first (DHCP, DNS, NAT, ACL, LB, LSP)
        // before parent rows (LRP, LS, LR) so detach-then-delete chains stay
        // satisfied without needing a multi-pass dependency walker.
        m.put("DHCP_Options", new Kind[]{Kind.DHCP_OPTIONS, Kind.DHCP_OPTIONS_V6});
        m.put("DNS", new Kind[]{Kind.DNS_RECORDS});
        // QoS rows have no parent-set referential-integrity contract (unlike
        // ACL/LB/LSP): they are deleted directly by UUID, same as DNS.
        m.put("QoS", new Kind[]{Kind.QOS});
        // PORT_FORWARDING migrated from Load_Balancer (legacy) to NAT
        // (current). The kind is registered against NAT here — its post-
        // migration home — and sweepStaleMappings probes Load_Balancer as a
        // fallback so legacy LB-PF rows are recognised as live (not stale)
        // during the migration window. The Load_Balancer entry below covers
        // any LB-PF row still tagged with cs_kind=PORT_FORWARDING in
        // external_ids so a reconcile after upgrade reports + drops them.
        m.put("NAT", new Kind[]{Kind.STATIC_NAT, Kind.SOURCE_NAT, Kind.VPC_SOURCE_NAT, Kind.PORT_FORWARDING});
        // FIREWALL (standalone-isolated Firewall service: baseline deny +
        // infra allow + per-rule ACLs) maps to the same NB `ACL` table as
        // NETWORK_ACL. It MUST be listed here or sweepStaleMappings never
        // walks FIREWALL rows → their ovn_logical_id_map bookkeeping rows leak
        // forever once the owning network is deleted (the NB ACL itself is
        // cascade-removed with the Logical_Switch, but the mapping row is not).
        m.put("ACL", new Kind[]{Kind.NETWORK_ACL, Kind.FIREWALL});
        m.put("Load_Balancer", new Kind[]{Kind.LOAD_BALANCER});
        m.put("Logical_Switch_Port", new Kind[]{Kind.NIC, Kind.ORPHAN_NIC, Kind.VPC_PUBLIC_RSP});
        m.put("Logical_Router_Port", new Kind[]{Kind.PUBLIC_LRP, Kind.VPC_PUBLIC_LRP});
        m.put("Logical_Switch", new Kind[]{Kind.NETWORK, Kind.PUBLIC_LS});
        m.put("Logical_Router", new Kind[]{Kind.VPC});
        m.put("HA_Chassis_Group", new Kind[]{Kind.HA_CHASSIS_GROUP});
        return m;
    }

    /** Per-call result: counts + samples for the API response surface. */
    public static final class Result {

        /** Synthetic table name used to record VLAN drift on the public
         *  localnet LSP. Surfaced in the orphans-by-table response so the
         *  admin API exposes the drift count without needing a new column. */
        public static final String LOCALNET_VLAN_TABLE = "Logical_Switch_Port_VLAN";

        /** Synthetic table key for OVS port-level hairpin acknowledgement.
         *  Counts the number of zones whose OVN-attached ports the
         *  reconciler swept for hairpin re-assertion. Per-plug enforcement
         *  on the agent side does the canonical correction. */
        public static final String OVS_HAIRPIN_TABLE = "Open_vSwitch_Hairpin";

        /** Synthetic table key for the bridge-wide OVS tc-policy
         *  acknowledgement. Counts the number of zones whose chassis the
         *  reconciler swept for tc-policy re-assertion. */
        public static final String OVS_TC_POLICY_TABLE = "Open_vSwitch_TcPolicy";

        /** Synthetic table key for hairpin drift count (real per-port drift
         *  reported by the agent sweep). Recorded by
         *  {@link #recordOvsPolicySweep}; orthogonal to the per-zone ack
         *  counter under {@link #OVS_HAIRPIN_TABLE}. */
        public static final String OVS_HAIRPIN_DRIFT_TABLE = "Open_vSwitch_Hairpin_Drift";

        /** Synthetic table key for hairpin drift entries actually re-applied
         *  by the agent (zero in dry-run; equal to drift count otherwise). */
        public static final String OVS_HAIRPIN_FIXED_TABLE = "Open_vSwitch_Hairpin_Fixed";

        /** Synthetic table key counting tc-policy stamps actually applied
         *  per chassis on the current pass. */
        public static final String OVS_TC_POLICY_APPLIED_TABLE = "Open_vSwitch_TcPolicy_Applied";

        private final boolean dryRun;
        private final Map<String, Integer> orphans = new LinkedHashMap<>();
        private final Map<String, Integer> staleMappings = new LinkedHashMap<>();

        public Result(final boolean dryRun) {
            this.dryRun = dryRun;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public Map<String, Integer> getOrphansByTable() {
            return orphans;
        }

        public Map<String, Integer> getStaleMappingsByTable() {
            return staleMappings;
        }

        public int totalOrphans() {
            int t = 0;
            for (final int v : orphans.values()) {
                t += v;
            }
            return t;
        }

        public int totalStaleMappings() {
            int t = 0;
            for (final int v : staleMappings.values()) {
                t += v;
            }
            return t;
        }

        public void recordOrphan(final String table, final String uuid, final Kind kind) {
            orphans.merge(table, 1, Integer::sum);
            LOGGER.debug("OvnReconcilerService: orphan {} {} (kind={})", table, uuid, kind);
        }

        public void recordStaleMapping(final String table, final OvnLogicalIdMapVO mapping) {
            staleMappings.merge(table, 1, Integer::sum);
            LOGGER.debug("OvnReconcilerService: stale mapping kind={} cs_id={} -> {}",
                    mapping.getCsKind(), mapping.getCsId(), mapping.getOvnUuid());
        }

        /** Record VLAN drift on the per-zone public localnet LSP. Surfaces
         *  under the synthetic {@link #LOCALNET_VLAN_TABLE} key so the admin
         *  API exposes the count alongside other drift categories. */
        public void recordVlanDrift(final String lspUuid, final Integer currentVlan, final Integer desiredVlan) {
            orphans.merge(LOCALNET_VLAN_TABLE, 1, Integer::sum);
            LOGGER.debug("OvnReconcilerService: localnet VLAN drift lsp={} current={} desired={}",
                    lspUuid, currentVlan, desiredVlan);
        }

        /**
         * Record an OVS hairpin / tc-policy reconciliation pass for a zone.
         * Counts the per-zone ack so the admin API surfaces that the sweep
         * categories were ticked, even when no chassis returned drift.
         */
        public void recordOvsPolicyAck(final long zoneId) {
            orphans.merge(OVS_HAIRPIN_TABLE, 1, Integer::sum);
            orphans.merge(OVS_TC_POLICY_TABLE, 1, Integer::sum);
            LOGGER.debug("OvnReconcilerService: ovs policy ack for zone={}", zoneId);
        }

        /**
         * Record a per-chassis sweep result. Counts drift (ports whose
         * hairpin differed from the resolved default), the fix subset
         * (zero in dry-run; equal to drift otherwise), and the tc-policy
         * apply boolean. Aggregated under the synthetic table keys
         * {@link #OVS_HAIRPIN_DRIFT_TABLE},
         * {@link #OVS_HAIRPIN_FIXED_TABLE}, and
         * {@link #OVS_TC_POLICY_APPLIED_TABLE} so the admin API can compute
         * the cluster-wide totals from the response.
         */
        public void recordOvsPolicySweep(final long hostId, final int portsScanned, final int hairpinDrifted,
                                         final int hairpinFixed, final boolean tcPolicyApplied) {
            orphans.merge(OVS_HAIRPIN_DRIFT_TABLE, hairpinDrifted, Integer::sum);
            orphans.merge(OVS_HAIRPIN_FIXED_TABLE, hairpinFixed, Integer::sum);
            if (tcPolicyApplied) {
                orphans.merge(OVS_TC_POLICY_APPLIED_TABLE, 1, Integer::sum);
            }
            LOGGER.debug("OvnReconcilerService: sweep host={} scanned={} drifted={} fixed={} tcApplied={}",
                    hostId, portsScanned, hairpinDrifted, hairpinFixed, tcPolicyApplied);
        }
    }
}
