package com.intechcore.polarion.extension.hotreload.service;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Descriptors of the installed extension bundles, read while their jars still exist.
 * <p>
 * Once a jar is deleted, {@code Bundle.getEntry} of the old revision returns {@code null} without an error, so an
 * uninstall would otherwise find nothing to take out.
 */
public final class DescriptorCache {

    private static final Map<Long, ExtensionDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    private DescriptorCache() {
    }

    public static void put(@NotNull Bundle bundle, @NotNull ExtensionDescriptor descriptor) {
        DESCRIPTORS.put(bundle.getBundleId(), descriptor);
    }

    @Nullable
    public static ExtensionDescriptor get(@NotNull Bundle bundle) {
        return DESCRIPTORS.get(bundle.getBundleId());
    }

    public static void remove(@NotNull Bundle bundle) {
        DESCRIPTORS.remove(bundle.getBundleId());
    }
}
