package com.cdq.assistant.chat.config;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cdq.assistant.chat.application.ChatOrchestrator;
import com.cdq.assistant.chat.application.ChatService;
import com.cdq.assistant.chat.application.EvidenceRequirementPolicy;
import com.cdq.assistant.chat.application.UserFacingAnswerPolicy;
import com.cdq.assistant.chat.tool.AssistantToolCatalog;
import com.cdq.assistant.rag.CdqFraudGuardSearchTool;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
@ConditionalOnProperty(
        name = "assistant.chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatConfiguration {

    @Bean("cdqRagToolCallback")
    ToolCallback cdqRagToolCallback(CdqFraudGuardSearchTool searchTool) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(searchTool)
                .build()
                .getToolCallbacks();
        if (callbacks.length != 1) {
            throw new IllegalStateException("Expected exactly one CDQ RAG callback");
        }
        return callbacks[0];
    }

    @Bean
    AssistantToolCatalog assistantToolCatalog(
            @Qualifier("cdqRagToolCallback") ToolCallback ragCallback,
            SyncMcpToolCallbackProvider mcpToolCallbackProvider) {
        return new AssistantToolCatalog(
                ragCallback, Arrays.asList(mcpToolCallbackProvider.getToolCallbacks()));
    }

    @Bean
    ChatOrchestrator chatOrchestrator(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            AssistantToolCatalog toolCatalog,
            EvidenceRequirementPolicy evidenceRequirementPolicy,
            UserFacingAnswerPolicy userFacingAnswerPolicy,
            ChatProperties properties) {
        return new ChatOrchestrator(
                chatModel,
                toolCallingManager,
                toolCatalog,
                evidenceRequirementPolicy,
                userFacingAnswerPolicy,
                properties.model(),
                properties.maxOutputTokens());
    }

    @Bean
    EvidenceRequirementPolicy evidenceRequirementPolicy() {
        return new EvidenceRequirementPolicy();
    }

    @Bean
    UserFacingAnswerPolicy userFacingAnswerPolicy() {
        return new UserFacingAnswerPolicy();
    }

    @Bean(destroyMethod = "close")
    ExecutorService chatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    ChatService chatService(
            ChatOrchestrator orchestrator,
            @Qualifier("chatExecutor") ExecutorService executor,
            ChatProperties properties) {
        return new ChatService(orchestrator, executor, properties.timeout());
    }
}
