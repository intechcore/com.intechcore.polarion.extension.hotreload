package com.intechcore.polarion.extension.hotreload.inject;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import com.intechcore.polarion.extension.hotreload.internal.Reflect;
import com.polarion.alm.tracker.ITrackerService;
import com.polarion.alm.tracker.workflow.config.IContribution;
import com.polarion.alm.tracker.workflow.IContributionsManager;
import com.polarion.platform.core.PlatformContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes HiveMind contributions straight into the Polarion singletons that copied them at startup.
 * <p>
 * Polarion never reads these configuration points again after startup: Guice copies them into its singletons, and
 * the HiveMind registry refuses new contributions once started. So the singletons are the only place to change.
 */
public class ContributionInjector {

    private static final String UI_BUNDLE = "com.polarion.alm.ui";
    private static final String WIKI_BUNDLE = "com.polarion.alm.wiki";
    private static final String GUICE_BUNDLE = "com.polarion.platform.guice";
    private static final String TRACKER_BUNDLE = "com.polarion.alm.tracker";
    private static final String INSTANCE_PREFIX = "instance:";

    private final List<String> log;

    public ContributionInjector(@NotNull List<String> log) {
        this.log = log;
    }

    /**
     * Removes what the old revision of a bundle contributed. Entries are matched by key, and object values also by
     * the class loader that loaded them, so stale objects go even when the new descriptor dropped their key.
     */
    public void remove(@NotNull ExtensionDescriptor descriptor, @Nullable ClassLoader oldClassLoader) {
        removeAdministrationPages(descriptor);
        removeWidgets(descriptor.getWidgets().keySet(), oldClassLoader);
        replaceVelocityContext(descriptor.getVelocityContext().keySet(), oldClassLoader, Map.of());
        replaceRenderingContext(descriptor.getRenderingContext().keySet(), oldClassLoader, Map.of());
        removeNavigationExtenders(oldClassLoader);
        removeWorkflowFunctions(oldClassLoader);
    }

    /**
     * Builds the lazily created HiveMind services this class reads, while every extension jar still exists.
     * HiveMind loads all contributed classes when it builds them: built later, after a jar was deleted, one class
     * resolves to {@code null} and ContributionsManager stops filling its functions half way (NPE in
     * {@code setFunctionsList}), losing Polarion's own functions too until restart.
     */
    public static void buildLazyServices() {
        ITrackerService trackerService = PlatformContext.getPlatform().lookupService(ITrackerService.class);
        trackerService.getWorkflowManager().getContributionsManager().getFunctions("WorkItem");
        Object provider = guiceInstance(UI_BUNDLE, "com.polarion.alm.portal.web.server.NavigationTopicProvider");
        for (Object extender : castMap(Reflect.getField(provider, "extenders")).values()) {
            Reflect.unwrapHiveMindProxy(extender);
        }
    }

    public void add(@NotNull ExtensionDescriptor descriptor, @NotNull Bundle bundle) {
        addAdministrationPages(descriptor.getAdministrationPages());
        addWidgets(descriptor.getWidgets(), bundle);
        replaceVelocityContext(Set.of(), null, instantiateValues(descriptor.getVelocityContext(), bundle));
        replaceRenderingContext(Set.of(), null, instantiateValues(descriptor.getRenderingContext(), bundle));
        addNavigationExtenders(descriptor.getNavigationExtenderClasses(), bundle);
        addWorkflowFunctions(descriptor.getWorkflowFunctions(), bundle);
    }

    // --- administration pages: AdministrationPageExtenderProvider (Guice singleton) ---

