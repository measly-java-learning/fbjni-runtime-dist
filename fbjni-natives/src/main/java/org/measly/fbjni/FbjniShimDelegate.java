package org.measly.fbjni;

import com.facebook.soloader.nativeloader.NativeLoaderDelegate;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Answers fbjni's {@code NativeLoader.loadLibrary("fbjni")}. In priority order: load the file
 * named by {@code fbjni.shim.library}; with {@code fbjni.shim.system=true}, use
 * {@code System.loadLibrary}; with {@code fbjni.shim.static=true}, load nothing; otherwise extract
 * the bundled library to the cache and load it. Other names go to {@code System.loadLibrary}.
 */
final class FbjniShimDelegate implements NativeLoaderDelegate {
    static final String LIBRARY_PROPERTY = "fbjni.shim.library";
    static final String SYSTEM_PROPERTY = "fbjni.shim.system";
    static final String STATIC_PROPERTY = "fbjni.shim.static";

    private final ShimEnvironment env;
    private boolean fbjniLoaded;
    private String fbjniPath;

    FbjniShimDelegate(ShimEnvironment env) {
        this.env = env;
    }

    @Override
    public boolean loadLibrary(String shortName, int flags) {
        if (!"fbjni".equals(shortName)) {
            System.loadLibrary(shortName);
            return true;
        }
        loadFbjni();
        return true;
    }

    private synchronized void loadFbjni() {
        if (fbjniLoaded) {
            return;
        }
        String library = env.property().apply(LIBRARY_PROPERTY);
        if (library != null && !library.isEmpty()) {
            String path = Path.of(library).toAbsolutePath().toString();
            System.load(path);
            fbjniPath = path;
        } else if (Boolean.parseBoolean(env.property().apply(SYSTEM_PROPERTY))) {
            System.loadLibrary("fbjni");
        } else if (Boolean.parseBoolean(env.property().apply(STATIC_PROPERTY))) {
            // The app linked fbjni into its own JNI library, whose JNI_OnLoad registers fbjni's natives
        } else {
            BundledLibrary bundled = BundledLibrary.forPlatform(
                    Platform.resourceDir(env.property().apply("os.name"), env.property().apply("os.arch")));
            Path path;
            try {
                path = LibraryCache.install(LibraryCache.candidates(env), bundled);
            } catch (IOException e) {
                throw BundledLibrary.linkError("fbjni-natives: cannot extract " + bundled.fileName()
                        + " to a cache directory; set -D" + LibraryCache.CACHE_DIR_PROPERTY + " or "
                        + LibraryCache.CACHE_DIR_ENV + " to a writable directory", e);
            }
            System.load(path.toString());
            fbjniPath = path.toString();
        }
        fbjniLoaded = true;
    }

    @Override
    public synchronized String getLibraryPath(String libName) {
        return "fbjni".equals(libName) ? fbjniPath : null;
    }

    @Override
    public int getSoSourcesVersion() {
        return 0;
    }
}
