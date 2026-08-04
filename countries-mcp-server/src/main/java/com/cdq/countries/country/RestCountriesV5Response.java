package com.cdq.countries.country;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Wire DTOs for the REST Countries v5 response shape. */
record RestCountriesV5Response(RestCountriesV5Data data) {

    record RestCountriesV5Data(List<RestCountriesV5Country> objects) {
    }

    record RestCountriesV5Country(
            RestCountriesV5Names names,
            RestCountriesV5Codes codes,
            List<RestCountriesV5Capital> capitals,
            String region,
            String subregion,
            Long population,
            List<RestCountriesV5Currency> currencies,
            List<RestCountriesV5Language> languages) {
    }

    record RestCountriesV5Names(String common, String official) {
    }

    record RestCountriesV5Codes(
            @JsonProperty("alpha_2") String alpha2,
            @JsonProperty("alpha_3") String alpha3) {
    }

    record RestCountriesV5Capital(String name, RestCountriesV5CapitalAttributes attributes) {
    }

    record RestCountriesV5CapitalAttributes(Boolean primary) {
    }

    record RestCountriesV5Currency(String code, String name) {
    }

    record RestCountriesV5Language(
            @JsonProperty("bcp47") String bcp47,
            String name) {
    }
}
