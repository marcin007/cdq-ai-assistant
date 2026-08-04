package com.cdq.countries.country;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountriesMcpToolsTest {

    private final CountryInfo poland = new CountryInfo("Poland", "Republic of Poland", "PL", "POL", "Europe",
            "Central Europe", 38_000_000L, List.of("Warsaw"), Map.of("PLN", "Polish zloty"), Map.of("pol", "Polish"));

    @Test
    void exposesExactlyTheRequiredMcpToolNamesAndInputDescription() throws NoSuchMethodException {
        Method byName = CountriesMcpTools.class.getMethod("countriesGetByName", String.class);
        Method byCapital = CountriesMcpTools.class.getMethod("countriesGetByCapital", String.class);

        assertThat(byName.getAnnotation(McpTool.class).name()).isEqualTo("countries_get_by_name");
        assertThat(byCapital.getAnnotation(McpTool.class).name()).isEqualTo("countries_get_by_capital");
        assertThat(byName.getParameters()[0].getAnnotation(McpToolParam.class).required()).isTrue();
    }

    @Test
    void delegatesAValidNameToTheTypedLookupGateway() {
        CountriesMcpTools tools = new CountriesMcpTools(new FixedGateway(CountryLookupResult.success(poland)));

        CountryLookupResult result = tools.countriesGetByName("Poland");

        assertThat(result).isEqualTo(CountryLookupResult.success(poland));
    }

    @Test
    void returnsASafeValidationErrorForBlankOrTooLongInput() {
        CountriesMcpTools tools = new CountriesMcpTools(new FixedGateway(CountryLookupResult.success(poland)));

        assertThat(tools.countriesGetByCapital(" ").error()).isEqualTo(new CountryLookupError(
                CountryLookupErrorCode.INVALID_INPUT, "Provide a country name or capital between 1 and 100 characters."));
        assertThat(tools.countriesGetByCapital("x".repeat(101)).error()).isEqualTo(new CountryLookupError(
                CountryLookupErrorCode.INVALID_INPUT, "Provide a country name or capital between 1 and 100 characters."));
    }

    @ParameterizedTest
    @EnumSource(value = CountryLookupErrorCode.class, names = {
            "AUTHENTICATION", "RATE_LIMITED", "TIMEOUT", "MALFORMED_RESPONSE", "UNAVAILABLE"
    })
    void throwsASanitizedFailureForEveryDependencyError(CountryLookupErrorCode code) {
        CountriesMcpTools tools = new CountriesMcpTools(
                new FixedGateway(CountryLookupResult.failure(code, "raw-secret-body test-api-key")));

        assertThatThrownBy(() -> tools.countriesGetByName("Poland"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("The countries service could not complete the lookup.")
                .hasMessageNotContaining("raw-secret-body")
                .hasMessageNotContaining("test-api-key");
    }

    @Test
    void keepsNotFoundAsANormalSafeDomainResult() {
        CountryLookupResult notFound = CountryLookupResult.failure(
                CountryLookupErrorCode.NOT_FOUND, "No matching country was found.");
        CountriesMcpTools tools = new CountriesMcpTools(new FixedGateway(notFound));

        assertThat(tools.countriesGetByCapital("Missing")).isEqualTo(notFound);
    }

    private record FixedGateway(CountryLookupResult result) implements CountryLookupGateway {
        @Override
        public CountryLookupResult byCommonName(String name) {
            return result;
        }

        @Override
        public CountryLookupResult byCapital(String capital) {
            return result;
        }
    }
}
