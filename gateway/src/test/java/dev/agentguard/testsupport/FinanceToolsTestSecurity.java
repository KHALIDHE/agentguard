package dev.agentguard.testsupport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Only used when the finance tools run inside the gateway's test JVM.
 *
 * The gateway brings Spring Security onto the classpath, and Spring Boot
 * would then put HTTP Basic login in front of the finance tools, which they
 * never have in production. This chain switches that off again; access stays
 * guarded by the service's own ServiceTokenFilter, exactly as in production.
 *
 * Lives outside dev.agentguard.gateway so the gateway's component scan never
 * picks it up.
 */
@Configuration(proxyBeanMethods = false)
public class FinanceToolsTestSecurity {

    @Bean
    SecurityFilterChain financeToolsOpenChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .build();
    }
}
