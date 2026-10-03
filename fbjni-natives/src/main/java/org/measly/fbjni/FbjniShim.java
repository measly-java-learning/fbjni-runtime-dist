package org.measly.fbjni;

import com.facebook.soloader.nativeloader.NativeLoader;

/**
 * Loads the libfbjni bundled in this jar for fbjni's Java classes. Call {@link #init()} once
 * before using any {@code com.facebook.jni} class.
 *
 * <p>The library is extracted once per version to a per-user cache: {@code -Dfbjni.shim.cachedir},
 * else {@code FBJNI_SHIM_CACHE_DIR}, else {@code $XDG_CACHE_HOME/fbjni-shim} or
 * {@code ~/.cache/fbjni-shim} on Linux and {@code ~/Library/Caches/fbjni-shim} on macOS, else
 * {@code java.io.tmpdir}. To skip extraction, set {@code -Dfbjni.shim.library=/path/to/libfbjni},
 * {@code -Dfbjni.shim.system=true} to use {@code java.library.path}, or
 * {@code -Dfbjni.shim.static=true} when fbjni is linked into the app's own JNI library.
 */
public final class FbjniShim {
    static {
        NativeLoader.initIfUninitialized(new FbjniShimDelegate(ShimEnvironment.system()));
    }

    private FbjniShim() {}

    /**
     * Installs the shim as SoLoader's {@code NativeLoader} delegate, unless the app already
     * installed one; such apps must load libfbjni themselves. Safe to call more than once.
     */
    public static void init() {
        // The static initializer does the work, exactly once
    }
}
