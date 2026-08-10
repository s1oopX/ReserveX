package com.reservex.crypto;

import com.reservex.config.ReserveXProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IdCardCryptoTest {

    @Test
    void usesFreshIvAndStablePepperedHash() {
        ReserveXProperties props = new ReserveXProperties();
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        props.getAes().getKeys().put("aes-v1", Base64.getEncoder().encodeToString(key));
        props.getIdHash().setPepper("test-pepper-with-at-least-32-bytes");

        IdCardCipher cipher = new IdCardCipher(props);
        IdCardHasher hasher = new IdCardHasher(props);
        String idCard = "11010519491231002X";

        IdCardCipher.Encrypted first = cipher.encrypt(idCard);
        IdCardCipher.Encrypted second = cipher.encrypt(idCard);

        assertEquals(46, first.ciphertext().length);
        assertFalse(Arrays.equals(
                Arrays.copyOf(first.ciphertext(), 12),
                Arrays.copyOf(second.ciphertext(), 12)));
        assertEquals(idCard, cipher.decrypt(first.ciphertext(), first.keyId()));
        assertEquals(hasher.hash(idCard), hasher.hash(idCard));
    }
}
