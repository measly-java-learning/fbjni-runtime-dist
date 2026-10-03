package org.measly.fbjni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryCacheTest {
    private static final byte[] BYTES = "not really a library".getBytes(StandardCharsets.US_ASCII);
    private static final String SHA = Sha256.hex(BYTES);

    @TempDir
    Path tmp;

    private final AtomicInteger opens = new AtomicInteger();

    private BundledLibrary library(byte[] bytes, String sha256) {
        return new BundledLibrary("0.8.1-1", Platform.LINUX_X86_64, "libfbjni.so", sha256, () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        });
    }

    private Path expectedDir(Path root) {
        return root.resolve("0.8.1-1").resolve("linux-x86_64").resolve(SHA.substring(0, 16));
    }

    private static Map<String, String> linuxProperties(String home) {
        return Map.of("os.name", "Linux", "user.home", home, "user.name", "alice", "java.io.tmpdir", "/tmp");
    }

    @Test
    void cacheDirPropertyBeatsEnvironment() {
        Map<String, String> props = new java.util.HashMap<>(linuxProperties("/home/alice"));
        props.put(LibraryCache.CACHE_DIR_PROPERTY, "/from/property");
        ShimEnvironment env = ShimEnvironment.of(props, Map.of(LibraryCache.CACHE_DIR_ENV, "/from/env"));
        assertEquals(List.of(new LibraryCache.CacheRoot(Path.of("/from/property"), false)), LibraryCache.candidates(env));
    }

    @Test
    void cacheDirEnvironmentUsedWithoutProperty() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("/home/alice"), Map.of(LibraryCache.CACHE_DIR_ENV, "/from/env"));
        assertEquals(List.of(new LibraryCache.CacheRoot(Path.of("/from/env"), false)), LibraryCache.candidates(env));
    }

    @Test
    void linuxUsesXdgCacheHomeThenTmp() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("/home/alice"), Map.of("XDG_CACHE_HOME", "/xdg"));
        assertEquals(List.of(
                new LibraryCache.CacheRoot(Path.of("/xdg/fbjni-shim"), false),
                new LibraryCache.CacheRoot(Path.of("/tmp/fbjni-shim-alice"), true)),
                LibraryCache.candidates(env));
    }

    @Test
    void linuxDefaultsToDotCache() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("/home/alice"), Map.of());
        assertEquals(Path.of("/home/alice/.cache/fbjni-shim"), LibraryCache.candidates(env).get(0).dir());
    }

    @Test
    void macosUsesLibraryCaches() {
        ShimEnvironment env = ShimEnvironment.of(
                Map.of("os.name", "Mac OS X", "user.home", "/Users/alice", "user.name", "alice", "java.io.tmpdir", "/tmp"),
                Map.of("XDG_CACHE_HOME", "/xdg"));
        assertEquals(Path.of("/Users/alice/Library/Caches/fbjni-shim"), LibraryCache.candidates(env).get(0).dir());
    }

    @Test
    void missingHomeUsesOnlyTmp() {
        ShimEnvironment env = ShimEnvironment.of(linuxProperties("?"), Map.of());
        assertEquals(List.of(new LibraryCache.CacheRoot(Path.of("/tmp/fbjni-shim-alice"), true)), LibraryCache.candidates(env));
    }

    @Test
    void installExtractsAVerifiedCopy() throws IOException {
        Path installed = LibraryCache.install(new LibraryCache.CacheRoot(tmp, false), library(BYTES, SHA));
        assertEquals(expectedDir(tmp).resolve("libfbjni.so"), installed);
        assertArrayEquals(BYTES, Files.readAllBytes(installed));
        try (Stream<Path> files = Files.list(expectedDir(tmp))) {
            assertEquals(List.of(installed), files.toList(), "no temp files left behind");
        }
    }

    @Test
    void installReusesAGoodCopyWithoutReextracting() throws IOException {
        LibraryCache.CacheRoot root = new LibraryCache.CacheRoot(tmp, false);
        Path first = LibraryCache.install(root, library(BYTES, SHA));
        Path second = LibraryCache.install(root, library(BYTES, SHA));
        assertEquals(first, second);
        assertEquals(1, opens.get());
    }

    @Test
    void installLeavesACorruptCopyAndExtractsBesideIt() throws IOException {
        Path corrupt = expectedDir(tmp).resolve("libfbjni.so");
        Files.createDirectories(corrupt.getParent());
        Files.write(corrupt, new byte[] {1, 2, 3});
        Path installed = LibraryCache.install(new LibraryCache.CacheRoot(tmp, false), library(BYTES, SHA));
        assertEquals(expectedDir(tmp).resolve("copy-1").resolve("libfbjni.so"), installed);
        assertArrayEquals(BYTES, Files.readAllBytes(installed));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(corrupt), "never overwrite an existing file");
    }

    @Test
    void installRejectsBytesThatDoNotMatchTheManifest() throws IOException {
        byte[] other = "tampered".getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class,
                () -> LibraryCache.install(new LibraryCache.CacheRoot(tmp, false), library(other, SHA)));
        try (Stream<Path> files = Files.list(expectedDir(tmp))) {
            assertEquals(0, files.count(), "a failed extraction leaves nothing behind");
        }
    }

    @Test
    void installFallsBackToTheNextRoot() throws IOException {
        Path notADirectory = Files.writeString(tmp.resolve("file"), "x");
        Path fallback = tmp.resolve("fallback");
        Path installed = LibraryCache.install(List.of(
                new LibraryCache.CacheRoot(notADirectory.resolve("cache"), false),
                new LibraryCache.CacheRoot(fallback, false)), library(BYTES, SHA));
        assertEquals(expectedDir(fallback).resolve("libfbjni.so"), installed);
    }

    @Test
    void sharedRootIsCreatedPrivate() throws IOException {
        Path shared = tmp.resolve("fbjni-shim-alice");
        LibraryCache.install(new LibraryCache.CacheRoot(shared, true), library(BYTES, SHA));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)));
    }

    @Test
    void requirePrivateRejectsAnotherOwner() throws IOException {
        UserPrincipal root = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("root");
        // As root (for example in a container) the temp dir is root's own, so root is not another owner
        assumeFalse(root.equals(Files.getOwner(tmp)), "running as root");
        assertThrows(IOException.class, () -> LibraryCache.requirePrivate(tmp, root));
    }

    @Test
    void requirePrivateRejectsAGroupWritableDirectory() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("open"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, () -> LibraryCache.requirePrivate(dir, Files.getOwner(dir)));
    }

    @Test
    void requirePrivateRejectsASymlink() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("target"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), target);
        assertThrows(IOException.class, () -> LibraryCache.requirePrivate(link, Files.getOwner(target)));
    }
}
