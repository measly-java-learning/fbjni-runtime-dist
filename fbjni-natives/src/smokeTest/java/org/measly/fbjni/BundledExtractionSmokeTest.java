package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.facebook.jni.HybridData;
import com.facebook.soloader.nativeloader.NativeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledExtractionSmokeTest {
    @TempDir
    static Path cacheDir;

    @Test
    void loadsTheBundledLibraryFromTheCache() throws Exception {
        System.out.println("smoke JVM: " + System.getProperty("java.vendor") + " " + System.getProperty("java.version")
                + " " + System.getProperty("os.arch"));
        assertEquals(Integer.getInteger("fbjni.smoke.javaVersion"), Runtime.version().feature(), "smoke-test JVM version");
        String expectedArch = System.getProperty("fbjni.smoke.osArch");
        if (expectedArch != null) {
            assertEquals(expectedArch, System.getProperty("os.arch"), "smoke-test JVM architecture");
        }
        System.setProperty("fbjni.shim.cachedir", cacheDir.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        Path loaded = Path.of(NativeLoader.getLibraryPath("fbjni"));
        assertTrue(loaded.startsWith(cacheDir), loaded + " is not under " + cacheDir);
        assertTrue(Files.isRegularFile(loaded));
    }
}
