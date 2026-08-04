package com.cdq.countries.country;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CountriesRestClient implements CountryLookupGateway {

    private static final String RESPONSE_FIELDS = "names.common,names.official,codes.alpha_2,codes.alpha_3,capitals,region,subregion,population,currencies,languages";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    CountriesRestClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Override
    public CountryLookupResult byCommonName(String name) {
        return lookup("/names.common/{value}", name);
    }

    @Override
    public CountryLookupResult byCapital(String capital) {
        return lookup("/capitals/{value}", capital);
    }

    private CountryLookupResult lookup(String path, String value) {
        try {
            String body = restClient.get()
                    .uri(builder -> builder.path(path)
                            .queryParam("response_fields", RESPONSE_FIELDS)
                            .build(value))
                    .retrieve()
                    .body(String.class);
            return mapBody(body);
        }
        catch (RestClientResponseException exception) {
            return CountryLookupResult.failure(errorForStatus(exception.getStatusCode().value()), messageForStatus(exception.getStatusCode().value()));
        }
        catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                return CountryLookupResult.failure(CountryLookupErrorCode.TIMEOUT, "The countries service timed out.");
            }
            return CountryLookupResult.failure(CountryLookupErrorCode.UNAVAILABLE, "The countries service is unavailable.");
        }
        catch (RestClientException exception) {
            return CountryLookupResult.failure(CountryLookupErrorCode.UNAVAILABLE, "The countries service is unavailable.");
        }
    }

    private CountryLookupResult mapBody(String body) {
        if (body == null) {
            return malformedResponse();
        }
        try {
            RestCountriesV5Response response = objectMapper.readValue(body, RestCountriesV5Response.class);
            if (response == null || response.data() == null || response.data().objects() == null) {
                return malformedResponse();
            }
            List<RestCountriesV5Response.RestCountriesV5Country> objects = response.data().objects();
            if (hasNullElement(objects) || objects.stream().anyMatch(country -> !hasValidArrayStructure(country))) {
                return malformedResponse();
            }
            if (objects.isEmpty()) {
                return CountryLookupResult.failure(CountryLookupErrorCode.NOT_FOUND, "No matching country was found.");
            }
            return CountryLookupResult.success(toCountryInfo(objects.getFirst()));
        }
        catch (JacksonException | IllegalArgumentException exception) {
            return malformedResponse();
        }
    }

    private boolean hasValidArrayStructure(RestCountriesV5Response.RestCountriesV5Country country) {
        return country != null
                && !hasNullElement(country.capitals())
                && !hasNullElement(country.currencies())
                && !hasNullElement(country.languages());
    }

    private boolean hasNullElement(List<?> values) {
        return values != null && values.stream().anyMatch(Objects::isNull);
    }

    private CountryInfo toCountryInfo(RestCountriesV5Response.RestCountriesV5Country country) {
        return new CountryInfo(
                text(country.names() == null ? null : country.names().common()),
                text(country.names() == null ? null : country.names().official()),
                text(country.codes() == null ? null : country.codes().alpha2()),
                text(country.codes() == null ? null : country.codes().alpha3()),
                text(country.region()), text(country.subregion()), country.population(),
                capitals(country.capitals()), currencies(country.currencies()), languages(country.languages()));
    }

    private List<String> capitals(List<RestCountriesV5Response.RestCountriesV5Capital> capitals) {
        if (capitals == null) {
            return List.of();
        }
        List<Capital> values = new ArrayList<>();
        for (RestCountriesV5Response.RestCountriesV5Capital capital : capitals) {
            String name = text(capital.name());
            if (!name.isBlank()) {
                boolean primary = capital.attributes() != null && Boolean.TRUE.equals(capital.attributes().primary());
                values.add(new Capital(name, primary));
            }
        }
        return values.stream().sorted(Comparator.comparing(Capital::primary).reversed()).map(Capital::name).toList();
    }

    private Map<String, String> currencies(List<RestCountriesV5Response.RestCountriesV5Currency> currencies) {
        if (currencies == null) {
            return Map.of();
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (RestCountriesV5Response.RestCountriesV5Currency currency : currencies) {
            putIfPresent(mapped, currency.code(), currency.name());
        }
        return Collections.unmodifiableMap(mapped);
    }

    private Map<String, String> languages(List<RestCountriesV5Response.RestCountriesV5Language> languages) {
        if (languages == null) {
            return Map.of();
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (RestCountriesV5Response.RestCountriesV5Language language : languages) {
            putIfPresent(mapped, language.bcp47(), language.name());
        }
        return Collections.unmodifiableMap(mapped);
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (!text(key).isBlank() && !text(value).isBlank()) {
            target.put(key, value);
        }
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private CountryLookupResult malformedResponse() {
        return CountryLookupResult.failure(CountryLookupErrorCode.MALFORMED_RESPONSE,
                "The countries service returned an invalid response.");
    }

    private boolean hasTimeoutCause(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private CountryLookupErrorCode errorForStatus(int status) {
        return switch (status) {
            case 404 -> CountryLookupErrorCode.NOT_FOUND;
            case 401, 403 -> CountryLookupErrorCode.AUTHENTICATION;
            case 429 -> CountryLookupErrorCode.RATE_LIMITED;
            default -> CountryLookupErrorCode.UNAVAILABLE;
        };
    }

    private String messageForStatus(int status) {
        return switch (errorForStatus(status)) {
            case NOT_FOUND -> "No matching country was found.";
            case AUTHENTICATION -> "The countries service authentication failed.";
            case RATE_LIMITED -> "The countries service rate limit was reached.";
            default -> "The countries service is unavailable.";
        };
    }

    private record Capital(String name, boolean primary) {
    }
}
