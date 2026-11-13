package dev.badalsingh.tracker.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.badalsingh.tracker.repo.TrackEventRepository;
import dev.badalsingh.tracker.repo.TrackedLinkRepository;
import dev.badalsingh.tracker.repo.TrackedMessageRepository;
import dev.badalsingh.tracker.repo.TrackedRecipientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Runs the whole product in one process (mode=all) on H2. Timing thresholds are zeroed so a request
 * made immediately after registration is scored like a late, real one.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tracker;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "tracker.mode=all",
        "tracker.api-key=test-key",
        "tracker.signing-secrets=test-signing-secret-0123456789abcdef",
        "tracker.public-base-url=https://track.example.test",
        "tracker.client-ip-header=remote",
        "tracker.ip-lists.enabled=false",
        "tracker.scoring.early-seconds=0",
        "tracker.scoring.settle-seconds=0"
})
@AutoConfigureMockMvc
class TrackerFlowIntegrationTest {
    private static final String BASE = "https://track.example.test";
    private static final String GMAIL_UA = "Mozilla/5.0 (Windows NT 5.1; rv:11.0) Gecko Firefox/11.0 (via ggpht.com GoogleImageProxy)";
    private static final String KEY = "X-Tracker-Key";
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private TrackEventRepository events;
    @Autowired private TrackedLinkRepository links;
    @Autowired private TrackedRecipientRepository recipients;
    @Autowired private TrackedMessageRepository messages;

    @BeforeEach
    void clean() {
        events.deleteAll();
        links.deleteAll();
        recipients.deleteAll();
        messages.deleteAll();
    }

    private JsonNode create(String requestId, String mode, String recipientsJson, String linksJson) throws Exception {
        String body = "{\"subject\":\"Hello\",\"clientRequestId\":\"" + requestId + "\",\"mode\":\"" + mode + "\","
                + "\"recipients\":" + recipientsJson + ",\"links\":" + linksJson + "}";
        String response = mvc.perform(post("/api/messages").header(KEY, "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static String path(String absoluteUrl) { return absoluteUrl.substring(BASE.length()); }

    @Test
    void eachRecipientHasTheirOwnPixelAndOpensAreAttributedToThem() throws Exception {
        JsonNode created = create("req-0001", "PER_RECIPIENT", "[\"alice@example.com\",\"bob@example.com\"]", "[]");
        JsonNode alice = created.get("recipients").get(0), bob = created.get("recipients").get(1);
        org.junit.jupiter.api.Assertions.assertNotEquals(alice.get("pixelUrl").asText(), bob.get("pixelUrl").asText());

        mvc.perform(get(path(alice.get("pixelUrl").asText())).header("User-Agent", GMAIL_UA))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_GIF))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        mvc.perform(get("/api/messages/recent").header(KEY, "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].recipients[0].address").value("alice@example.com"))
                .andExpect(jsonPath("$[0].recipients[0].status").value("OPENED"))
                .andExpect(jsonPath("$[0].recipients[1].status").value("NO_ACTIVITY"))
                .andExpect(jsonPath("$[0].status").value("OPENED"));
    }

    @Test
    void forgedOrUnknownTokensStillGetTheImageButRecordNothing() throws Exception {
        JsonNode created = create("req-0002", "PER_RECIPIENT", "[\"alice@example.com\"]", "[]");
        String real = path(created.get("recipients").get(0).get("pixelUrl").asText()); // /t/<38 chars>.gif
        char c = real.charAt(real.length() - 5);
        String forged = real.substring(0, real.length() - 5) + (c == 'A' ? 'B' : 'A') + ".gif";

        mvc.perform(get(forged)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_GIF));
        mvc.perform(get("/t/" + "x".repeat(38) + ".gif")).andExpect(status().isOk());
        mvc.perform(get("/t/short.gif")).andExpect(status().isOk());

        mvc.perform(get("/api/stats").header(KEY, "test-key"))
                .andExpect(jsonPath("$.totalEvents").value(0));
    }

    @Test
    void headRequestsAreTreatedAsAutomated() throws Exception {
        JsonNode created = create("req-0003", "PER_RECIPIENT", "[\"alice@example.com\"]", "[]");
        mvc.perform(head(path(created.get("recipients").get(0).get("pixelUrl").asText())).header("User-Agent", GMAIL_UA))
                .andExpect(status().isOk());
        mvc.perform(get("/api/messages/recent").header(KEY, "test-key"))
                .andExpect(jsonPath("$[0].recipients[0].status").value("AUTOMATED_ONLY"));
    }

