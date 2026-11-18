package com.intechcore.polarion.extension.hotreload.inject;

import com.polarion.platform.core.PlatformContext;
import com.polarion.platform.jobs.IJobService;
import com.polarion.platform.jobs.IJobUnitFactory;
import com.polarion.platform.jobs.IJobUnitRepository;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The job unit factories of {@code com.polarion.platform.jobs.configuration}. Polarion registers them in the job
 * unit repository at startup, and the scheduler looks a factory up by name each time a job starts. So a factory
 * of the new revision takes effect with the next run; a running job keeps its old unit.
 */
final class JobUnitFactories {

    private final IJobUnitRepository repository;
    private final List<String> log;

    JobUnitFactories(@NotNull IJobUnitRepository repository, @NotNull List<String> log) {
        this.repository = repository;
        this.log = log;
    }

    @NotNull
    static JobUnitFactories lookup(@NotNull List<String> log) {
        return new JobUnitFactories(PlatformContext.getPlatform().lookupService(IJobService.class).getJobUnitRepository(), log);
    }

    void remove(@NotNull Set<String> names) {
        List<String> removed = new ArrayList<>();
        for (String name : names) {
            if (repository.getAvailableFactoryNames().contains(name)) {
                repository.unregisterJobUnitFactory(name);
                removed.add(name);
            }
        }
        if (!removed.isEmpty()) {
            log.add("job unit factories: removed " + removed);
        }
    }

    /**
     * @param factories the factory class names by job name
     * @param newInstance creates an object of a class of the new revision
     */
    void add(@NotNull Map<String, String> factories, @NotNull Function<String, Object> newInstance) {
        if (factories.isEmpty()) {
            return;
        }
        factories.forEach((name, className) -> repository.registerJobUnitFactory(name, (IJobUnitFactory) newInstance.apply(className)));
        log.add("job unit factories: added " + factories.keySet());
    }
}
