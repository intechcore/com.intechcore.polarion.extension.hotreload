package com.intechcore.polarion.extension.hotreload.inject;

import com.polarion.platform.jobs.IJobDescriptor;
import com.polarion.platform.jobs.IJobUnit;
import com.polarion.platform.jobs.IJobUnitFactory;
import com.polarion.platform.jobs.internal.service.JobUnitRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Runs against the real job unit repository of Polarion.
 */
class JobUnitFactoriesTest {

    public static class NewFactory implements IJobUnitFactory {
        @Override
        public String getName() {
            return "new";
        }

        @Override
        public IJobDescriptor getJobDescriptor(IJobUnit unit) {
            return null;
        }

        @Override
        public IJobUnit createJobUnit(String name) {
            return null;
        }
    }

    private final JobUnitRepository repository = new JobUnitRepository();
    private final List<String> log = new ArrayList<>();

    @Test
    void replacesTheFactoriesOfTheOldRevision() {
        IJobUnitFactory old = mock(IJobUnitFactory.class);
        IJobUnitFactory polarion = mock(IJobUnitFactory.class);
        repository.registerJobUnitFactory("my.job", old);
        repository.registerJobUnitFactory("jobs.cleanup", polarion);
        JobUnitFactories factories = new JobUnitFactories(repository, log);

        factories.remove(Set.of("my.job", "my.never.registered"));

        assertThat(repository.getAvailableFactoryNames()).containsExactly("jobs.cleanup");

        Map<String, String> added = new LinkedHashMap<>();
        added.put("my.job", NewFactory.class.getName());
        factories.add(added, className -> new NewFactory());

        assertThat(repository.getJobUnitFactory("my.job")).isInstanceOf(NewFactory.class);
        assertThat(repository.getJobUnitFactory("jobs.cleanup")).isSameAs(polarion);
        assertThat(log).containsExactly("job unit factories: removed [my.job]", "job unit factories: added [my.job]");
    }

    @Test
    void doesNothingWithoutFactories() {
        JobUnitFactories factories = new JobUnitFactories(repository, log);

        factories.remove(Set.of());
        factories.add(Map.of(), className -> {
            throw new AssertionError("not called");
        });

        assertThat(log).isEmpty();
    }
}
