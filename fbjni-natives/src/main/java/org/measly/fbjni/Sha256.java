package org.measly.fbjni;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lowercase hex SHA-256, the form used in fbjni-natives.properties. */
final class Sha256 {
    private Sha256() {}

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static String hex(InputStream in) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) != -1; ) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return hex(in);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("every JVM provides SHA-256", e);
        }
    }
}
