package dev.badalsingh.tracker.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenSignerTest {
    private final TokenSigner signer = new TokenSigner(List.of("primary-secret-0123456789"));

    @Test
    void signedTokenVerifiesAndReturnsId() {
        String id = signer.newId();
        String token = signer.sign(id);
        assertEquals(TokenSigner.TOKEN_LENGTH, token.length());
        assertEquals(id, signer.verify(token).orElseThrow());
    }

    @Test
    void tamperedIdOrSignatureIsRejected() {
        String token = signer.sign(signer.newId());
        char first = token.charAt(0);
        String badId = (first == 'A' ? 'B' : 'A') + token.substring(1);
        char last = token.charAt(token.length() - 1);
        String badSig = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertTrue(signer.verify(badId).isEmpty());
        assertTrue(signer.verify(badSig).isEmpty());
    }

    @Test
    void malformedTokensAreRejectedWithoutThrowing() {
        assertTrue(signer.verify(null).isEmpty());
        assertTrue(signer.verify("").isEmpty());
        assertTrue(signer.verify("short").isEmpty());
        assertTrue(signer.verify("x".repeat(38) + "!").isEmpty());
        assertTrue(signer.verify("!".repeat(38)).isEmpty());
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        TokenSigner other = new TokenSigner(List.of("a-completely-different-secret"));
        assertTrue(signer.verify(other.sign(other.newId())).isEmpty());
    }

    @Test
    void rotationAcceptsOldTokensButSignsWithNewKey() {
        TokenSigner old = new TokenSigner(List.of("old-secret-0123456789abc"));
        String oldToken = old.sign(old.newId());
        TokenSigner rotated = new TokenSigner(List.of("new-secret-0123456789abc", "old-secret-0123456789abc"));
        assertTrue(rotated.verify(oldToken).isPresent());
        assertTrue(old.verify(rotated.sign(rotated.newId())).isEmpty());
    }

    @Test
    void idsAreUnique() {
        assertNotEquals(signer.newId(), signer.newId());
        assertEquals(TokenSigner.ID_LENGTH, signer.newId().length());
    }

    @Test
    void weakSecretsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TokenSigner(List.of("short")));
        assertThrows(IllegalArgumentException.class, () -> new TokenSigner(List.of()));
    }
}
