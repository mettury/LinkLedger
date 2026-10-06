package dev.linkledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void createRedirectHeadAnalyticsAndDisable() throws Exception {
        String response = mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-secret-key")
                        .contentType("application/json").content("{\"url\":\"https://example.com/item?id=42#details\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(response);
        String code = created.get("code").asText();
        mvc.perform(head("/" + code)).andExpect(status().isFound());
        mvc.perform(get("/api/v1/urls/" + code + "/analytics").header("X-API-Key", "test-secret-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRedirects").value(0));
        mvc.perform(get("/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/item?id=42#details"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/urls/" + code + "/analytics").header("X-API-Key", "test-secret-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRedirects").value(1))
                .andExpect(jsonPath("$.lastAccessedAt").isNotEmpty());
        mvc.perform(delete("/api/v1/urls/" + code).header("X-API-Key", "test-secret-key"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/" + code)).andExpect(status().isGone());
    }

    @Test
    void idempotencyReplaysAndRejectsChangedRequest() throws Exception {
        String key = java.util.UUID.randomUUID().toString();
        String body = "{\"url\":\"https://example.com/idempotent\"}";
        String first = mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-secret-key")
                        .header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-secret-key")
                        .header("Idempotency-Key", key).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(first).get("code")).isEqualTo(mapper.readTree(second).get("code"));
        mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-secret-key").header("Idempotency-Key", key)
                        .contentType("application/json").content("{\"url\":\"https://example.com/changed\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void protectsManagementAndMetricsButAllowsHealth() throws Exception {
        mvc.perform(post("/api/v1/urls").servletPath("/api/v1/urls").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").servletPath("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/missing123")).andExpect(status().isNotFound());
    }

    @Test
    void validatesMalformedUnknownFieldsAndPastExpiry() throws Exception {
        for (String body : new String[]{"{", "{\"url\":\"javascript:alert(1)\"}",
                "{\"url\":\"https://example.com\",\"extra\":1}",
                "{\"url\":\"https://example.com\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}"}) {
            mvc.perform(post("/api/v1/urls").header("X-API-Key", "test-secret-key")
                            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }
}
