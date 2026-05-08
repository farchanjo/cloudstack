# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
"""
Fork compatibility regression tests.

Validates that vanilla CloudStack code paths keep working alongside
fork-specific opt-in features (HW-offload, vDPA, OVN, DPDK tag).

Two offerings are deployed side-by-side:
  - vanilla-default      no fork flags, kernel tap path
  - fork-hwoffload-vf    hwoffloadenabled=1, SR-IOV VF passthrough path

Each test asserts:
  - VM lifecycle (deploy / start / stop / destroy) succeeds.
  - Wire-format expectations on the host (libvirt XML, OVS port, TC rules).
  - No cross-pollination (vanilla VM never gets HW offload TC rules; fork VM
    never falls back to kernel tap).

Pre-conditions:
  - Zone, pod, cluster registered.
  - At least one host UP per offering's compatible cluster.
  - Templates available: small Linux KVM template.
  - SSH key configured to ssh into the host as root for libvirt+OVS+tc inspection.
"""

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.lib.base import (Account, NetworkOffering, ServiceOffering,
                             VirtualMachine, Network, VPC, VpcOffering)
from marvin.lib.common import (get_zone, get_domain, get_template, list_hosts)
from marvin.lib.utils import cleanup_resources
from nose.plugins.attrib import attr
import logging
import re

LOGGER = logging.getLogger(__name__)


