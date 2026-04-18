# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""
HW Offload intent client for VRs running on a SR-IOV-enabled host.

When the VR boots with VFs in passthrough, this module:
  1. Detects whether eth0/eth1 (guest/public) are mlx5 VFs (driver=mlx5_core).
  2. If so, marks intent_mode=true on the config databag.
  3. Provides a serializer that converts in-memory NAT/ACL/LB rules into a
     JSON IntentSpec matching HwOffloadIntentApi.IntentSpec on the host.
  4. POSTs the IntentSpec to the host's API at http://<gateway-cloud0>:9999/v1/hwoffload/intent
     with HMAC-SHA256 signature.

When intent_mode is false (no VF), CsAddress/CsNetfilter/CsLbHwOffload fall
back to traditional iptables/nftables programming.

This file is the BRAIN of Phase C — keeps the VR thin: it only describes
what it WANTS, and the host agent does the HW programming.
"""

import hashlib
import hmac
import json
import logging
import os
import subprocess
import time
import urllib.error
import urllib.request


HWOFFLOAD_API_PORT = 9999
HWOFFLOAD_API_PATH = "/v1/hwoffload/intent"
DEFAULT_TIMEOUT_SEC = 10
SECRET_PATH = "/var/cache/cloud/hwoffload-secret"  # provisioned by libvirt/userdata
VR_ID_PATH = "/var/cache/cloud/vr-id"


class CsHwOffloadIntent(object):
    """
    Client + intent builder. Holds incremental state for a single configure.py run:
    NAT rules, ACL rules, LB rules are appended via add_* methods, then submit()
    serializes everything and POSTs to the host API.
    """

    def __init__(self, config):
        self.config = config
        self.nat_rules = []
        self.acl_rules = []
        self.lb_rules = []
        self.guest_vf_pci = None
        self.public_vf_pci = None
        self.ct_zone = None
        self._enabled = self._detect()

    # ------------------------------------------------------------------ detection
    def is_enabled(self):
        return self._enabled

    def _detect(self):
        """
        Detect whether eth0 (guest) and eth1 (public) are mlx5 VFs in passthrough.
        Sets guest_vf_pci / public_vf_pci as a side-effect.
        """
        try:
            self.guest_vf_pci = self._vf_pci_for("eth0")
            self.public_vf_pci = self._vf_pci_for("eth1")
        except Exception as e:
            logging.warning("CsHwOffloadIntent detect failed: %s", e)
            return False
        # Both interfaces must be VFs for HW offload to make sense for a NAT VR.
        if self.guest_vf_pci and self.public_vf_pci:
            self.ct_zone = self._derive_ct_zone()
            return True
        return False

    @staticmethod
    def _vf_pci_for(iface):
        """Return PCI address (dddd:bb:ss.f) if iface is bound to mlx5_core, else None."""
        sys_dev = os.path.realpath("/sys/class/net/%s/device" % iface)
        if not sys_dev or not os.path.isdir(sys_dev):
            return None
        driver = ""
        drv_link = os.path.realpath(os.path.join(sys_dev, "driver"))
        if drv_link:
            driver = os.path.basename(drv_link)
        if driver != "mlx5_core":
            return None
        return os.path.basename(sys_dev)

    def _derive_ct_zone(self):
        """Derive a stable ct zone id from the VR's UUID (first 4 bytes of sha1 mod 60000+1024)."""
        vr_id = self._vr_id() or "default"
        h = hashlib.sha1(vr_id.encode("utf-8")).digest()
        return 1024 + int.from_bytes(h[:2], "big") % 60000

    @staticmethod
    def _vr_id():
        if os.path.isfile(VR_ID_PATH):
            try:
                with open(VR_ID_PATH) as f:
                    return f.read().strip()
            except IOError:
                pass
        # Fallback: derive from hostname (e.g. r-274-VM)
        try:
            return subprocess.check_output(["hostname"], universal_newlines=True).strip()
        except Exception:
            return None

    # ------------------------------------------------------------------ rule add API
    def add_nat(self, direction, match_addr, translate_addr, ip_proto="tcp",
                match_port=0, prio=50):
        """
        direction: 'SNAT' | 'DNAT'
        match_addr: src_ip (SNAT) or dst_ip (DNAT). May be None to match any.
        translate_addr: target IP for NAT
        match_port: dst_port match (0 = any)
        prio: chain-1 priority (lower = matched first)
        """
        self.nat_rules.append({
            "dir": direction,
            "matchAddr": match_addr,
            "matchPort": int(match_port) if match_port else 0,
            "translateAddr": translate_addr,
            "ipProto": ip_proto,
            "prio": int(prio),
        })

    def add_acl(self, action, src_ip=None, dst_ip=None, port=0, ip_proto="tcp",
                stateful=True, prio=80):
        """
        action: 'DROP' | 'ACCEPT'
        """
        self.acl_rules.append({
            "matchSrcIp": src_ip,
            "matchDstIp": dst_ip,
            "matchPort": int(port) if port else 0,
            "ipProto": ip_proto,
            "action": action,
            "stateful": bool(stateful),
            "prio": int(prio),
        })

    def add_lb(self, vip, port, backends, method="hash", prio=40):
        """
        vip + port: virtual IP and L4 port to balance
        backends: list of backend IPs
        method: 'hash' (5-tuple) or 'round_robin'
        """
        self.lb_rules.append({
            "vip": vip,
            "port": int(port),
            "backends": list(backends),
            "method": method,
            "prio": int(prio),
        })

    # ------------------------------------------------------------------ databag → intent
    def populate_from_databag(self, config):
        """
        Walk the standard CloudStack databags (forwarding_rules, staticnat_rules,
        firewall_rules, network_acl, load_balancer) and translate each into the
        equivalent NAT/ACL/LB intent entry. Best-effort: rules CS doesn't yet
        translate cleanly are left in iptables fallback only.
        """
        try:
            self._populate_static_nat(config)
            self._populate_port_forwarding(config)
            self._populate_firewall(config)
            self._populate_network_acl(config)
            self._populate_load_balancer(config)
        except Exception as e:
            logging.error("CsHwOffloadIntent populate_from_databag failed: %s", e)

    def _populate_static_nat(self, config):
        # staticnat_rules: list of { protocol, public_ip, internal_ip, ... }
        bag = self._read_bag(config, "staticnat_rules")
        for rule in self._iter_rules(bag):
            pub = rule.get("public_ip")
            internal = rule.get("internal_ip") or rule.get("internal")
            if not pub or not internal:
                continue
            # Static NAT is bidirectional: SNAT (internal→external) + DNAT (external→internal)
            self.add_nat("SNAT", match_addr=internal, translate_addr=pub,
                         ip_proto=rule.get("protocol", "tcp"), prio=20)
            self.add_nat("DNAT", match_addr=pub, translate_addr=internal,
                         ip_proto=rule.get("protocol", "tcp"), prio=20)

    def _populate_port_forwarding(self, config):
        # forwarding_rules: { protocol, public_ip, public_port, internal_ip, internal_port }
        bag = self._read_bag(config, "forwarding_rules")
        for rule in self._iter_rules(bag):
            pub = rule.get("public_ip")
            internal = rule.get("internal_ip")
            pub_port = rule.get("public_port")
            if not pub or not internal or not pub_port:
                continue
            self.add_nat("DNAT",
                         match_addr=pub, match_port=int(pub_port),
                         translate_addr=internal,
                         ip_proto=rule.get("protocol", "tcp"),
                         prio=30)

    def _populate_firewall(self, config):
        # firewall_rules: { protocol, src_cidr, dst_port, allowed }
        bag = self._read_bag(config, "firewall_rules")
        for rule in self._iter_rules(bag):
            src = rule.get("source_cidr_list") or rule.get("src_cidr")
            port = rule.get("dst_port") or rule.get("public_port") or 0
            action = "ACCEPT" if rule.get("allowed", True) else "DROP"
            self.add_acl(action,
                         src_ip=src,
                         port=int(port) if port else 0,
                         ip_proto=rule.get("protocol", "tcp"),
                         stateful=True, prio=70)

    def _populate_network_acl(self, config):
        # network_acl: { protocol, source_cidr, traffictype, action ('Allow'/'Deny'), start_port, end_port }
        bag = self._read_bag(config, "network_acl")
        for rule in self._iter_rules(bag):
            src = rule.get("source_cidr") or rule.get("source_cidr_list")
            port = rule.get("start_port") or 0
            action_str = (rule.get("action") or "Allow").lower()
            action = "ACCEPT" if action_str == "allow" else "DROP"
            self.add_acl(action,
                         src_ip=src,
                         port=int(port) if port else 0,
                         ip_proto=rule.get("protocol", "tcp"),
                         stateful=True, prio=60)

    def _populate_load_balancer(self, config):
        # load_balancer: { sourceIp, sourcePort, destPort, destinationServers, algorithm }
        bag = self._read_bag(config, "load_balancer")
        for rule in self._iter_rules(bag):
            vip = rule.get("sourceIp") or rule.get("public_ip")
            port = rule.get("sourcePort") or rule.get("public_port")
            backends = []
            for dest in rule.get("destinationServers", []) or rule.get("backends", []):
                if isinstance(dest, dict):
                    bip = dest.get("ip") or dest.get("address")
                else:
                    bip = dest
                if bip:
                    backends.append(bip)
            if not vip or not port or not backends:
                continue
            method = (rule.get("algorithm") or "hash").lower()
            method = "round_robin" if "robin" in method else "hash"
            self.add_lb(vip, int(port), backends, method=method, prio=40)

    @staticmethod
    def _read_bag(config, name):
        """
        Robust databag accessor. Supports both the new CsConfig accessor pattern
        and falls back to direct file read if accessor is not exposed.
        """
        getter = getattr(config, "get_dbag", None) or getattr(config, "dbag", None)
        if callable(getter):
            try:
                return getter(name)
            except Exception:
                pass
        # Fallback: read from /etc/cloudstack/<name>.json
        path = "/etc/cloudstack/%s.json" % name
        if os.path.isfile(path):
            try:
                with open(path) as f:
                    return json.load(f)
            except Exception:
                return {}
        return {}

    @staticmethod
    def _iter_rules(bag):
        """A databag is typically dict-of-rules or {'rules': [...]}; flatten it."""
        if not bag:
            return
        if isinstance(bag, list):
            for item in bag:
                if isinstance(item, dict):
                    yield item
            return
        if isinstance(bag, dict):
            for k, v in bag.items():
                if isinstance(v, dict):
                    yield v
                elif isinstance(v, list):
                    for item in v:
                        if isinstance(item, dict):
                            yield item

    # ------------------------------------------------------------------ submit
    def submit(self):
        """
        Build IntentSpec, sign with HMAC-SHA256, POST to host API.
        Returns True on 2xx response, False otherwise. Falls back silently if
        the API is unreachable so the caller can keep iptables fallback programmed.
        """
        if not self._enabled:
            return False
        spec = {
            "vrId": self._vr_id(),
            "version": int(time.time()),
            "guestVfPci": self.guest_vf_pci,
            "publicVfPci": self.public_vf_pci,
            "ctZone": self.ct_zone,
            "natRules": self.nat_rules,
            "aclRules": self.acl_rules,
            "lbRules": self.lb_rules,
        }
        body = json.dumps(spec).encode("utf-8")
        secret = self._load_secret()
        if not secret:
            logging.warning("CsHwOffloadIntent: no shared secret on disk; skipping submit")
            return False
        sig = hmac.new(secret, body, hashlib.sha256).hexdigest()
        gateway = self._cloud0_gateway() or "169.254.0.1"
        url = "http://%s:%d%s" % (gateway, HWOFFLOAD_API_PORT, HWOFFLOAD_API_PATH)
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("X-CS-VR-Id", spec["vrId"])
        req.add_header("X-CS-Auth", sig)
        try:
            with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT_SEC) as resp:
                code = resp.getcode()
                logging.info("HW offload intent submitted: HTTP %d (rules: nat=%d acl=%d lb=%d)",
                             code, len(self.nat_rules), len(self.acl_rules), len(self.lb_rules))
                return 200 <= code < 300
        except urllib.error.HTTPError as e:
            logging.error("HW offload intent rejected: HTTP %d (%s)", e.code, e.read()[:200])
            return False
        except Exception as e:
            logging.error("HW offload intent submit failed: %s", e)
            return False

    def submit_empty(self):
        """Submit an empty intent — used by BACKUP VR to instruct host to clear rules."""
        self.nat_rules = []
        self.acl_rules = []
        self.lb_rules = []
        return self.submit()

    @staticmethod
    def _load_secret():
        if not os.path.isfile(SECRET_PATH):
            return None
        try:
            with open(SECRET_PATH, "rb") as f:
                data = f.read().strip()
            # Accept hex-encoded secret for human-readability; fallback to raw bytes
            try:
                return bytes.fromhex(data.decode("ascii"))
            except Exception:
                return data
        except IOError:
            return None

    @staticmethod
    def _cloud0_gateway():
        """Return the default gateway on cloud0 (169.254.0.x), used as host API endpoint."""
        try:
            out = subprocess.check_output(
                ["ip", "-4", "route", "show", "default", "dev", "eth0"],
                universal_newlines=True, stderr=subprocess.DEVNULL,
            )
            for tok in out.split():
                if tok.count(".") == 3:
                    return tok
        except Exception:
            pass
        # Fallback: try cloud0 link-local default
        try:
            out = subprocess.check_output(
                ["ip", "-4", "addr", "show", "dev", "eth0"],
                universal_newlines=True, stderr=subprocess.DEVNULL,
            )
            for line in out.splitlines():
                line = line.strip()
                if line.startswith("inet 169.254."):
                    # Use .1 of the same /16 as host gateway by convention.
                    addr = line.split()[1].split("/")[0]
                    parts = addr.split(".")
                    parts[-1] = "1"
                    return ".".join(parts)
        except Exception:
            pass
        return None
