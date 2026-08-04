package com.cdq.countries.country;

import java.util.List;
import java.util.Map;

/** Compact, safe representation of a REST Countries result. */
public record CountryInfo(
        String commonName,
        String officialName,
        String alpha2Code,
        String alpha3Code,
        String region,
        String subregion,
        Long population,
        List<String> capitals,
        Map<String, String> currencies,
        Map<String, String> languages) {
}
