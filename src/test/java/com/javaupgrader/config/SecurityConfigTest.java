package com.javaupgrader.config;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
                properties = "GITHUB_TOKEN=test-token")
class SecurityConfigTest {

    @Autowired
    WebApplicationContext wac;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(springSecurity())
            .build();
    }

    // Replaces the auto-configured decoder so no real JWKS endpoint is needed
    @MockitoBean
    JwtDecoder jwtDecoder;

    // Avoids requiring ANTHROPIC_API_KEY in the test environment
    @MockitoBean
    AnthropicClient anthropicClient;

    @Test
    void sseEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/sse"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void messageEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/mcp/message"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void sseEndpoint_withValidJwt_isNotRejectedBySecurity() throws Exception {
        int status = mockMvc.perform(get("/sse").with(jwt()))
            .andReturn().getResponse().getStatus();
        // Security layer accepts the request; actual SSE response may vary
        assert status != 401 && status != 403
            : "Expected security to pass, but got HTTP " + status;
    }

    @Test
    void messageEndpoint_withValidJwt_isNotRejectedBySecurity() throws Exception {
        int status = mockMvc.perform(post("/mcp/message").with(jwt()))
            .andReturn().getResponse().getStatus();
        assert status != 401 && status != 403
            : "Expected security to pass, but got HTTP " + status;
    }
}
