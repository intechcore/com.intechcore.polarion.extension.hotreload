package com.intechcore.polarion.extension.hotreload.inject;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import com.polarion.platform.jobs.IJobDescriptor;
import com.polarion.platform.jobs.IJobUnit;
import com.polarion.platform.jobs.IJobUnitFactory;
import com.polarion.platform.jobs.internal.service.JobUnitRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
    private final JobUnitFactories factories = new JobUnitFactories(() -> repository, log);
    private final IJobUnitFactory polarion = mock(IJobUnitFactory.class);

    @Test
    void replacesTheFactoriesAndRemovesTheDroppedOnes() {
        IJobUnitFactory old = register("my.job");
        register("my.dropped.job");
        repository.registerJobUnitFactory("jobs.cleanup", polarion);

        factories.collect("my.ext", descriptor("my.job", "my.dropped.job"));
        // The old factories work until the new revision is in.
        assertThat(repository.getJobUnitFactory("my.job")).isSameAs(old);

        factories.replace("my.ext", descriptor("my.job"), className -> new NewFactory());
        factories.finish(Set.of());

        assertThat(repository.getAvailableFactoryNames()).containsExactlyInAnyOrder("my.job", "jobs.cleanup");
        assertThat(repository.getJobUnitFactory("my.job")).isInstanceOf(NewFactory.class);
        assertThat(repository.getJobUnitFactory("jobs.cleanup")).isSameAs(polarion);
        assertThat(log).containsExactly("job unit factories: added [my.job]", "job unit factories: removed [my.dropped.job]");
    }

    @Test
    void keepsTheOldFactoryWhenTheNewOneNeedsARestart() {
        IJobUnitFactory old = register("my.job");
        ExtensionDescriptor configured = new ExtensionDescriptor();
        configured.getJobsNeedingRestart().add("my.job");

        factories.collect("my.ext", descriptor("my.job"));
        factories.replace("my.ext", configured, className -> {
            throw new AssertionError("not built");
        });
        factories.finish(Set.of());

        assertThat(repository.getJobUnitFactory("my.job")).isSameAs(old);
        assertThat(log).isEmpty();
    }

    @Test
    void removesTheFactoriesOfAnUninstalledBundleAndKeepsThoseOfAnUnfinishedOne() {
        register("my.gone.job");
        IJobUnitFactory failed = register("my.failed.job");

        factories.collect("my.gone", descriptor("my.gone.job"));
        factories.collect("my.failed", descriptor("my.failed.job"));
        factories.finish(Set.of("my.failed"));

        assertThat(repository.getAvailableFactoryNames()).containsExactly("my.failed.job");
        assertThat(repository.getJobUnitFactory("my.failed.job")).isSameAs(failed);
        assertThat(log).containsExactly("job unit factories: removed [my.gone.job]");
    }

    @Test
    void doesNotTouchTheRepositoryWithoutJobs() {
        JobUnitFactories noRepository = new JobUnitFactories(() -> {
            throw new AssertionError("not looked up");
        }, log);

        noRepository.collect("my.ext", new ExtensionDescriptor());
        noRepository.replace("my.ext", new ExtensionDescriptor(), className -> null);
        noRepository.finish(Set.of());

        assertThat(log).isEmpty();
    }

    private IJobUnitFactory register(String name) {
        IJobUnitFactory factory = mock(IJobUnitFactory.class);
        repository.registerJobUnitFactory(name, factory);
        return factory;
    }

    private static ExtensionDescriptor descriptor(String... jobs) {
        ExtensionDescriptor descriptor = new ExtensionDescriptor();
        for (String job : jobs) {
            descriptor.getJobUnitFactories().put(job, NewFactory.class.getName());
        }
        return descriptor;
    }
}
