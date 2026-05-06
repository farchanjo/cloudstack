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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.ovn.client.OvnException;
import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.element.OvnConstants;
import com.cloud.network.vpc.dao.VpcDao;
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
        LOGGER.info("OvnReconcilerService: zone={} dryRun={} purgeUntagged={} orphansFound={} staleMappingsFound={}",
                zoneId, dryRun, purgeUntagged, out.totalOrphans(), out.totalStaleMappings());
        return out;
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
    private void sweepUntaggedRows(final OvnNbClient nb, final OvnControllerVO controller,
                                   final String table, final boolean dryRun, final Result out) {
        if (!"DHCP_Options".equals(table) && !"DNS".equals(table) && !"ACL".equals(table)) {
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
                final boolean nbGone = !nb.rowExistsByUuid(table, mapping.getOvnUuid());
                final boolean csGone = !cloudstackEntityExists(kind, mapping.getCsId());
                if (!nbGone && !csGone) {
                    continue;
                }
                out.recordStaleMapping(table, mapping);
                if (!dryRun) {
                    // CS entity gone but NB row still alive -> drop NB row first
                    // (otherwise the next plugin touch will resurrect it via the
                    // ensure* helpers' rowExistsByUuid path).
                    if (!nbGone && csGone) {
                        deleteByTable(nb, controller, table, mapping.getOvnUuid(), kind);
                        out.recordOrphan(table, mapping.getOvnUuid(), kind);
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
        m.put("NAT", new Kind[]{Kind.STATIC_NAT, Kind.SOURCE_NAT, Kind.VPC_SOURCE_NAT});
        m.put("ACL", new Kind[]{Kind.NETWORK_ACL});
        m.put("Load_Balancer", new Kind[]{Kind.LOAD_BALANCER, Kind.PORT_FORWARDING});
        m.put("Logical_Switch_Port", new Kind[]{Kind.NIC, Kind.ORPHAN_NIC});
        m.put("Logical_Router_Port", new Kind[]{Kind.PUBLIC_LRP, Kind.VPC_PUBLIC_LRP});
        m.put("Logical_Switch", new Kind[]{Kind.NETWORK, Kind.PUBLIC_LS});
        m.put("Logical_Router", new Kind[]{Kind.VPC});
        m.put("HA_Chassis_Group", new Kind[]{Kind.HA_CHASSIS_GROUP});
        return m;
    }

    /** Per-call result: counts + samples for the API response surface. */
    public static final class Result {

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
    }
}
