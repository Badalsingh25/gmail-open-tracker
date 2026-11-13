package dev.badalsingh.tracker.config;

import dev.badalsingh.tracker.core.RateLimiter;
import dev.badalsingh.tracker.core.SignalExtractor;
import dev.badalsingh.tracker.ingest.IpListHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans for the public ingest side (modes ingest and all). */
@Configuration
@ConditionalOnExpression("'${tracker.mode:all}' != 'api'")
public class IngestBeans {
    @Bean
    SignalExtractor signalExtractor(IpListHolder lists) {
        return new SignalExtractor(lists::apple, lists::google);
    }

    /** Per source address. Generous: Gmail's and Apple's proxies fetch for many users from shared addresses. */
    @Bean
    RateLimiter ingestRateLimiter(@Value("${tracker.rate-limit.burst:120}") int burst,
                                  @Value("${tracker.rate-limit.per-second:20}") double perSecond) {
        return new RateLimiter(burst, perSecond);
    }
}