    @Test
    void clickingATrackedLinkRedirectsAndMarksTheRecipientEngaged() throws Exception {
        JsonNode created = create("req-0004", "PER_RECIPIENT", "[\"alice@example.com\"]", "[\"https://example.com/offer?id=7\"]");
        String tracked = created.get("recipients").get(0).get("trackedLinks").get("https://example.com/offer?id=7").asText();

        mvc.perform(get(path(tracked)).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Chrome/120"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/offer?id=7"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        mvc.perform(get("/api/messages/recent").header(KEY, "test-key"))
                .andExpect(jsonPath("$[0].recipients[0].status").value("ENGAGED"))
                .andExpect(jsonPath("$[0].recipients[0].clickEvents").value(1));
    }

    @Test
    void aLinkTokenCannotBeUsedToReachAnotherMessagesLink() throws Exception {
        JsonNode one = create("req-0005", "PER_RECIPIENT", "[\"a@example.com\"]", "[\"https://example.com/one\"]");
        JsonNode two = create("req-0006", "PER_RECIPIENT", "[\"b@example.com\"]", "[\"https://example.com/two\"]");
        String tokenOfOne = path(one.get("recipients").get(0).get("pixelUrl").asText()).replace("/t/", "").replace(".gif", "");
        String linkIdOfTwo = path(two.get("recipients").get(0).get("trackedLinks").get("https://example.com/two").asText())
                .substring(path(two.get("recipients").get(0).get("trackedLinks").get("https://example.com/two").asText()).lastIndexOf('/') + 1);

        mvc.perform(get("/l/" + tokenOfOne + "/" + linkIdOfTwo)).andExpect(status().isNotFound());
        mvc.perform(get("/l/" + tokenOfOne + "/not-a-number")).andExpect(status().isNotFound());
        mvc.perform(get("/l/" + "x".repeat(38) + "/1")).andExpect(status().isNotFound());
    }

    @Test
    void onlyPlainHttpLinksAreAccepted() throws Exception {
        for (String bad : new String[]{"javascript:alert(1)", "data:text/html,hi", "ftp://example.com/x", "https://", "not a url"}) {
            mvc.perform(post("/api/messages").header(KEY, "test-key").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"clientRequestId\":\"bad-" + Math.abs(bad.hashCode()) + "\",\"recipients\":[\"a@example.com\"],\"links\":[\"" + bad + "\"]}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void sharedModeCreatesOneRecipientForTheWholeGroup() throws Exception {
        JsonNode created = create("req-0007", "SHARED", "[\"a@example.com\",\"b@example.com\"]", "[]");
        org.junit.jupiter.api.Assertions.assertEquals(1, created.get("recipients").size());
        org.junit.jupiter.api.Assertions.assertTrue(created.get("sharedCopy").asBoolean());
        org.junit.jupiter.api.Assertions.assertTrue(created.get("recipients").get(0).get("address").asText().contains("b@example.com"));
    }

    @Test
    void registrationIsIdempotent() throws Exception {
        JsonNode first = create("req-0008", "PER_RECIPIENT", "[\"a@example.com\"]", "[]");
        mvc.perform(post("/api/messages").header(KEY, "test-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"req-0008\",\"recipients\":[\"other@example.com\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.get("id").asLong()))
                .andExpect(jsonPath("$.recipients[0].address").value("a@example.com"))
                .andExpect(jsonPath("$.recipients[0].pixelUrl").value(first.get("recipients").get(0).get("pixelUrl").asText()));
        mvc.perform(get("/api/messages/recent").header(KEY, "test-key")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void deleteRemovesMessageRecipientsLinksAndEvents() throws Exception {
        JsonNode created = create("req-0009", "PER_RECIPIENT", "[\"a@example.com\"]", "[\"https://example.com/x\"]");
        mvc.perform(get(path(created.get("recipients").get(0).get("pixelUrl").asText())).header("User-Agent", GMAIL_UA)).andExpect(status().isOk());
        long recipientId = created.get("recipients").get(0).get("id").asLong();

        mvc.perform(delete("/api/messages/" + created.get("id").asLong()).header(KEY, "test-key")).andExpect(status().isNoContent());
        mvc.perform(get("/api/recipients/" + recipientId + "/events").header(KEY, "test-key")).andExpect(status().isNotFound());
        mvc.perform(get("/api/stats").header(KEY, "test-key"))
                .andExpect(jsonPath("$.trackedMessages").value(0))
                .andExpect(jsonPath("$.trackedRecipients").value(0))
                .andExpect(jsonPath("$.totalEvents").value(0));
    }

    @Test
    void searchFilterOpenedOnlyReturnsMessagesWithAnOpenedRecipient() throws Exception {
        JsonNode opened = create("req-0010", "PER_RECIPIENT", "[\"seen@example.com\"]", "[]");
        create("req-0011", "PER_RECIPIENT", "[\"unseen@example.com\"]", "[]");
        mvc.perform(get(path(opened.get("recipients").get(0).get("pixelUrl").asText())).header("User-Agent", GMAIL_UA)).andExpect(status().isOk());

        mvc.perform(get("/api/messages/search?filter=opened").header(KEY, "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].recipients[0].address").value("seen@example.com"));
        mvc.perform(get("/api/messages/search?filter=none").header(KEY, "test-key"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].recipients[0].address").value("unseen@example.com"));
        mvc.perform(get("/api/messages/search?filter=bogus").header(KEY, "test-key")).andExpect(status().isBadRequest());
    }

    @Test
    void dashboardApiNeedsTheKeyButTheIngestEndpointsAndHealthDoNot() throws Exception {
        mvc.perform(get("/api/messages/recent")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\"x\",\"recipients\":[\"a@example.com\"]}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("all"));
        mvc.perform(get("/t/" + "x".repeat(38) + ".gif")).andExpect(status().isOk());
    }
}
