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
        assertEquals(17, Runtime.version().feature(), "smoke tests run on the oldest supported JDK");
        System.setProperty("fbjni.shim.cachedir", cacheDir.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        Path loaded = Path.of(NativeLoader.getLibraryPath("fbjni"));
        assertTrue(loaded.startsWith(cacheDir), loaded + " is not under " + cacheDir);
        assertTrue(Files.isRegularFile(loaded));
    }
}
