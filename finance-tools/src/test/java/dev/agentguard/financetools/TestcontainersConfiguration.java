package dev.agentguard.financetools;

import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/** A real Oracle Free for tests. @ServiceConnection wires url, user and password into Spring. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    OracleContainer oracleFreeContainer() {
        // Each project gets its own user, so a reused container is never shared
        // with another project's Flyway history.
        return new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withUsername("finance")
            .withPassword("finance")
            .withStartupTimeout(Duration.ofMinutes(5))
            .withReuse(true);
    }
}
