package com.cdq.assistant.chat.config;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import com.cdq.assistant.chat.application.ChatGroundingException;
import com.cdq.assistant.chat.application.EvidenceRequirementPolicy;
import com.cdq.assistant.chat.application.UserFacingAnswerPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatConfigurationTest {

    @Test
    void exposesAUserFacingAnswerPolicyBean() throws Exception {
        Method policyBean = ChatConfiguration.class.getDeclaredMethod("userFacingAnswerPolicy");

        assertThat(policyBean.isAnnotationPresent(Bean.class)).isTrue();
        UserFacingAnswerPolicy policy =
                (UserFacingAnswerPolicy) policyBean.invoke(new ChatConfiguration());
        assertThat(policy.release("reasoning</think> Final answer"))
                .isEqualTo("Final answer");
    }

    @Test
    void exposesAnEvidenceRequirementPolicyBeanForChatOrchestration() throws Exception {
        assertThatCode(() -> ChatConfiguration.class
                        .getDeclaredMethod("evidenceRequirementPolicy"))
                .doesNotThrowAnyException();

        Method policyBean = ChatConfiguration.class.getDeclaredMethod("evidenceRequirementPolicy");
        assertThat(policyBean.isAnnotationPresent(Bean.class)).isTrue();

        EvidenceRequirementPolicy policy =
                (EvidenceRequirementPolicy) policyBean.invoke(new ChatConfiguration());
        assertThatThrownBy(() -> policy.verify("What is Germany's capital?", List.of()))
                .isInstanceOf(ChatGroundingException.class);
    }

    @Test
    void bindsTheRequiredModelToolDeadlineAndCountriesConnectionConfiguration()
            throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setConversionService(new ApplicationConversionService());
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        ChatProperties chatProperties = Binder.get(environment)
                .bind("assistant.chat", Bindable.of(ChatProperties.class))
                .get();

        assertThat(environment.getProperty("spring.ai.ollama.chat.model"))
                .isEqualTo("qwen3:4b-instruct-2507-q4_K_M");
        assertThat(environment.getProperty("spring.ai.ollama.chat.think")).isNull();
        assertThat(environment.getProperty(
                        "spring.ai.ollama.chat.temperature", Double.class))
                .isEqualTo(0.1);
        assertThat(Binder.get(environment)
                        .bind(
                                "spring.ai.ollama.init.pull-model-strategy",
                                Bindable.of(PullModelStrategy.class))
                        .get())
                .isEqualTo(PullModelStrategy.NEVER);
        assertThat(environment.getProperty(
                        "spring.ai.tools.throw-exception-on-error", Boolean.class))
                .isTrue();
        assertThat(chatProperties.model()).isEqualTo("qwen3:4b-instruct-2507-q4_K_M");
        assertThat(chatProperties.maxOutputTokens()).isEqualTo(256);
        assertThat(chatProperties.timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(environment.getProperty(
                        "spring.http.clients.read-timeout", Duration.class))
                .isEqualTo(Duration.ofSeconds(40));
        assertThat(environment.getProperty(
                "spring.ai.mcp.client.streamable-http.connections.countries.url"))
                .isEqualTo("http://127.0.0.1:8081");
        assertThat(environment.getProperty(
                        "spring.ai.mcp.client.streamable-http.connections.countries.endpoint"))
                .isEqualTo("/mcp");
    }
}
