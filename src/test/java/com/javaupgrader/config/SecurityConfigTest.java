package com.javaupgrader.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
                properties = {
                    "GITHUB_TOKEN=test-token",
                    // Prevent Spring AI LLM auto-configuration from activating: both
                    // AnthropicChatAutoConfiguration and OpenAiChatAutoConfiguration guard on
                    // spring.ai.model.chat matching their provider name. Setting it to "none"
                    // skips all chat-model auto-configuration so no API key is required.
                    "spring.ai.model.chat=none"
                })
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

    // Replaces the auto-configured decoder so no real JWKS endpoint is needed.
    @MockitoBean
    JwtDecoder jwtDecoder;

    // ChatClient is an interface, so Mockito can mock it without issue.
    // LlmConfig.anthropicChatClient() is @ConditionalOnMissingBean(ChatClient.class), so it
    // defers to this mock — no AnthropicChatModel (a final class that can't be mocked) needed.
    @MockitoBean
    ChatClient chatClient;

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
