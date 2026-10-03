package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.facebook.jni.HybridData;
import com.facebook.soloader.nativeloader.NativeLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryPropertySmokeTest {
    @TempDir
    static Path dir;

    @Test
    void loadsTheNamedFileWithoutExtracting() throws Exception {
        BundledLibrary bundled = BundledLibrary.forPlatform(
                Platform.resourceDir(System.getProperty("os.name"), System.getProperty("os.arch")));
        Path library = dir.resolve(bundled.fileName());
        try (InputStream in = bundled.open()) {
            Files.copy(in, library);
        }
        Path cache = dir.resolve("cache");
        System.setProperty("fbjni.shim.library", library.toString());
        System.setProperty("fbjni.shim.cachedir", cache.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        assertEquals(library.toAbsolutePath().toString(), NativeLoader.getLibraryPath("fbjni"));
        assertFalse(Files.exists(cache), "nothing should be extracted");
    }
}
