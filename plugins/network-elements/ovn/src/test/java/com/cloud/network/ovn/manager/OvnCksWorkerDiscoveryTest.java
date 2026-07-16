package com.cloud.network.ovn.manager;

import static org.junit.Assert.assertThrows;

import java.sql.SQLException;

import org.junit.Test;

import com.cloud.network.ovn.client.OvnException;

public class OvnCksWorkerDiscoveryTest {
    @Test
    public void sqlFailureIsPropagatedAsOvnException() {
        final OvnCksWorkerDiscovery discovery = new OvnCksWorkerDiscovery((cluster, network) -> {
            throw new SQLException("connection lost");
        });
        assertThrows(OvnException.class, () -> discovery.listWorkerGuestIps("cluster-1", 42L));
    }
}
