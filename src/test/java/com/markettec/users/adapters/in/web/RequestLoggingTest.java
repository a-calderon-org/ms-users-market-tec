package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.application.ports.in.GetHealthQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @MockitoSpyBean GetHealthQuery health;

    private JsonNode completed(CapturedOutput output, String correlation) throws Exception {
        for (String line : output.getOut().lines().filter(s -> s.startsWith("{")).toList()) {
            var event=json.readTree(line);
            if ("request_completed".equals(event.path("message").asText()) && correlation.equals(event.path("correlationId").asText())) return event;
        }
        fail("Missing correlated JSON completion event"); return null;
    }

    @Test
    void requestLogContainsCorrelationButNoRawPathQueryOrCredentials(CapturedOutput output) throws Exception {
        http.perform(get("/customers/private-marker@example.invalid").queryParam("token", "query-secret-marker")
                        .header("Authorization", "Bearer bearer-secret-marker").header("Cookie", "session=cookie-secret-marker")
                        .header(CorrelationIdFilter.HEADER_NAME, "safe-request"))
                .andExpect(status().isNotFound());
        var event=completed(output,"safe-request");
        assertEquals(404,event.path("status").asInt());
        assertEquals("GET",event.path("method").asText());
        assertEquals("/**",event.path("route").asText());
        for (String marker:List.of("private-marker", "query-secret-marker", "bearer-secret-marker", "cookie-secret-marker")) assertFalse(output.getOut().contains(marker));
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void oversizedCorrelationIdIsReplaced() throws Exception {
        var response=http.perform(get("/health").header(CorrelationIdFilter.HEADER_NAME,"x".repeat(129)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(response.getHeader(CorrelationIdFilter.HEADER_NAME).matches("[0-9a-f-]{36}"));
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void correlationIdWithControlCharactersIsReplaced(CapturedOutput output) throws Exception {
        var response=http.perform(get("/health").header(CorrelationIdFilter.HEADER_NAME,"unsafe\r\nforged-marker"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String correlation=response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertTrue(correlation.matches("[0-9a-f-]{36}"));
        completed(output,correlation);
        assertFalse(output.getOut().contains("forged-marker"));
    }

    @Test
    void normalRequestsProduceJsonAndDoNotRetainThePreviousContext(CapturedOutput output) throws Exception {
        http.perform(get("/health").header(CorrelationIdFilter.HEADER_NAME,"first-request")).andExpect(status().isOk());
        assertNull(MDC.get("correlationId"));
        var response=http.perform(get("/health")).andExpect(status().isOk()).andReturn().getResponse();
        String next=response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotEquals("first-request",next);
        assertEquals(200,completed(output,next).path("status").asInt());
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void failedRequestHasSafeEnvelopeAndCorrelated500Log(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("private-exception-marker")).when(health).getHealth();
        http.perform(get("/health").header(CorrelationIdFilter.HEADER_NAME,"failed-request"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.correlationId").value("failed-request"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-exception-marker"))));
        assertEquals(500,completed(output,"failed-request").path("status").asInt());
        var line=output.getOut().lines().filter(s->s.contains("\"request_failed\"")).findFirst().orElseThrow();
        var failure=json.readTree(line);
        assertEquals("java.lang.IllegalStateException",failure.path("exception_type").asText());
        assertEquals("failed-request",failure.path("correlationId").asText());
        assertFalse(output.getOut().contains("private-exception-marker"));
        assertNull(MDC.get("correlationId"));
    }

    @Test
    void escapingFilterFailureIsLoggedAs500AndRestoresTheEnclosingContext(CapturedOutput output) throws Exception {
        MDC.put("correlationId","enclosing-context"); MDC.put("outer-private","outer-secret-marker");
        var previous=MDC.getCopyOfContextMap();
        var request=new MockHttpServletRequest("GET","/private-route-marker");
        request.addHeader(CorrelationIdFilter.HEADER_NAME,"escaping-request");
        try {
            assertThrows(IOException.class,()->new CorrelationIdFilter().doFilter(request,new MockHttpServletResponse(),(req,res)->{
                assertEquals(Map.of("correlationId","escaping-request"),MDC.getCopyOfContextMap());
                MDC.put("leaked-child","child-secret-marker");
                MDC.put("correlationId","mutated-child-context");
                throw new IOException("private-failure-marker");
            }));
            assertEquals(previous,MDC.getCopyOfContextMap());
            assertEquals(500,completed(output,"escaping-request").path("status").asInt());
            for(String marker:List.of("outer-secret-marker","child-secret-marker","private-route-marker","private-failure-marker")) assertFalse(output.getOut().contains(marker));
        } finally { MDC.clear(); }
    }

    @Test
    void sqlAndStartupDiagnosticsCannotSerializeSecretsOrArbitraryContext(CapturedOutput output) throws Exception {
        MDC.put("correlationId","diagnostic-request");MDC.put("accessToken","mdc-secret-marker");
        try {
            LoggerFactory.getLogger("com.microsoft.sqlserver.jdbc.SQLServerConnection").atError()
                    .addKeyValue("password","kv-secret-marker").setCause(new SQLException("sql-secret-marker"))
                    .log("Bearer message-secret-marker");
            LoggerFactory.getLogger("org.springframework.boot.SpringApplication").error("startup-secret-marker",new IllegalStateException("cause-secret-marker"));
        } finally { MDC.clear(); }
        var events=output.getOut().lines().filter(s->s.contains("\"correlationId\":\"diagnostic-request\"")).toList();
        assertEquals(2,events.size());
        assertEquals("java.sql.SQLException",json.readTree(events.getFirst()).path("exception_type").asText());
        for(String line:events) {
            var event=json.readTree(line);assertEquals("runtime_event",event.path("message").asText());
            assertFalse(event.has("stack_trace"));assertFalse(event.has("password"));assertFalse(event.has("accessToken"));
        }
        for(String marker:List.of("mdc-secret-marker","kv-secret-marker","sql-secret-marker","message-secret-marker","startup-secret-marker","cause-secret-marker")) assertFalse(output.getOut().contains(marker));
    }
}
