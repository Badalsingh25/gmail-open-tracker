package dev.badalsingh.tracker.core;

import java.util.ArrayList;
import java.util.List;

/** Pure configuration sanity checks, so weak production settings fail fast instead of silently running. */
public final class ConfigChecks {
    private ConfigChecks() {}

    public static final String DEFAULT_API_KEY = "dev-only-change-me";
    public static final String DEFAULT_SIGNING_SECRET = "dev-only-signing-secret-change-me";

    public static List<String> problems(String mode, String apiKey, List<String> signingSecrets, String publicBaseUrl) {
        List<String> problems = new ArrayList<>();
        boolean ingest = !"api".equals(mode);
        boolean api = !"ingest".equals(mode);

        if (!List.of("all", "ingest", "api").contains(mode)) problems.add("tracker.mode must be all, ingest or api (was '" + mode + "')");

        if (ingest || api) { // both modes issue/verify tokens
            if (signingSecrets == null || signingSecrets.isEmpty()) problems.add("tracker.signing-secrets is empty");
            else for (String s : signingSecrets) {
                if (DEFAULT_SIGNING_SECRET.equals(s)) problems.add("tracker.signing-secrets still uses the development default");
                else if (s.length() < 32) problems.add("each signing secret must be at least 32 characters");
            }
        }
        if (api) {
            if (apiKey == null || DEFAULT_API_KEY.equals(apiKey)) problems.add("tracker.api-key still uses the development default");
            else if (apiKey.length() < 20) problems.add("tracker.api-key must be at least 20 characters");
        }
        if (publicBaseUrl == null || !publicBaseUrl.startsWith("https://") || publicBaseUrl.contains("localhost")) {
            problems.add("tracker.public-base-url must be a public https:// URL, otherwise recipients' mail clients cannot load the pixel");
        }
        return problems;
    }
}
