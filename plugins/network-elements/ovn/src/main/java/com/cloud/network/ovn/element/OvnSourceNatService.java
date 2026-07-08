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
package com.cloud.network.ovn.element;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.config.OvnNetworkConfig;
import com.cloud.network.ovn.dao.OvnControllerVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapDao;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO;
import com.cloud.network.ovn.dao.OvnLogicalIdMapVO.Kind;
import com.cloud.network.ovn.dao.OvnPendingDeletionDao;
import com.cloud.network.ovn.dao.OvnPendingDeletionVO;
import com.cloud.network.ovn.manager.OvnPluginManager;

/**
 * Emits an OVN {@code snat} rule when a tier attaches to the VPC public-side
 * LR. The OVN NAT rule maps the tier's CIDR to the LR's public-side gateway
 * IP. Mirrors the rule shape of the live {@code lr-test} lab on production:
 *
 * <pre>
 *   ovn-nbctl lr-nat-add lr-test snat 192.168.100.1 10.101.0.0/24
 * </pre>
 */
@Component
public class OvnSourceNatService {

    private static final Logger LOGGER = LogManager.getLogger(OvnSourceNatService.class);

    /** OVN NAT type for source NAT. */
    public static final String NAT_TYPE_SNAT = "snat";

    /**
     * Name of the OVN NB {@code Address_Set} that carries the configured
     * SNAT destination exemptions (see {@link
     * OvnNetworkConfig#SnatExemptedDestinations}). MUST use underscores
     * only: ovn-controller's match-language parser rejects a hyphen inside
     * an address-set name token ({@code "Syntax error at `$rr' expecting
     * address set name"} was observed for {@code rr-snat-exempt}).
     */
    public static final String SNAT_EXEMPT_ADDRESS_SET = "rr_snat_exempt";

    @Inject
    private OvnPluginManager pluginManager;
    @Inject
    private OvnLogicalIdMapDao logicalIdMapDao;
    @Inject
    private OvnPendingDeletionDao pendingDeletionDao;

