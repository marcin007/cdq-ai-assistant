package com.cdq.countries.country;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class CountriesMcpTools {

    private static final String INVALID_INPUT_MESSAGE = "Provide a country name or capital between 1 and 100 characters.";

    private final CountryLookupGateway countryLookupGateway;

    public CountriesMcpTools(CountryLookupGateway countryLookupGateway) {
        this.countryLookupGateway = countryLookupGateway;
    }

    @McpTool(name = "countries_get_by_name", description = "Get country information by its common name.")
    public CountryLookupResult countriesGetByName(
            @McpToolParam(required = true, description = "A nonblank country name, up to 100 characters.") String name) {
        return valid(name) ? requireAvailable(countryLookupGateway.byCommonName(name.trim())) : invalidInput();
    }

    @McpTool(name = "countries_get_by_capital", description = "Get country information by a capital city.")
    public CountryLookupResult countriesGetByCapital(
            @McpToolParam(required = true, description = "A nonblank capital name, up to 100 characters.") String capital) {
        return valid(capital) ? requireAvailable(countryLookupGateway.byCapital(capital.trim())) : invalidInput();
    }

    private boolean valid(String value) {
        return value != null && !value.isBlank() && value.trim().length() <= 100;
    }

    private CountryLookupResult invalidInput() {
        return CountryLookupResult.failure(CountryLookupErrorCode.INVALID_INPUT, INVALID_INPUT_MESSAGE);
    }

    private CountryLookupResult requireAvailable(CountryLookupResult result) {
        if (result.error() == null
                || result.error().code() == CountryLookupErrorCode.INVALID_INPUT
                || result.error().code() == CountryLookupErrorCode.NOT_FOUND) {
            return result;
        }
        throw new IllegalStateException("The countries service could not complete the lookup.");
    }
}
