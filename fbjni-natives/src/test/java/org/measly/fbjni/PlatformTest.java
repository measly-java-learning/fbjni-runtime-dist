package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class PlatformTest {
    @ParameterizedTest
    @CsvSource({
        "Linux, amd64, linux-x86_64",
        "Linux, x86_64, linux-x86_64",
        "Linux, aarch64, linux-aarch64",
        "Linux, arm64, linux-aarch64",
        "Mac OS X, aarch64, macos",
        "Mac OS X, x86_64, macos",
    })
    void mapsSupportedPlatforms(String osName, String osArch, String expected) {
        assertEquals(expected, Platform.resourceDir(osName, osArch));
    }

    @ParameterizedTest
    @CsvSource({
        "Windows 11, amd64",
        "Linux, riscv64",
        "FreeBSD, amd64",
    })
    void rejectsUnsupportedPlatformsWithOverrideHint(String osName, String osArch) {
        UnsatisfiedLinkError e = assertThrows(UnsatisfiedLinkError.class, () -> Platform.resourceDir(osName, osArch));
        assertTrue(e.getMessage().contains(osName + " " + osArch), e.getMessage());
        assertTrue(e.getMessage().contains("-Dfbjni.shim.library="), e.getMessage());
    }

    @Test
    void fileNames() {
        assertEquals("libfbjni.so", Platform.fileName(Platform.LINUX_X86_64));
        assertEquals("libfbjni.so", Platform.fileName(Platform.LINUX_AARCH64));
        assertEquals("libfbjni.dylib", Platform.fileName(Platform.MACOS));
    }
}
