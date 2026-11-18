package com.intechcore.polarion.extension.hotreload.inject;

import com.intechcore.polarion.extension.hotreload.internal.Reflect;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The internal maps of Polarion's ContributionsManager. One holder sits in {@code contributions} (used to create
 * instances) and in the map of its kind (listed in the workflow editor), so a change must cover all of them.
 */
final class WorkflowContributions {

    static final List<String> MAPS = List.of("contributions", "conditions", "functions", "validators", "signaturePolicies");

    private final Object manager;

    WorkflowContributions(@NotNull Object manager) {
        this.manager = manager;
    }

    /**
     * @return the distinct holders whose class matches, from every map
     */
    @NotNull
    Set<Object> holders(@NotNull Predicate<Class<?>> classMatches) {
        Set<Object> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : MAPS) {
            for (Map<String, Object> holders : byPrototype(name).values()) {
                for (Object holder : holders.values()) {
                    if (Reflect.call(holder, "getClazz") instanceof Class<?> clazz && classMatches.test(clazz)) {
                        result.add(holder);
                    }
                }
            }
        }
        return result;
    }

    /**
     * @return whether the holder is a workflow function, which the descriptor of its bundle declares in full
     */
    boolean isFunction(@NotNull Object holder) {
        return byPrototype("functions").values().stream().anyMatch(holders -> holders.containsValue(holder));
    }

    /**
     * Removes the holders from every map. Workflow requests read the maps at the same time, so each map is replaced
     * by a changed copy and never changed in place.
     *
     * @return the names of the removed holders
     */
    @NotNull
    Set<String> remove(@NotNull Set<Object> holders) {
        Set<String> removed = new LinkedHashSet<>();
        if (holders.isEmpty()) {
            return removed;
        }
        for (String name : MAPS) {
            Object contributions = Reflect.getField(manager, name);
            Map<String, Map<String, Object>> next = new HashMap<>();
            boolean changed = false;
            for (Map.Entry<String, Map<String, Object>> prototype : byPrototype(name).entrySet()) {
                Map<String, Object> kept = new HashMap<>(prototype.getValue());
                changed |= kept.entrySet().removeIf(e -> {
                    boolean match = holders.contains(e.getValue());
                    if (match) {
                        removed.add(e.getKey());
                    }
                    return match;
                });
                next.put(prototype.getKey(), kept);
            }
            if (changed) {
                Reflect.setField(contributions, "contributions", next);
            }
        }
        return removed;
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> byPrototype(@NotNull String name) {
        Object map = Reflect.getField(Reflect.getField(manager, name), "contributions");
        return map != null ? (Map<String, Map<String, Object>>) map : Map.of();
    }
}