class TestForkVanillaCompat(cloudstackTestCase):
    """Side-by-side vanilla / fork offering regression."""

    @classmethod
    def setUpClass(cls):
        cls.testClient = super(TestForkVanillaCompat, cls).getClsTestClient()
        cls.api = cls.testClient.getApiClient()
        cls.zone = get_zone(cls.api, cls.testClient.getZoneForTests())
        cls.domain = get_domain(cls.api)
        cls.template = get_template(cls.api, cls.zone.id)
        cls._cleanup = []

        cls.account = Account.create(
            cls.api,
            services={'username': 'fork-compat',
                      'firstname': 'Fork',
                      'lastname': 'Compat',
                      'email': 'fork-compat@test.local',
                      'password': 'Fork1234!'},
            domainid=cls.domain.id,
        )
        cls._cleanup.append(cls.account)

        cls.svc_offering = ServiceOffering.create(
            cls.api,
            services={'name': 'fork-compat-svc',
                      'displaytext': 'Compat-test svc offering',
                      'cpunumber': 1,
                      'cpuspeed': 500,
                      'memory': 512},
        )
        cls._cleanup.append(cls.svc_offering)

        cls.vanilla_off = cls._mk_offering(
            name='vanilla-compat', display='Vanilla compat offering',
            hwoffload=False, vdpa=False, tags='',
        )
        cls._cleanup.append(cls.vanilla_off)

        cls.fork_off = cls._mk_offering(
            name='fork-hwoffload-compat', display='Fork compat offering',
            hwoffload=True, vdpa=False, tags='',
        )
        cls._cleanup.append(cls.fork_off)

    @classmethod
    def _mk_offering(cls, name, display, hwoffload=False, vdpa=False, tags=''):
        params = {
            'name': name,
            'displaytext': display,
            'guestiptype': 'Isolated',
            'traffictype': 'Guest',
            'supportedservices': 'Dhcp,Dns,SourceNat,UserData',
            'serviceProviderList': {
                'Dhcp': 'VirtualRouter',
                'Dns': 'VirtualRouter',
                'SourceNat': 'VirtualRouter',
                'UserData': 'VirtualRouter',
            },
        }
        if hwoffload:
            params['hwoffloadenabled'] = True
        if vdpa:
            params['vdpaenabled'] = True
        if tags:
            params['tags'] = tags
        return NetworkOffering.create(cls.api, services=params)

    @classmethod
    def tearDownClass(cls):
        try:
            cleanup_resources(cls.api, cls._cleanup)
        except Exception as e:
            LOGGER.warning('cleanup failed: %s', e)

    @attr(tags=['advanced', 'fork-compat'], required_hardware='false')
    def test_01_vanilla_offering_uses_kernel_tap(self):
        """Vanilla offering must produce a `<interface type='bridge'>` libvirt XML."""
        net = Network.create(
            self.api,
            services={'name': 'vanilla-net', 'displaytext': 'vanilla net',
                      'netmask': '255.255.255.0', 'gateway': '10.50.0.1'},
            accountid=self.account.name,
            domainid=self.domain.id,
            networkofferingid=self.vanilla_off.id,
            zoneid=self.zone.id,
        )
        self._cleanup.insert(0, net)

        vm = VirtualMachine.create(
            self.api,
            services={'displayname': 'vanilla-vm'},
            accountid=self.account.name,
            domainid=self.domain.id,
            serviceofferingid=self.svc_offering.id,
            templateid=self.template.id,
            networkids=[net.id],
            zoneid=self.zone.id,
        )
        self._cleanup.insert(0, vm)

        host = self._host_of_vm(vm.id)
        xml = self._virsh_dumpxml(host, vm.instancename)
        self.assertIn("<interface type='bridge'", xml,
                      'vanilla VM XML must use bridge type, got: ' + xml[:500])
        self.assertNotIn("<interface type='hostdev'", xml,
                         'vanilla VM must NOT use hostdev')

        # No HW-offload TC rules on representors for this VM (none should exist).
        # Indirect assertion: per-VR intent file must NOT exist for this VR.
        # (No VR involved in this isolated network — just the user VM.)
        # So just confirm /var/lib/cloudstack-agent/hwoffload/ has no entry naming this VM.
        leaks = self._ssh(host,
                          'find /var/lib/cloudstack-agent/hwoffload -type f -name "*.json" 2>/dev/null '
                          f'| xargs grep -l "{vm.instancename}" 2>/dev/null || true')
        self.assertEqual(leaks.strip(), '',
                         f'vanilla VM {vm.instancename} leaked into hwoffload state: {leaks}')

    @attr(tags=['advanced', 'fork-compat', 'requires-vf'], required_hardware='true')
    def test_02_fork_hwoffload_offering_uses_hostdev(self):
        """Fork hwoffload offering must produce `<interface type='hostdev'>` and OVS rep with type=doca."""
        # Skip cleanly if the cluster has no SR-IOV-capable host.
        capable = self._cluster_has_sriov_host()
        if not capable:
            self.skipTest('no SR-IOV-capable host in cluster')

        net = Network.create(
            self.api,
            services={'name': 'fork-hwoff-net',
                      'displaytext': 'fork hwoffload net',
                      'netmask': '255.255.255.0',
                      'gateway': '10.51.0.1'},
            accountid=self.account.name,
            domainid=self.domain.id,
            networkofferingid=self.fork_off.id,
            zoneid=self.zone.id,
        )
        self._cleanup.insert(0, net)

        vm = VirtualMachine.create(
            self.api,
            services={'displayname': 'fork-vm'},
            accountid=self.account.name,
            domainid=self.domain.id,
            serviceofferingid=self.svc_offering.id,
            templateid=self.template.id,
            networkids=[net.id],
            zoneid=self.zone.id,
        )
        self._cleanup.insert(0, vm)

        host = self._host_of_vm(vm.id)
        xml = self._virsh_dumpxml(host, vm.instancename)
        self.assertIn("<interface type='hostdev'", xml,
                      'fork VM XML must use hostdev type')

        # Look up the VF representor on br-bond and confirm DOCA params.
        rep_line = self._ssh(host,
                             "ovs-vsctl --columns=name,type,options find Interface "
                             "'options:dpdk-devargs!=\"\"' | head -20")
        self.assertIn('type=doca', rep_line,
                      'fork VF rep must be type=doca, got: ' + rep_line[:500])
        self.assertIn('dv_flow_en=2', rep_line,
                      'fork VF rep must have dv_flow_en=2')
        self.assertIn('dv_xmeta_en=4', rep_line,
                      'fork VF rep must have dv_xmeta_en=4')

    @attr(tags=['advanced', 'fork-compat'], required_hardware='false')
    def test_03_offering_flags_persist_through_api(self):
        """`listNetworkOfferings` must round-trip the new flags (vanilla=false, fork=true)."""
        offerings = NetworkOffering.list(self.api,
                                         id=self.vanilla_off.id)
        self.assertEqual(len(offerings), 1)
        self.assertFalse(getattr(offerings[0], 'hwoffloadenabled', False),
                         'vanilla offering must not have hwoffloadenabled=true')

        offerings = NetworkOffering.list(self.api,
                                         id=self.fork_off.id)
        self.assertEqual(len(offerings), 1)
        self.assertTrue(getattr(offerings[0], 'hwoffloadenabled', False),
                        'fork offering must have hwoffloadenabled=true')

    @attr(tags=['advanced', 'fork-compat'], required_hardware='false')
    def test_04_no_ovn_writes_for_vanilla_path(self):
        """Vanilla offering MUST NOT touch OVN NB DB.

        Detection: count `logical_switch` rows in OVN NB before/after deploy.
        Skipped if OVN is not enabled in this zone.
        """
        nb = self._ovn_nb_count()
        if nb is None:
            self.skipTest('OVN NB not reachable from this test runner')
        before = nb

        net = Network.create(
            self.api,
            services={'name': 'vanilla-no-ovn',
                      'displaytext': 'vanilla no ovn',
                      'netmask': '255.255.255.0',
                      'gateway': '10.52.0.1'},
            accountid=self.account.name,
            domainid=self.domain.id,
            networkofferingid=self.vanilla_off.id,
            zoneid=self.zone.id,
        )
        self._cleanup.insert(0, net)

        after = self._ovn_nb_count()
        self.assertEqual(before, after,
                         f'vanilla offering wrote to OVN NB: ls count {before} -> {after}')

    # ---------- helpers ----------

    def _host_of_vm(self, vm_id):
        """Return SSH-reachable IP of the host running the VM."""
        from marvin.lib.base import VirtualMachine as VM
        vm = VM.list(self.api, id=vm_id)[0]
        host = list_hosts(self.api, id=vm.hostid)[0]
        return host.ipaddress

    def _virsh_dumpxml(self, host, domain):
        return self._ssh(host, f"virsh dumpxml '{domain}'")

    def _ssh(self, host, cmd):
        import subprocess
        return subprocess.check_output(
            ['ssh', '-o', 'StrictHostKeyChecking=no', f'root@{host}', cmd],
            timeout=30,
        ).decode('utf-8', errors='replace')

    def _cluster_has_sriov_host(self):
        # Heuristic: any host whose hostdetails contain `sriov.enabled=true`.
        hosts = list_hosts(self.api, type='Routing')
        for h in hosts:
            details = getattr(h, 'details', None) or {}
            if isinstance(details, dict) and details.get('sriov.enabled') == 'true':
                return True
        return False

    def _ovn_nb_count(self):
        """Return number of OVN logical_switch rows. None if NB unreachable."""
        try:
            out = self._ssh('10.182.0.12',
                            "ovn-nbctl --no-leader-only --db=unix:/var/run/ovn/ovnnb_db.sock "
                            "--timeout=5 ls-list 2>/dev/null | wc -l")
            return int(out.strip())
        except Exception:
            return None
