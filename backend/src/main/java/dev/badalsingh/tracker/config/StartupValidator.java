package dev.badalsingh.tracker.config;

import dev.badalsingh.tracker.core.ConfigChecks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** With TRACKER_STRICT=true (set it in production) weak or default settings stop the app from starting. */
@Component
public class StartupValidator {
    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    public StartupValidator(@Value("${tracker.strict:false}") boolean strict,
                            @Value("${tracker.mode:all}") String mode,
                            @Value("${tracker.api-key:}") String apiKey,
                            @Value("${tracker.signing-secrets:}") String signingSecrets,
                            @Value("${tracker.public-base-url:}") String publicBaseUrl) {
        List<String> problems = ConfigChecks.problems(mode, apiKey, CoreBeans.splitSecrets(signingSecrets), publicBaseUrl);
        if (problems.isEmpty()) return;
        if (strict) {
            throw new IllegalStateException("Refusing to start in strict mode: " + String.join("; ", problems));
        }
        problems.forEach(p -> log.warn("Configuration warning (fatal when tracker.strict=true): {}", p));
    }
}
