package dev.interviewcoach.identity;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("!bootstrap")
final class PasswordHashes {

    private static final String ENCODING_ID = "pbkdf2-sha256-v1";
    private final PasswordEncoder encoder;

    PasswordHashes() {
        var pbkdf2 = new Pbkdf2PasswordEncoder("", 16, 310_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        pbkdf2.setEncodeHashAsBase64(true);
        this.encoder = new DelegatingPasswordEncoder(ENCODING_ID, Map.of(ENCODING_ID, pbkdf2));
    }

    String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
