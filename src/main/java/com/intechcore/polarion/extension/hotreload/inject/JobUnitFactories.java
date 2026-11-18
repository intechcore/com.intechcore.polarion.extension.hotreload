package com.intechcore.polarion.extension.hotreload.inject;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import com.polarion.platform.jobs.IJobUnitFactory;
import com.polarion.platform.jobs.IJobUnitRepository;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The job unit factories of {@code com.polarion.platform.jobs.configuration}. Polarion registers them in the job
 * unit repository at startup, and the scheduler looks a factory up by name each time a job starts. So a factory
 * of the new revision takes effect with the next run; a running job keeps its old unit.
 * <p>
 * Old factories stay registered until the new revision is in: a job whose new factory needs a restart, or whose
 * bundle did not come back, keeps running with the old one.
 */
final class JobUnitFactories {

    private final Supplier<IJobUnitRepository> repository;
    private final List<String> log;
    /** The job names of the old revisions, by bundle symbolic name. */
    private final Map<String, Set<String>> oldNames = new LinkedHashMap<>();

    JobUnitFactories(@NotNull Supplier<IJobUnitRepository> repository, @NotNull List<String> log) {
        this.repository = repository;
        this.log = log;
    }

    void collect(@NotNull String symbolicName, @NotNull ExtensionDescriptor old) {
        Set<String> names = allNames(old);
        if (!names.isEmpty()) {
            oldNames.computeIfAbsent(symbolicName, k -> new LinkedHashSet<>()).addAll(names);
        }
    }

    /**
     * Registers the factories of the new revision and removes the old ones it no longer declares.
     *
     * @param newInstance creates an object of a class of the new revision
     */
    void replace(@NotNull String symbolicName, @NotNull ExtensionDescriptor descriptor, @NotNull Function<String, Object> newInstance) {
        Map<String, String> factories = descriptor.getJobUnitFactories();
        if (!factories.isEmpty()) {
            factories.forEach((name, className) -> repository.get().registerJobUnitFactory(name, (IJobUnitFactory) newInstance.apply(className)));
            log.add("job unit factories: added " + factories.keySet());
        }
        Set<String> dropped = new LinkedHashSet<>(oldNames.getOrDefault(symbolicName, Set.of()));
        oldNames.remove(symbolicName);
        dropped.removeAll(allNames(descriptor));
        unregister(dropped);
    }

    /**
     * Removes the factories of the bundles that are gone; those of the unfinished bundles stay.
     */
    void finish(@NotNull Set<String> unfinished) {
        Set<String> gone = new LinkedHashSet<>();
        oldNames.forEach((symbolicName, names) -> {
            if (!unfinished.contains(symbolicName)) {
                gone.addAll(names);
            }
        });
        oldNames.clear();
        unregister(gone);
    }

    private void unregister(@NotNull Set<String> names) {
        if (names.isEmpty()) {
            return;
        }
        List<String> removed = new ArrayList<>();
        for (String name : names) {
            if (repository.get().getAvailableFactoryNames().contains(name)) {
                repository.get().unregisterJobUnitFactory(name);
                removed.add(name);
            }
        }
        if (!removed.isEmpty()) {
            log.add("job unit factories: removed " + removed);
        }
    }

    @NotNull
    private static Set<String> allNames(@NotNull ExtensionDescriptor descriptor) {
        Set<String> names = new LinkedHashSet<>(descriptor.getJobUnitFactories().keySet());
        names.addAll(descriptor.getJobsNeedingRestart());
        return names;
    }
}
