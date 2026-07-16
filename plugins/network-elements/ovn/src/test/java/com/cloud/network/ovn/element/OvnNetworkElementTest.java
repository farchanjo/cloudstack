package com.cloud.network.ovn.element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Test;

import com.cloud.network.ovn.client.OvnNbClient;
import com.cloud.network.ovn.client.transport.OvsdbConnectionPool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Wire regression for atomic LSP address/port-security creation. */
public class OvnNetworkElementTest {
    @Test
    public void lspInsertContainsAddressesAndPortSecurityTogether() {
        final OvsdbConnectionPool pool = mock(OvsdbConnectionPool.class);
        final OvnNbClient client = new OvnNbClient(pool);
        when(pool.call(anyString(), any())).thenReturn(emptyReply(), insertReply());
        client.addLogicalSwitchPort("ls-1", "lsp-1", List.of("aa:bb 10.0.0.2"), null, null);
        final org.mockito.ArgumentCaptor<JsonNode> capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, times(2)).call(anyString(), capture.capture());
        final ArrayNode lookup = (ArrayNode) capture.getAllValues().get(0);
        assertEquals("select", lookup.get(1).get("op").asText());
        final ArrayNode transaction = (ArrayNode) capture.getAllValues().get(1);
        final JsonNode insert = transaction.get(1);
        assertEquals("insert", insert.get("op").asText());
        final JsonNode row = insert.get("row");
        assertEquals("set", row.get("addresses").get(0).asText());
        assertEquals("set", row.get("port_security").get(0).asText());
        assertEquals(row.get("addresses"), row.get("port_security"));
        assertEquals("mutate", transaction.get(2).get("op").asText());
        for (int i = 1; i < transaction.size(); i++) {
            final JsonNode operation = transaction.get(i);
            assertFalse("port_security must not be updated separately",
                    "update".equals(operation.get("op").asText())
                            && operation.get("row").has("port_security"));
        }
    }

    private static ArrayNode emptyReply() {
        final ObjectMapper mapper = new ObjectMapper();
        return mapper.createArrayNode();
    }

    private static ArrayNode insertReply() {
        final ObjectMapper mapper = new ObjectMapper();
        final ArrayNode result = mapper.createArrayNode();
        final var row = mapper.createObjectNode();
        final var uuid = mapper.createArrayNode(); uuid.add("uuid"); uuid.add("lsp-uuid");
        row.set("uuid", uuid); result.add(row); return result;
    }
}
