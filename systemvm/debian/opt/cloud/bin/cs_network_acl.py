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

import os
from netaddr import *


def macdevice_map():
    device_map = {}
    for eth in os.listdir('/sys/class/net'):
        if not eth.startswith('eth'):
            continue
        try:
            with open('/sys/class/net/%s/address' % eth) as f:
                mac_address = f.read().replace('\n', '')
                device_map[mac_address] = eth
        except (IOError, OSError):
            continue
    return device_map


def merge(dbag, data):
    # The 'device' field comes from NicTO.deviceId on management side, which
    # may not match VR's actual interface ordering when a HW offload guest
    # NIC (hostdev) is present (it shifts subsequent interfaces by one).
    # When mac_address is provided, resolve the actual eth name by MAC.
    mac = data.get('mac_address')
    if mac:
        device_map = macdevice_map()
        if mac in device_map:
            data['device'] = device_map[mac]
    dbag[data['device']] = data
    return dbag
