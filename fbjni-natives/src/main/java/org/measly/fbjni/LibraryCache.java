package org.measly.fbjni;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.attribute.PosixFilePermission.GROUP_WRITE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts the bundled library to a persistent per-user cache. Paths are content-addressed, files
 * appear only by atomic rename, and an existing file is never overwritten, because another process
 * may have it mapped.
 */
final class LibraryCache {
    static final String CACHE_DIR_PROPERTY = "fbjni.shim.cachedir";
    static final String CACHE_DIR_ENV = "FBJNI_SHIM_CACHE_DIR";
    private static final int MAX_COPIES = 16;

    /** A cache directory; {@code shared} marks one under java.io.tmpdir, which must be private. */
    record CacheRoot(Path dir, boolean shared) {}

    private LibraryCache() {}

    /** Cache roots to try, in order. An explicit property or variable is the only candidate. */
    static List<CacheRoot> candidates(ShimEnvironment env) {
        String explicit = nonEmpty(env.property().apply(CACHE_DIR_PROPERTY));
        if (explicit == null) {
            explicit = nonEmpty(env.env().apply(CACHE_DIR_ENV));
        }
        if (explicit != null) {
            return List.of(new CacheRoot(Path.of(explicit), false));
        }
        List<CacheRoot> roots = new ArrayList<>();
        Path platformDir = platformCacheDir(env);
        if (platformDir != null) {
            roots.add(new CacheRoot(platformDir, false));
        }
        String user = nonEmpty(env.property().apply("user.name"));
        String name = user == null ? "fbjni-shim" : "fbjni-shim-" + user;
        roots.add(new CacheRoot(Path.of(env.property().apply("java.io.tmpdir"), name), true));
        return roots;
    }

    private static Path platformCacheDir(ShimEnvironment env) {
        String home = nonEmpty(env.property().apply("user.home"));
        if (home == null || home.equals("?")) {
            return null;
        }
        if (env.property().apply("os.name").toLowerCase(Locale.ROOT).startsWith("mac")) {
            return Path.of(home, "Library", "Caches", "fbjni-shim");
        }
        String xdg = nonEmpty(env.env().apply("XDG_CACHE_HOME"));
        if (xdg != null && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg, "fbjni-shim");
        }
        return Path.of(home, ".cache", "fbjni-shim");
    }

    /** Installs into the first root that works; throws the first failure if none does. */
    static Path install(List<CacheRoot> roots, BundledLibrary library) throws IOException {
        IOException failure = null;
        for (CacheRoot root : roots) {
            try {
                return install(root, library);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        throw failure;
    }

    static Path install(CacheRoot root, BundledLibrary library) throws IOException {
        if (root.shared()) {
            try {
                Files.createDirectory(root.dir(),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException e) {
                // Someone made it first; requirePrivate decides whether we may use it
            }
            requirePrivate(root.dir(), currentUser(root.dir().getParent()));
        } else {
            Files.createDirectories(root.dir());
        }
        Path dir = root.dir()
                .resolve(library.version())
                .resolve(library.resourceDir())
                .resolve(library.sha256().substring(0, 16));
        for (int i = 0; i < MAX_COPIES; i++) {
            Path target = (i == 0 ? dir : dir.resolve("copy-" + i)).resolve(library.fileName());
            if (Files.exists(target)) {
                if (library.sha256().equals(Sha256.hex(target))) {
                    return target;
                }
                // Corrupt, but another process may have it mapped: leave it and extract beside it
                continue;
            }
            Files.createDirectories(target.getParent());
            return extract(library, target);
        }
        throw new IOException(dir + " holds " + MAX_COPIES + " copies of " + library.fileName()
                + " that fail verification");
    }

    private static Path extract(BundledLibrary library, Path target) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "." + library.fileName() + "-", ".tmp");
        try {
            try (InputStream in = library.open()) {
                Files.copy(in, temp, REPLACE_EXISTING);
            }
            String actual = Sha256.hex(temp);
            if (!actual.equals(library.sha256())) {
                throw new IOException(library.resourcePath() + " has SHA-256 " + actual
                        + ", but fbjni-natives.properties says " + library.sha256());
            }
            // A racing JVM may rename identical bytes onto the same name; rename replaces the
            // directory entry, so a copy already loaded elsewhere stays intact
            Files.move(temp, target, ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Refuses a directory that is a symlink, owned by someone else, or writable by others. */
    static void requirePrivate(Path dir, UserPrincipal expectedOwner) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException(dir + " is not a directory");
        }
        if (!attributes.owner().equals(expectedOwner)) {
            throw new IOException(dir + " is owned by " + attributes.owner().getName()
                    + ", not " + expectedOwner.getName());
        }
        if (attributes.permissions().contains(GROUP_WRITE) || attributes.permissions().contains(OTHERS_WRITE)) {
            throw new IOException(dir + " is writable by other users");
        }
    }

    /** The owner of a file this process creates, which works even without a passwd entry. */
    private static UserPrincipal currentUser(Path dir) throws IOException {
        Path probe = Files.createTempFile(dir, ".fbjni-shim-", ".probe");
        try {
            return Files.getOwner(probe);
        } finally {
            Files.delete(probe);
        }
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
