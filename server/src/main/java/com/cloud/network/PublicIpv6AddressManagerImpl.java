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
package com.cloud.network;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import javax.persistence.EntityExistsException;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.UserPublicIpv6Address.State;
import com.cloud.network.dao.UserPublicIpv6AddressDao;
import com.cloud.user.Account;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.googlecode.ipv6.IPv6Address;
import com.googlecode.ipv6.IPv6Network;

/**
 * Public IPv6 VIP/FIP inventory manager. Lazy-invents Free-pool rows
 * ({@code ::1000}–{@code ::ffff}); unique (zone, address) handles races.
 * Prefix from configuration name {@link #PUBLIC_IPV6_PREFIX_CONFIG} via
 * {@link ConfigurationDao} — no dependency on OVN plugin classes.
 */
public class PublicIpv6AddressManagerImpl extends ManagerBase implements PublicIpv6AddressManager {

    @Inject
    private UserPublicIpv6AddressDao userPublicIpv6AddressDao;
    @Inject
    private ConfigurationDao configDao;
    @Inject
    private AccountDao accountDao;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        return super.configure(name, params);
    }

    @Override
    public String getPublicIpv6Prefix() {
        String value = configDao.getValue(PUBLIC_IPV6_PREFIX_CONFIG);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private String requirePublicIpv6Prefix() {
        String prefix = getPublicIpv6Prefix();
        if (StringUtils.isBlank(prefix)) {
            throw new CloudRuntimeException(
                    "Public IPv6 prefix is not configured (" + PUBLIC_IPV6_PREFIX_CONFIG + ")");
        }
        try {
            NetUtils.standardizeIp6Cidr(prefix);
        } catch (IllegalArgumentException ex) {
            throw new CloudRuntimeException("Invalid public IPv6 prefix in " + PUBLIC_IPV6_PREFIX_CONFIG + ": " + prefix,
                    ex);
        }
        return prefix;
    }

    /**
     * Host id is the low 64 bits of the address relative to the /64 (or shorter)
     * network prefix. For Free-pool documentation ({@code ::1000}–{@code ::ffff})
     * this is the last hextet when the interface-id high bits are zero.
     */
    long hostIdOf(String canonicalAddress, String prefixCidr) {
        IPv6Network network = IPv6Network.fromString(NetUtils.standardizeIp6Cidr(prefixCidr));
        IPv6Address addr = IPv6Address.fromString(NetUtils.standardizeIp6Address(canonicalAddress));
        if (!network.contains(addr)) {
            throw new InvalidParameterValueException(
                    "Address " + canonicalAddress + " is not inside prefix " + prefixCidr);
        }
        IPv6Address first = network.getFirst();
        // low 64 bits of (addr - network base); host ids we care about fit in int
        long hostLow = addr.getLowBits() - first.getLowBits();
        return hostLow;
    }

    String addressForHostId(String prefixCidr, long hostId) {
        IPv6Network network = IPv6Network.fromString(NetUtils.standardizeIp6Cidr(prefixCidr));
        IPv6Address first = network.getFirst();
        IPv6Address addr = IPv6Address.fromLongs(first.getHighBits(), first.getLowBits() + hostId);
        return NetUtils.standardizeIp6Address(addr.toString());
    }

    boolean isTransportHostId(long hostId) {
        return hostId >= 0 && hostId <= TRANSPORT_HOST_MAX;
    }

    boolean isFreePoolHostId(long hostId) {
        return hostId >= FREE_POOL_HOST_MIN && hostId <= FREE_POOL_HOST_MAX;
    }

    @Override
    public UserPublicIpv6AddressVO allocate(long dataCenterId, Account owner, Long networkId, Long vpcId,
            boolean isSystem, Boolean display)
            throws InsufficientAddressCapacityException, ConcurrentOperationException {
        return allocate(dataCenterId, owner, null, networkId, vpcId, isSystem, display);
    }

    @Override
    @DB
    public UserPublicIpv6AddressVO allocate(long dataCenterId, Account owner, String requestedAddress,
            Long networkId, Long vpcId, boolean isSystem, Boolean display)
            throws InsufficientAddressCapacityException, ConcurrentOperationException, InvalidParameterValueException {

        if (owner == null) {
            throw new InvalidParameterValueException("Account owner is required to allocate a public IPv6 address");
        }
        final String prefix = requirePublicIpv6Prefix();
        final String requestedCanonical = requestedAddress == null ? null
                : NetUtils.standardizeIp6Address(requestedAddress);

        if (requestedCanonical != null) {
            long hostId = hostIdOf(requestedCanonical, prefix);
            if (!isFreePoolHostId(hostId)) {
                if (isTransportHostId(hostId) || hostId < FREE_POOL_HOST_MIN) {
                    throw new InvalidParameterValueException(
                            "Address " + requestedCanonical
                                    + " is outside the Free pool (host ids 0x"
                                    + Integer.toHexString(FREE_POOL_HOST_MIN) + "–0x"
                                    + Integer.toHexString(FREE_POOL_HOST_MAX)
                                    + "); transport band 0x0–0x"
                                    + Integer.toHexString(TRANSPORT_HOST_MAX)
                                    + " is reserved; use importAllocated for grandfather VIPs");
                }
                throw new InvalidParameterValueException(
                        "Address " + requestedCanonical + " is outside the Free pool host-id range (0x"
                                + Integer.toHexString(FREE_POOL_HOST_MIN) + "–0x"
                                + Integer.toHexString(FREE_POOL_HOST_MAX) + ")");
            }
        }

        Account locked = null;
        try {
            locked = accountDao.acquireInLockTable(owner.getId());
            if (locked == null) {
                throw new ConcurrentOperationException("Unable to acquire account lock for account " + owner.getId());
            }

            return Transaction.execute(
                    (TransactionCallbackWithException<UserPublicIpv6AddressVO, InsufficientAddressCapacityException>) status ->
                            allocateInTransaction(dataCenterId, owner, requestedCanonical, networkId, vpcId, isSystem,
                                    display, prefix));
        } finally {
            if (locked != null) {
                accountDao.releaseFromLockTable(owner.getId());
            }
        }
    }

    private UserPublicIpv6AddressVO allocateInTransaction(long dataCenterId, Account owner, String requestedCanonical,
            Long networkId, Long vpcId, boolean isSystem, Boolean display, String prefix)
            throws InsufficientAddressCapacityException {

        if (requestedCanonical != null) {
            return claimOrInvent(dataCenterId, owner, requestedCanonical, networkId, vpcId, isSystem, display);
        }

        // Prefer reclaiming Free rows first
        List<UserPublicIpv6AddressVO> freeRows = userPublicIpv6AddressDao.listByZoneAndState(dataCenterId, State.Free);
        for (UserPublicIpv6AddressVO free : freeRows) {
            long hostId;
            try {
                hostId = hostIdOf(free.getAddress(), prefix);
            } catch (InvalidParameterValueException ex) {
                logger.warn("Skipping Free row id={} address={} not in current prefix: {}", free.getId(),
                        free.getAddress(), ex.getMessage());
                continue;
            }
            if (!isFreePoolHostId(hostId)) {
                continue;
            }
            UserPublicIpv6AddressVO locked = userPublicIpv6AddressDao.lockRow(free.getId(), true);
            if (locked == null || locked.getState() != State.Free) {
                continue;
            }
            return markAllocated(locked, owner, networkId, vpcId, isSystem, display);
        }

        // Lazy invent: walk Free-pool host ids and skip those already present
        Set<String> existing = existingCanonicalAddresses(dataCenterId);
        for (int hostId = FREE_POOL_HOST_MIN; hostId <= FREE_POOL_HOST_MAX; hostId++) {
            String candidate = addressForHostId(prefix, hostId);
            if (existing.contains(candidate)) {
                continue;
            }
            try {
                return inventAllocated(dataCenterId, owner, candidate, networkId, vpcId, isSystem, display);
            } catch (EntityExistsException race) {
                // Unique-key race from GenericDaoBase — another node invented the same address; try next
                logger.debug("Race inventing public IPv6 {} (EntityExistsException): {}", candidate, race.getMessage());
                existing.add(candidate);
            } catch (CloudRuntimeException race) {
                // Unique-key race wrapped as CloudRuntimeException; try next
                logger.debug("Race inventing public IPv6 {}: {}", candidate, race.getMessage());
                existing.add(candidate);
            }
        }

        throw new InsufficientAddressCapacityException(
                "No free public IPv6 addresses available in Free pool for zone " + dataCenterId,
                com.cloud.dc.DataCenter.class, dataCenterId);
    }

    private Set<String> existingCanonicalAddresses(long dataCenterId) {
        Set<String> existing = new HashSet<>();
        for (UserPublicIpv6AddressVO row : userPublicIpv6AddressDao.listByZone(dataCenterId)) {
            if (row.getAddress() != null) {
                existing.add(NetUtils.standardizeIp6Address(row.getAddress()));
            }
        }
        return existing;
    }

    private UserPublicIpv6AddressVO claimOrInvent(long dataCenterId, Account owner, String canonical, Long networkId,
            Long vpcId, boolean isSystem, Boolean display) throws InsufficientAddressCapacityException {
        UserPublicIpv6AddressVO existing = userPublicIpv6AddressDao.findByZoneAndAddress(dataCenterId, canonical);
        if (existing == null) {
            return inventAllocated(dataCenterId, owner, canonical, networkId, vpcId, isSystem, display);
        }
        UserPublicIpv6AddressVO locked = userPublicIpv6AddressDao.lockRow(existing.getId(), true);
        if (locked == null || locked.getState() != State.Free) {
            throw new InsufficientAddressCapacityException(
                    "Requested public IPv6 " + canonical + " is not available",
                    com.cloud.dc.DataCenter.class, dataCenterId);
        }
        return markAllocated(locked, owner, networkId, vpcId, isSystem, display);
    }

    private UserPublicIpv6AddressVO inventAllocated(long dataCenterId, Account owner, String canonical, Long networkId,
            Long vpcId, boolean isSystem, Boolean display) {
        UserPublicIpv6AddressVO vo = new UserPublicIpv6AddressVO(canonical, dataCenterId);
        assignOwner(vo, owner, networkId, vpcId, isSystem, display);
        vo.setState(State.Allocated);
        vo.setAllocatedTime(new Date());
        userPublicIpv6AddressDao.persist(vo);
        // Unique-key violation surfaces as runtime from the DB layer (race with another node)
        logger.info("Invented public IPv6 {} (id={}) for account {} in zone {}", canonical, vo.getId(), owner.getId(),
                dataCenterId);
        return vo;
    }

    private UserPublicIpv6AddressVO markAllocated(UserPublicIpv6AddressVO vo, Account owner, Long networkId, Long vpcId,
            boolean isSystem, Boolean display) {
        assignOwner(vo, owner, networkId, vpcId, isSystem, display);
        vo.setState(State.Allocated);
        vo.setAllocatedTime(new Date());
        userPublicIpv6AddressDao.update(vo.getId(), vo);
        logger.info("Allocated public IPv6 {} (id={}) to account {}", vo.getAddress(), vo.getId(), owner.getId());
        return vo;
    }

    private void assignOwner(UserPublicIpv6AddressVO vo, Account owner, Long networkId, Long vpcId, boolean isSystem,
            Boolean display) {
        vo.setAccountId(owner.getId());
        vo.setDomainId(owner.getDomainId());
        vo.setNetworkId(networkId);
        vo.setVpcId(vpcId);
        vo.setSystem(isSystem);
        if (display != null) {
            vo.setDisplay(display);
        }
    }

    @Override
    @DB
    public UserPublicIpv6AddressVO associate(long id, Long networkId, Long vpcId) throws InvalidParameterValueException {
        UserPublicIpv6AddressVO vo = userPublicIpv6AddressDao.findById(id);
        if (vo == null) {
            throw new InvalidParameterValueException("Public IPv6 address id=" + id + " not found");
        }
        if (vo.getState() != State.Allocated && vo.getState() != State.Allocating) {
            throw new InvalidParameterValueException(
                    "Public IPv6 address " + vo.getAddress() + " is not allocated (state=" + vo.getState() + ")");
        }
        if (networkId != null) {
            vo.setNetworkId(networkId);
        }
        if (vpcId != null) {
            vo.setVpcId(vpcId);
        }
        userPublicIpv6AddressDao.update(vo.getId(), vo);
        return vo;
    }

    @Override
    @DB
    public boolean release(long id) throws ConcurrentOperationException, ResourceUnavailableException {
        UserPublicIpv6AddressVO vo = userPublicIpv6AddressDao.lockRow(id, true);
        if (vo == null) {
            return false;
        }
        if (vo.getState() == State.Free) {
            return true;
        }
        Long accountId = vo.getAccountIdObject();
        Account locked = null;
        try {
            if (accountId != null && accountId > 0) {
                locked = accountDao.acquireInLockTable(accountId);
                if (locked == null) {
                    throw new ConcurrentOperationException("Unable to acquire account lock for account " + accountId);
                }
            }
            vo.setState(State.Free);
            vo.setAccountId(null);
            vo.setDomainId(null);
            vo.setNetworkId(null);
            vo.setVpcId(null);
            vo.setAllocatedTime(null);
            vo.setSystem(false);
            userPublicIpv6AddressDao.update(vo.getId(), vo);
            logger.info("Released public IPv6 {} (id={}) back to Free", vo.getAddress(), vo.getId());
            return true;
        } finally {
            if (locked != null && accountId != null) {
                accountDao.releaseFromLockTable(accountId);
            }
        }
    }

    @Override
    @DB
    public UserPublicIpv6AddressVO importAllocated(long dataCenterId, Account owner, String address, Long networkId,
            Long vpcId, boolean isSystem, Boolean display)
            throws InvalidParameterValueException, ConcurrentOperationException {

        if (owner == null) {
            throw new InvalidParameterValueException("Account owner is required to import a public IPv6 address");
        }
        if (StringUtils.isBlank(address)) {
            throw new InvalidParameterValueException("Address is required for importAllocated");
        }
        final String prefix = requirePublicIpv6Prefix();
        final String canonical = NetUtils.standardizeIp6Address(address);
        // Must be inside the configured prefix; transport-band grandfather is allowed
        hostIdOf(canonical, prefix);

        Account locked = null;
        try {
            locked = accountDao.acquireInLockTable(owner.getId());
            if (locked == null) {
                throw new ConcurrentOperationException("Unable to acquire account lock for account " + owner.getId());
            }

            return Transaction.execute((TransactionCallbackWithException<UserPublicIpv6AddressVO, InvalidParameterValueException>) status -> {
                UserPublicIpv6AddressVO existing = userPublicIpv6AddressDao.findByZoneAndAddress(dataCenterId, canonical);
                if (existing != null) {
                    if (existing.getState() == State.Allocated
                            && existing.getAccountIdObject() != null
                            && existing.getAccountIdObject().equals(owner.getId())) {
                        return existing;
                    }
                    throw new InvalidParameterValueException(
                            "Public IPv6 " + canonical + " already exists in zone " + dataCenterId + " (state="
                                    + existing.getState() + ")");
                }
                UserPublicIpv6AddressVO vo = new UserPublicIpv6AddressVO(canonical, dataCenterId);
                assignOwner(vo, owner, networkId, vpcId, isSystem, display);
                vo.setState(State.Allocated);
                vo.setAllocatedTime(new Date());
                userPublicIpv6AddressDao.persist(vo);
                logger.info("Imported grandfather public IPv6 {} (id={}) for account {} in zone {}", canonical,
                        vo.getId(), owner.getId(), dataCenterId);
                return vo;
            });
        } finally {
            if (locked != null) {
                accountDao.releaseFromLockTable(owner.getId());
            }
        }
    }

    @Override
    public UserPublicIpv6AddressVO findById(long id) {
        return userPublicIpv6AddressDao.findById(id);
    }

    @Override
    public UserPublicIpv6AddressVO findByZoneAndAddress(long dataCenterId, String address) {
        if (StringUtils.isBlank(address)) {
            return null;
        }
        return userPublicIpv6AddressDao.findByZoneAndAddress(dataCenterId, NetUtils.standardizeIp6Address(address));
    }

    @Override
    public List<? extends UserPublicIpv6Address> listByAccount(long accountId) {
        return userPublicIpv6AddressDao.listByAccount(accountId);
    }

    @Override
    public List<? extends UserPublicIpv6Address> listByZone(long dataCenterId) {
        return userPublicIpv6AddressDao.listByZone(dataCenterId);
    }

    @Override
    public List<? extends UserPublicIpv6Address> listByAccountAndZone(long accountId, long dataCenterId) {
        return userPublicIpv6AddressDao.listByAccountAndZone(accountId, dataCenterId);
    }

    @Override
    public List<? extends UserPublicIpv6Address> listByNetwork(long networkId) {
        return userPublicIpv6AddressDao.listByNetwork(networkId);
    }

    @Override
    public List<? extends UserPublicIpv6Address> listByVpc(long vpcId) {
        return userPublicIpv6AddressDao.listByVpc(vpcId);
    }

    // Exposed for unit tests / diagnostics; not part of public manager contract
    static final String EVENT_ASSIGN = EventTypes.EVENT_PUBLIC_IPV6_ASSIGN;
    static final String EVENT_RELEASE = EventTypes.EVENT_PUBLIC_IPV6_RELEASE;
}
