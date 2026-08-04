package com.cdq.assistant.chat.application;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.cdq.assistant.chat.tool.AssistantToolCatalog;
import com.cdq.assistant.chat.tool.SourceRecord;
import com.cdq.assistant.chat.tool.ToolInvocationLedger;
import com.cdq.assistant.chat.tool.TrackingToolCallback;

public final class ChatOrchestrator implements ChatOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatOrchestrator.class);

    public static final String SYSTEM_POLICY =
            "Answer in the same language as the user's current message. "
                    + "Use countries_get_by_name or countries_get_by_capital for country and capital facts. "
                    + "Use get_weather for current temperature. "
                    + "Use search_cdq_fraud_guard for CDQ Fraud Guard product facts. "
                    + "Never invent data that a required tool does not provide. "
                    + "Weather temperature values are Celsius because the weather server returns current.temp_c. "
                    + "For multi-step factual answers, state each factual relationship explicitly and associate each dynamic value with the entity it describes. "
                    + "For a country-capital weather request, explicitly state that the capital is the capital of the country and explicitly state the current temperature in that capital.";

    private static final int MAX_MODEL_CALLS = 4;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final AssistantToolCatalog toolCatalog;
    private final EvidenceRequirementPolicy evidenceRequirementPolicy;
    private final UserFacingAnswerPolicy userFacingAnswerPolicy;
    private final String model;
    private final int maxOutputTokens;

    public ChatOrchestrator(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            AssistantToolCatalog toolCatalog,
            EvidenceRequirementPolicy evidenceRequirementPolicy,
            UserFacingAnswerPolicy userFacingAnswerPolicy,
            String model,
            int maxOutputTokens) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolCatalog = toolCatalog;
        this.evidenceRequirementPolicy = evidenceRequirementPolicy;
        this.userFacingAnswerPolicy = userFacingAnswerPolicy;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public ChatResult chat(String message) {
        ToolInvocationLedger ledger = new ToolInvocationLedger();
        List<ToolCallback> callbacks = toolCatalog.tools().stream()
                .map(tool -> new TrackingToolCallback(tool.callback(), tool.source(), ledger))
                .map(ToolCallback.class::cast)
                .toList();
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(model)
                .temperature(0.1)
                .numPredict(maxOutputTokens)
                .toolCallbacks(callbacks)
                .build();
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(SYSTEM_POLICY),
                        new UserMessage(message.strip())),
                options);

        for (int modelCall = 1; modelCall <= MAX_MODEL_CALLS; modelCall++) {
            ChatResponse response = callModel(prompt);
            requireResponse(response);
            if (!response.hasToolCalls()) {
                String answer = releaseAnswer(response.getResult().getOutput().getText());
                List<SourceRecord> sources = ledger.toSourceRecords();
                evidenceRequirementPolicy.verify(message, sources);
                return new ChatResult(answer, sources);
            }
            if (modelCall == MAX_MODEL_CALLS) {
                logModelFailure();
                throw new ToolRoundLimitException();
            }
            prompt = nextPrompt(prompt, response, options);
        }
        throw new IllegalStateException("Unreachable model loop");
    }

    private String releaseAnswer(String modelText) {
        try {
            return userFacingAnswerPolicy.release(modelText);
        } catch (ChatDependencyException exception) {
            logModelFailure();
            throw exception;
        }
    }

    private ChatResponse callModel(Prompt prompt) {
        try {
            return chatModel.call(prompt);
        } catch (ChatDependencyException exception) {
            logModelFailure();
            throw exception;
        } catch (RuntimeException exception) {
            logModelFailure();
            if (isTimeoutFailure(exception)) {
                throw new ChatTimeoutException();
            }
            throw new ChatDependencyException(exception);
        }
    }

    private static boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Prompt nextPrompt(
            Prompt prompt, ChatResponse response, OllamaChatOptions options) {
        try {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            if (result == null || result.conversationHistory() == null) {
                throw new ChatDependencyException();
            }
            return new Prompt(result.conversationHistory(), options);
        } catch (ChatDependencyException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatDependencyException(exception);
        }
    }

    private static void requireResponse(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            logModelFailure();
            throw new ChatDependencyException();
        }
    }

    private static void logModelFailure() {
        LOGGER.warn("Chat model invocation failed; dependency details were suppressed.");
    }
}
