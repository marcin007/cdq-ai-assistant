package com.cdq.countries;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = CountriesMcpServerApplication.class)
class CountriesMcpServerApplicationContextTest {

    @Test
    void startsApplicationContextWithoutExternalDependencies() {
    }
}
