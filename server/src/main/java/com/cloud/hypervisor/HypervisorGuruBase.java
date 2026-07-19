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
package com.cloud.hypervisor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.agent.api.to.VirtualMachineMetadataTO;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.projects.ProjectVO;
import com.cloud.projects.dao.ProjectDao;
import com.cloud.server.ResourceTag;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.cloudstack.vm.UnmanagedInstanceTO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.gpu.GPU;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.ovn.config.OvnNicTunables;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;

public abstract class HypervisorGuruBase extends AdapterBase implements HypervisorGuru, Configurable {

    /**
     * Tokens that, when present as a comma-separated tag on a NetworkOffering,
     * route the NIC through the OVS DPDK vhost-user PMD path instead of the
     * legacy kernel tap. Matched after splitting the offering's tag string by
     * comma and trimming/lower-casing each token, so substrings inside other
     * tag names (e.g. "vhost-userless") cannot trigger a false positive.
     */
    private static final Set<String> DPDK_TOKENS = Set.of("dpdk", "vhost-user", "virtio-fast");

    @Inject
    protected
    NicDao nicDao;
    @Inject
    protected
    NetworkDao networkDao;
    @Inject
    protected VpcDao vpcDao;
    @Inject
    protected AccountManager accountManager;
    @Inject
    protected DomainDao domainDao;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private NetworkOfferingDetailsDao networkOfferingDetailsDao;
    @Inject
    protected
    VMInstanceDao virtualMachineDao;
    @Inject
    private VMInstanceDetailsDao _vmInstanceDetailsDao;
    @Inject
    private NicSecondaryIpDao _nicSecIpDao;
    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private com.cloud.network.router.VfPoolManager vfPoolManager;
    @Inject
    private com.cloud.network.router.dao.SriovVfPoolDao sriovVfPoolDao;
    @Inject
    private com.cloud.agent.AgentManager agentManager;
    @Inject
    private com.cloud.offerings.dao.NetworkOfferingDao networkOfferingDao;
    @Inject
    protected ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    protected VgpuProfileDao vgpuProfileDao;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    private NetworkDetailsDao networkDetailsDao;
    @Inject
    protected DomainRouterDao routerDao;
    @Inject
    protected
    HostDao hostDao;
    @Inject
    private UserVmManager userVmManager;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected ProjectDao projectDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected HostPodDao hostPodDao;
    @Inject
    private ConfigurationManager configurationManager;
    @Inject
    ResourceTagDao tagsDao;

    public static ConfigKey<Boolean> VmMinMemoryEqualsMemoryDividedByMemOverprovisioningFactor = new ConfigKey<Boolean>("Advanced", Boolean.class, "vm.min.memory.equals.memory.divided.by.mem.overprovisioning.factor", "true",
            "If we set this to 'true', a minimum memory (memory/ mem.overprovisioning.factor) will be set to the VM, independent of using a scalable service offering or not.", true, ConfigKey.Scope.Cluster);

    public static ConfigKey<Boolean> VmMinCpuSpeedEqualsCpuSpeedDividedByCpuOverprovisioningFactor = new ConfigKey<Boolean>("Advanced", Boolean.class, "vm.min.cpu.speed.equals.cpu.speed.divided.by.cpu.overprovisioning.factor", "true",
            "If we set this to 'true', a minimum CPU speed (cpu speed/ cpu.overprovisioning.factor) will be set on the VM, independent of using a scalable service offering or not.", true, ConfigKey.Scope.Cluster);

    /**
     * Number of virtqueues to request from {@code vdpa dev add ... max_vqs <N>}
     * when a NIC is plumbed via vDPA. Default 33 covers 16 RX + 16 TX + 1
     * control queue, matching ConnectX-6 Dx defaults. Override per-host
     * via the {@code hwoffload.vdpa.max_vqs} agent property.
     */
    public static ConfigKey<Integer> VmVdpaMaxVqs = new ConfigKey<Integer>("Advanced", Integer.class,
            "vm.vdpa.max_vqs", "33",
            "Default queue count requested from `vdpa dev add ... max_vqs <N>` for VMs whose NetworkOffering has vdpaEnabled=true.",
            true);

    private Map<NetworkOffering.Detail, String> getNicDetails(Network network) {
        if (network == null) {
            logger.debug("Unable to get NIC details as the network is null");
            return null;
        }
        Map<NetworkOffering.Detail, String> details = networkOfferingDetailsDao.getNtwkOffDetails(network.getNetworkOfferingId());
        if (details != null) {
            details.putIfAbsent(NetworkOffering.Detail.PromiscuousMode, NetworkOrchestrationService.PromiscuousMode.value().toString());
            details.putIfAbsent(NetworkOffering.Detail.MacAddressChanges, NetworkOrchestrationService.MacAddressChanges.value().toString());
            details.putIfAbsent(NetworkOffering.Detail.ForgedTransmits, NetworkOrchestrationService.ForgedTransmits.value().toString());
            details.putIfAbsent(NetworkOffering.Detail.MacLearning, NetworkOrchestrationService.MacLearning.value().toString());
        }
        NetworkDetailVO pvlantypeDetail = networkDetailsDao.findDetail(network.getId(), ApiConstants.ISOLATED_PVLAN_TYPE);
        if (pvlantypeDetail != null) {
            details.putIfAbsent(NetworkOffering.Detail.pvlanType, pvlantypeDetail.getValue());
        }
        return details;
    }

