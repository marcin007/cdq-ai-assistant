package com.cdq.countries.country;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("countries")
public record CountriesClientProperties(
        @DefaultValue("https://api.restcountries.com/countries/v5") String apiBaseUrl,
        @NotBlank String apiKey) {
}
