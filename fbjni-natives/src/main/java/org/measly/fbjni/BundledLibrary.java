package org.measly.fbjni;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The libfbjni this jar carries for one platform, as recorded in fbjni-natives.properties. */
record BundledLibrary(String version, String resourceDir, String fileName, String sha256, Source source) {
    static final String MANIFEST = "/fbjni-natives/fbjni-natives.properties";

    /** Supplies the library's bytes; tests pass their own. */
    interface Source {
        InputStream open() throws IOException;
    }

    static BundledLibrary forPlatform(String resourceDir) {
        Properties manifest = new Properties();
        try (InputStream in = BundledLibrary.class.getResourceAsStream(MANIFEST)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("fbjni-natives: " + MANIFEST + " is missing from the jar");
            }
            manifest.load(in);
        } catch (IOException e) {
            throw linkError("fbjni-natives: cannot read " + MANIFEST, e);
        }
        String sha256 = manifest.getProperty("sha256." + resourceDir);
        if (sha256 == null) {
            throw new UnsatisfiedLinkError("fbjni-natives: the jar has no libfbjni for " + resourceDir);
        }
        String fileName = Platform.fileName(resourceDir);
        String path = "/fbjni-natives/" + resourceDir + "/" + fileName;
        return new BundledLibrary(manifest.getProperty("version"), resourceDir, fileName, sha256, () -> {
            InputStream in = BundledLibrary.class.getResourceAsStream(path);
            if (in == null) {
                throw new FileNotFoundException(path + " is missing from the jar");
            }
            return in;
        });
    }

    String resourcePath() {
        return "/fbjni-natives/" + resourceDir + "/" + fileName;
    }

    InputStream open() throws IOException {
        return source.open();
    }

    static UnsatisfiedLinkError linkError(String message, Throwable cause) {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
        error.initCause(cause);
        return error;
    }
}
