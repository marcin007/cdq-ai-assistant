package com.cdq.assistant.chat.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.cdq.assistant.chat.tool.SourceKind;
import com.cdq.assistant.chat.tool.SourceRecord;

public final class EvidenceRequirementPolicy {

    private static final int CASE_INSENSITIVE_UNICODE =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern SMALL_TALK = Pattern.compile(
            "\\A(?:hi|hello|cześć|dziękuję|thanks|thank\\s+you)[!.]?\\z",
            CASE_INSENSITIVE_UNICODE);
    private static final Pattern CDQ_OR_FRAUD_GUARD = Pattern.compile(
            "\\b(?:cdq|fraud\\s+guard)\\b", CASE_INSENSITIVE_UNICODE);
    private static final Pattern WEATHER = Pattern.compile(
            "\\b(?:weather|temperature|degrees?\\s+celsius|pogoda|pogodę|temperatura|temperaturę)\\b",
            CASE_INSENSITIVE_UNICODE);
    private static final Pattern COUNTRY_OR_CAPITAL = Pattern.compile(
            "\\b(?:country|countries|capital|germany|kraj|kraju|kraje|państwo|państwa|niemcy|stolica|stolicy)\\b",
            CASE_INSENSITIVE_UNICODE);
    private static final Pattern BERLIN = Pattern.compile(
            "\\bberlin(?:a|ie|em|owi|u)?\\b", CASE_INSENSITIVE_UNICODE);

    public void verify(String message, List<SourceRecord> successfulSources) {
        String normalized = normalize(message);
        Set<SourceKind> actual = successfulSources.stream()
                .map(SourceRecord::kind)
                .collect(Collectors.toUnmodifiableSet());
        if (SMALL_TALK.matcher(normalized).matches()) {
            return;
        }
        Set<SourceKind> required = requiredSources(normalized);
        boolean satisfied = required.isEmpty() ? !actual.isEmpty() : actual.containsAll(required);
        if (!satisfied) {
            throw new ChatGroundingException();
        }
    }

    private static String normalize(String message) {
        return Normalizer.normalize(message, Normalizer.Form.NFC).strip();
    }

    private static Set<SourceKind> requiredSources(String message) {
        if (CDQ_OR_FRAUD_GUARD.matcher(message).find()) {
            return Set.of(SourceKind.CDQ_RAG);
        }

        boolean requiresWeather = WEATHER.matcher(message).find();
        boolean requiresCountries = COUNTRY_OR_CAPITAL.matcher(message).find();
        if (requiresWeather && requiresCountries) {
            return Set.of(SourceKind.WEATHER, SourceKind.REST_COUNTRIES);
        }
        if (requiresWeather) {
            return Set.of(SourceKind.WEATHER);
        }
        if (requiresCountries) {
            return Set.of(SourceKind.REST_COUNTRIES);
        }
        if (BERLIN.matcher(message).find()) {
            return Set.of(SourceKind.REST_COUNTRIES);
        }
        return Set.of();
    }
}
