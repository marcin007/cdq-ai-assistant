package com.cdq.countries.country;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CountriesClientPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(CountriesClientConfiguration.class)
            .withPropertyValues("countries.api-base-url=https://api.restcountries.com/countries/v5");

    @Test
    void failsContextBindingWhenCountriesApiKeyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("apiKey")
                    .hasMessageNotContaining("Authorization: Bearer");
        });
    }

    @Test
    void failsContextBindingWhenCountriesApiKeyIsBlank() {
        contextRunner
                .withPropertyValues("countries.api-key=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("apiKey")
                            .hasMessageNotContaining("Authorization: Bearer");
                });
    }
}
