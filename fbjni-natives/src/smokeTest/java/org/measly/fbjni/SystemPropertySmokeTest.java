package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.facebook.jni.HybridData;
import com.facebook.soloader.nativeloader.NativeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The build points java.library.path at the host's extracted library. */
class SystemPropertySmokeTest {
    @TempDir
    static Path dir;

    @Test
    void loadsFromJavaLibraryPath() throws Exception {
        Path cache = dir.resolve("cache");
        System.setProperty("fbjni.shim.system", "true");
        System.setProperty("fbjni.shim.cachedir", cache.toString());
        FbjniShim.init();
        new HybridData().resetNative();
        assertNull(NativeLoader.getLibraryPath("fbjni"));
        assertFalse(Files.exists(cache), "nothing should be extracted");
    }
}
