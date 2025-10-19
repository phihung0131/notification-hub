package org.example.tenantservice;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Integration test that starts the Spring context - disabled for fast unit test runs")
@SpringBootTest
class TenantServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
