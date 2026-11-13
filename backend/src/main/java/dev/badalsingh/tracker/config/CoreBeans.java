package dev.badalsingh.tracker.config;

import dev.badalsingh.tracker.core.OpenScorer;
import dev.badalsingh.tracker.core.ScoringConfig;
import dev.badalsingh.tracker.core.TokenSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/** Beans needed in every mode. */
@Configuration
public class CoreBeans {
    @Bean
    TokenSigner tokenSigner(@Value("${tracker.signing-secrets}") String secrets) {
        return new TokenSigner(splitSecrets(secrets));
    }

    @Bean
    OpenScorer openScorer(@Value("${tracker.scoring.early-seconds:30}") int earlySeconds,
                          @Value("${tracker.scoring.settle-seconds:300}") int settleSeconds,
                          @Value("${tracker.scoring.burst-millis:2000}") long burstMillis,
                          @Value("${tracker.scoring.opened-threshold:70}") int openedThreshold,
                          @Value("${tracker.scoring.possible-threshold:35}") int possibleThreshold,
                          @Value("${tracker.scoring.noise-floor:15}") int noiseFloor,
                          @Value("${tracker.scoring.repeat-gap-seconds:3600}") int repeatGapSeconds) {
        return new OpenScorer(new ScoringConfig(earlySeconds, settleSeconds, burstMillis,
                openedThreshold, possibleThreshold, noiseFloor, repeatGapSeconds));
    }

    public static List<String> splitSecrets(String secrets) {
        return Arrays.stream(secrets == null ? new String[0] : secrets.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
