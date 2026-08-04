package com.cdq.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(classes = AssistantApplication.class)
class AssistantApplicationContextTest {

    @Test
    void startsApplicationContextWithoutExternalDependencies() {
    }
}
