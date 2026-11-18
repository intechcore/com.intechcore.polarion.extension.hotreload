package com.intechcore.polarion.extension.hotreload.descriptor;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one extension bundle contributes through {@code plugin.xml} and {@code META-INF/hivemodule.xml}.
 */
public class ExtensionDescriptor {

    public static final String ADMINISTRATION_PAGE_EXTENDERS = "com.polarion.xray.webui.administrationPageExtenders";
    public static final String WORKFLOW_FUNCTIONS = "com.polarion.alm.tracker.workflow.functions";
    public static final String RICH_PAGE_WIDGETS = "com.polarion.alm.richpages.widgets";
    public static final String RICH_PAGE_VELOCITY_CONTEXT = "com.polarion.alm.richpages.velocityContext";
    public static final String WIKI_RENDERING_CONTEXT = "com.polarion.alm.wiki.renderingContext";
    public static final String CUSTOM_NAVIGATION_EXTENDERS = "com.polarion.xray.webui.customNavigationExtenders";
    public static final String JOB_UNIT_FACTORIES = "com.polarion.platform.jobs.configuration";

    public record Webapp(@NotNull String name, @NotNull String contextRoot, boolean hasErrorReporter) {
    }

    public record WorkflowParameter(String name, String id, String description) {
    }

    public record WorkflowFunction(@NotNull String name, @NotNull String className, String prototype, boolean hidden,
                                   String description, @NotNull List<WorkflowParameter> parameters) {
    }

    private final List<Webapp> webapps = new ArrayList<>();
    private final List<Map<String, String>> administrationPages = new ArrayList<>();
    private final List<WorkflowFunction> workflowFunctions = new ArrayList<>();
    private final Map<String, String> widgets = new LinkedHashMap<>();
    private final Map<String, String> velocityContext = new LinkedHashMap<>();
    private final Map<String, String> renderingContext = new LinkedHashMap<>();
    private final List<String> navigationExtenderClasses = new ArrayList<>();
    private final Map<String, String> jobUnitFactories = new LinkedHashMap<>();
    private final Set<String> jobsNeedingRestart = new LinkedHashSet<>();
    private final List<String> restartRequiredReasons = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public List<Webapp> getWebapps() {
        return webapps;
    }

    public List<Map<String, String>> getAdministrationPages() {
        return administrationPages;
    }

    public List<WorkflowFunction> getWorkflowFunctions() {
        return workflowFunctions;
    }

    public Map<String, String> getWidgets() {
        return widgets;
    }

    public Map<String, String> getVelocityContext() {
        return velocityContext;
    }

    public Map<String, String> getRenderingContext() {
        return renderingContext;
    }

    public List<String> getNavigationExtenderClasses() {
        return navigationExtenderClasses;
    }

    /**
     * @return the factory class names by job name
     */
    public Map<String, String> getJobUnitFactories() {
        return jobUnitFactories;
    }

    /**
     * @return the job names whose factory a rescan cannot build
     */
    public Set<String> getJobsNeedingRestart() {
        return jobsNeedingRestart;
    }

    public List<String> getRestartRequiredReasons() {
        return restartRequiredReasons;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
