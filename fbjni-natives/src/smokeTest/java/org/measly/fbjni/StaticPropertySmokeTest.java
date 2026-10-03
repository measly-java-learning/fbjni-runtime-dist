package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.facebook.jni.HybridData;
import org.junit.jupiter.api.Test;

/** In static mode the app's own JNI library provides fbjni, so the shim must load nothing. */
class StaticPropertySmokeTest {
    @Test
    void loadsNothing() {
        System.setProperty("fbjni.shim.static", "true");
        FbjniShim.init();
        HybridData data = new HybridData();
        assertThrows(UnsatisfiedLinkError.class, data::resetNative);
    }
}
