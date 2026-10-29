package dev.interviewcoach.identity;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

final class RandomAccountPassword {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomAccountPassword() {
    }

    static String generate() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
