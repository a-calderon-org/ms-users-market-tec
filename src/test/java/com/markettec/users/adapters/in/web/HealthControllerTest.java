package com.markettec.users.adapters.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsHealthAndGeneratedCorrelationId() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME,
                        matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("ms-users"))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.correlationId").isString());
    }

    @Test
    void propagatesValidCorrelationId() throws Exception {
        mockMvc.perform(get("/health").header(CorrelationIdFilter.HEADER_NAME, "test-correlation"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "test-correlation"))
                .andExpect(jsonPath("$.correlationId").value("test-correlation"));
    }

    @Test
    void unknownRouteReturnsStandardErrorEnvelope() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.correlationId").isString())
                .andExpect(jsonPath("$.error.timestamp").isNumber());
    }
}
