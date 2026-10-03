package org.measly.fbjni;

import java.util.Locale;

/** Maps the JVM's {@code os.name} and {@code os.arch} to the jar's resource directory for libfbjni. */
final class Platform {
    static final String LINUX_X86_64 = "linux-x86_64";
    static final String LINUX_AARCH64 = "linux-aarch64";
    static final String MACOS = "macos";

    private Platform() {}

    static String resourceDir(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            // One universal2 dylib covers arm64 and x86_64, including x86_64 JVMs under Rosetta
            return MACOS;
        }
        if (os.equals("linux")) {
            switch (osArch) {
                case "amd64":
                case "x86_64":
                    return LINUX_X86_64;
                case "aarch64":
                case "arm64":
                    return LINUX_AARCH64;
                default:
                    break;
            }
        }
        throw new UnsatisfiedLinkError("fbjni-natives has no libfbjni for " + osName + " " + osArch
                + "; it supports Linux x86_64 and aarch64, and macOS arm64 and x86_64."
                + " Provide the library with -Dfbjni.shim.library=/path/to/libfbjni,"
                + " -Dfbjni.shim.system=true or -Dfbjni.shim.static=true.");
    }

    static String fileName(String resourceDir) {
        return resourceDir.equals(MACOS) ? "libfbjni.dylib" : "libfbjni.so";
    }
}
