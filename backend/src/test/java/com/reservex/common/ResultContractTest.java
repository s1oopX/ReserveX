package com.reservex.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultContractTest {

    @Test
    void serializesTheDocumentedEnvelope() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-1");
        try {
            var mapper = new ObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);
            var json = mapper.readTree(mapper.writeValueAsString(Result.ok()));

            assertEquals("OK", json.get("code").asText());
            assertEquals("成功", json.get("msg").asText());
            assertTrue(json.get("data").isNull());
            assertEquals("req-1", json.get("requestId").asText());
            assertFalse(json.has("message"));
            assertFalse(json.has("ok"));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }
}
