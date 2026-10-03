package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BundledLibraryTest {
    @ParameterizedTest
    @ValueSource(strings = {Platform.LINUX_X86_64, Platform.LINUX_AARCH64, Platform.MACOS})
    void manifestHashMatchesBundledBytes(String resourceDir) throws Exception {
        BundledLibrary library = BundledLibrary.forPlatform(resourceDir);
        assertEquals(Platform.fileName(resourceDir), library.fileName());
        assertTrue(library.version().matches("\\d+\\.\\d+\\.\\d+-\\d+"), library.version());
        try (InputStream in = library.open()) {
            assertEquals(library.sha256(), Sha256.hex(in));
        }
    }

    @Test
    void unknownPlatformIsALinkError() {
        assertThrows(UnsatisfiedLinkError.class, () -> BundledLibrary.forPlatform("windows-x86_64"));
    }

    @Test
    void sha256OfKnownInput() throws Exception {
        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        byte[] bytes = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(abc, Sha256.hex(bytes));
        assertEquals(abc, Sha256.hex(new ByteArrayInputStream(bytes)));
    }
}
