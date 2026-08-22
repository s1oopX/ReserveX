package com.reservex.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservex.controller.ApiRootController;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class JsonApiFilterTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiRootController(), new PatchController())
            .addFilters(new RequestIdFilter(), new JsonApiFilter(new ObjectMapper()))
            .build();

    @RestController
    static class PatchController {
        @PatchMapping("/api/users/me")
        void update() {
        }
    }

    @Test
    void apiRejectsRepresentationsOtherThanJson() throws Exception {
        assertEquals(406, responseStatus("application/xml"));
        assertEquals(406, responseStatus("text/plain"));
        assertEquals(406, responseStatus("application/json;q=0"));
        assertEquals(406, responseStatus("*/*;q=0"));
        assertEquals(406, responseStatus("application/json;q=0, */*;q=1"));
        assertEquals(406, responseStatus("application/json;q=0, application/*;q=0.8"));
        assertEquals(406, responseStatus("application/json;charset=utf-16"));
        assertEquals(406, responseStatus("application/json;foo=bar"));
        assertEquals(200, responseStatus("application/json"));
        assertEquals(200, responseStatus("application/json;charset=utf-8"));
        assertEquals(200, responseStatus("application/json;foo=bar, application/json;q=0.5"));
        assertEquals(200, responseStatus("*/*"));
        assertEquals(200, responseStatus("application/json;q=0.5, */*;q=0"));
        assertEquals(200, responseStatus("application/xml, application/json;q=0.5"));
    }

    @Test
    void apiRejectsNonJsonBodiesEvenWhenTheEndpointDoesNotReadOne() throws Exception {
        mvc.perform(post("/api")
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .contentType("application/xml")
                        .content("<x/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType("application/json"))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertEquals(415, mvc.perform(post("/api")
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .content("{}"))
                .andReturn().getResponse().getStatus());
        assertEquals(415, bodyStatus("*/*"));
        assertEquals(415, bodyStatus("application/*"));
        assertEquals(405, mvc.perform(post("/api")
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .contentType("application/json")
                        .content("{}"))
                .andReturn().getResponse().getStatus());
        assertEquals(405, bodyStatus("application/json;charset=UTF-8"));
    }

    @Test
    void crossOriginRequestsUseTheJsonErrorContract() throws Exception {
        mvc.perform(options("/api/sessions")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void optionsAdvertisesPatchRepresentation() throws Exception {
        mvc.perform(options("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_PATCH, "application/json"));
        mvc.perform(options("/api"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCEPT_PATCH));
    }

    @Test
    void optionsWithoutPatchDoesNotWriteAnEmptyAcceptPatchHeader() throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/api");
        var response = new MockHttpServletResponse() {
            @Override
            public void setHeader(String name, String value) {
                if (HttpHeaders.ACCEPT_PATCH.equalsIgnoreCase(name) && value == null) {
                    throw new AssertionError("Accept-Patch must be omitted instead of written empty");
                }
                super.setHeader(name, value);
            }
        };

        new JsonApiFilter(new ObjectMapper()).doFilterInternal(request, response,
                (req, res) -> ((HttpServletResponse) res)
                        .setHeader(HttpHeaders.ALLOW, "GET,HEAD,OPTIONS"));

        assertNull(response.getHeader(HttpHeaders.ACCEPT_PATCH));
    }

    private int bodyStatus(String contentType) throws Exception {
        return mvc.perform(post("/api")
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .contentType(contentType)
                        .content("{}"))
                .andReturn().getResponse().getStatus();
    }

    private int responseStatus(String accept) throws Exception {
        var response = mvc.perform(get("/api").header(HttpHeaders.ACCEPT, accept))
                .andReturn().getResponse();
        if (response.getStatus() == 406) {
            assertEquals("application/json", response.getContentType());
            assertEquals(response.getHeader(RequestIdFilter.HEADER),
                    new ObjectMapper().readTree(response.getContentAsString()).get("requestId").asText());
        }
        return response.getStatus();
    }
}
