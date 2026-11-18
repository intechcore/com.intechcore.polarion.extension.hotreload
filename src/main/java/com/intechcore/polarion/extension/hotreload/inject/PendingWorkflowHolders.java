package com.intechcore.polarion.extension.hotreload.inject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Workflow holders of the old revisions, by bundle symbolic name, waiting for the class of the new revision. It
 * outlives one rescan: the holders of a bundle that did not come back wait for the next one. The rescan lock of
 * RedeployService guards it.
 */
public final class PendingWorkflowHolders {

    private static final Map<String, Set<Object>> HOLDERS = new LinkedHashMap<>();

    private PendingWorkflowHolders() {
    }

    public static void add(@NotNull String symbolicName, @NotNull Set<Object> holders) {
        HOLDERS.computeIfAbsent(symbolicName, k -> Collections.newSetFromMap(new IdentityHashMap<>())).addAll(holders);
    }

    @Nullable
    static Set<Object> take(@NotNull String symbolicName) {
        return HOLDERS.remove(symbolicName);
    }

    /**
     * @return the holders of all bundles except the kept ones, which are no longer pending
     */
    @NotNull
    static Set<Object> takeAllExcept(@NotNull Set<String> kept) {
        Set<Object> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        HOLDERS.entrySet().removeIf(e -> {
            if (kept.contains(e.getKey())) {
                return false;
            }
            taken.addAll(e.getValue());
            return true;
        });
        return taken;
    }

    @NotNull
    public static Set<String> symbolicNames() {
        return new LinkedHashSet<>(HOLDERS.keySet());
    }

    public static void clear() {
        HOLDERS.clear();
    }
}
