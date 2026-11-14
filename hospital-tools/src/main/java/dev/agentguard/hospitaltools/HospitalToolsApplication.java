package dev.agentguard.hospitaltools;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HospitalToolsApplication {

    /**
     * The configuration file is hospital-tools.yml, not application.yml, so
     * this service can run next to the gateway in one JVM (end-to-end tests)
     * without the two reading each other's settings.
     */
    public static final Map<String, Object> DEFAULTS = Map.of("spring.config.name", "hospital-tools");

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HospitalToolsApplication.class);
        app.setDefaultProperties(DEFAULTS);
        app.run(args);
    }
}
