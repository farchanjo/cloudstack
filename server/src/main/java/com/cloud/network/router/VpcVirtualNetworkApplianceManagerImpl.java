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
package com.cloud.network.router;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.network.dao.NetworkDao;
import com.cloud.network.vpc.dao.VpcDao;
import org.apache.cloudstack.agent.routing.ManageServiceCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.Command.OnError;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.SetupGuestNetworkCommand;
import com.cloud.agent.api.routing.AggregationControlCommand;
import com.cloud.agent.api.routing.AggregationControlCommand.Action;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruBase;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.IpAddress;
import com.cloud.network.MonitoringService;
import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.VirtualRouterProvider;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.MonitoringServiceVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.RemoteAccessVpnVO;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLManager;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.PrivateIpAddress;
import com.cloud.network.vpc.PrivateIpVO;
import com.cloud.network.vpc.StaticRouteVO;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.StaticRouteDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.network.vpn.Site2SiteVpnManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.UserStatisticsVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VpcVirtualNetworkApplianceManagerImpl extends VirtualNetworkApplianceManagerImpl implements VpcVirtualNetworkApplianceManager {

    @Inject
    private NetworkACLManager _networkACLMgr;
    @Inject
    private VMInstanceDao _vmDao;
    @Inject
    private StaticRouteDao _staticRouteDao;
    @Inject
    private VpcManager _vpcMgr;
    @Inject
    private PrivateIpDao _privateIpDao;
    @Inject
    private Site2SiteVpnManager _s2sVpnMgr;
    @Inject
    private VpcGatewayDao _vpcGatewayDao;
    @Inject
    private NetworkACLItemDao _networkACLItemDao;
    @Inject
    private EntityManager _entityMgr;
    @Inject
    protected HypervisorGuruManager _hvGuruMgr;
    @Inject
    private VfPoolManager _vfPoolManager;
    @Inject
    private com.cloud.offerings.dao.NetworkOfferingDao _networkOfferingDao2;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected VpcDao vpcDao;
    @Inject
    private LoadBalancerDao loadBalancerDao;

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _itMgr.registerGuru(VirtualMachine.Type.DomainRouter, this);
        return super.configure(name, params);
    }

    @Override
    public boolean addVpcRouterToGuestNetwork(final VirtualRouter router, final Network network, final Map<VirtualMachineProfile.Param, Object> params)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (network.getTrafficType() != TrafficType.Guest) {
            logger.warn("Network " + network + " is not of type " + TrafficType.Guest);
            return false;
        }

        // Add router to the Guest network
        boolean result = true;
        try {

            // 1) add nic to the router
            _routerDao.addRouterToGuestNetwork(router, network);

            final NicProfile guestNic = _itMgr.addVmToNetwork(router, network, null);
            if (network.getVpcId() != null) {
                VpcVO vpc = _vpcDao.findById(network.getVpcId());
                guestNic.setMtu(vpc.getPublicMtu());
            }

            // 2) setup guest network
            if (guestNic != null) {
                result = setupVpcGuestNetwork(network, router, true, guestNic);
            } else {
                logger.warn("Failed to add router " + router + " to guest network " + network);
                result = false;
            }
            // 3) apply networking rules
            if (result) {
                boolean reprogramNetwork = params != null && params.get(Param.ReProgramGuestNetworks) != null && (Boolean) params.get(Param.ReProgramGuestNetworks) == true;
                sendNetworkRulesToRouter(router.getId(), network.getId(), reprogramNetwork);
            }
        } catch (final Exception ex) {
            logger.warn("Failed to add router " + router + " to network " + network + " due to ", ex);
            result = false;
        } finally {
            if (!result) {
                logger.debug("Removing the router " + router + " from network " + network + " as a part of cleanup");
                if (removeVpcRouterFromGuestNetwork(router, network)) {
                    logger.debug("Removed the router " + router + " from network " + network + " as a part of cleanup");
                } else {
                    logger.warn("Failed to remove the router " + router + " from network " + network + " as a part of cleanup");
                }
            } else {
                logger.debug("Successfully added router " + router + " to guest network " + network);
            }
        }

        return result;
    }

    @Override
    public boolean removeVpcRouterFromGuestNetwork(final VirtualRouter router, final Network network) throws ConcurrentOperationException,
    ResourceUnavailableException {
        if (network.getTrafficType() != TrafficType.Guest) {
            logger.warn("Network " + network + " is not of type " + TrafficType.Guest);
            return false;
        }

        boolean result = true;
        try {
            // Check if router is a part of the Guest network
            if (!_networkModel.isVmPartOfNetwork(router.getId(), network.getId())) {
                logger.debug("Router " + router + " is not a part of the Guest network " + network);
                return result;
            }

            result = setupVpcGuestNetwork(network, router, false, _networkModel.getNicProfile(router, network.getId(), null));
            if (!result) {
                logger.warn("Failed to destroy guest network config " + network + " on router " + router);
                return false;
            }

            result = result && _itMgr.removeVmFromNetwork(router, network, null);
        } finally {
            if (result) {
                _routerDao.removeRouterFromGuestNetwork(router.getId(), network.getId());
            }
        }

        return result;
    }

    @Override
    public boolean stopKeepAlivedOnRouter(VirtualRouter router,
            Network network) throws ConcurrentOperationException, ResourceUnavailableException {
        return manageKeepalivedServiceOnRouter(router, network, "stop");
    }

    @Override
    public boolean startKeepAlivedOnRouter(VirtualRouter router,
            Network network) throws ConcurrentOperationException, ResourceUnavailableException {
        return manageKeepalivedServiceOnRouter(router, network, "start");
    }

    private boolean manageKeepalivedServiceOnRouter(VirtualRouter router,
            Network network, String action) throws ConcurrentOperationException, ResourceUnavailableException {
        if (network.getTrafficType() != TrafficType.Guest) {
            logger.warn("Network {} is not of type {}", network, TrafficType.Guest);
            return false;
        }
        boolean result = true;
        try {
            if (router.getState() == State.Running) {
                final ManageServiceCommand stopCommand = new ManageServiceCommand("keepalived", action);
                stopCommand.setAccessDetail(NetworkElementCommand.ROUTER_IP, _routerControlHelper.getRouterControlIp(router.getId()));

                final Commands cmds = new Commands(Command.OnError.Stop);
                cmds.addCommand("manageKeepalived", stopCommand);
                _nwHelper.sendCommandsToRouter(router, cmds);

                final Answer setupAnswer = cmds.getAnswer("manageKeepalived");
                if (!(setupAnswer != null && setupAnswer.getResult())) {
                    logger.warn("Unable to {} keepalived on router {}", action, router);
                    result = false;
                }
            } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
                logger.debug("Router {} is in {}, so not sending command to the backend", router.getInstanceName(), router.getState());
            } else {
                String message = "Unable to " + action + " keepalived on virtual router [" + router + "] is not in the right state " + router.getState();
                logger.warn(message);
                throw new ResourceUnavailableException(message, DataCenter.class, router.getDataCenterId());
            }
        } catch (final Exception ex) {
            logger.warn("Failed to {}  keepalived on router {} to network {} due to {}", action, router, network, ex.getLocalizedMessage());
            logger.debug("Failed to {}  keepalived on router {} to network {}", action, router, network, ex);
            result = false;
        }
        return result;
    }

    protected boolean setupVpcGuestNetwork(final Network network, final VirtualRouter router, final boolean add, final NicProfile guestNic) throws ConcurrentOperationException,
    ResourceUnavailableException {

        boolean result = true;
        if (router.getState() == State.Running) {
            final SetupGuestNetworkCommand setupCmd = _commandSetupHelper.createSetupGuestNetworkCommand((DomainRouterVO) router, add, guestNic);

            final Commands cmds = new Commands(Command.OnError.Stop);
            cmds.addCommand("setupguestnetwork", setupCmd);
            _nwHelper.sendCommandsToRouter(router, cmds);

            final Answer setupAnswer = cmds.getAnswer("setupguestnetwork");
            final String setup = add ? "set" : "destroy";
            if (!(setupAnswer != null && setupAnswer.getResult())) {
                logger.warn("Unable to " + setup + " guest network on router " + router);
                result = false;
            }
            return result;
        } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
            logger.debug("Router " + router.getInstanceName() + " is in " + router.getState() + ", so not sending setup guest network command to the backend");
            return true;
        } else {
            logger.warn("Unable to setup guest network on virtual router " + router + " is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to setup guest network on the backend," + " virtual router " + router + " is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }
    }

    @Override
    public boolean finalizeVirtualMachineProfile(final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context) {
        final DomainRouterVO domainRouterVO = _routerDao.findById(profile.getId());

        final Long vpcId = domainRouterVO.getVpcId();

        if (vpcId != null) {
            if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
                // HW offload tier pre-alloc (PRE-start): any guest tier with a
                // hw-offload offering that does not yet have a VR NIC attached
                // gets one allocated NOW and inserted into profile.getNics(),
                // so the tier's hostdev VF appears in the libvirt boot XML on
                // this VR start (not on the next restart).
                //
                // This is the companion of the existing post-start pre-alloc
                // in finalizeCommandsOnStart: without this PRE-start pass, a
                // first VM deploy into a brand-new tier boots the VR with only
                // control+public NICs and DHCP/SetupGuestNetwork fails with
                // "Unable to apply dhcp entry on router". Running it here makes
                // the restartVpc path (or any VR start on a VPC with freshly
                // added tiers) converge in a single start cycle.
                try {
                    final java.util.List<? extends Network> vpcNetworksEarly = _vpcMgr.getVpcNetworks(vpcId);
                    for (final Network vpcNetwork : vpcNetworksEarly) {
                        if (vpcNetwork.getTrafficType() != TrafficType.Guest) continue;
                        if (_networkModel.isPrivateGateway(vpcNetwork.getId())) continue;
                        if (!isHwOffloadNetwork(vpcNetwork.getId())) continue;
                        final Nic existingNic = _nicDao.findByNtwkIdAndInstanceId(vpcNetwork.getId(), domainRouterVO.getId());
                        if (existingNic != null) continue;
                        boolean alreadyInProfile = false;
                        for (final NicProfile np : profile.getNics()) {
                            if (np.getNetworkId() == vpcNetwork.getId()) { alreadyInProfile = true; break; }
                        }
                        if (alreadyInProfile) continue;
                        logger.info("PRE-start pre-alloc: allocating NIC for HW offload network {} on VR {} (vpcId={})",
                                vpcNetwork.getName(), domainRouterVO.getInstanceName(), vpcId);
                        _routerDao.addRouterToGuestNetwork(domainRouterVO, vpcNetwork);
                        final NicProfile nicProfile = _networkMgr.createNicForVm(vpcNetwork, null, context, profile, true);
                        if (nicProfile != null) {
                            profile.addNic(nicProfile);
                            logger.info("PRE-start pre-alloc: added NIC profile deviceId={} ip={} network={} to VR {} boot profile",
                                    nicProfile.getDeviceId(), nicProfile.getIPv4Address(), vpcNetwork.getName(),
                                    domainRouterVO.getInstanceName());
                        }
                    }
                } catch (final Exception preAllocEx) {
                    logger.warn("PRE-start pre-alloc failed for VR {} vpcId={}; VR may still boot but require a restart for new HW offload tiers",
                            domainRouterVO.getInstanceName(), vpcId, preAllocEx);
                }
                String defaultDns1 = null;
                String defaultDns2 = null;
                String defaultIp6Dns1 = null;
                String defaultIp6Dns2 = null;
                boolean isDnsConfigured = false;
                // Phase B/4 (multi-tier safe): HW-offload guest NICs and Public
                // are hostdev VFs and must stay in boot XML (VfPassthroughVifDriver
                // doesn't support hot-attach). Non-HW guests are removed and
                // hot-plugged later as before.
                //
                // Layout (Layout A — preserves DB device_id ordering, matches
                // legacy systemvm assumptions like PUBLIC_INTERFACES=eth1):
                //   eth0        -> Control (DB device_id=0)
                //   eth1        -> Public  (DB device_id=1, allocated 2nd)
                //   eth2..ethN  -> Guest tiers (DB device_id=2..N, allocated in tier creation order)
                //
                // We do NOT reassign deviceIds. The DB device_id is the
                // authoritative position used everywhere — boot cmdline
                // generator, libvirt slot order, SetupGuestNetworkCommand,
                // CsAddress, dnsmasq listen-address, iptables INPUT rules.
                boolean hasHwOffloadGuest = false;
                for (final NicProfile probeNic : profile.getNics()) {
                    if (probeNic.getTrafficType() == TrafficType.Guest && isHwOffloadNetwork(probeNic.getNetworkId())) {
                        hasHwOffloadGuest = true;
                        break;
                    }
                }

                // remove non-HW-offload public and guest nics as we will plug them later
                final Iterator<NicProfile> it = profile.getNics().iterator();
                while (it.hasNext()) {
                    final NicProfile nic = it.next();
                    if (nic.getTrafficType() == TrafficType.Public || nic.getTrafficType() == TrafficType.Guest) {
                        // save dns information
                        if (nic.getTrafficType() == TrafficType.Public || !isDnsConfigured) {
                            defaultDns1 = nic.getIPv4Dns1();
                            defaultDns2 = nic.getIPv4Dns2();
                            defaultIp6Dns1 = nic.getIPv6Dns1();
                            defaultIp6Dns2 = nic.getIPv6Dns2();
                            isDnsConfigured = true;
                        }
                        if (nic.getTrafficType() == TrafficType.Guest && isHwOffloadNetwork(nic.getNetworkId())) {
                            logger.info("Keeping HW offload guest NIC network={} in boot profile as eth{} (deviceId={}) for VF passthrough",
                                    nic.getNetworkId(), nic.getDeviceId(), nic.getDeviceId());
                            continue; // kept, DB device_id flows through
                        }
                        if (nic.getTrafficType() == TrafficType.Public && hasHwOffloadGuest) {
                            logger.info("Keeping public NIC network={} in boot profile as eth{} (deviceId={}) for VF passthrough (Phase B/4 — sibling guest is HW-offload)",
                                    nic.getNetworkId(), nic.getDeviceId(), nic.getDeviceId());
                            continue; // kept, DB device_id flows through
                        }
                        logger.debug("Removing NIC " + nic + " of type " + nic.getTrafficType() + " from the NICs passed on Instance start. " + "The NIC will be plugged later");
                        it.remove();
                    }
                }

                // add vpc cidr/dns/networkdomain to the boot load args
                final StringBuilder buf = profile.getBootArgsBuilder();
                final Vpc vpc = _entityMgr.findById(Vpc.class, vpcId);
                buf.append(" vpccidr=" + vpc.getCidr() + " domain=" + vpc.getNetworkDomain());
                buf.append(" publicMtu=").append(vpc.getPublicMtu());
                buf.append(" dns1=").append(defaultDns1);
                if (defaultDns2 != null) {
                    buf.append(" dns2=").append(defaultDns2);
                }
                if (defaultIp6Dns1 != null) {
                    buf.append(" ip6dns1=").append(defaultIp6Dns1);
                }
                if (defaultIp6Dns2 != null) {
                    buf.append(" ip6dns2=").append(defaultIp6Dns2);
                }
                if (routedIpv4Manager.isRoutedVpc(vpc)) {
                    buf.append(" is_routed=true");
                }
            }
        }

        super.finalizeVirtualMachineProfile(profile, dest, context);
        appendSourceNatIpToBootArgs(profile);
        return true;
    }

    private void appendSourceNatIpToBootArgs(final VirtualMachineProfile profile) {
        final StringBuilder buf = profile.getBootArgsBuilder();
        final DomainRouterVO router = _routerDao.findById(profile.getVirtualMachine().getId());
        if (router != null && router.getVpcId() != null) {
            List<IPAddressVO> vpcIps = _ipAddressDao.listByAssociatedVpc(router.getVpcId(), true);
            if (CollectionUtils.isNotEmpty(vpcIps)) {
                buf.append(String.format(" source_nat_ip=%s", vpcIps.get(0).getAddress().toString()));
            }
            appendPublicIpv6ToBootArgs(router, buf);
            logger.debug("The final Boot Args for " + profile + ": " + buf);
        }
    }

    /**
     * Appends the VR's public-NIC IPv6 address, prefix length and gateway to
     * the kernel boot arguments. IPv6 is routed (not NATted), so we pass the
     * NIC's own v6 address rather than an associated public IP entry.
     *
     * Uses the actual NIC device_id from the DB (not hardcoded eth1). This is
     * a fallback for legacy (non-HW-offload) VPC VRs where Public is plugged
     * post-boot; for HW-offload VRs the main finalize loop in the parent class
     * already emits eth&lt;deviceId&gt;ip6= from NicProfile and we skip here
     * to avoid duplicate entries.
     */
    private void appendPublicIpv6ToBootArgs(final DomainRouterVO router, final StringBuilder buf) {
        final List<NicVO> nics = _nicDao.listByVmId(router.getId());
        for (NicVO nic : nics) {
            final Network nicNetwork = _networkDao.findById(nic.getNetworkId());
            if (nicNetwork == null || nicNetwork.getTrafficType() != TrafficType.Public) {
                continue;
            }
            if (nic.getIPv6Address() == null || nic.getIPv6Gateway() == null || nic.getIPv6Cidr() == null) {
                continue;
            }
            final String cidr = nic.getIPv6Cidr();
            final int slash = cidr.indexOf('/');
            if (slash < 0) {
                continue;
            }
            // Skip if the main finalize loop already emitted eth<N>ip6= for this
            // Public NIC (HW-offload layout keeps Public in profile.getNics()).
            final String alreadyEmitted = " eth" + nic.getDeviceId() + "ip6=";
            if (buf.indexOf(alreadyEmitted) >= 0) {
                break;
            }
            final String prelen = cidr.substring(slash + 1);
            buf.append(" eth").append(nic.getDeviceId()).append("ip6=").append(nic.getIPv6Address());
            buf.append(" eth").append(nic.getDeviceId()).append("ip6prelen=").append(prelen);
            buf.append(" ip6gateway=").append(nic.getIPv6Gateway());
            break;
        }
    }

    @Override
    public boolean finalizeCommandsOnStart(final Commands cmds, final VirtualMachineProfile profile) {
        final DomainRouterVO domainRouterVO = _routerDao.findById(profile.getId());

        Map<String, String> details = new HashMap<String, String>();

        if(profile.getHypervisorType() == Hypervisor.HypervisorType.VMware){
            HypervisorGuru hvGuru = _hvGuruMgr.getGuru(profile.getHypervisorType());
            VirtualMachineTO vmTO = hvGuru.implement(profile);
            if(vmTO.getDetails() != null){
                details = vmTO.getDetails();
            }
        }

        final boolean isVpc = domainRouterVO.getVpcId() != null;
        if (!isVpc) {
            return super.finalizeCommandsOnStart(cmds, profile);
        }

        if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
            // 1) FORM SSH CHECK COMMAND
            final NicProfile controlNic = getControlNic(profile);
            if (controlNic == null) {
                logger.error("Control network doesn't exist for the router " + domainRouterVO);
                return false;
            }

            finalizeSshAndVersionAndNetworkUsageOnStart(cmds, profile, domainRouterVO, controlNic);

            // 2) FORM PLUG NIC COMMANDS
            final List<Pair<Nic, Network>> guestNics = new ArrayList<Pair<Nic, Network>>();
            final List<Pair<Nic, Network>> publicNics = new ArrayList<Pair<Nic, Network>>();
            final List<Pair<Nic, Network>> privateGatewayNics = new ArrayList<Pair<Nic, Network>>();
            final Map<String, String> vlanMacAddress = new HashMap<String, String>();

            final List<? extends Nic> routerNics = _nicDao.listByVmIdOrderByDeviceId(profile.getId());
            for (final Nic routerNic : routerNics) {
                final Network network = _networkModel.getNetwork(routerNic.getNetworkId());
                if (network.getTrafficType() == TrafficType.Guest) {
                    final Pair<Nic, Network> guestNic = new Pair<Nic, Network>(routerNic, network);
                    if (_networkModel.isPrivateGateway(routerNic.getNetworkId())) {
                        privateGatewayNics.add(guestNic);
                    } else {
                        guestNics.add(guestNic);
                    }
                } else if (network.getTrafficType() == TrafficType.Public) {
                    final Pair<Nic, Network> publicNic = new Pair<Nic, Network>(routerNic, network);
                    publicNics.add(publicNic);
                    String vlanTag = null;
                    if (Objects.nonNull(routerNic.getBroadcastUri())) {
                        vlanTag = BroadcastDomainType.getValue(routerNic.getBroadcastUri());
                    } else {
                        vlanTag = "nsx-"+routerNic.getIPv4Address();
                    }
                    vlanMacAddress.put(vlanTag, routerNic.getMacAddress());
                }
            }
            int deviceId = 1; //Public and Guest networks start from device_id = 1

            final List<Command> usageCmds = new ArrayList<Command>();

            // 3) PREPARE PLUG NIC COMMANDS
            try {
                // add VPC router to public networks
                final List<PublicIp> sourceNat = new ArrayList<PublicIp>(1);
                for (final Pair<Nic, Network> nicNtwk : publicNics) {
                    final Nic publicNic = updateNicWithDeviceId(nicNtwk.first().getId(), deviceId);
                    deviceId ++;
                    final Network publicNtwk = nicNtwk.second();
                    final IPAddressVO userIp = _ipAddressDao.findByIpAndSourceNetworkId(publicNtwk.getId(), publicNic.getIPv4Address());

                    if (userIp.isSourceNat()) {
                        final PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                        sourceNat.add(publicIp);

                        if (domainRouterVO.getPublicIpAddress() == null) {
                            final DomainRouterVO routerVO = _routerDao.findById(domainRouterVO.getId());
                            routerVO.setPublicIpAddress(publicNic.getIPv4Address());
                            routerVO.setPublicNetmask(publicNic.getIPv4Netmask());
                            routerVO.setPublicMacAddress(publicNic.getMacAddress());
                            _routerDao.update(routerVO.getId(), routerVO);
                        }
                    }
                    String broadcastURI = publicNic.getBroadcastUri() != null ? publicNic.getBroadcastUri().toString() : null;
                    // Phase B/4: when the VR has any HW-offload guest tier, the public NIC
                    // is also promoted to a hostdev VF (VfPassthroughVifDriver — no hot-plug).
                    // It's already in the boot domain XML via finalizeVirtualMachineProfile,
                    // so we skip the post-start PlugNicCommand to avoid double-attach.
                    boolean publicHwOffload = vrHasAnyHwOffloadGuestTier(domainRouterVO);
                    if (publicHwOffload) {
                        logger.info("HW offload public NIC for VR {} already in boot domain XML; skipping PlugNic", domainRouterVO.getInstanceName());
                    } else {
                        final PlugNicCommand plugNicCmd = new PlugNicCommand(_nwHelper.getNicTO(domainRouterVO, publicNic.getNetworkId(), broadcastURI),
                                domainRouterVO.getInstanceName(), domainRouterVO.getType(), details);
                        cmds.addCommand(plugNicCmd);
                    }
                    final VpcVO vpc = _vpcDao.findById(domainRouterVO.getVpcId());
                    if (routedIpv4Manager.isRoutedVpc(vpc)) {
                        continue;
                    }
                    final NetworkUsageCommand netUsageCmd = new NetworkUsageCommand(domainRouterVO.getPrivateIpAddress(), domainRouterVO.getInstanceName(), true, publicNic.getIPv4Address(), vpc.getCidr());
                    usageCmds.add(netUsageCmd);
                    UserStatisticsVO stats = _userStatsDao.findBy(domainRouterVO.getAccountId(), domainRouterVO.getDataCenterId(), publicNtwk.getId(), publicNic.getIPv4Address(), domainRouterVO.getId(),
                            domainRouterVO.getType().toString());
                    if (stats == null) {
                        stats = new UserStatisticsVO(domainRouterVO.getAccountId(), domainRouterVO.getDataCenterId(), publicNic.getIPv4Address(), domainRouterVO.getId(), domainRouterVO.getType().toString(),
                                publicNtwk.getId());
                        _userStatsDao.persist(stats);
                    }
                }

                // create ip assoc for source nat
                if (!sourceNat.isEmpty()) {
                    _commandSetupHelper.createVpcAssociatePublicIPCommands(domainRouterVO, sourceNat, cmds, vlanMacAddress);
                }

                // add VPC router to private gateway networks
                for (final Pair<Nic, Network> nicNtwk : privateGatewayNics) {
                    final Nic guestNic = updateNicWithDeviceId(nicNtwk.first().getId(), deviceId);
                    deviceId ++;
                    // plug guest nic
                    final PlugNicCommand plugNicCmd = new PlugNicCommand(_nwHelper.getNicTO(domainRouterVO, guestNic.getNetworkId(), null), domainRouterVO.getInstanceName(), domainRouterVO.getType(), details);
                    cmds.addCommand(plugNicCmd);
                    // set private network
                    final PrivateIpVO ipVO = _privateIpDao.findByIpAndSourceNetworkId(guestNic.getNetworkId(), guestNic.getIPv4Address());
                    final Network network = _networkDao.findById(guestNic.getNetworkId());
                    BroadcastDomainType.getValue(network.getBroadcastUri());
                    final String netmask = NetUtils.getCidrNetmask(network.getCidr());
                    final PrivateIpAddress ip = new PrivateIpAddress(ipVO, network.getBroadcastUri().toString(), network.getGateway(), netmask, guestNic.getMacAddress());

                    final List<PrivateIpAddress> privateIps = new ArrayList<PrivateIpAddress>(1);
                    privateIps.add(ip);
                    _commandSetupHelper.createVpcAssociatePrivateIPCommands(domainRouterVO, privateIps, cmds, true);

                    final Long privateGwAclId = _vpcGatewayDao.getNetworkAclIdForPrivateIp(ipVO.getVpcId(), ipVO.getNetworkId(), ipVO.getIpAddress());

                    if (privateGwAclId != null) {
                        // set network acl on private gateway
                        final List<NetworkACLItemVO> networkACLs = _networkACLItemDao.listByACL(privateGwAclId);
                        logger.debug("Found " + networkACLs.size() + " network ACLs to apply as a part of VPC VR " + domainRouterVO + " start for private gateway ip = "
                                + ipVO.getIpAddress());

                        _commandSetupHelper.createNetworkACLsCommands(networkACLs, domainRouterVO, cmds, ipVO.getNetworkId(), true);
                    }
                }

                // Pre-allocate guest NICs for HW offload networks before VR boots.
                // VPC VRs normally get guest NICs via PlugNicCommand (hot-plug) AFTER boot,
                // but PCI passthrough (hostdev) cannot be hot-plugged — it must be in the
                // domain XML at boot time. So we allocate the NIC now, before StartCommand.
                if (guestNics.isEmpty() && domainRouterVO.getVpcId() != null) {
                    final List<? extends Network> vpcNetworks = _vpcMgr.getVpcNetworks(domainRouterVO.getVpcId());
                    logger.info("HW offload pre-alloc: vpcId={} vpcNetworks.size={} for VR {}",
                            domainRouterVO.getVpcId(), vpcNetworks != null ? vpcNetworks.size() : "null",
                            domainRouterVO.getInstanceName());
                    for (final Network vpcNetwork : vpcNetworks) {
                        logger.info("HW offload pre-alloc: checking network={} traffic={} offering={} isPrivGw={}",
                                vpcNetwork.getName(), vpcNetwork.getTrafficType(),
                                vpcNetwork.getNetworkOfferingId(),
                                _networkModel.isPrivateGateway(vpcNetwork.getId()));
                        if (vpcNetwork.getTrafficType() != TrafficType.Guest) continue;
                        if (_networkModel.isPrivateGateway(vpcNetwork.getId())) continue;
                        if (!isHwOffloadNetwork(vpcNetwork.getId())) continue;

                        final Nic existingNic = _nicDao.findByNtwkIdAndInstanceId(vpcNetwork.getId(), domainRouterVO.getId());
                        if (existingNic != null) continue;

                        logger.info("Pre-allocating guest NIC for HW offload network {} on VR {}",
                                vpcNetwork.getName(), domainRouterVO.getInstanceName());
                        final ReservationContext context = new ReservationContextImpl(null, null,
                                _accountMgr.getSystemUser(), _accountMgr.getSystemAccount());
                        final VirtualMachineProfileImpl vmProfile =
                                new VirtualMachineProfileImpl(domainRouterVO, null, null, null, null);
                        final NicProfile nicProfile = _networkMgr.createNicForVm(vpcNetwork, null, context, vmProfile, true);
                        if (nicProfile != null) {
                            _routerDao.addRouterToGuestNetwork(domainRouterVO, vpcNetwork);
                            final Nic newNic = _nicDao.findByNtwkIdAndInstanceId(vpcNetwork.getId(), domainRouterVO.getId());
                            if (newNic != null) {
                                guestNics.add(new Pair<>(newNic, vpcNetwork));
                                logger.info("Pre-allocated guest NIC id={} ip={} for HW offload network {} on VR {}",
                                        newNic.getId(), newNic.getIPv4Address(), vpcNetwork.getName(),
                                        domainRouterVO.getInstanceName());
                            }
                        }
                    }
                }

                // add VPC router to guest networks
                logger.info("finalizeCommandsOnStart: guestNics.size={} for VR {}", guestNics.size(), domainRouterVO.getInstanceName());
                for (final Pair<Nic, Network> nicNtwk : guestNics) {
                    final Nic guestNic = updateNicWithDeviceId(nicNtwk.first().getId(), deviceId);
                    deviceId ++;

                    // Check if this guest NIC's offering has HW offload enabled.
                    // PCI passthrough (hostdev) cannot be hot-plugged — it must be in the
                    // boot XML. The StartCommand's VirtualMachineTO already contains this NIC
                    // (added by NetworkOrchestrator.prepare → vmProfile.getNics()), and
                    // HypervisorGuruBase.allocateVfIfHwOffload() already enriched it with VF
                    // info during toVirtualMachineTO(). So we skip the PlugNicCommand and
                    // only issue SetupGuestNetworkCommand for configuration.
                    boolean skipPlugNic = isHwOffloadNetwork(guestNic.getNetworkId());
                    if (!skipPlugNic) {
                        NicTO guestNicTo = _nwHelper.getNicTO(domainRouterVO, guestNic.getNetworkId(), null);
                        final PlugNicCommand plugNicCmd = new PlugNicCommand(guestNicTo, domainRouterVO.getInstanceName(), domainRouterVO.getType(), details);
                        cmds.addCommand(plugNicCmd);
                    } else {
                        logger.info("HW offload NIC for network {} already in boot domain XML; skipping PlugNic", guestNic.getNetworkId());
                    }
                    // SetupGuestNetworkCommand is always needed (DHCP, ACLs, routing)
                    final VirtualMachine vm = _vmDao.findById(domainRouterVO.getId());
                    final NicProfile nicProfile = _networkModel.getNicProfile(vm, guestNic.getNetworkId(), null);
                    if (skipPlugNic) {
                        // HW offload VF is the second boot device (after control eth0) → eth1
                        nicProfile.setDeviceId(1);
                        logger.info("Adjusted deviceId to 1 for HW offload guest NIC (eth1 in VR)");
                    }
                    final SetupGuestNetworkCommand setupCmd = _commandSetupHelper.createSetupGuestNetworkCommand(domainRouterVO, true, nicProfile);
                    cmds.addCommand(setupCmd);
                }
            } catch (final Exception ex) {
                logger.warn("Failed to add router " + domainRouterVO + " to network due to exception ", ex);
                return false;
            }

            // 4) RE-APPLY ALL STATIC ROUTE RULES
            final List<StaticRouteVO> routes = _vpcMgr.getVpcStaticRoutes(domainRouterVO.getVpcId());
            final List<StaticRouteProfile> staticRouteProfiles = _vpcMgr.getVpcStaticRoutes(routes);

            logger.debug("Found " + staticRouteProfiles.size() + " static routes to apply as a part of vpc route " + domainRouterVO + " start");
            if (!staticRouteProfiles.isEmpty()) {
                _commandSetupHelper.createStaticRouteCommands(staticRouteProfiles, domainRouterVO, cmds);
            }

            // 5) RE-APPLY ALL REMOTE ACCESS VPNs
            final RemoteAccessVpnVO vpn = _vpnDao.findByAccountAndVpc(domainRouterVO.getAccountId(), domainRouterVO.getVpcId());
            if (vpn != null) {
                _commandSetupHelper.createApplyVpnCommands(true, vpn, domainRouterVO, cmds);
            }

            // 6) REPROGRAM GUEST NETWORK
            boolean reprogramGuestNtwks = true;
            if (profile.getParameter(Param.ReProgramGuestNetworks) != null && (Boolean) profile.getParameter(Param.ReProgramGuestNetworks) == false) {
                reprogramGuestNtwks = false;
            }

            final VirtualRouterProvider vrProvider = _vrProviderDao.findById(domainRouterVO.getElementId());
            if (vrProvider == null) {
                throw new CloudRuntimeException("Cannot find related virtual router provider of router: " + domainRouterVO.getHostName());
            }
            final Provider provider = Network.Provider.getProvider(vrProvider.getType().toString());
            if (provider == null) {
                throw new CloudRuntimeException("Cannot find related provider of virtual router provider: " + vrProvider.getType().toString());
            }

            Map<String, String> routerHealthCheckConfig = getRouterHealthChecksConfig(domainRouterVO);
            if (reprogramGuestNtwks && publicNics.size() > 0) {
                finalizeMonitorService(cmds, profile, domainRouterVO, provider, publicNics.get(0).second().getId(), true, routerHealthCheckConfig);
            }

            for (final Pair<Nic, Network> nicNtwk : guestNics) {
                final Nic guestNic = nicNtwk.first();
                final long guestNetworkId = guestNic.getNetworkId();
                final AggregationControlCommand startCmd = new AggregationControlCommand(Action.Start, domainRouterVO.getInstanceName(), controlNic.getIPv4Address(), _routerControlHelper.getRouterIpInNetwork(
                        guestNetworkId, domainRouterVO.getId()));
                cmds.addCommand(startCmd);
                if (reprogramGuestNtwks) {
                    finalizeIpAssocForNetwork(cmds, domainRouterVO, provider, guestNetworkId, vlanMacAddress);
                    finalizeNetworkRulesForNetwork(cmds, domainRouterVO, provider, guestNetworkId);
                    finalizeMonitorService(cmds, profile, domainRouterVO, provider, guestNetworkId, true, routerHealthCheckConfig);
                }

                finalizeUserDataAndDhcpOnStart(cmds, domainRouterVO, provider, guestNetworkId);
                final AggregationControlCommand finishCmd = new AggregationControlCommand(Action.Finish, domainRouterVO.getInstanceName(), controlNic.getIPv4Address(), _routerControlHelper.getRouterIpInNetwork(
                        guestNetworkId, domainRouterVO.getId()));
                cmds.addCommand(finishCmd);
            }

            createApplyLoadBalancingRulesCommandsForVpc(cmds, domainRouterVO, provider, guestNics);

            // Add network usage commands
            cmds.addCommands(usageCmds);
        }
        return true;
    }

    private void createApplyLoadBalancingRulesCommandsForVpc(final Commands cmds, DomainRouterVO domainRouterVO, Provider provider,
                                                             List<Pair<Nic, Network>> guestNics) {
        final List<LoadBalancerVO> lbs = loadBalancerDao.listByVpcIdAndScheme(domainRouterVO.getVpcId(), Scheme.Public);
        final List<LoadBalancingRule> lbRules = new ArrayList<>();
        createLoadBalancingRulesList(lbRules, lbs);
        logger.debug("Found " + lbRules.size() + " load balancing rule(s) to apply as a part of VPC VR " + domainRouterVO + " start.");
        if (!lbRules.isEmpty()) {
            for (final Pair<Nic, Network> nicNtwk : guestNics) {
                final Nic guestNic = nicNtwk.first();
                final long guestNetworkId = guestNic.getNetworkId();
                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Lb, provider)) {
                    _commandSetupHelper.createApplyLoadBalancingRulesCommands(lbRules, domainRouterVO, cmds, guestNetworkId);
                    break;
                }
            }
        }
    }

    @Override
    protected List<MonitoringServiceVO> getDefaultServicesToMonitor(NetworkVO network) {
        if (network.getTrafficType() == TrafficType.Public) {
            return Arrays.asList(_monitorServiceDao.getServiceByName(MonitoringService.Service.Ssh.toString()));
        }
        return super.getDefaultServicesToMonitor(network);
    }

    @Override
    protected void finalizeNetworkRulesForNetwork(final Commands cmds, final DomainRouterVO domainRouterVO, final Provider provider, final Long guestNetworkId) {

        super.finalizeNetworkRulesForNetwork(cmds, domainRouterVO, provider, guestNetworkId);

        if (domainRouterVO.getVpcId() != null) {

            if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.NetworkACL, Provider.VPCVirtualRouter)) {
                    final List<NetworkACLItemVO> networkACLs = _networkACLMgr.listNetworkACLItems(guestNetworkId);
                    if (networkACLs != null && !networkACLs.isEmpty()) {
                        logger.debug("Found {} network ACLs to apply as a part of VPC VR {} start for guest network {}", networkACLs.size(), domainRouterVO, _networkModel.getNetwork(guestNetworkId));
                        _commandSetupHelper.createNetworkACLsCommands(networkACLs, domainRouterVO, cmds, guestNetworkId, false);
                    }
                }
            }
        }
    }

    protected boolean sendNetworkRulesToRouter(final long routerId, final long networkId, final boolean reprogramNetwork) throws ResourceUnavailableException {
        final DomainRouterVO router = _routerDao.findById(routerId);
        final Commands cmds = new Commands(OnError.Continue);

        final VirtualRouterProvider vrProvider = _vrProviderDao.findById(router.getElementId());
        if (vrProvider == null) {
            throw new CloudRuntimeException("Cannot find related virtual router provider of router: " + router.getHostName());
        }
        final Provider provider = Network.Provider.getProvider(vrProvider.getType().toString());
        if (provider == null) {
            throw new CloudRuntimeException("Cannot find related provider of virtual router provider: " + vrProvider.getType().toString());
        }

        if (reprogramNetwork) {
            finalizeNetworkRulesForNetwork(cmds, router, provider, networkId);
        }

        finalizeMonitorService(cmds, getVirtualMachineProfile(router), router, provider, networkId, false, getRouterHealthChecksConfig(router));

        return _nwHelper.sendCommandsToRouter(router, cmds);
    }

    private VirtualMachineProfile getVirtualMachineProfile(DomainRouterVO router) {
        final ServiceOfferingVO offering = _serviceOfferingDao.findById(router.getId(), router.getServiceOfferingId());
        final VirtualMachineTemplate template = _entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, router.getTemplateId());
        final Account owner = _entityMgr.findById(Account.class, router.getAccountId());
        final VirtualMachineProfileImpl profile = new VirtualMachineProfileImpl(router, template, offering, owner, null);
        for (final NicProfile nic : _networkMgr.getNicProfiles(router)) {
            profile.addNic(nic);
        }
        return profile;
    }

    /**
     * @param router
     * @param add
     * @param privateNic
     * @return
     * @throws ResourceUnavailableException
     */
    protected boolean setupVpcPrivateNetwork(final VirtualRouter router, final boolean add, final NicProfile privateNic) throws ResourceUnavailableException {

        if (router.getState() == State.Running) {
            final PrivateIpVO ipVO = _privateIpDao.findByIpAndSourceNetworkId(privateNic.getNetworkId(), privateNic.getIPv4Address());
            final Network network = _networkDao.findById(privateNic.getNetworkId());
            final String netmask = NetUtils.getCidrNetmask(network.getCidr());
            final PrivateIpAddress ip = new PrivateIpAddress(ipVO, network.getBroadcastUri().toString(), network.getGateway(), netmask, privateNic.getMacAddress());

            final List<PrivateIpAddress> privateIps = new ArrayList<PrivateIpAddress>(1);
            privateIps.add(ip);
            final Commands cmds = new Commands(Command.OnError.Stop);
            _commandSetupHelper.createVpcAssociatePrivateIPCommands(router, privateIps, cmds, add);

            try {
                if (_nwHelper.sendCommandsToRouter(router, cmds)) {
                    logger.debug("Successfully applied ip association for ip " + ip + " in vpc network " + network);
                    return true;
                } else {
                    logger.warn("Failed to associate ip address " + ip + " in vpc network " + network);
                    return false;
                }
            } catch (final Exception ex) {
                logger.warn("Failed to send  " + (add ? "add " : "delete ") + " private network " + network + " commands to rotuer ");
                return false;
            }
        } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
            logger.debug("Router " + router.getInstanceName() + " is in " + router.getState() + ", so not sending setup private network command to the backend");
        } else {
            logger.warn("Unable to setup private gateway, virtual router " + router + " is not in the right state " + router.getState());

            throw new ResourceUnavailableException("Unable to setup Private gateway on the backend," + " virtual router " + router + " is not in the right state",
                    DataCenter.class, router.getDataCenterId());
        }
        return true;
    }

    @Override
    public boolean destroyPrivateGateway(final PrivateGateway gateway, final VirtualRouter router) throws ConcurrentOperationException, ResourceUnavailableException {
        boolean result = true;

        if (!_networkModel.isVmPartOfNetwork(router.getId(), gateway.getNetworkId())) {
            logger.debug("Router doesn't have nic for gateway " + gateway + " so no need to removed it");
            return result;
        }

        final Network privateNetwork = _networkModel.getNetwork(gateway.getNetworkId());
        final NicProfile nicProfile = _networkModel.getNicProfile(router, privateNetwork.getId(), null);

        logger.debug("Releasing private ip for gateway " + gateway + " from " + router);
        result = setupVpcPrivateNetwork(router, false, nicProfile);
        if (!result) {
            logger.warn("Failed to release private ip for gateway " + gateway + " on router " + router);
            return false;
        }

        // revoke network acl on the private gateway.
        if (!_networkACLMgr.revokeACLItemsForPrivateGw(gateway)) {
            logger.debug("Failed to delete network acl items on " + gateway + " from router " + router);
            return false;
        }

        logger.debug("Removing router " + router + " from private network " + privateNetwork + " as a part of delete private gateway");
        result = result && _itMgr.removeVmFromNetwork(router, privateNetwork, null);
        logger.debug("Private gateawy " + gateway + " is removed from router " + router);
        return result;
    }

    @Override
    protected void finalizeIpAssocForNetwork(final Commands cmds, final VirtualRouter domainRouterVO, final Provider provider, final Long guestNetworkId,
            final Map<String, String> vlanMacAddress) {

        if (domainRouterVO.getVpcId() == null) {
            super.finalizeIpAssocForNetwork(cmds, domainRouterVO, provider, guestNetworkId, vlanMacAddress);
            return;
        }

        if (domainRouterVO.getState() == State.Starting || domainRouterVO.getState() == State.Running) {
            final ArrayList<? extends PublicIpAddress> publicIps = getPublicIpsToApply(provider, guestNetworkId, IpAddress.State.Releasing);

            if (publicIps != null && !publicIps.isEmpty()) {
                logger.debug("Found " + publicIps.size() + " ip(s) to apply as a part of domR " + domainRouterVO + " start.");
                // Re-apply public ip addresses - should come before PF/LB/VPN
                _commandSetupHelper.createVpcAssociatePublicIPCommands(domainRouterVO, publicIps, cmds, vlanMacAddress);
            }
        }
    }

    @Override
    public boolean startSite2SiteVpn(final Site2SiteVpnConnection conn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() != State.Running) {
            logger.warn("Unable to apply site-to-site VPN configuration, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to apply site 2 site VPN configuration," + " virtual router is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        return applySite2SiteVpn(true, router, conn);
    }

    @Override
    public boolean startSite2SiteVpn(DomainRouterVO router) throws ResourceUnavailableException {
        boolean result = true;
        List<Site2SiteVpnConnectionVO> conns = _s2sVpnMgr.getConnectionsForRouter(router);
        for (Site2SiteVpnConnectionVO conn : conns) {
            result = result && startSite2SiteVpn(conn, router);
        }

        return result;
    }

    @Override
    public boolean stopSite2SiteVpn(final Site2SiteVpnConnection conn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() != State.Running) {
            logger.warn("Unable to apply site-to-site VPN configuration, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to apply site 2 site VPN configuration," + " virtual router is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        return applySite2SiteVpn(false, router, conn);
    }

    protected boolean applySite2SiteVpn(final boolean isCreate, final VirtualRouter router, final Site2SiteVpnConnection conn) throws ResourceUnavailableException {
        final Commands cmds = new Commands(Command.OnError.Continue);
        _commandSetupHelper.createSite2SiteVpnCfgCommands(conn, isCreate, router, cmds);
        return _nwHelper.sendCommandsToRouter(router, cmds);
    }

    protected Pair<Map<String, PublicIpAddress>, Map<String, PublicIpAddress>> getNicsToChangeOnRouter(final List<? extends PublicIpAddress> publicIps, final VirtualRouter router) {
        // 1) check which nics need to be plugged/unplugged and plug/unplug them

        final Map<String, PublicIpAddress> nicsToPlug = new HashMap<String, PublicIpAddress>();
        final Map<String, PublicIpAddress> nicsToUnplug = new HashMap<String, PublicIpAddress>();

        // find out nics to unplug
        for (final PublicIpAddress ip : publicIps) {
            final long publicNtwkId = ip.getNetworkId();

            // if ip is not associated to any network, and there are no firewall
            // rules, release it on the backend
            if (!_vpcMgr.isIpAllocatedToVpc(ip)) {
                ip.setState(IpAddress.State.Releasing);
            }

            if (ip.getState() == IpAddress.State.Releasing) {
                final Nic nic = _nicDao.findByIp4AddressAndNetworkIdAndInstanceId(publicNtwkId, router.getId(), ip.getAddress().addr());
                if (nic != null) {
                    nicsToUnplug.put(ip.getVlanTag(), ip);
                    logger.debug("Need to unplug the nic for ip=" + ip + "; vlan=" + ip.getVlanTag() + " in public network id =" + publicNtwkId);
                }
            }
        }

        // find out nics to plug
        for (final PublicIpAddress ip : publicIps) {
            final URI broadcastUri = BroadcastDomainType.Vlan.toUri(ip.getVlanTag());
            final long publicNtwkId = ip.getNetworkId();

            // if ip is not associated to any network, and there are no firewall
            // rules, release it on the backend
            if (!_vpcMgr.isIpAllocatedToVpc(ip)) {
                ip.setState(IpAddress.State.Releasing);
            }

            if (ip.getState() == IpAddress.State.Allocated || ip.getState() == IpAddress.State.Allocating) {
                // nic has to be plugged only when there are no nics for this
                // vlan tag exist on VR
                final Nic nic = _nicDao.findByNetworkIdInstanceIdAndBroadcastUri(publicNtwkId, router.getId(), broadcastUri.toString());

                if (nic == null && nicsToPlug.get(ip.getVlanTag()) == null) {
                    nicsToPlug.put(ip.getVlanTag(), ip);
                    logger.debug("Need to plug the nic for ip=" + ip + "; vlan=" + ip.getVlanTag() + " in public network id =" + publicNtwkId);
                } else {
                    final PublicIpAddress nicToUnplug = nicsToUnplug.get(ip.getVlanTag());
                    if (nicToUnplug != null) {
                        final NicVO nicVO = _nicDao.findByIp4AddressAndNetworkIdAndInstanceId(publicNtwkId, router.getId(), nicToUnplug.getAddress().addr());
                        nicVO.setIPv4Address(ip.getAddress().addr());
                        _nicDao.update(nicVO.getId(), nicVO);
                        logger.debug("Updated the nic " + nicVO + " with the new ip address " + ip.getAddress().addr());
                        nicsToUnplug.remove(ip.getVlanTag());
                    }
                }
            }
        }

        final Pair<Map<String, PublicIpAddress>, Map<String, PublicIpAddress>> nicsToChange = new Pair<Map<String, PublicIpAddress>, Map<String, PublicIpAddress>>(nicsToPlug,
                nicsToUnplug);
        return nicsToChange;
    }

    @Override
    public void finalizeStop(final VirtualMachineProfile profile, final Answer answer) {
        super.finalizeStop(profile, answer);
        // Mark VPN connections as Disconnected
        final DomainRouterVO router = _routerDao.findById(profile.getId());
        final Long vpcId = router.getVpcId();
        if (vpcId != null) {
            _s2sVpnMgr.markDisconnectVpnConnByVpc(vpcId);
        }
    }

    @Override
    public List<DomainRouterVO> getVpcRouters(final long vpcId) {
        return _routerDao.listByVpcId(vpcId);
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public boolean startRemoteAccessVpn(final RemoteAccessVpn vpn, final VirtualRouter router) throws ResourceUnavailableException {
        if (router.getState() != State.Running) {
            logger.warn("Unable to apply remote access VPN configuration, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to apply remote access VPN configuration," + " virtual router is not in the right state", DataCenter.class,
                    router.getDataCenterId());
        }

        final Commands cmds = new Commands(Command.OnError.Stop);
        _commandSetupHelper.createApplyVpnCommands(true, vpn, router, cmds);

        try {
            _agentMgr.send(router.getHostId(), cmds);
        } catch (final OperationTimedoutException e) {
            logger.debug("Failed to start remote access VPN: ", e);
            throw new AgentUnavailableException("Unable to send commands to virtual router ", router.getHostId(), e);
        }
        Answer answer = cmds.getAnswer("users");
        if (answer == null || !answer.getResult()) {
            String errorMessage = (answer == null) ? "null answer object" : answer.getDetails();
            DataCenter zone = _entityMgr.findById(DataCenter.class, router.getDataCenterId());
            Account account = _entityMgr.findById(Account.class, vpn.getAccountId());
            logger.error("Unable to start vpn: unable add users to vpn in zone {} for account {} on domR: {} due to {}", zone, account, router, errorMessage);
            throw new ResourceUnavailableException(String.format("Unable to start vpn: Unable to add users to vpn in zone %s for account %s on domR: %s due to %s", zone, account, router.getInstanceName(), errorMessage), DataCenter.class, router.getDataCenterId());
        }
        answer = cmds.getAnswer("startVpn");
        if (answer == null || !answer.getResult()) {
            String errorMessage = (answer == null) ? "null answer object" : answer.getDetails();
            DataCenter zone = _entityMgr.findById(DataCenter.class, router.getDataCenterId());
            Account account = _entityMgr.findById(Account.class, vpn.getAccountId());
            logger.error("Unable to start vpn in zone {} for account {} on domR: {} due to {}", zone, account, router, errorMessage);
            throw new ResourceUnavailableException(String.format("Unable to start vpn in zone %s for account %s on domR: %s due to %s", zone, account, router.getInstanceName(), errorMessage), DataCenter.class, router.getDataCenterId());
        }

        return true;
    }

    @Override
    public boolean stopRemoteAccessVpn(final RemoteAccessVpn vpn, final VirtualRouter router) throws ResourceUnavailableException {
        boolean result = true;

        if (router.getState() == State.Running) {
            final Commands cmds = new Commands(Command.OnError.Continue);
            _commandSetupHelper.createApplyVpnCommands(false, vpn, router, cmds);
            result = result && _nwHelper.sendCommandsToRouter(router, cmds);
        } else if (router.getState() == State.Stopped) {
            logger.debug("Router " + router + " is in Stopped state, not sending deleteRemoteAccessVpn command to it");
        } else {
            logger.warn("Failed to stop remote access VPN: domR " + router + " is not in right state " + router.getState());
            throw new ResourceUnavailableException("Failed to stop remote access VPN: domR is not in right state " + router.getState(), DataCenter.class,
                    router.getDataCenterId());
        }
        return true;
    }

    @Override
    public boolean postStateTransitionEvent(final StateMachine2.Transition<State, VirtualMachine.Event> transition, final VirtualMachine vo, final boolean status, final Object opaque) {
        // Without this VirtualNetworkApplianceManagerImpl.postStateTransitionEvent() gets called twice as part of listeners -
        // once from VpcVirtualNetworkApplianceManagerImpl and once from VirtualNetworkApplianceManagerImpl itself
        releaseHwOffloadVfsOnExpunge(transition, vo);
        return true;
    }

    /**
     * Phase B/4 leak fix: VfPoolManager.allocate() is called from
     * HypervisorGuruBase.allocateVfIfHwOffload() during VR start, but nothing
     * was releasing entries when the VR was destroyed/expunged. Pool entries
     * accumulated as state=ALLOCATED with stale allocated_to_nic_id values,
     * eventually exhausting the pool on a host so future VRs would fall back
     * to bridge/TAP.
     *
     * <p>Hook into the VR state transition: when going to {@code Expunging}
     * (the canonical end-of-life state for forced destroy), release every VF
     * that was allocated to any NIC of this VM. We don't release on
     * {@code Stopped} because the VR may be restarted with the same NIC IDs
     * and we want VF affinity preserved.
     */
    private void releaseHwOffloadVfsOnExpunge(final StateMachine2.Transition<State, VirtualMachine.Event> transition,
                                              final VirtualMachine vo) {
        if (_vfPoolManager == null || vo == null || vo.getType() != VirtualMachine.Type.DomainRouter) {
            return;
        }
        final State to = transition.getToState();
        if (to != State.Expunging && to != State.Destroyed) {
            return;
        }
        try {
            // NicVO.vf_pci_address column is never populated by VfPoolManager.allocate()
            // (the source of truth is sriov_vf_pool.allocated_to_nic_id), so we can't
            // gate on nic.getVfPciAddress() != null. Just call releaseByNicId for every
            // NIC of the VR — it's a no-op if no VF is allocated to that NIC.
            final java.util.List<NicVO> nics = _nicDao.listByVmId(vo.getId());
            for (final NicVO nic : nics) {
                if (nic == null) {
                    continue;
                }
                try {
                    if (_vfPoolManager.releaseByNicId(nic.getId())) {
                        logger.info("Released HW offload VF on VR {} expunge (nicId={})",
                                vo.getInstanceName(), nic.getId());
                    }
                } catch (Exception inner) {
                    logger.warn("Failed to release VF for nicId {} of VR {}: {}",
                            nic.getId(), vo.getInstanceName(), inner.getMessage());
                }
            }
            // Safety net: the loop above only covers NICs with removed IS NULL. If
            // any NIC of this VR was already soft-removed by NetworkOrchestrator
            // (cleanupNics) before our transition hook fired, listByVmId misses it
            // and its VF stays ALLOCATED. releaseByVmId joins sriov_vf_pool against
            // nics.instance_id without the removed filter, catching the leak.
            try {
                int swept = _vfPoolManager.releaseByVmId(vo.getId());
                if (swept > 0) {
                    logger.info("Released {} HW offload VF(s) on VR {} expunge via VM-id sweep", swept, vo.getInstanceName());
                }
            } catch (Exception vmSweepEx) {
                logger.warn("Failed VM-id VF sweep for VR {}: {}", vo.getInstanceName(), vmSweepEx.getMessage());
            }
            // Every VR expunge also runs a global orphan sweep so any VFs pinned to
            // long-removed NICs from earlier bugs/races get reclaimed. Piggy-backing
            // on the VR-destroy hook avoids adding a dedicated scheduled executor
            // while still providing periodic convergence in practice (any non-empty
            // DC has VR churn).
            try {
                int orphans = _vfPoolManager.sweepOrphans();
                if (orphans > 0) {
                    logger.info("Swept {} orphan VF(s) back to FREE during VR {} expunge", orphans, vo.getInstanceName());
                }
            } catch (Exception orphanEx) {
                logger.warn("Orphan sweep failed during VR {} expunge: {}", vo.getInstanceName(), orphanEx.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Failed to release HW offload VFs for VR {} on transition to {}: {}",
                    vo.getInstanceName(), to, e.getMessage());
        }
    }

    private Nic updateNicWithDeviceId(final long nicId, int deviceId) {
        NicVO nic = _nicDao.findById(nicId);
        nic.setDeviceId(deviceId);
        _nicDao.update(nic.getId(), nic);
        return nic;
    }

    /**
     * Phase B/4: returns true if the VR has any guest tier whose offering has
     * {@code hw_offload_enabled=1}. Used to decide whether the VR's public NIC
     * should also be promoted to a hostdev VF (so the full HW NAT pipeline can
     * span both guest-rep and public-rep on the e-switch).
     */
    private boolean vrHasAnyHwOffloadGuestTier(final DomainRouterVO vr) {
        try {
            final java.util.List<? extends Network> vpcNetworks = _vpcMgr.getVpcNetworks(vr.getVpcId());
            if (vpcNetworks == null) {
                return false;
            }
            for (final Network n : vpcNetworks) {
                if (n.getTrafficType() != TrafficType.Guest) continue;
                if (_networkModel.isPrivateGateway(n.getId())) continue;
                if (isHwOffloadNetwork(n.getId())) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.warn("vrHasAnyHwOffloadGuestTier: failed for VR {}", vr.getInstanceName(), e);
        }
        return false;
    }

    private boolean isHwOffloadNetwork(long networkId) {
        try {
            Network network = _networkDao.findById(networkId);
            if (network == null) {
                logger.debug("isHwOffloadNetwork: network {} not found", networkId);
                return false;
            }
            com.cloud.offerings.NetworkOfferingVO offering = _networkOfferingDao2.findById(network.getNetworkOfferingId());
            // Treat vDPA offerings as HW-offload from the VR pre-alloc gate's
            // perspective: both need a VF reserved on the destination host
            // before the VR boots so we can patch the domain XML with either
            // <interface type='hostdev'> or <interface type='vdpa'>.
            boolean result = offering != null && (offering.isHwOffloadEnabled() || offering.isVdpaEnabled());
            logger.info("isHwOffloadNetwork: network={} offeringId={} offeringName={} hwOffload={} vdpa={}",
                    networkId, network.getNetworkOfferingId(),
                    offering != null ? offering.getName() : "null",
                    offering != null && offering.isHwOffloadEnabled(),
                    offering != null && offering.isVdpaEnabled());
            return result;
        } catch (Exception e) {
            logger.warn("isHwOffloadNetwork: exception for network {}", networkId, e);
            return false;
        }
    }

    private NicTO enrichWithVfIfHwOffload(NicTO nicTo, long networkId, long hostId, long nicId) {
        try {
            Network network = _networkDao.findById(networkId);
            if (network == null) return nicTo;
            com.cloud.offerings.NetworkOfferingVO offering = _networkOfferingDao2.findById(network.getNetworkOfferingId());
            if (offering == null || _vfPoolManager == null) {
                return nicTo;
            }
            // vDPA branch wins when both flags are set on a single offering
            // (defensive: the API rejects that combo, but the DB allows it).
            if (offering.isVdpaEnabled()) {
                int maxVqs = HypervisorGuruBase.VmVdpaMaxVqs.value();
                SriovVfPoolVO vf = _vfPoolManager.allocateForVdpa(hostId, nicId, nicTo.getMac(), maxVqs);
                if (vf == null) {
                    logger.warn("VPC PlugNic: no FREE VF for vDPA on host {} (network {}); bridge/TAP fallback",
                            hostId, networkId);
                    return nicTo;
                }
                nicTo.setVfPciAddress(vf.getPciAddress());
                nicTo.setVfPfName(vf.getPfName());
                nicTo.setUseVdpa(Boolean.TRUE);
                nicTo.setVdpaMaxVqs(maxVqs);
                logger.info("VPC PlugNic: allocated vDPA VF {} (PCI {}) for NIC on network {} host {}",
                        vf.getUuid(), vf.getPciAddress(), networkId, hostId);
                return nicTo;
            }
            if (offering.isHwOffloadEnabled()) {
                SriovVfPoolVO vf = _vfPoolManager.allocate(hostId, nicId);
                nicTo.setVfPciAddress(vf.getPciAddress());
                nicTo.setVfPfName(vf.getPfName());
                nicTo.setUseHwOffload(Boolean.TRUE);
                logger.info("VPC PlugNic: allocated VF {} (PCI {}) for NIC on network {} host {}",
                        vf.getUuid(), vf.getPciAddress(), networkId, hostId);
            }
        } catch (Exception e) {
            logger.warn("VPC PlugNic: VF allocation failed for network {} host {} (bridge/TAP fallback)", networkId, hostId, e);
        }
        return nicTo;
    }
}