    /**
     * Removes pages by key, and also every page whose URL points into one of the bundle's web applications:
     * those pages cannot work once the context is gone.
     */
    private void removeAdministrationPages(@NotNull ExtensionDescriptor descriptor) {
        Set<String> keys = adminKeys(descriptor.getAdministrationPages());
        List<String> urlPrefixes = descriptor.getWebapps().stream().map(w -> "/" + w.name() + "/").toList();
        if (keys.isEmpty() && urlPrefixes.isEmpty()) {
            return;
        }
        Object provider = guiceInstance(UI_BUNDLE, "com.polarion.alm.administration.web.server.AdministrationPageExtenderProvider");
        List<Object> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Object extender : currentAdministrationPages(provider)) {
            Object pageUrl = Reflect.getField(extender, "pageUrl");
            boolean intoRemovedWebapp = pageUrl instanceof String url && urlPrefixes.stream().anyMatch(url::startsWith);
            if (keys.contains(adminKey(extender)) || intoRemovedWebapp) {
                removed.add(adminKey(extender));
            } else {
                kept.add(extender);
            }
        }
        if (!removed.isEmpty()) {
            Reflect.call(provider, "setAdministrationPageExtenders", new LinkedHashSet<>(kept));
            syncAdministrationPagesConfiguration(kept);
            log.add("administration pages: removed " + removed);
        }
    }

    private void addAdministrationPages(@NotNull List<Map<String, String>> pages) {
        if (pages.isEmpty()) {
            return;
        }
        Object provider = guiceInstance(UI_BUNDLE, "com.polarion.alm.administration.web.server.AdministrationPageExtenderProvider");
        Set<String> newKeys = adminKeys(pages);
        List<Object> result = new ArrayList<>();
        for (Object extender : currentAdministrationPages(provider)) {
            if (!newKeys.contains(adminKey(extender))) {
                result.add(extender);
            }
        }
        for (Map<String, String> page : pages) {
            result.add(newAdministrationPage(page));
        }
        Reflect.call(provider, "setAdministrationPageExtenders", new LinkedHashSet<>(result));
        syncAdministrationPagesConfiguration(result);
        log.add("administration pages: added " + newKeys);
    }

    /**
     * Mirrors the menu into the HiveMind configuration point it was copied from at startup. admin-menu-organizer
     * reads its declared order from there, and refuses or drops entries the registry does not know.
     * The provider holds the same instances as the registry, so entries are compared by identity:
     * known entries keep their declared position, new ones go to the end.
     */
    private void syncAdministrationPagesConfiguration(@NotNull List<Object> menu) {
        Object registry = Reflect.call(PlatformContext.getPlatform(), "getRegistry");
        Object infrastructure = Reflect.getField(registry, "_infrastructure");
        Object point = castMap(Reflect.getField(infrastructure, "_configurationPoints")).get(ExtensionDescriptor.ADMINISTRATION_PAGE_EXTENDERS);
        if (point == null) {
            log.add("WARNING HiveMind configuration " + ExtensionDescriptor.ADMINISTRATION_PAGE_EXTENDERS + " not found, not synchronized");
            return;
        }
        Reflect.call(point, "getElements");
        List<?> declared = (List<?>) Reflect.getField(point, "_elements");

        Set<Object> inMenu = Collections.newSetFromMap(new IdentityHashMap<>());
        inMenu.addAll(menu);
        Set<Object> inDeclared = Collections.newSetFromMap(new IdentityHashMap<>());
        inDeclared.addAll(declared);

        List<Object> next = new ArrayList<>();
        declared.stream().filter(inMenu::contains).forEach(next::add);
        menu.stream().filter(e -> !inDeclared.contains(e)).forEach(next::add);
        List<Object> unmodifiable = Collections.unmodifiableList(next);

        Reflect.setField(point, "_elements", unmodifiable);
        Object proxy = Reflect.getField(point, "_elementsProxy");
        if (proxy != null && Reflect.hasField(proxy, "_inner")) {
            Reflect.setField(proxy, "_inner", unmodifiable);
        }
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private static List<Object> currentAdministrationPages(@NotNull Object provider) {
        return new ArrayList<>((List<Object>) Reflect.getField(provider, "extenders"));
    }

    /**
     * Keys come from Polarion's own getters, which derive a missing id from the name and a missing parent node id
     * from the parent node name.
     */
    @NotNull
    private static Set<String> adminKeys(@NotNull List<Map<String, String>> pages) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, String> page : pages) {
            keys.add(adminKey(newAdministrationPage(page)));
        }
        return keys;
    }

    @NotNull
    private static Object newAdministrationPage(@NotNull Map<String, String> attributes) {
        Class<?> extenderClass = Reflect.loadClass(UI_BUNDLE, "com.polarion.alm.administration.web.server.AdministrationPageExtender");
        Object extender = Reflect.newInstance(extenderClass);
        attributes.forEach((name, value) -> setBeanProperty(extender, name, value));
        return extender;
    }

    @NotNull
    private static String adminKey(@NotNull Object extender) {
        return Reflect.call(extender, "getParentNodeId") + "/" + Reflect.call(extender, "getId");
    }

    private static void setBeanProperty(@NotNull Object bean, @NotNull String name, @NotNull String value) {
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        if (name.endsWith("Scope")) {
            Reflect.call(bean, setter, Boolean.parseBoolean(value));
        } else {
            Reflect.call(bean, setter, value);
        }
    }

    // --- rich page widgets: live map of the richPageWidgetContribution service ---

    private void removeWidgets(@NotNull Set<String> ids, @Nullable ClassLoader oldClassLoader) {
        Map<String, Object> widgets = widgetMap();
        List<String> removed = new ArrayList<>();
        for (String id : new ArrayList<>(widgets.keySet())) {
            if (ids.contains(id) || loadedBy(widgets.get(id), oldClassLoader)) {
                widgets.remove(id);
                removed.add(id);
            }
        }
        if (!removed.isEmpty()) {
            log.add("widgets: removed " + removed);
        }
    }

    private void addWidgets(@NotNull Map<String, String> widgets, @NotNull Bundle bundle) {
        if (widgets.isEmpty()) {
            return;
        }
        widgetMap().putAll(instantiateValues(widgets, bundle));
        log.add("widgets: added " + widgets.keySet());
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private static Map<String, Object> widgetMap() {
        Object registered = Reflect.call(widgetService(), "getRegisteredWidgets");
        return (Map<String, Object>) Reflect.getField(registered, "map");
    }

    @NotNull
    private static Object widgetService() {
        Class<?> type = Reflect.loadClass(UI_BUNDLE, "com.polarion.alm.server.rp.RichPageWidgetContribution");
        return PlatformContext.getPlatform().lookupService(type);
    }

    // --- velocity context of rich pages and wiki rendering context (Guice singletons) ---

    private void replaceVelocityContext(@NotNull Set<String> removeKeys, @Nullable ClassLoader oldClassLoader, @NotNull Map<String, Object> add) {
        Object provider = guiceInstance(UI_BUNDLE, "com.polarion.alm.shared.api.model.rp.internal.RichPageVelocityContextProviderImpl");
        Map<String, Object> current = castMap(Reflect.call(provider, "getRichPageVelocityContextMap"));
        Map<String, Object> next = replace(current, removeKeys, oldClassLoader, add);
        if (next != null) {
            Reflect.call(provider, "setRichPageVelocityContextMap", next);
            log.add("rich page velocity context: now " + next.keySet().size() + " entries, changed " + changedKeys(current, next));
        }
    }

    private void replaceRenderingContext(@NotNull Set<String> removeKeys, @Nullable ClassLoader oldClassLoader, @NotNull Map<String, Object> add) {
        Object wikiService = guiceInstance(WIKI_BUNDLE, "com.polarion.alm.wiki.internal.WikiService");
        Map<String, Object> current = castMap(Reflect.call(wikiService, "getWikiRenderingContextMap"));
        Map<String, Object> next = replace(current, removeKeys, oldClassLoader, add);
        if (next != null) {
            Reflect.call(wikiService, "setWikiRenderingContextMap", next);
            log.add("wiki rendering context: now " + next.keySet().size() + " entries, changed " + changedKeys(current, next));
        }
    }

    /**
     * @return the new map, or {@code null} when nothing changes
     */
    @Nullable
    private static Map<String, Object> replace(@NotNull Map<String, Object> current, @NotNull Set<String> removeKeys,
                                               @Nullable ClassLoader oldClassLoader, @NotNull Map<String, Object> add) {
        Map<String, Object> next = new HashMap<>(current);
        boolean changed = next.keySet().removeIf(k -> removeKeys.contains(k) || loadedBy(current.get(k), oldClassLoader));
        if (!add.isEmpty()) {
            next.putAll(add);
            changed = true;
        }
        return changed ? next : null;
    }

    @NotNull
    private static Set<String> changedKeys(@NotNull Map<String, Object> before, @NotNull Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>();
        before.forEach((k, v) -> {
            if (after.get(k) != v) {
                keys.add(k);
            }
        });
        after.forEach((k, v) -> {
            if (before.get(k) != v) {
                keys.add(k);
            }
        });
        return keys;
    }

    // --- navigation extenders: NavigationTopicProvider (Guice singleton) ---

    private void removeNavigationExtenders(@Nullable ClassLoader oldClassLoader) {
        if (oldClassLoader == null) {
            return;
        }
        Object provider = guiceInstance(UI_BUNDLE, "com.polarion.alm.portal.web.server.NavigationTopicProvider");
        Map<String, Object> extenders = castMap(Reflect.getField(provider, "extenders"));
        List<String> removed = new ArrayList<>();
        extenders.entrySet().removeIf(e -> {
            // Extenders contributed with translator="service" are HiveMind proxies of a generated class.
            boolean stale = loadedBy(Reflect.unwrapHiveMindProxy(e.getValue()), oldClassLoader);
            if (stale) {
                removed.add(e.getKey());
            }
            return stale;
        });
        if (!removed.isEmpty()) {
            log.add("navigation extenders: removed " + removed);
        }
    }

    private void addNavigationExtenders(@NotNull List<String> classNames, @NotNull Bundle bundle) {
        if (classNames.isEmpty()) {
            return;
        }
        Object provider = guiceInstance(UI_BUNDLE, "com.polarion.alm.portal.web.server.NavigationTopicProvider");
        Class<?> wrapperClass = Reflect.loadClass(UI_BUNDLE, "com.polarion.alm.portal.web.server.NavigationExtenderWrapper");
        Set<Object> wrappers = new LinkedHashSet<>();
        for (String className : classNames) {
            Object wrapper = Reflect.newInstance(wrapperClass);
            Reflect.call(wrapper, "setExtender", Reflect.newInstance(loadClass(bundle, className)));
            wrappers.add(wrapper);
        }
        Reflect.call(provider, "setExtenders", wrappers);
        log.add("navigation extenders: added " + classNames);
    }

    // --- workflow functions: ContributionsManager creates a new instance per call ---

    /**
     * {@code getFunctions} returns a copy, so removal goes to the two internal maps of the real ContributionsManager:
     * {@code contributions} (used to create instances) and {@code functions} (listed in the workflow editor).
     */
    private void removeWorkflowFunctions(@Nullable ClassLoader oldClassLoader) {
        if (oldClassLoader == null) {
            return;
        }
        ITrackerService trackerService = PlatformContext.getPlatform().lookupService(ITrackerService.class);
        Object manager = Reflect.unwrapHiveMindProxy(trackerService.getWorkflowManager().getContributionsManager());
        if (!manager.getClass().getName().endsWith(".ContributionsManager")) {
            log.add("WARNING workflow functions not removed: unexpected manager " + manager.getClass().getName());
            return;
        }
        Set<String> removed = new LinkedHashSet<>();
        for (String field : List.of("contributions", "functions")) {
            Map<String, Map<String, Object>> byPrototype = castNestedMap(Reflect.getField(Reflect.getField(manager, field), "contributions"));
            for (Map<String, Object> holders : byPrototype.values()) {
                holders.entrySet().removeIf(e -> {
                    Object clazz = Reflect.call(e.getValue(), "getClazz");
                    boolean stale = clazz instanceof Class<?> c && c.getClassLoader() == oldClassLoader;
                    if (stale) {
                        removed.add(e.getKey());
                    }
                    return stale;
                });
            }
        }
        if (!removed.isEmpty()) {
            log.add("workflow functions: removed " + removed);
        }
    }

    private void addWorkflowFunctions(@NotNull List<ExtensionDescriptor.WorkflowFunction> functions, @NotNull Bundle bundle) {
        if (functions.isEmpty()) {
            return;
        }
        ITrackerService trackerService = PlatformContext.getPlatform().lookupService(ITrackerService.class);
        IContributionsManager manager = trackerService.getWorkflowManager().getContributionsManager();
        Class<?> holderClass = Reflect.loadClass(TRACKER_BUNDLE, "com.polarion.alm.tracker.internal.workflow.ContributionHolder");
        Class<?> parameterClass = Reflect.loadClass(TRACKER_BUNDLE, "com.polarion.alm.tracker.internal.workflow.Parameter");

        for (ExtensionDescriptor.WorkflowFunction function : functions) {
            Class<?> functionClass = loadClass(bundle, function.className());
            String prototype = function.prototype() != null ? function.prototype() : "WorkItem";
            IContribution existing = manager.getFunctions(prototype).get(function.name());
            if (existing != null) {
                Reflect.call(existing, "setClazz", functionClass);
                log.add("workflow function '" + function.name() + "': class replaced");
                continue;
            }
            Object holder = Reflect.newInstance(holderClass);
            Reflect.call(holder, "setName", function.name());
            Reflect.call(holder, "setClazz", functionClass);
            Reflect.call(holder, "setHidden", function.hidden());
            if (function.prototype() != null) {
                Reflect.call(holder, "setPrototype", function.prototype());
            }
            if (function.description() != null) {
                Reflect.call(holder, "setDescription", function.description());
            }
            for (ExtensionDescriptor.WorkflowParameter parameter : function.parameters()) {
                Object p = Reflect.newInstance(parameterClass);
                Reflect.call(p, "setName", parameter.name());
                Reflect.call(p, "setId", parameter.id());
                if (parameter.description() != null) {
                    Reflect.call(p, "setDescription", parameter.description());
                }
                Reflect.call(holder, "addParameter", p);
            }
            Map<String, Object> toAdd = new HashMap<>();
            toAdd.put(function.name(), holder);
            Reflect.call(manager, "addFunctions", toAdd);
            log.add("workflow function '" + function.name() + "': added");
        }
    }

    // --- helpers ---

    @NotNull
    private static Object guiceInstance(@NotNull String bundleSymbolicName, @NotNull String className) {
        Class<?> guicePlatform = Reflect.loadClass(GUICE_BUNDLE, "com.polarion.platform.guice.ipi.GuicePlatform");
        Object injector = Reflect.callStatic(guicePlatform, "getGlobalInjector");
        // Every consumer used here is bound in(Singleton.class), so this returns the live instance, not a new one.
        return Reflect.call(injector, "getInstance", Reflect.loadClass(bundleSymbolicName, className));
    }

    @NotNull
    private Map<String, Object> instantiateValues(@NotNull Map<String, String> values, @NotNull Bundle bundle) {
        Map<String, Object> result = new HashMap<>();
        values.forEach((key, value) -> {
            if (value.startsWith(INSTANCE_PREFIX)) {
                result.put(key, Reflect.newInstance(loadClass(bundle, value.substring(INSTANCE_PREFIX.length()))));
            } else {
                log.add("WARNING: '" + key + "' = '" + value + "' skipped, only instance: values are supported");
            }
        });
        return result;
    }

    @NotNull
    private static Class<?> loadClass(@NotNull Bundle bundle, @NotNull String className) {
        try {
            return bundle.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class " + className + " not found in " + bundle.getSymbolicName(), e);
        }
    }

    private static boolean loadedBy(@Nullable Object value, @Nullable ClassLoader classLoader) {
        return value != null && classLoader != null && value.getClass().getClassLoader() == classLoader;
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> castNestedMap(@Nullable Object map) {
        return map != null ? (Map<String, Map<String, Object>>) map : Map.of();
    }

    @NotNull
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(@Nullable Object map) {
        return map != null ? (Map<String, Object>) map : Map.of();
    }
}
