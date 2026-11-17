package com.intechcore.polarion.extension.hotreload.inject;

import com.intechcore.polarion.extension.hotreload.internal.Reflect;
import com.polarion.alm.tracker.ITrackerService;
import com.polarion.alm.tracker.internal.workflow.ContributionHolder;
import com.polarion.alm.tracker.internal.workflow.ContributionsManager;
import com.polarion.platform.core.IPlatform;
import com.polarion.platform.core.PlatformContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Runs against the real ContributionsManager of Polarion, so the field names the injector reads are checked too.
 */
class WorkflowHoldersTest {

    static class OldCondition {
    }

    static class NewCondition {
    }

    static class OldFunction {
    }

    static class OldValidator {
    }

    private final List<String> log = new ArrayList<>();
    private final ContributionsManager manager = new ContributionsManager();
    private MockedStatic<PlatformContext> platformContext;
    private ContributionHolder condition;
    private ContributionHolder polarionFunction;

    @BeforeEach
    void setUp() {
        ITrackerService trackerService = mock(ITrackerService.class, Answers.RETURNS_DEEP_STUBS);
        when(trackerService.getWorkflowManager().getContributionsManager()).thenReturn(manager);
        IPlatform platform = mock(IPlatform.class);
        when(platform.lookupService(ITrackerService.class)).thenReturn(trackerService);
        platformContext = mockStatic(PlatformContext.class);
        platformContext.when(PlatformContext::getPlatform).thenReturn(platform);

        condition = holder("myCondition", OldCondition.class, "conditions");
        holder("myFunction", OldFunction.class, "functions");
        holder("myValidator", OldValidator.class, "validators");
        // A class of the JDK has no class loader of its own, like Polarion's classes have another one than the extension.
        polarionFunction = holder("polarionFunction", String.class, "functions");
    }

    @AfterEach
    void tearDown() {
        platformContext.close();
    }

    @Test
    void pointsTheHoldersOfEveryKindToTheNewRevision() throws Exception {
        Map<String, Map<String, Object>> conditionsBefore = raw("conditions");
        ContributionInjector injector = new ContributionInjector(log);
        Bundle bundle = mock(Bundle.class);
        when(bundle.getSymbolicName()).thenReturn("my.ext");
        when(bundle.loadClass(OldCondition.class.getName())).thenAnswer(invocation -> NewCondition.class);
        when(bundle.loadClass(OldValidator.class.getName())).thenThrow(new ClassNotFoundException("gone"));

        injector.collectWorkflowHolders("my.ext", OldCondition.class.getClassLoader());
        injector.repointWorkflowHolders(bundle);
        injector.finish();

        assertThat(condition.getClazz()).isEqualTo(NewCondition.class);
        assertThat(all("contributions")).containsOnlyKeys("myCondition", "polarionFunction");
        assertThat(all("conditions")).containsEntry("myCondition", condition);
        assertThat(all("validators")).isEmpty();
        // Removed although its class exists: addWorkflowFunctions creates it again from the new descriptor.
        assertThat(all("functions")).containsOnlyKeys("polarionFunction");
        assertThat(polarionFunction.getClazz()).isEqualTo(String.class);
        assertThat(flatten(conditionsBefore)).containsEntry("myCondition", condition);
        assertThat(log).hasSize(2).first().isEqualTo("workflow contributions: class replaced [myCondition]");
        assertThat(log.get(1)).startsWith("workflow contributions: removed [").contains("myFunction", "myValidator");
    }

    @Test
    void removesTheHoldersNoNewRevisionClaims() {
        Map<String, Map<String, Object>> functionsBefore = raw("functions");
        ContributionInjector injector = new ContributionInjector(log);

        injector.collectWorkflowHolders("my.ext", OldCondition.class.getClassLoader());
        injector.finish();

        assertThat(all("contributions")).containsOnlyKeys("polarionFunction");
        assertThat(all("conditions")).isEmpty();
        assertThat(all("validators")).isEmpty();
        assertThat(all("functions")).containsOnlyKeys("polarionFunction");
        // Replaced, not changed in place: a request that holds the old map keeps a consistent view.
        assertThat(flatten(functionsBefore)).containsOnlyKeys("myFunction", "polarionFunction");
        assertThat(log).singleElement().asString()
                .startsWith("workflow contributions: removed [")
                .contains("myCondition", "myFunction");
    }

    @Test
    void doesNothingWithoutAnOldClassLoader() {
        ContributionInjector injector = new ContributionInjector(log);

        injector.collectWorkflowHolders("my.ext", null);
        injector.finish();

        assertThat(all("functions")).containsOnlyKeys("myFunction", "polarionFunction");
        assertThat(log).isEmpty();
    }

    private ContributionHolder holder(String name, Class<?> clazz, String kind) {
        ContributionHolder holder = new ContributionHolder();
        holder.setName(name);
        holder.setClazz(clazz);
        Reflect.call(Reflect.getField(manager, "contributions"), "add", name, holder);
        Reflect.call(Reflect.getField(manager, kind), "add", name, holder);
        return holder;
    }

    /**
     * @return the holders of one map over all prototypes
     */
    private Map<String, Object> all(String kind) {
        return flatten(raw(kind));
    }

    /**
     * @return the map object the manager holds now, not a copy
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> raw(String kind) {
        return (Map<String, Map<String, Object>>) Reflect.getField(Reflect.getField(manager, kind), "contributions");
    }

    private static Map<String, Object> flatten(Map<String, Map<String, Object>> byPrototype) {
        Map<String, Object> result = new HashMap<>();
        byPrototype.values().forEach(result::putAll);
        return result;
    }
}
