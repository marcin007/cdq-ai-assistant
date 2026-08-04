package com.cdq.countries.country;

public record CountryLookupResult(CountryInfo country, CountryLookupError error) {

    public static CountryLookupResult success(CountryInfo country) {
        return new CountryLookupResult(country, null);
    }

    public static CountryLookupResult failure(CountryLookupErrorCode code, String message) {
        return new CountryLookupResult(null, new CountryLookupError(code, message));
    }
}
