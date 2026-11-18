package com.intechcore.polarion.extension.hotreload.descriptor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the contributions of one extension bundle the way HiveMind and the Tomcat plugin would.
 * Only the configuration points the team extensions use are supported; anything else becomes a warning.
 */
public final class DescriptorParser {

    private static final Set<String> SUPPORTED_POINTS = Set.of(
            ExtensionDescriptor.ADMINISTRATION_PAGE_EXTENDERS,
            ExtensionDescriptor.WORKFLOW_FUNCTIONS,
            ExtensionDescriptor.RICH_PAGE_WIDGETS,
            ExtensionDescriptor.RICH_PAGE_VELOCITY_CONTEXT,
            ExtensionDescriptor.WIKI_RENDERING_CONTEXT,
            ExtensionDescriptor.CUSTOM_NAVIGATION_EXTENDERS);

    private static final String INSTANCE_PREFIX = "instance:";

    private DescriptorParser() {
    }

    @NotNull
    public static ExtensionDescriptor parse(@Nullable InputStream pluginXml, @Nullable InputStream hivemoduleXml, @Nullable String guiceModulesHeader) {
        ExtensionDescriptor descriptor = new ExtensionDescriptor();
        if (pluginXml != null) {
            parsePluginXml(read(pluginXml), descriptor);
        }
        if (hivemoduleXml != null) {
            parseHivemodule(read(hivemoduleXml), descriptor);
        }
        if (guiceModulesHeader != null && !guiceModulesHeader.isBlank()) {
            descriptor.getRestartRequiredReasons().add("Guice-Modules header (" + guiceModulesHeader.trim() + "): the Guice injector is built once at startup");
        }
        return descriptor;
    }

    private static void parsePluginXml(@NotNull Document document, @NotNull ExtensionDescriptor descriptor) {
        for (Element extension : children(document.getDocumentElement(), "extension")) {
            String point = extension.getAttribute("point");
            if (!"com.polarion.portal.tomcat.webapps".equals(point)) {
                descriptor.getWarnings().add("plugin.xml extension point " + point + " is not refreshed");
                continue;
            }
            for (Element webapp : children(extension, "webapp")) {
                descriptor.getWebapps().add(new ExtensionDescriptor.Webapp(
                        webapp.getAttribute("name"), webapp.getAttribute("contextRoot"), webapp.hasAttribute("errorReporter")));
            }
        }
    }

    private static void parseHivemodule(@NotNull Document document, @NotNull ExtensionDescriptor descriptor) {
        Element module = document.getDocumentElement();
        String moduleId = module.getAttribute("id");

        Map<String, String> serviceClasses = new HashMap<>();
        for (Element servicePoint : children(module, "service-point")) {
            Element construct = firstDescendant(servicePoint, "construct");
            if (construct != null) {
                serviceClasses.put(servicePoint.getAttribute("id"), construct.getAttribute("class"));
                serviceClasses.put(moduleId + "." + servicePoint.getAttribute("id"), construct.getAttribute("class"));
            }
        }

        for (Element implementation : children(module, "implementation")) {
            descriptor.getRestartRequiredReasons().add("<implementation service-id=\"" + implementation.getAttribute("service-id")
                    + "\">: HiveMind service implementations and interceptors are built once at startup");
        }

        for (Element contribution : children(module, "contribution")) {
            String point = contribution.getAttribute("configuration-id");
            if (!SUPPORTED_POINTS.contains(point)) {
                descriptor.getRestartRequiredReasons().add("contribution to " + point + " is not supported");
                continue;
            }
            switch (point) {
                case ExtensionDescriptor.ADMINISTRATION_PAGE_EXTENDERS -> children(contribution, "extender")
                        .forEach(e -> descriptor.getAdministrationPages().add(attributes(e)));
                case ExtensionDescriptor.WORKFLOW_FUNCTIONS -> children(contribution, "function")
                        .forEach(e -> descriptor.getWorkflowFunctions().add(workflowFunction(e)));
                case ExtensionDescriptor.RICH_PAGE_WIDGETS -> children(contribution, "widget")
                        .forEach(e -> descriptor.getWidgets().put(e.getAttribute("id"), e.getAttribute("instance")));
                case ExtensionDescriptor.RICH_PAGE_VELOCITY_CONTEXT -> children(contribution, "item")
                        .forEach(e -> contextItem(e, point, descriptor.getVelocityContext(), descriptor));
                case ExtensionDescriptor.WIKI_RENDERING_CONTEXT -> children(contribution, "item")
                        .forEach(e -> contextItem(e, point, descriptor.getRenderingContext(), descriptor));
                case ExtensionDescriptor.CUSTOM_NAVIGATION_EXTENDERS -> children(contribution, "extenders")
                        .forEach(e -> {
                            String serviceId = e.getAttribute("extender");
                            String className = serviceClasses.get(serviceId);
                            if (className == null) {
                                descriptor.getWarnings().add("navigation extender service " + serviceId + " has no <construct class>, skipped");
                            } else {
                                descriptor.getNavigationExtenderClasses().add(className);
                            }
                        });
                default -> throw new IllegalStateException(point);
            }
        }
    }

    /**
     * Only an {@code instance:} value can be created again from the new revision. Any other value keeps the object
     * built at startup.
     */
    private static void contextItem(@NotNull Element item, @NotNull String point, @NotNull Map<String, String> context,
                                    @NotNull ExtensionDescriptor descriptor) {
        String key = item.getAttribute("key");
        String value = item.getAttribute("value");
        context.put(key, value);
        if (!value.startsWith(INSTANCE_PREFIX)) {
            descriptor.getRestartRequiredReasons().add(point + " item '" + key + "' (" + value + "): only instance: values are reloaded");
        }
    }

    @NotNull
    private static ExtensionDescriptor.WorkflowFunction workflowFunction(@NotNull Element function) {
        List<ExtensionDescriptor.WorkflowParameter> parameters = new ArrayList<>();
        for (Element parameter : children(function, "parameter")) {
            parameters.add(new ExtensionDescriptor.WorkflowParameter(
                    parameter.getAttribute("name"), parameter.getAttribute("id"), nullIfEmpty(parameter.getAttribute("description"))));
        }
        Element description = children(function, "description").stream().findFirst().orElse(null);
        return new ExtensionDescriptor.WorkflowFunction(
                function.getAttribute("name"),
                function.getAttribute("clazz"),
                function.hasAttribute("prototype") ? function.getAttribute("prototype") : null,
                Boolean.parseBoolean(function.getAttribute("hidden")),
                description != null ? description.getTextContent().trim() : null,
                parameters);
    }

    @NotNull
    private static Map<String, String> attributes(@NotNull Element element) {
        Map<String, String> result = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            result.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return result;
    }

    @NotNull
    private static List<Element> children(@NotNull Element parent, @NotNull String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    @Nullable
    private static Element firstDescendant(@NotNull Element parent, @NotNull String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    @Nullable
    private static String nullIfEmpty(@NotNull String value) {
        return value.isEmpty() ? null : value;
    }

    @NotNull
    private static Document read(@NotNull InputStream inputStream) {
        try (InputStream in = inputStream) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse descriptor: " + e.getMessage(), e);
        }
    }
}
