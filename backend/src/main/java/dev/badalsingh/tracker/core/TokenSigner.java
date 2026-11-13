package dev.badalsingh.tracker.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Issues and verifies signed tracking tokens: {@code <id:22><signature:16>} (38 URL-safe characters).
 *
 * <p>The id is 128 random bits; the signature is a truncated HMAC-SHA256 over the id. Verification is
 * stateless, so guessed, forged or enumerated tokens are rejected before any database access.
 *
 * <p>Limit: a signature proves a token was issued by this server. It cannot stop someone who has a
 * genuine URL (the recipient, or anyone the email is forwarded to) from requesting it again; the
 * scorer treats such traffic with suspicion instead.
 *
 * <p>Several secrets may be configured so a key can be rotated: the first signs, all verify.
 */
public final class TokenSigner {
    public static final int ID_LENGTH = 22;
    public static final int SIGNATURE_LENGTH = 16;
    public static final int TOKEN_LENGTH = ID_LENGTH + SIGNATURE_LENGTH;
    private static final int MIN_SECRET_LENGTH = 16;

    private final List<byte[]> keys;
    private final SecureRandom random = new SecureRandom();

    public TokenSigner(List<String> secrets) {
        if (secrets == null || secrets.isEmpty()) throw new IllegalArgumentException("At least one signing secret is required");
        for (String secret : secrets) {
            if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException("Signing secrets must be at least " + MIN_SECRET_LENGTH + " characters");
            }
        }
        this.keys = secrets.stream().map(s -> s.getBytes(StandardCharsets.UTF_8)).toList();
    }

    /** A fresh random public id (22 URL-safe characters). */
    public String newId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sign(String id) {
        if (id == null || id.length() != ID_LENGTH) throw new IllegalArgumentException("Invalid id");
        return id + mac(keys.get(0), id);
    }

    /** Returns the id when the token is well-formed and correctly signed by any configured key. */
    public Optional<String> verify(String token) {
        if (token == null || token.length() != TOKEN_LENGTH || !isUrlSafe(token)) return Optional.empty();
        String id = token.substring(0, ID_LENGTH);
        byte[] supplied = token.substring(ID_LENGTH).getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (byte[] key : keys) { // no early exit: keep timing independent of which key matched
            ok |= MessageDigest.isEqual(mac(key, id).getBytes(StandardCharsets.UTF_8), supplied);
        }
        return ok ? Optional.of(id) : Optional.empty();
    }

    private static boolean isUrlSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean valid = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!valid) return false;
        }
        return true;
    }

    private static String mac(byte[] key, String id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = mac.doFinal(("v1|" + id).getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[12]; // 96 bits -> exactly 16 base64url characters
            System.arraycopy(full, 0, truncated, 0, 12);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
