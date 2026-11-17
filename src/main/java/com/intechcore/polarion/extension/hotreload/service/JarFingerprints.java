package com.intechcore.polarion.extension.hotreload.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content hashes of the jars the installed extension bundles came from.
 * <p>
 * A file time does not tell whether a jar changed: {@code cp -p}, {@code rsync -t} and reproducible builds keep an
 * old time on new content.
 */
public final class JarFingerprints {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Map<Long, String> FINGERPRINTS = new ConcurrentHashMap<>();

    private JarFingerprints() {
    }

    public static void put(@NotNull Bundle bundle, @NotNull Path jar) {
        FINGERPRINTS.put(bundle.getBundleId(), of(jar));
    }

    @Nullable
    public static String get(@NotNull Bundle bundle) {
        return FINGERPRINTS.get(bundle.getBundleId());
    }

    public static void remove(@NotNull Bundle bundle) {
        FINGERPRINTS.remove(bundle.getBundleId());
    }

    @NotNull
    public static String of(@NotNull Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot read " + jar, e);
        }
    }
}
