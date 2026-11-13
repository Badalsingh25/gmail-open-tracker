package dev.badalsingh.tracker.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigChecksTest {
    private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String GOOD_KEY = "a-long-random-api-key-123";

    @Test
    void developmentDefaultsAreFlagged() {
        var problems = ConfigChecks.problems("all", ConfigChecks.DEFAULT_API_KEY, List.of(ConfigChecks.DEFAULT_SIGNING_SECRET), "http://localhost:8080");
        assertEquals(3, problems.size());
    }

    @Test
    void properProductionConfigHasNoProblems() {
        assertTrue(ConfigChecks.problems("all", GOOD_KEY, List.of(GOOD_SECRET), "https://track.example.com").isEmpty());
    }

    @Test
    void ingestModeDoesNotNeedAnApiKey() {
        assertTrue(ConfigChecks.problems("ingest", ConfigChecks.DEFAULT_API_KEY, List.of(GOOD_SECRET), "https://track.example.com").isEmpty());
    }

    @Test
    void shortSecretsAndUnknownModesAreFlagged() {
        assertEquals(1, ConfigChecks.problems("ingest", null, List.of("too-short-secret-1234"), "https://track.example.com").size());
        assertFalse(ConfigChecks.problems("sideways", GOOD_KEY, List.of(GOOD_SECRET), "https://track.example.com").isEmpty());
    }

    @Test
    void plainHttpOrLocalhostPublicUrlIsFlagged() {
        assertFalse(ConfigChecks.problems("ingest", GOOD_KEY, List.of(GOOD_SECRET), "http://track.example.com").isEmpty());
        assertFalse(ConfigChecks.problems("ingest", GOOD_KEY, List.of(GOOD_SECRET), "https://localhost:8080").isEmpty());
    }
}
