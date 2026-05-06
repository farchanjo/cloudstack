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
package com.cloud.network.ovn.client.op;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Helpers to render the OVSDB row reference forms (RFC 7047 §5.2.1):
 *
 * <ul>
 *   <li>{@code ["uuid", "<real-uuid>"]} — references an existing row by id.
 *   <li>{@code ["named-uuid", "<placeholder>"]} — references another row
 *       inserted in the same transaction.
 * </ul>
 */
public final class OvnRowRef {

    private OvnRowRef() {
    }

    public static ArrayNode realUuid(final String uuid) {
        final ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        arr.add("uuid");
        arr.add(uuid);
        return arr;
    }

    public static ArrayNode namedUuid(final String name) {
        final ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        arr.add("named-uuid");
        arr.add(name);
        return arr;
    }

    /**
     * Builds an OVSDB set literal: {@code ["set", [ ... ]]}. The output is a
     * one-element set containing the supplied row reference. Used when an
     * OVSDB column has type {@code set} but we only care about adding one
     * element at insert time.
     */
    public static ArrayNode singletonSet(final ArrayNode element) {
        final ArrayNode set = JsonNodeFactory.instance.arrayNode();
        set.add("set");
        final ArrayNode elements = JsonNodeFactory.instance.arrayNode();
        elements.add(element);
        set.add(elements);
        return set;
    }
}