    /**
     * Adds an SNAT rule for the given tier CIDR.
     *
     * @param zoneId       CloudStack zone (selects the controller)
     * @param sourceTierId CloudStack network id (used to key the mapping row)
     * @param lrUuid       OVN LR UUID
     * @param externalIp   public-side gateway IP (e.g. {@code 192.168.100.1})
     * @param logicalIp    tier CIDR (e.g. {@code 10.101.0.0/24})
     */
    public String addSnat(final long zoneId, final long sourceTierId, final String lrUuid,
                          final String externalIp, final String logicalIp) {
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        final String natUuid = nb.addNatRule(lrUuid, NAT_TYPE_SNAT, externalIp, logicalIp, null);
        if (controller != null) {
            logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.SOURCE_NAT, sourceTierId, controller.getId(), natUuid,
                    NAT_TYPE_SNAT + "-" + sourceTierId));
        }
        LOGGER.info("OVN SNAT {} added: {} -> {} on LR {}", natUuid, logicalIp, externalIp, lrUuid);
        return natUuid;
    }

    /**
     * Removes the SNAT rule recorded for the given tier.
     *
     * <p>Enqueues the NAT UUID into {@code ovn_pending_deletion} BEFORE the
     * synchronous NB call so the async retry queue holds the UUID even when
     * the sync delete fails.
     */
    public void removeSnatForTier(final long zoneId, final long sourceTierId) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.SOURCE_NAT, sourceTierId, controller.getId());
        if (mapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), zoneId, Kind.SOURCE_NAT, mapping.getOvnUuid(), sourceTierId);
        try {
            pluginManager.nbClient(zoneId).deleteNatRule(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.SOURCE_NAT.name());
        } catch (RuntimeException e) {
            // Mapping survives so reconciler + processor can retry.
            LOGGER.warn("OvnSourceNatService.removeSnatForTier: NAT {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // VPC-level SourceNAT — one snat row per VPC mapping the parent CIDR
    // (e.g. 10.235.0.0/16) to the VPC's source-NAT public IP. Keyed under
    // Kind.VPC_SOURCE_NAT so it never collides with the per-tier rows
    // emitted from applyIps when an IP is explicitly associated to a tier.
    // ------------------------------------------------------------------

    /**
     * Idempotent VPC-level SNAT writer. Re-running with the same external
     * IP returns the existing UUID; running with a new external IP updates
     * the NAT row in place (UUID stays, external_ip column changes) so the
     * LR.nat strong-ref set does not need to be rewritten.
     */
    public String ensureVpcSourceNat(final long zoneId, final long vpcId, final String lrUuid,
                                     final String externalIp, final String vpcCidr) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            throw new com.cloud.network.ovn.client.OvnException("no OVN controller for zone " + zoneId);
        }
        final OvnNbClient nb = pluginManager.nbClient(zoneId);
        final OvnLogicalIdMapVO existing = logicalIdMapDao.findByCsId(Kind.VPC_SOURCE_NAT, vpcId, controller.getId());
        // Stale-mapping guard: if the NAT row was deleted out-of-band (manual
        // ovn-nbctl, parent LR cascade we missed bookkeeping for, prior bug),
        // updateNatRule is a silent no-op (OVSDB update against zero-row
        // WHERE clause does nothing). Verify before update so we fall
        // through to create on miss.
        if (existing != null && nb.rowExistsByUuid("NAT", existing.getOvnUuid())) {
            return updateVpcSourceNat(nb, existing.getOvnUuid(), lrUuid, externalIp, vpcCidr);
        }
        if (existing != null) {
            LOGGER.warn("OvnSourceNatService.ensureVpcSourceNat: VPC_SOURCE_NAT mapping vpc={} -> {} stale (NAT row gone); recreating",
                    vpcId, existing.getOvnUuid());
            logicalIdMapDao.remove(existing.getId());
        }
        return createVpcSourceNat(controller.getId(), nb, vpcId, lrUuid, externalIp, vpcCidr);
    }

    /** Update path: rewrite external_ip / logical_ip in place. Cheap (one
     *  OVSDB update; LR.nat reference stays valid). Re-applies the
     *  destination exemption on every call — cheap idempotent no-op when
     *  nothing drifted, and self-heals a manually removed exemption. */
    private String updateVpcSourceNat(final OvnNbClient nb, final String natUuid,
                                      final String lrUuid, final String externalIp, final String vpcCidr) {
        nb.updateNatRule(natUuid, externalIp, vpcCidr);
        applySnatDestinationExemption(nb, natUuid);
        LOGGER.info("OVN VPC SNAT {} updated: {} -> {} on LR {}", natUuid, vpcCidr, externalIp, lrUuid);
        return natUuid;
    }

    private String createVpcSourceNat(final long controllerId, final OvnNbClient nb,
                                      final long vpcId, final String lrUuid, final String externalIp,
                                      final String vpcCidr) {
        final String natUuid = nb.addNatRule(lrUuid, NAT_TYPE_SNAT, externalIp, vpcCidr, null);
        applySnatDestinationExemption(nb, natUuid);
        logicalIdMapDao.persist(new OvnLogicalIdMapVO(Kind.VPC_SOURCE_NAT, vpcId, controllerId, natUuid,
                NAT_TYPE_SNAT + "-vpc-" + vpcId));
        LOGGER.info("OVN VPC SNAT {} added: {} -> {} on LR {}", natUuid, vpcCidr, externalIp, lrUuid);
        return natUuid;
    }

    /**
     * Applies the configured destination-exemption {@code Address_Set} to
     * the VPC-wide SNAT NAT row so guests can reach specific fabric
     * destinations (e.g. BGP route reflectors) with their real address
     * instead of the VPC's source-NAT IP.
     *
     * <p>Empty config = no-op. This intentionally does NOT clear an
     * exemption already present on the NAT row (e.g. applied by an operator
     * directly in the NB DB) — the safer default is to leave existing
     * exemptions alone rather than have a routine reconcile silently
     * un-peer a guest from the fabric.
     */
    private void applySnatDestinationExemption(final OvnNbClient nb, final String natUuid) {
        final String csv = OvnNetworkConfig.SnatExemptedDestinations.value();
        if (StringUtils.isBlank(csv)) {
            return;
        }
        final List<String> addresses = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (addresses.isEmpty()) {
            return;
        }
        final String addressSetUuid = nb.ensureAddressSet(SNAT_EXEMPT_ADDRESS_SET, addresses);
        nb.natSetExemptedExtIps(natUuid, addressSetUuid);
        LOGGER.info("OvnSourceNatService.applySnatDestinationExemption: NAT {} exempted_ext_ips -> {} ({})",
                natUuid, SNAT_EXEMPT_ADDRESS_SET, addresses);
    }

    /**
     * Removes the VPC-level SNAT row when the VPC is shut down or its
     * source-NAT IP is released. Best-effort — caller already drops the
     * containing LR via cascade in most flows.
     *
     * <p>Enqueues the NAT UUID into {@code ovn_pending_deletion} BEFORE the
     * synchronous NB call so the async retry queue holds the UUID even when
     * the sync delete fails.
     */
    public void removeVpcSourceNat(final long zoneId, final long vpcId) {
        final OvnControllerVO controller = pluginManager.findControllerForZone(zoneId);
        if (controller == null) {
            return;
        }
        final OvnLogicalIdMapVO mapping = logicalIdMapDao.findByCsId(Kind.VPC_SOURCE_NAT, vpcId, controller.getId());
        if (mapping == null) {
            return;
        }
        // Enqueue first so async retry covers any sync failure mode.
        enqueueIfAbsent(controller.getId(), zoneId, Kind.VPC_SOURCE_NAT, mapping.getOvnUuid(), vpcId);
        try {
            pluginManager.nbClient(zoneId).deleteNatRule(mapping.getOvnUuid());
            logicalIdMapDao.remove(mapping.getId());
            pendingDeletionDao.markSucceededByOvnUuid(mapping.getOvnUuid(), Kind.VPC_SOURCE_NAT.name());
        } catch (RuntimeException e) {
            LOGGER.warn("OvnSourceNatService.removeVpcSourceNat: NAT {} delete failed; mapping retained for retry: {}",
                    mapping.getOvnUuid(), e.getMessage());
        }
    }

    private void enqueueIfAbsent(final long controllerId, final long zoneId, final Kind kind,
                                  final String ovnUuid, final long csId) {
        if (ovnUuid == null || ovnUuid.isEmpty()) {
            return;
        }
        if (pendingDeletionDao.isPendingByOvnUuid(ovnUuid, kind.name())) {
            return;
        }
        pendingDeletionDao.persist(new OvnPendingDeletionVO(
                UUID.randomUUID().toString(), controllerId, zoneId, kind, ovnUuid, csId));
        LOGGER.info("OvnSourceNatService: enqueued pending deletion kind={} ovn_uuid={} cs_id={}", kind, ovnUuid, csId);
    }
}
