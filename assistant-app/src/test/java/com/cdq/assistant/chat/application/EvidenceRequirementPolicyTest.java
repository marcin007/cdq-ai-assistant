package com.cdq.assistant.chat.application;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import com.cdq.assistant.chat.tool.SourceKind;
import com.cdq.assistant.chat.tool.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceRequirementPolicyTest {

    private final EvidenceRequirementPolicy policy = new EvidenceRequirementPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"Hi", "Hello!", "Cześć", "Dziękuję.", "Thanks", "Thank you!"})
    void allowsSmallTalkWithoutSources(String message) {
        assertThatCode(() -> policy.verify(message, List.of())).doesNotThrowAnyException();
    }

    @Test
    void doesNotTreatNontrivialThanksTextAsSmallTalk() {
        assertThatThrownBy(() -> policy.verify("Thanks for Germany's capital", List.of()))
                .isInstanceOf(ChatGroundingException.class);
        assertThatThrownBy(() -> policy.verify(
                        "Thanks for Germany's capital", List.of(source(SourceKind.WEATHER))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatCode(() -> policy.verify(
                        "Thanks for Germany's capital", List.of(source(SourceKind.REST_COUNTRIES))))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresCountriesForTheCanonicalBerlinQuestion() {
        String message = "What do you know about Berlin?";

        assertThatThrownBy(() -> policy.verify(message, List.of(source(SourceKind.WEATHER))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatThrownBy(() -> policy.verify(message, List.of(source(SourceKind.CDQ_RAG))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatCode(() -> policy.verify(message, List.of(source(SourceKind.REST_COUNTRIES))))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresWeatherForTheDegreesCelsiusMunichParaphrase() {
        String message = "How many degrees Celsius is it in Munich right now?";

        assertThatThrownBy(() -> policy.verify(message, List.of(source(SourceKind.REST_COUNTRIES))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatThrownBy(() -> policy.verify(message, List.of(source(SourceKind.CDQ_RAG))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatCode(() -> policy.verify(message, List.of(source(SourceKind.WEATHER))))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresCountriesAndWeatherForCapitalTemperature() {
        String message = "What is the temperature of Germany's capital?";

        assertThatThrownBy(() -> policy.verify(
                        message, List.of(source(SourceKind.REST_COUNTRIES))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatThrownBy(() -> policy.verify(
                        message, List.of(source(SourceKind.WEATHER))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatCode(() -> policy.verify(
                        message,
                        List.of(
                                source(SourceKind.REST_COUNTRIES),
                                source(SourceKind.WEATHER))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("singleSourceCases")
    void requiresTheExpectedSingleSource(String message, SourceKind required, SourceKind wrong) {
        assertThatThrownBy(() -> policy.verify(message, List.of(source(wrong))))
                .isInstanceOf(ChatGroundingException.class);
        assertThatCode(() -> policy.verify(message, List.of(source(required))))
                .doesNotThrowAnyException();
    }

    @Test
    void unsupportedFactualQuestionFailsClosedWithoutAnySource() {
        assertThatThrownBy(() -> policy.verify("Who founded Acme?", List.of()))
                .isInstanceOf(ChatGroundingException.class);
    }

    private static Stream<Arguments> singleSourceCases() {
        return Stream.of(
                Arguments.of("What does CDQ Fraud Guard do?", SourceKind.CDQ_RAG, SourceKind.WEATHER),
                Arguments.of("Co robi CDQ Fraud Guard?", SourceKind.CDQ_RAG, SourceKind.REST_COUNTRIES),
                Arguments.of("What is Germany's capital?", SourceKind.REST_COUNTRIES, SourceKind.CDQ_RAG),
                Arguments.of("Jaka jest stolica Niemiec?", SourceKind.REST_COUNTRIES, SourceKind.WEATHER),
                Arguments.of("What is the current weather in Berlin?", SourceKind.WEATHER, SourceKind.CDQ_RAG),
                Arguments.of("Jaka jest aktualna pogoda w Berlinie?", SourceKind.WEATHER, SourceKind.REST_COUNTRIES));
    }

    private static SourceRecord source(SourceKind kind) {
        return new SourceRecord(kind, "test", URI.create("https://example.test"));
    }
}
