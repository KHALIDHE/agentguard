package dev.agentguard.financetools;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinanceToolsApplication {

    /**
     * The configuration file is finance-tools.yml, not application.yml, so
     * this service can run next to the gateway in one JVM (end-to-end tests)
     * without the two reading each other's settings.
     */
    public static final Map<String, Object> DEFAULTS = Map.of("spring.config.name", "finance-tools");

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FinanceToolsApplication.class);
        app.setDefaultProperties(DEFAULTS);
        app.run(args);
    }
}
