package org.measly.fbjni;

import java.util.Map;
import java.util.function.Function;

/** Where the shim reads system properties and environment variables; tests pass maps instead. */
record ShimEnvironment(Function<String, String> property, Function<String, String> env) {
    static ShimEnvironment system() {
        return new ShimEnvironment(System::getProperty, System::getenv);
    }

    static ShimEnvironment of(Map<String, String> properties, Map<String, String> env) {
        return new ShimEnvironment(properties::get, env::get);
    }
}