    @Override
    public NicTO toNicTO(NicProfile profile) {
        NicTO to = new NicTO();
        to.setDeviceId(profile.getDeviceId());
        to.setBroadcastType(profile.getBroadcastType());
        to.setType(profile.getTrafficType());
        to.setIp(profile.getIPv4Address());
        to.setNetmask(profile.getIPv4Netmask());
        to.setMac(profile.getMacAddress());
        to.setDns1(profile.getIPv4Dns1());
        to.setDns2(profile.getIPv4Dns2());
        to.setGateway(profile.getIPv4Gateway());
        to.setDefaultNic(profile.isDefaultNic());
        to.setBroadcastUri(profile.getBroadCastUri());
        to.setIsolationuri(profile.getIsolationUri());
        to.setNetworkRateMbps(profile.getNetworkRate());
        to.setName(profile.getName());
        to.setSecurityGroupEnabled(profile.isSecurityGroupEnabled());
        to.setIp6Address(profile.getIPv6Address());
        to.setIp6Gateway(profile.getIPv6Gateway());
        to.setIp6Cidr(profile.getIPv6Cidr());
        to.setMtu(profile.getMtu());
        to.setIp6Dns1(profile.getIPv6Dns1());
        to.setIp6Dns2(profile.getIPv6Dns2());
        to.setNetworkId(profile.getNetworkId());
        to.setEnabled(profile.isEnabled());

        NetworkVO network = networkDao.findById(profile.getNetworkId());
        to.setNetworkUuid(network.getUuid());
        Account account = accountManager.getAccount(network.getAccountId());
        Domain domain = domainDao.findById(network.getDomainId());
        DataCenter zone = dcDao.findById(network.getDataCenterId());
        if (Objects.isNull(zone)) {
            throw new CloudRuntimeException(String.format("Failed to find zone with ID: %s", network.getDataCenterId()));
        }
        if (Objects.isNull(account)) {
            throw new CloudRuntimeException(String.format("Failed to find account with ID: %s", network.getAccountId()));
        }
        if (Objects.isNull(domain)) {
            throw new CloudRuntimeException(String.format("Failed to find domain with ID: %s", network.getDomainId()));
        }
        VpcVO vpc = null;
        if (Objects.nonNull(network.getVpcId())) {
            vpc = vpcDao.findById(network.getVpcId());
        }
        to.setNetworkSegmentName(getNetworkName(zone.getId(), domain.getId(), account.getId(), vpc, network.getId()));

        // Workaround to make sure the TO has the UUID we need for Nicira integration
        NicVO nicVO = nicDao.findById(profile.getId());
        if (nicVO != null) {
            to.setUuid(nicVO.getUuid());
            // disable pxe on system vm nics to speed up boot time
            if (nicVO.getVmType() != VirtualMachine.Type.User) {
                to.setPxeDisable(true);
            }
            List<String> secIps = null;
            if (nicVO.getSecondaryIp()) {
                secIps = _nicSecIpDao.getSecondaryIpAddressesForNic(nicVO.getId());
            }
            to.setNicSecIps(secIps);

            // Propagate SR-IOV VF binding. Null vf_pci_address means traditional bridge/TAP.
            // Branch order (mutually exclusive):
            //   1. vDPA: pool row's vdpa_kind=VDPA → set useVdpa, vdpaMaxVqs.
            //      Agent's VdpaVifDriver runs `vdpa dev add ... mac <mac> max_vqs <N>`
            //      and emits <interface type='vdpa'>.
            //   2. hostdev passthrough: pool row's vdpa_kind=PASSTHROUGH → set
            //      useHwOffload. Agent's VfPassthroughVifDriver emits
            //      <interface type='hostdev' managed='yes'>.
            // The two flags are mutually exclusive on the wire; an older agent
            // that does not understand useVdpa silently ignores it.
            String vfPci = nicVO.getVfPciAddress();
            if (vfPci != null && !vfPci.isEmpty()) {
                to.setVfPciAddress(vfPci);
                to.setVfPfName(nicVO.getVfPfName());
                if (isVdpaBoundVf(nicVO)) {
                    to.setUseVdpa(Boolean.TRUE);
                    to.setVdpaMaxVqs(VmVdpaMaxVqs.value());
                } else {
                    to.setUseHwOffload(Boolean.TRUE);
                }
            } else {
                /*
                 * DPDK selection is tag-based (offering tags carry "dpdk" or "vhost-user"
                 * tokens). The previous ovn.dpdk.enabled ConfigKey was removed as it was
                 * never consulted by the resolution chain — keeping it would mislead
                 * operators into thinking it had effect. Tags remain the source of truth.
                 */
                // NetworkOffering tag-based DPDK vhost-user enablement.
                // Tag set is a comma-separated list. A NIC is routed through the OVS DPDK
                // vhost-user PMD path (OvsVifDriver auto-creates dpdkvhostuserclient port —
                // multi-queue, live-migratable) when ANY token in DPDK_TOKENS is present
                // as a whole token. Substring matches are rejected: "vhost-userless" must
                // not flip the flag. Anything else falls back to the kernel tap path.
                try {
                    com.cloud.offerings.NetworkOfferingVO offering =
                            networkOfferingDao.findById(network.getNetworkOfferingId());
                    if (offering != null && offering.getTags() != null) {
                        boolean dpdkTagged = Arrays.stream(offering.getTags().split(","))
                                .map(String::trim)
                                .map(String::toLowerCase)
                                .anyMatch(DPDK_TOKENS::contains);
                        if (dpdkTagged) {
                            to.setDpdkEnabled(true);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("toNicTO: NetworkOffering tag lookup failed for nic {}: {}", to, e.getMessage());
                }
            }
            // OVN datapath enablement (orthogonal to VF/vDPA/DPDK). When the
            // NetworkOffering carries the "useOvn" tag, populate the OVN
            // binding fields the agent VifDrivers consume to write
            // external_ids:iface-id on br-int. Names match the convention
            // used in OvnGuestNetworkGuru.logicalSwitchNameFor / OvnNetworkElement.buildLspName.
            try {
                com.cloud.offerings.NetworkOfferingVO offeringForOvn =
                        networkOfferingDao.findById(network.getNetworkOfferingId());
                if (offeringForOvn != null && offeringForOvn.getTags() != null) {
                    boolean ovnTagged = Arrays.stream(offeringForOvn.getTags().split(","))
                            .map(String::trim)
                            .anyMatch("useOvn"::equalsIgnoreCase);
                    if (ovnTagged) {
                        to.setUseOvn(Boolean.TRUE);
                        to.setOvnLsName("ls-" + network.getUuid());
                        if (StringUtils.isNotBlank(nicVO.getUuid())) {
                            to.setOvnLspName("lsp-" + nicVO.getUuid());
                        }
                        // Resolve all OVN-managed NIC tunables only when this NIC
                        // sits on the OVN datapath. Non-OVN paths (legacy bridge,
                        // OvsVifDriver, DPDK-only) are intentionally untouched —
                        // wire compat is preserved by leaving the wrapper fields
                        // null on NicTO.
                        populateOvnTunables(to, profile, nicVO, network);
                    }
                }
            } catch (Exception e) {
                logger.debug("toNicTO: OVN tag lookup failed for nic {}: {}", to, e.getMessage());
            }
        } else {
            logger.warn("Unable to load NicVO for NicProfile {}", profile);
            //Workaround for dynamically created nics
            //FixMe: uuid and secondary IPs can be made part of nic profile
            to.setUuid(UUID.randomUUID().toString());
        }
        to.setDetails(getNicDetails(network));
        populateDvrGatewayDetails(to, network, profile);
        // Enrich VXLAN peer details for the standalone toNicTO path (PlugNicCommand,
        // hot-plug of additional VR tier NICs after VR is already running). Without
        // this, agent would only have static agent.properties fallback, and any
        // VR-host that never had the static config would skip mesh creation.
        // The toVirtualMachineTO path also calls this — duplicate is idempotent.
        try {
            VMInstanceVO vm = virtualMachineDao.findById(profile.getVirtualMachineId());
            if (vm != null) {
                enrichVxlanPeerDetails(to, vm);
            }
        } catch (RuntimeException e) {
            logger.debug("toNicTO: enrichVxlanPeerDetails skipped for nic {}: {}", to, e.getMessage());
        }

        //check whether the this nic has secondary ip addresses set
        //set nic secondary ip address in NicTO which are used for security group
        // configuration. Use full when vm stop/start
        return to;
    }

    /**
     * Populate {@code dvr.vpc.id} and {@code dvr.gw.mac} NIC details so the
     * KVM agent's DvrManager can install cross-tier shortcut flows on the
     * VM host — even when the centralized VR lives on a different host.
     *
     * <p>Rules:
     * <ul>
     *   <li>Only for Guest-traffic NICs on a network that belongs to a VPC.</li>
     *   <li>Skip when this NIC IS the VR on that tier (VR's NicTO is built
     *       for the VR itself; gateway MAC will be learned from the agent's
     *       own plug event).</li>
     *   <li>When no VR exists yet (VPC is still coming up), leave the gw MAC
     *       detail unset — the agent tolerates it and falls back to the
     *       direct bridge path. A subsequent re-plug (VM start after VR is
     *       up) will populate the detail correctly.</li>
     * </ul>
     */
    private void populateDvrGatewayDetails(NicTO to, NetworkVO network, NicProfile profile) {
        if (to == null || network == null || profile == null) {
            return;
        }
        if (network.getVpcId() == null) {
            return;
        }
        if (to.getType() != com.cloud.network.Networks.TrafficType.Guest) {
            return;
        }
        VpcVO vpc = vpcDao.findById(network.getVpcId());
        if (vpc == null) {
            return;
        }
        to.setNicDetail("dvr.vpc.id", vpc.getUuid());
        // Look up the VR(s) of this VPC, and find the VR's NIC on THIS
        // network to extract its MAC. Multi-VR (redundant) VPCs: use the
        // first VR — both VR MACs answer ARP for the tier gateway via
        // VRRP, and the agent matches on either real MAC.
        java.util.List<DomainRouterVO> routers = routerDao.listByVpcId(vpc.getId());
        if (routers == null || routers.isEmpty()) {
            return;
        }
        for (DomainRouterVO router : routers) {
            NicVO vrNic = nicDao.findByNtwkIdAndInstanceId(network.getId(), router.getId());
            if (vrNic != null && vrNic.getMacAddress() != null) {
                to.setNicDetail("dvr.gw.mac", vrNic.getMacAddress());
                logger.debug("DVR populate: nic {} vpc={} network={} gwMac={} vr={}",
                        profile.getId(), vpc.getUuid(), network.getUuid(),
                        vrNic.getMacAddress(), router.getInstanceName());
                return;
            }
        }
    }

    /**
     * Resolve every OVN-managed NIC tunable (vDPA / SR-IOV VF / multiqueue /
     * generic NIC ethtool / TC-offload / OVN binding / BFD / conntrack /
     * SubFunction) using the four-layer chain
     * <pre>VM detail &gt; Network detail &gt; NetworkOffering detail &gt; global ConfigKey</pre>
     * and stamp the resolved values into {@link NicTO} as wrapper-typed fields.
     *
     * <p>Inputs read once from the DB to keep the hot {@code toNicTO} path cheap:
     * <ul>
     *   <li>{@code user_vm_details} / {@code vm_instance_details} via
     *       {@link VMInstanceDetailsDao#listDetailsKeyPairs(long)}</li>
     *   <li>{@code network_details} via
     *       {@link NetworkDetailsDao#listDetailsKeyPairs(long)}</li>
     *   <li>{@code network_offering_details} via
     *       {@link NetworkOfferingDetailsDao#getNtwkOffDetails(long)}</li>
     * </ul>
     *
     * <p>Each ConfigKey default lives in
     * {@code com.cloud.network.ovn.config.OvnNicConfig} (plugin module);
     * we look it up via reflection on the literal class name to avoid a
     * server -&gt; OVN plugin compile-time dependency. The resolution
     * algorithm itself lives in
     * {@link com.cloud.network.ovn.config.OvnNicTunables} (api module) so
     * both server and OVN plugin share the same canonical key strings.
     *
     * <p>Wire compatibility: every field on {@link NicTO} is a wrapper
     * type, so older agents that don't know the field simply ignore the
     * unknown JSON property. Older mgmt servers that don't call this
     * method leave the field null and the agent falls back to its
     * historical hardcoded behavior.
     */
    private void populateOvnTunables(final NicTO to,
                                     final NicProfile profile,
                                     final NicVO nicVO,
                                     final NetworkVO network) {
        if (to == null || profile == null || network == null) {
            return;
        }
        if (!Boolean.TRUE.equals(to.getUseOvn())) {
            return;
        }
        Map<String, String> vmDetails = null;
        Map<String, String> netDetails = null;
        Map<NetworkOffering.Detail, String> offeringDetails = null;
        try {
            if (_vmInstanceDetailsDao != null && profile.getVirtualMachineId() > 0) {
                vmDetails = _vmInstanceDetailsDao.listDetailsKeyPairs(profile.getVirtualMachineId());
            }
        } catch (RuntimeException e) {
            logger.debug("populateOvnTunables: VM details fetch failed for vm {}: {}",
                    profile.getVirtualMachineId(), e.getMessage());
        }
        try {
            if (networkDetailsDao != null) {
                netDetails = networkDetailsDao.listDetailsKeyPairs(network.getId());
            }
        } catch (RuntimeException e) {
            logger.debug("populateOvnTunables: network details fetch failed for net {}: {}",
                    network.getId(), e.getMessage());
        }
        try {
            if (networkOfferingDetailsDao != null) {
                offeringDetails = networkOfferingDetailsDao.getNtwkOffDetails(network.getNetworkOfferingId());
            }
        } catch (RuntimeException e) {
            logger.debug("populateOvnTunables: offering details fetch failed for offering {}: {}",
                    network.getNetworkOfferingId(), e.getMessage());
        }

        // vDPA fine-grained.
        Integer maxVqs = OvnNicTunables.resolve(OvnNicTunables.OVN_VDPA_MAX_VQS,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (maxVqs != null && OvnNicTunables.isValidQueueCount(maxVqs)) {
            to.setVdpaMaxVqs(maxVqs);
        }
        Integer queuePairs = OvnNicTunables.resolve(OvnNicTunables.OVN_VDPA_QUEUE_PAIRS,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (queuePairs != null && queuePairs > 0) {
            to.setVdpaQueuePairs(queuePairs);
        }
        to.setVdpaEventIdx(OvnNicTunables.resolve(OvnNicTunables.OVN_VDPA_EVENT_IDX,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setVdpaIndirectDesc(OvnNicTunables.resolve(OvnNicTunables.OVN_VDPA_INDIRECT_DESC,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setVdpaIommu(OvnNicTunables.resolve(OvnNicTunables.OVN_VDPA_IOMMU,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setVdpaPacked(OvnNicTunables.resolve(OvnNicTunables.OVN_VDPA_PACKED,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));

        // SR-IOV VF tunables.
        to.setVfTrust(OvnNicTunables.resolve(OvnNicTunables.OVN_VF_TRUST,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setVfSpoofcheck(OvnNicTunables.resolve(OvnNicTunables.OVN_VF_SPOOFCHECK,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setVfLinkState(OvnNicTunables.resolve(OvnNicTunables.OVN_VF_LINK_STATE,
                vmDetails, netDetails, offeringDetails, null, String.class));
        Integer vfMaxRate = OvnNicTunables.resolve(OvnNicTunables.OVN_VF_MAX_TX_RATE,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (OvnNicTunables.isValidRate(vfMaxRate)) {
            to.setVfMaxTxRate(vfMaxRate);
        }
        Integer vfMinRate = OvnNicTunables.resolve(OvnNicTunables.OVN_VF_MIN_TX_RATE,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (OvnNicTunables.isValidRate(vfMinRate)) {
            to.setVfMinTxRate(vfMinRate);
        }
        Integer vfVlan = OvnNicTunables.resolve(OvnNicTunables.OVN_VF_VLAN,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (OvnNicTunables.isValidVlan(vfVlan)) {
            to.setVfVlan(vfVlan);
        }
        Integer vfQos = OvnNicTunables.resolve(OvnNicTunables.OVN_VF_QOS,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (OvnNicTunables.isValidQos(vfQos)) {
            to.setVfQos(vfQos);
        }

        // vhost / multiqueue.
        Integer vhostQ = OvnNicTunables.resolve(OvnNicTunables.OVN_VHOST_QUEUES,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (vhostQ != null && vhostQ >= 0) {
            to.setVhostQueues(vhostQ);
        }
        to.setVhostDriver(OvnNicTunables.resolve(OvnNicTunables.OVN_VHOST_DRIVER,
                vmDetails, netDetails, offeringDetails, null, String.class));
        Integer vhostTxQ = OvnNicTunables.resolve(OvnNicTunables.OVN_VHOST_TX_QUEUE_SIZE,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (vhostTxQ != null && vhostTxQ > 0) {
            to.setVhostTxQueueSize(vhostTxQ);
        }
        Integer vhostRxQ = OvnNicTunables.resolve(OvnNicTunables.OVN_VHOST_RX_QUEUE_SIZE,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (vhostRxQ != null && vhostRxQ > 0) {
            to.setVhostRxQueueSize(vhostRxQ);
        }

        // Generic NIC tunables.
        Integer mtu = OvnNicTunables.resolve(OvnNicTunables.OVN_MTU,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (mtu != null && mtu > 0 && to.getMtu() == null) {
            // Don't clobber an MTU already set from NicProfile (operator
            // override on the NIC itself); the OVN tunable is a fallback.
            to.setMtu(mtu);
        }
        to.setTso(OvnNicTunables.resolve(OvnNicTunables.OVN_TSO,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setGso(OvnNicTunables.resolve(OvnNicTunables.OVN_GSO,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setGro(OvnNicTunables.resolve(OvnNicTunables.OVN_GRO,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setLro(OvnNicTunables.resolve(OvnNicTunables.OVN_LRO,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setCsumOffload(OvnNicTunables.resolve(OvnNicTunables.OVN_CSUM_OFFLOAD,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        to.setDriverModel(OvnNicTunables.resolve(OvnNicTunables.OVN_DRIVER_MODEL,
                vmDetails, netDetails, offeringDetails, null, String.class));

        // OVS / TC offload.
        to.setTcOffload(OvnNicTunables.resolve(OvnNicTunables.OVN_TC_OFFLOAD,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        // Per-port hairpin: applies to whichever port the OVN VifDriver attaches
        // to br-int (VF rep, vDPA OVS port, virtio tap). Default-on at the
        // ConfigKey layer so a fresh deployment immediately benefits from
        // VF<->VF same-host hw-offload via TC flower / mlx5 eswitch.
        to.setOvsHairpin(OvnNicTunables.resolve(OvnNicTunables.OVN_OVS_HAIRPIN,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        // Bridge-wide tc-policy. Agent applies via {@code ovs-vsctl set
        // Open_vSwitch . other_config:tc-policy} once per JVM at the first
        // OVN-aware plug.
        to.setOvsTcPolicy(OvnNicTunables.resolve(OvnNicTunables.OVN_OVS_TC_POLICY,
                vmDetails, netDetails, offeringDetails, null, String.class));

        // OVN binding / chassis.
        to.setRequestedChassis(OvnNicTunables.resolve(OvnNicTunables.OVN_REQUESTED_CHASSIS,
                vmDetails, netDetails, offeringDetails, null, String.class));
        Integer prio = OvnNicTunables.resolve(OvnNicTunables.OVN_HA_CHASSIS_PRIORITY,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (prio != null && prio >= 0) {
            to.setHaChassisPriority(prio);
        }

        // BFD.
        to.setBfdEnable(OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_ENABLE,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));
        Integer bfdMinRx = OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_MIN_RX,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (bfdMinRx != null && bfdMinRx > 0) {
            to.setBfdMinRx(bfdMinRx);
        }
        Integer bfdMinTx = OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_MIN_TX,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (bfdMinTx != null && bfdMinTx > 0) {
            to.setBfdMinTx(bfdMinTx);
        }
        Integer bfdMul = OvnNicTunables.resolve(OvnNicTunables.OVN_BFD_MULTIPLIER,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (bfdMul != null && bfdMul > 0) {
            to.setBfdMultiplier(bfdMul);
        }

        // Conntrack timeouts.
        Integer ctSnat = OvnNicTunables.resolve(OvnNicTunables.OVN_CT_SNAT_INACTIVE_TIMEOUT,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (ctSnat != null && ctSnat > 0) {
            to.setCtSnatTimeout(ctSnat);
        }
        Integer ctTcp = OvnNicTunables.resolve(OvnNicTunables.OVN_CT_TCP_INACTIVE_TIMEOUT,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (ctTcp != null && ctTcp > 0) {
            to.setCtTcpTimeout(ctTcp);
        }
        Integer ctUdp = OvnNicTunables.resolve(OvnNicTunables.OVN_CT_UDP_INACTIVE_TIMEOUT,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (ctUdp != null && ctUdp > 0) {
            to.setCtUdpTimeout(ctUdp);
        }
        Integer ctIcmp = OvnNicTunables.resolve(OvnNicTunables.OVN_CT_ICMP_INACTIVE_TIMEOUT,
                vmDetails, netDetails, offeringDetails, null, Integer.class);
        if (ctIcmp != null && ctIcmp > 0) {
            to.setCtIcmpTimeout(ctIcmp);
        }

        // OVN LSP ARP proxy (ARP suppression per port).
        to.setLspArpProxy(OvnNicTunables.resolve(OvnNicTunables.OVN_LSP_ARP_PROXY,
                vmDetails, netDetails, offeringDetails, null, Boolean.class));

        if (logger.isDebugEnabled()) {
            logger.debug("populateOvnTunables: nic={} ls={} lsp={} resolved tunables (non-null only): {}",
                    profile.getId(), to.getOvnLsName(), to.getOvnLspName(), to);
        }
    }

    /**
     * Allocate an SR-IOV VF for this NIC when the VR needs HW offload.
     *
     * <p>Two trigger paths:
     * <ul>
     *   <li><b>Guest-tier NIC</b>: when this NIC's network offering has
     *       {@code hw_offload_enabled=1} (the explicit per-tier opt-in).</li>
     *   <li><b>Public NIC</b> (Phase B/4): when the same VR also has at least
     *       one Guest NIC on a HW-offload network. We propagate HW offload to
     *       the public NIC so the agent can install DNAT rules in HW on its
     *       representor (full HW pipeline: DNAT on public-rep, SNAT on
     *       guest-rep). Without this, kernel iptables PREROUTING DNAT runs
     *       on the soft-path and the chain-1 catch-all has to absorb the flow.</li>
     * </ul>
     *
     * <p>Control NICs (link-local 169.254/16) are NEVER promoted — they're a
     * per-host bridge (cloud0) used by the agent for VR config push and have
     * no presence on the hardware NIC.
     */
    /**
     * True when the NIC's allocated SR-IOV VF pool row is currently bound as
     * a vDPA mgmt-device (i.e. {@code vdpa_kind = VDPA}). False when the row
     * is unset, missing, or in {@code PASSTHROUGH} state. The lookup goes
     * through the DAO (not the manager) to keep this hot read cheap — it
     * runs on every {@code toNicTO} for VRs and HW-offload user VMs.
     */
    private boolean isVdpaBoundVf(NicVO nicVO) {
        if (nicVO == null || sriovVfPoolDao == null) {
            return false;
        }
        Long poolId = nicVO.getVfPoolId();
        if (poolId == null) {
            return false;
        }
        try {
            com.cloud.network.router.SriovVfPoolVO row = sriovVfPoolDao.findById(poolId);
            return row != null
                    && com.cloud.network.router.SriovVfPoolVO.VdpaKind.VDPA.name().equals(row.getVdpaKind());
        } catch (RuntimeException e) {
            logger.debug("isVdpaBoundVf: lookup failed for nic {} pool {}: {}",
                    nicVO.getId(), poolId, e.getMessage());
            return false;
        }
    }

    private void allocateVfIfHwOffload(NicTO nicTo, NicProfile nicProfile, VirtualMachineProfile vmProfile) {
        if (vfPoolManager == null) {
            final NetworkVO network = networkDao.findById(nicProfile.getNetworkId());
            final com.cloud.offerings.NetworkOfferingVO offering = network == null ? null
                    : networkOfferingDao.findById(network.getNetworkOfferingId());
            if (offering != null && offering.isVdpaEnabled()) {
                throw vdpaCapacityFailure(vmProfile.getHostId(), nicProfile.getId(),
                        new IllegalStateException("vDPA pool manager is unavailable"));
            }
            return;
        }
        final boolean isVr = vmProfile.getType() == com.cloud.vm.VirtualMachine.Type.DomainRouter;
        boolean shouldVdpa = false;
        try {
            NetworkVO network = networkDao.findById(nicProfile.getNetworkId());
            if (network == null) {
                return;
            }
            // Skip control NIC (link-local cloud0) — never gets a VF.
            if (network.getTrafficType() == com.cloud.network.Networks.TrafficType.Control ||
                network.getTrafficType() == com.cloud.network.Networks.TrafficType.Management) {
                return;
            }
            // Non-VRs (user VMs) receive a VF when the offering has hwOffloadEnabled=1
            // OR vdpaEnabled=1.  The Bug 5 mutex means vDPA offerings always carry
            // hwOffloadEnabled=false, so we must admit either flag independently.
            if (!isVr) {
                com.cloud.offerings.NetworkOfferingVO off =
                        networkOfferingDao.findById(network.getNetworkOfferingId());
                final boolean wantHwOffload = off != null && off.isHwOffloadEnabled();
                final boolean wantVdpa = off != null && off.isVdpaEnabled();
                if (!wantHwOffload && !wantVdpa) {
                    return;
                }
            }
            boolean shouldOffload = false;
            com.cloud.offerings.NetworkOfferingVO offering = networkOfferingDao.findById(network.getNetworkOfferingId());
            if (offering != null && offering.isVdpaEnabled()) {
                // vDPA path: highest priority. Mutually exclusive with hostdev
                // passthrough — a single offering enables one or the other.
                shouldVdpa = true;
            } else if (offering != null && offering.isHwOffloadEnabled()) {
                shouldOffload = true;
            } else if (network.getTrafficType() == com.cloud.network.Networks.TrafficType.Public &&
                       vrHasAnyHwOffloadGuestNic(vmProfile)) {
                // Phase B/4: public NIC inherits HW offload when at least one guest tier has it.
                shouldOffload = true;
                logger.debug("Public NIC for VR {} promoted to HW offload (sibling guest tier is HW-offload)",
                        vmProfile.getVirtualMachine().getInstanceName());
            }
            if (!shouldOffload && !shouldVdpa) {
                return;
            }
            Long hostId = vfAllocationHostId(vmProfile);
            if (hostId == null) {
                return;
            }
            if (shouldVdpa) {
                int maxVqs = VmVdpaMaxVqs.value();
                com.cloud.network.router.SriovVfPoolVO vf = vfPoolManager.allocateForVdpa(
                        hostId, nicProfile.getId(), nicTo.getMac(), maxVqs);
                if (vf == null) {
                    throw vdpaCapacityFailure(hostId, nicProfile.getId(), null);
                }
                nicTo.setVfPciAddress(vf.getPciAddress());
                nicTo.setVfPfName(vf.getPfName());
                nicTo.setVfRepName(vf.getRepresentorName());
                nicTo.setUseVdpa(Boolean.TRUE);
                nicTo.setVdpaMaxVqs(maxVqs);
                logger.info("Allocated vDPA VF {} (PCI {}) on host {} for NIC {} ({} traffic, vDPA mgmtdev)",
                        vf.getUuid(), vf.getPciAddress(), hostId, nicProfile.getId(), network.getTrafficType());
                return;
            }
            com.cloud.network.router.SriovVfPoolVO vf = vfPoolManager.allocate(hostId, nicProfile.getId());
            nicTo.setVfPciAddress(vf.getPciAddress());
            nicTo.setVfPfName(vf.getPfName());
            nicTo.setVfRepName(vf.getRepresentorName());

            nicTo.setUseHwOffload(Boolean.TRUE);
            logger.info("Allocated VF {} (PCI {}) on host {} for NIC {} ({} traffic, HW offload)",
                    vf.getUuid(), vf.getPciAddress(), hostId, nicProfile.getId(), network.getTrafficType());
        } catch (com.cloud.exception.InsufficientCapacityException e) {
            if (shouldVdpa) {
                throw vdpaCapacityFailure(vmProfile.getHostId(), nicProfile.getId(), e);
            }
            logger.warn("No free VF for HW offload on host {}; NIC {} will use bridge/TAP fallback",
                    vmProfile.getHostId(), nicProfile.getId());
        } catch (Exception e) {
            if (shouldVdpa) {
                throw vdpaCapacityFailure(vmProfile.getHostId(), nicProfile.getId(), e);
            }
            logger.warn("Failed to allocate VF for HW offload", e);
        }
    }

    static CloudRuntimeException vdpaCapacityFailure(final Long hostId, final long nicId,
            final Throwable cause) {
        final String message = String.format(
                "Insufficient vDPA VF capacity on host %s for NIC %s; refusing TAP fallback",
                hostId, nicId);
        return cause == null ? new CloudRuntimeException(message) : new CloudRuntimeException(message, cause);
    }

    static Long vfAllocationHostId(final VirtualMachineProfile vmProfile) {
        return vmProfile == null ? null : vmProfile.getHostId();
    }

    /**
     * True if the VR (DomainRouter) has any Guest-traffic NIC on a network whose
     * offering has {@code hw_offload_enabled=1}. Used to decide whether the
     * Public NIC of the same VR should also be promoted to a hostdev VF
     * (Phase B/4 — full HW NAT pipeline).
     */
    private boolean vrHasAnyHwOffloadGuestNic(VirtualMachineProfile vmProfile) {
        if (vmProfile.getNics() == null) {
            return false;
        }
        for (com.cloud.vm.NicProfile other : vmProfile.getNics()) {
            if (other == null || other.getNetworkId() == 0) {
                continue;
            }
            NetworkVO net = networkDao.findById(other.getNetworkId());
            if (net == null) {
                continue;
            }
            if (net.getTrafficType() != com.cloud.network.Networks.TrafficType.Guest) {
                continue;
            }
            com.cloud.offerings.NetworkOfferingVO off = networkOfferingDao.findById(net.getNetworkOfferingId());
            if (off != null && off.isHwOffloadEnabled()) {
                return true;
            }
        }
        return false;
    }

    private String getNetworkName(long zoneId, long domainId, long accountId, VpcVO vpc, long networkId) {
        String prefix = String.format("D%s-A%s-Z%s", domainId, accountId, zoneId);
        if (Objects.isNull(vpc)) {
            return prefix + "-S" + networkId;
        }
        return prefix + "-V" + vpc.getId() + "-S" + networkId;
    }


    /**
     * Add extra configuration from VM details. Extra configuration is stored as details starting with 'extraconfig'
     */
    private void addExtraConfig(Map<String, String> details, VirtualMachineTO to, long accountId, Hypervisor.HypervisorType hypervisorType) {
        for (String key : details.keySet()) {
            if (key.startsWith(ApiConstants.EXTRA_CONFIG)) {
                String extraConfig = details.get(key);
                userVmManager.validateExtraConfig(accountId, hypervisorType, extraConfig);
                to.addExtraConfig(key, extraConfig);
            }
        }
    }

    /**
     * Add extra configurations from service offering to the VM TO.
     * Extra configuration keys are expected in formats:
     * - "extraconfig-N"
     * - "extraconfig-CONFIG_NAME"
     */
    protected void addServiceOfferingExtraConfiguration(ServiceOffering offering, VirtualMachineTO to) {
        List<ServiceOfferingDetailsVO> details = _serviceOfferingDetailsDao.listDetails(offering.getId());
        if (CollectionUtils.isNotEmpty(details)) {
            for (ServiceOfferingDetailsVO detail : details) {
                if (detail.getName().startsWith(ApiConstants.EXTRA_CONFIG)) {
                    configurationManager.validateExtraConfigInServiceOfferingDetail(detail.getName());
                    to.addExtraConfig(detail.getName(), detail.getValue());
                }
            }
        }
    }

    /**
     * Enrich a VXLAN-isolated {@link NicTO} with the current dynamic peer list
     * so the KVM agent can plumb a full OVS VXLAN mesh without relying on a
     * static {@code vxlan.peers} / {@code vxlan.local.ip} pair in
     * {@code agent.properties}.
     *
     * <p>Populated detail keys (see {@code VxlanTunnelManager}):
     * <ul>
     *   <li>{@code vxlan.peers}: comma-separated management IPs of all
     *       {@code KVM} hosts in the same zone that are currently
     *       {@link com.cloud.host.Status#Up}. Filtering by zone (instead of
     *       cluster) matches the BGP/OVS fabric model: every data node in
     *       Slytherin is a valid tunnel endpoint.</li>
     *   <li>{@code vxlan.vm.name}: the VM instance name this NIC belongs to
     *       ({@code i-2-414-VM}, {@code r-417-VM} …). The agent uses this as
     *       the reference-count key so tunnel cleanup on VM stop/expunge is
     *       safe when multiple VMs share a VNI on the same host.</li>
     * </ul>
     *
     * <p>When the NIC is not VXLAN-broadcast, or the zone lookup yields zero
     * Up peers, this method is a no-op and the agent falls back to its own
     * {@code agent.properties} defaults.
     *
     * <p>Guarded: any exception here must never fail the TO assembly — the
     * worst case is the agent falls back to static config or empty peers.
     */
    private void enrichVxlanPeerDetails(NicTO nicTo, VirtualMachine vm) {
        if (nicTo == null || vm == null) {
            return;
        }
        try {
            if (nicTo.getBroadcastUri() == null) {
                return;
            }
            String scheme = nicTo.getBroadcastUri().getScheme();
            if (scheme == null || !"vxlan".equalsIgnoreCase(scheme)) {
                return;
            }
            // vm.getInstanceName() is stable across VM lifecycle (i-2-414-VM,
            // r-417-VM) — exactly what the agent needs as ref-count key.
            String instanceName = vm.getInstanceName();
            if (StringUtils.isNotBlank(instanceName)) {
                nicTo.setNicDetail("vxlan.vm.name", instanceName);
            }
            Long zoneId = vm.getDataCenterId();
            if (zoneId == null) {
                return;
            }
            List<HostVO> hosts = hostDao.listAllHostsUpByZoneAndHypervisor(zoneId, Hypervisor.HypervisorType.KVM);
            if (CollectionUtils.isEmpty(hosts)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (HostVO host : hosts) {
                if (host == null) {
                    continue;
                }
                String ip = host.getPrivateIpAddress();
                if (StringUtils.isBlank(ip)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(ip.trim());
            }
            if (sb.length() > 0) {
                nicTo.setNicDetail("vxlan.peers", sb.toString());
            }
            // "vxlan.local.ip" is the target host's own mgmt IP. It is known
            // only at execution time (the Command dispatcher picks the host
            // at the very end), so we cannot fill it here — the agent detects
            // it at runtime from its uplink device as a fallback.
        } catch (RuntimeException e) {
            logger.debug("enrichVxlanPeerDetails: skipped for nic={} due to {}", nicTo, e.getMessage());
        }
    }

    protected VirtualMachineTO toVirtualMachineTO(VirtualMachineProfile vmProfile) {
        ServiceOffering offering = serviceOfferingDao.findById(vmProfile.getId(), vmProfile.getServiceOfferingId());
        VirtualMachine vm = vmProfile.getVirtualMachine();
        Long clusterId = findClusterOfVm(vm);
        boolean divideMemoryByOverprovisioning = true;
        boolean divideCpuByOverprovisioning = true;

        if (clusterId != null) {
            divideMemoryByOverprovisioning = VmMinMemoryEqualsMemoryDividedByMemOverprovisioningFactor.valueIn(clusterId);
            divideCpuByOverprovisioning = VmMinCpuSpeedEqualsCpuSpeedDividedByCpuOverprovisioningFactor.valueIn(clusterId);
        }

        Long minMemory = (long)(offering.getRamSize() / (divideMemoryByOverprovisioning ? vmProfile.getMemoryOvercommitRatio() : 1));
        int minspeed = (int)(offering.getSpeed() / (divideCpuByOverprovisioning ? vmProfile.getCpuOvercommitRatio() : 1));
        int maxspeed = (offering.getSpeed());
        VirtualMachineTO to = new VirtualMachineTO(vm.getId(), vm.getInstanceName(), vm.getType(), offering.getCpu(), minspeed, maxspeed, minMemory * 1024l * 1024l,
                offering.getRamSize() * 1024l * 1024l, null, null, vm.isHaEnabled(), vm.limitCpuUse(), vm.getVncPassword());
        to.setBootArgs(vmProfile.getBootArgs());

        Map<VirtualMachineProfile.Param, Object> map = vmProfile.getParameters();
        if (MapUtils.isNotEmpty(map)) {
            if (map.containsKey(VirtualMachineProfile.Param.BootMode)) {
                if (StringUtils.isNotBlank((String) map.get(VirtualMachineProfile.Param.BootMode))) {
                    to.setBootMode((String) map.get(VirtualMachineProfile.Param.BootMode));
                }
            }

            if (map.containsKey(VirtualMachineProfile.Param.BootType)) {
                if (StringUtils.isNotBlank((String) map.get(VirtualMachineProfile.Param.BootType))) {
                    to.setBootType((String) map.get(VirtualMachineProfile.Param.BootType));
                }
            }
        }

        List<NicProfile> nicProfiles = vmProfile.getNics();
        NicTO[] nics = new NicTO[nicProfiles.size()];
        int i = 0;
        for (NicProfile nicProfile : nicProfiles) {
            if (vm.getType() == VirtualMachine.Type.NetScalerVm) {
                nicProfile.setBroadcastType(BroadcastDomainType.Native);
            }
            NicTO nicTo = toNicTO(nicProfile);
            allocateVfIfHwOffload(nicTo, nicProfile, vmProfile);
            enrichVxlanPeerDetails(nicTo, vm);
            nics[i++] = nicTo;
        }

        to.setNics(nics);
        to.setDisks(vmProfile.getDisks().toArray(new DiskTO[vmProfile.getDisks().size()]));

        CPU.CPUArch templateArch = vmProfile.getTemplate().getArch();
        if (templateArch != null) {
            to.setArch(templateArch.getType());
        } else {
            if (vmProfile.getTemplate().getBits() == 32) {
                to.setArch(CPU.CPUArch.x86.getType());
            } else if("s390x".equals(System.getProperty("os.arch"))) {
                to.setArch("s390x");
            } else {
                to.setArch(CPU.CPUArch.amd64.getType());
            }
        }

        Map<String, String> detailsInVm = _vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId());
        if (detailsInVm != null) {
            to.setDetails(detailsInVm);
            addExtraConfig(detailsInVm, to, vm.getAccountId(), vm.getHypervisorType());
        }

        addServiceOfferingExtraConfiguration(offering, to);

        // Set GPU details
        ServiceOfferingDetailsVO offeringDetail = _serviceOfferingDetailsDao.findDetail(offering.getId(), GPU.Keys.vgpuType.toString());
        if (offering.getVgpuProfileId() != null || offeringDetail != null) {
                to.setGpuDevice(getGpuDevice(offering, offeringDetail, vm, vmProfile.getHostId()));
        }

        // Workaround to make sure the TO has the UUID we need for Niciri integration
        VMInstanceVO vmInstance = virtualMachineDao.findById(to.getId());
        to.setEnableDynamicallyScaleVm(vmInstance.isDynamicallyScalable());
        to.setUuid(vmInstance.getUuid());

        to.setVmData(vmProfile.getVmData());
        to.setConfigDriveLabel(vmProfile.getConfigDriveLabel());
        to.setConfigDriveIsoRootFolder(vmProfile.getConfigDriveIsoRootFolder());
        to.setConfigDriveIsoFile(vmProfile.getConfigDriveIsoFile());
        to.setConfigDriveLocation(vmProfile.getConfigDriveLocation());
        to.setState(vm.getState());

        return to;
    }

    private GPUDeviceTO getGpuDevice(ServiceOffering offering, ServiceOfferingDetailsVO offeringDetail, VirtualMachine vm, long hostId) {
        if (offering.getVgpuProfileId() != null) {
            VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(offering.getVgpuProfileId());
            if (vgpuProfile != null) {
                int gpuCount = offering.getGpuCount() != null ? offering.getGpuCount() : 1;
                return _resourceMgr.getGPUDevice(vm, hostId, vgpuProfile, gpuCount);
            }
        } else if (offeringDetail != null) {
            ServiceOfferingDetailsVO groupName = _serviceOfferingDetailsDao.findDetail(offering.getId(), GPU.Keys.pciDevice.toString());
            return _resourceMgr.getGPUDevice(vm.getHostId(), groupName.getValue(), offeringDetail.getValue());
        }
        return null;
    }


    protected Long findClusterOfVm(VirtualMachine vm) {
        HostVO host = hostDao.findById(vm.getHostId());
        if (host != null) {
            return host.getClusterId();
        }

        logger.debug(String.format("VM [%s] does not have a host id. Trying the last host.", ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm, "instanceName", "id", "uuid")));
        host = hostDao.findById(vm.getLastHostId());
        if (host != null) {
            return host.getClusterId();
        }

        logger.debug(String.format("VM [%s] does not have a last host id.", ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm, "instanceName", "id", "uuid")));
        return null;
    }

    @Override
    /**
     * The basic implementation assumes that the initial "host" defined to execute the command is the host that is in fact going to execute it.
     * However, subclasses can extend this behavior, changing the host that is going to execute the command in runtime.
     * The first element of the 'Pair' indicates if the hostId has been changed; this means, if you change the hostId, but you do not inform this action in the return 'Pair' object, we will use the original "hostId".
     *
     * Side note: it seems that the 'hostId' received here is normally the ID of the SSVM that has an entry at the host table. Therefore, this methods gives the opportunity to change from the SSVM to a real host to execute a command.
     */
    public Pair<Boolean, Long> getCommandHostDelegation(long hostId, Command cmd) {
        return new Pair<Boolean, Long>(Boolean.FALSE, new Long(hostId));
    }

    @Override
    public List<Command> finalizeExpunge(VirtualMachine vm) {
        return null;
    }

    @Override
    public List<Command> finalizeExpungeNics(VirtualMachine vm, List<NicProfile> nics) {
        return null;
    }

    @Override
    public List<Command> finalizeExpungeVolumes(VirtualMachine vm) {
        return null;
    }

    @Override
    public Map<String, String> getClusterSettings(long vmId) {
        return null;
    }

    @Override
    public VirtualMachine importVirtualMachineFromBackup(long zoneId, long domainId, long accountId, long userId,
                                                         String vmInternalName, Backup backup) throws Exception {
        return null;
    }

    @Override
    public boolean attachRestoredVolumeToVirtualMachine(long zoneId, String location, Backup.VolumeInfo volumeInfo,
                                                        VirtualMachine vm, long poolId, Backup backup) throws Exception {
        return false;
    }

    public List<Command> finalizeMigrate(VirtualMachine vm, Map<Volume, StoragePool> volumeToPool) {
        return null;
    }

     @Override
    public String getConfigComponentName() {
        return HypervisorGuruBase.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {VmMinMemoryEqualsMemoryDividedByMemOverprovisioningFactor,
                VmMinCpuSpeedEqualsCpuSpeedDividedByCpuOverprovisioningFactor,
                HypervisorCustomDisplayName,
                VmVdpaMaxVqs
        };
    }

    @Override
    public Pair<UnmanagedInstanceTO, Boolean> getHypervisorVMOutOfBandAndCloneIfRequired(String hostIp, String vmName, Map<String, String> params) {
        logger.error("Unsupported operation: cannot clone external VM");
        return null;
    }

    @Override
    public boolean removeClonedHypervisorVMOutOfBand(String hostIp, String vmName, Map<String, String> params) {
        logger.error("Unsupported operation: cannot remove external VM");
        return false;
    }

    @Override
    public String createVMTemplateOutOfBand(String hostIp, String vmName, Map<String, String> params, DataStoreTO templateLocation, int threadsCountToExportOvf) {
        logger.error("Unsupported operation: cannot create template file");
        return null;
    }

    @Override
    public boolean removeVMTemplateOutOfBand(DataStoreTO templateLocation, String templateDir) {
        logger.error("Unsupported operation: cannot remove template file");
        return false;
    }

    /**
     * Generates VirtualMachineMetadataTO object from VirtualMachineProfile
     * It is a helper function to be used in the inherited classes to avoid repetition
     * while generating metadata for multiple Guru implementations
     *
     * @param  vmProfile  virtual machine profile object
     * @return      A VirtualMachineMetadataTO ready to be appended to VirtualMachineTO object
     * @see         KVMGuru
     */
    protected VirtualMachineMetadataTO makeVirtualMachineMetadata(VirtualMachineProfile vmProfile) {
        String vmName = "unknown",
                instanceName = "unknown",
                displayName = "unknown",
                instanceUuid = "unknown",
                clusterName = "unknown",
                clusterUuid = "unknown",
                zoneUuid = "unknown",
                zoneName = "unknown",
                podUuid = "unknown",
                podName = "unknown",
                domainUuid = "unknown",
                domainName = "unknown",
                accountUuid = "unknown",
                accountName = "unknown",
                projectName = "", // the project can be empty
                projectUuid = "", // the project can be empty
                serviceOfferingName = "unknown";
        long created = 0L;
        Integer cpuCores = -1, memory = -1;
        List<String> serviceOfferingTags = new ArrayList<>();
        HashMap<String, String> resourceTags = new HashMap<>();

        UserVmVO vmVO = userVmDao.findById(vmProfile.getVirtualMachine().getId());
        if (vmVO != null) {
            instanceUuid = vmVO.getUuid();
            vmName = vmVO.getHostName(); // this returns the VM name field
            instanceName = vmVO.getInstanceName();
            displayName = vmVO.getDisplayName();
            created = vmVO.getCreated().getTime() / 1000L;

            HostVO host = hostDao.findById(vmVO.getHostId());
            if (host != null) {
                // Find zone and cluster
                Long clusterId = host.getClusterId();
                ClusterVO cluster = clusterDao.findById(clusterId);

                if (cluster != null) {
                    clusterName = cluster.getName();
                    clusterUuid = cluster.getUuid();

                    DataCenterVO zone = dataCenterDao.findById(cluster.getDataCenterId());
                    if (zone != null) {
                        zoneUuid = zone.getUuid();
                        zoneName = zone.getName();
                    }

                    HostPodVO pod = hostPodDao.findById(cluster.getPodId());
                    if (pod != null) {
                        podUuid = pod.getUuid();
                        podName = pod.getName();
                    }
                }
            } else {
                logger.warn("Could not find the Host object for the virtual machine (null value returned). Libvirt metadata for cluster, pod, zone will not be populated.");
            }

            DomainVO domain = domainDao.findById(vmVO.getDomainId());
            if (domain != null) {
                domainUuid = domain.getUuid();
                domainName = domain.getName();
            } else {
                logger.warn("Could not find the Domain object for the virtual machine (null value returned). Libvirt metadata for domain will not be populated.");
            }

            Account account = accountManager.getAccount(vmVO.getAccountId());
            if (account != null) {
                accountUuid = account.getUuid();
                accountName = account.getName();

                ProjectVO project = projectDao.findByProjectAccountId(account.getId());
                if (project != null) {
                    projectName = project.getName();
                    projectUuid = project.getUuid();
                }
            } else {
                logger.warn("Could not find the Account object for the virtual machine (null value returned). Libvirt metadata for account and project will not be populated.");
            }

            List<? extends ResourceTag> resourceTagsList = tagsDao.listBy(vmVO.getId(), ResourceTag.ResourceObjectType.UserVm);
            if (resourceTagsList != null) {
                for (ResourceTag tag : resourceTagsList) {
                    resourceTags.put(tag.getKey(), tag.getValue());
                }
            }
        } else {
            logger.warn("Could not find the VirtualMachine object by its profile (null value returned). Libvirt metadata will not be populated.");
        }

        ServiceOffering serviceOffering = vmProfile.getServiceOffering();
        if (serviceOffering != null) {
            serviceOfferingName = serviceOffering.getName();
            cpuCores = serviceOffering.getCpu();
            memory = serviceOffering.getRamSize();

            String hostTagsCommaSeparated = serviceOffering.getHostTag();
            if (hostTagsCommaSeparated != null) { // when service offering has no host tags, this value is null
                serviceOfferingTags = Arrays.asList(hostTagsCommaSeparated.split(","));
            }
        } else {
            logger.warn("Could not find the ServiceOffering object by its profile (null value returned). Libvirt metadata for service offering will not be populated.");
        }


        return new VirtualMachineMetadataTO(
                vmName, // name
                instanceName, // internalName
                displayName, // displayName
                instanceUuid , // instanceUUID
                cpuCores, // cpuCores
                memory, // memory
                created, // created, unix epoch in seconds
                System.currentTimeMillis() / 1000L, // started, unix epoch in seconds
                domainUuid, // ownerDomainUUID
                domainName, // ownerDomainName
                accountUuid, // ownerAccountUUID
                accountName, // ownerAccountName
                projectUuid,
                projectName,
                serviceOfferingName,
                serviceOfferingTags, // serviceOfferingTags
                zoneName,
                zoneUuid,
                podName,
                podUuid,
                clusterName,
                clusterUuid,
                resourceTags
        );
    }
}
