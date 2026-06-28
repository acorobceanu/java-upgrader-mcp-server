package com.javaupgrader.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the single {@link ChatClient} bean used by {@link com.javaupgrader.agent.JavaUpgraderAgent}.
 *
 * <p>Set {@code llm.provider} in {@code application.properties} (or the environment) to switch
 * between providers. The property also controls which Spring AI auto-configuration activates
 * via {@code spring.ai.model.chat=${llm.provider:anthropic}}.
 *
 * <ul>
 *   <li>{@code anthropic} (default) — requires {@code ANTHROPIC_API_KEY} environment variable</li>
 *   <li>{@code openai} — requires {@code OPENAI_API_KEY} environment variable</li>
 * </ul>
 */
@Configuration
public class LlmConfig {

    /**
     * Anthropic-backed {@link ChatClient}, active when {@code llm.provider=anthropic} (or unset).
     *
     * <p>Spring AI auto-configuration creates the {@link AnthropicChatModel} bean; this method
     * wraps it in a {@link ChatClient} with default settings.
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "anthropic", matchIfMissing = true)
    public ChatClient anthropicChatClient(AnthropicChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * OpenAI-backed {@link ChatClient}, active when {@code llm.provider=openai}.
     *
     * <p>Spring AI auto-configuration creates the {@link OpenAiChatModel} bean; this method
     * wraps it in a {@link ChatClient} with default settings.
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    @ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
    public ChatClient openAiChatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
