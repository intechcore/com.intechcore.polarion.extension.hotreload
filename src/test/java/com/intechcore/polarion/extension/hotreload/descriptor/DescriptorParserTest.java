package com.intechcore.polarion.extension.hotreload.descriptor;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorParserTest {

    private static InputStream xml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyInputGivesAnEmptyDescriptor() {
        ExtensionDescriptor descriptor = DescriptorParser.parse(null, null, null);

        assertThat(descriptor.getWebapps()).isEmpty();
        assertThat(descriptor.getAdministrationPages()).isEmpty();
        assertThat(descriptor.getRestartRequiredReasons()).isEmpty();
        assertThat(descriptor.getWarnings()).isEmpty();
    }

    @Test
    void readsWebappsAndWarnsAboutOtherPluginXmlPoints() {
        String pluginXml = """
                <plugin>
                    <extension point="com.polarion.portal.tomcat.webapps">
                        <webapp name="polarion/a" contextRoot="webapp/a"/>
                        <webapp name="polarion/b" contextRoot="webapp/b" errorReporter="x"/>
                    </extension>
                    <extension point="other.point"/>
                    <extension point="com.polarion.portal.tomcat.externalJsFile">
                        <file location="external-js/loader.js"/>
                    </extension>
                </plugin>
                """;

        ExtensionDescriptor descriptor = DescriptorParser.parse(xml(pluginXml), null, null);

        assertThat(descriptor.getWebapps()).containsExactly(
                new ExtensionDescriptor.Webapp("polarion/a", "webapp/a", false),
                new ExtensionDescriptor.Webapp("polarion/b", "webapp/b", true));
        assertThat(descriptor.getWarnings()).containsExactly(
                "plugin.xml extension point other.point is not refreshed",
                "plugin.xml extension point com.polarion.portal.tomcat.externalJsFile: the new files are served, but browsers"
                        + " that loaded an old file keep it until their cache is cleared, also after a restart");
    }

    @Test
    void readsEverySupportedContribution() {
        String hivemodule = """
                <module id="my.module" version="1.0.0">
                    <service-point id="nav" interface="x.Nav">
                        <invoke-factory><construct class="x.NavImpl"/></invoke-factory>
                    </service-point>
                    <contribution configuration-id="com.polarion.xray.webui.administrationPageExtenders">
                        <extender id="page" name="Page" parentNodeId="node"/>
                    </contribution>
                    <contribution configuration-id="com.polarion.alm.tracker.workflow.functions">
                        <function name="fn" clazz="x.Fn" hidden="true">
                            <description> Does things </description>
                            <parameter name="p" id="pid" description="param"/>
                            <parameter name="q" id="qid"/>
                        </function>
                        <function name="proto" clazz="x.Proto" prototype="Condition"/>
                    </contribution>
                    <contribution configuration-id="com.polarion.alm.richpages.widgets">
                        <widget id="w" instance="instance:x.Widget"/>
                    </contribution>
                    <contribution configuration-id="com.polarion.alm.richpages.velocityContext">
                        <item key="vk" value="instance:x.Velocity"/>
                    </contribution>
                    <contribution configuration-id="com.polarion.alm.wiki.renderingContext">
                        <item key="rk" value="instance:x.Rendering"/>
                    </contribution>
                    <contribution configuration-id="com.polarion.xray.webui.customNavigationExtenders">
                        <extenders extender="my.module.nav"/>
                        <extenders extender="missing"/>
                    </contribution>
                </module>
                """;

        ExtensionDescriptor descriptor = DescriptorParser.parse(null, xml(hivemodule), null);

        assertThat(descriptor.getAdministrationPages())
                .containsExactly(Map.of("id", "page", "name", "Page", "parentNodeId", "node"));
        assertThat(descriptor.getWorkflowFunctions()).containsExactly(
                new ExtensionDescriptor.WorkflowFunction("fn", "x.Fn", null, true, "Does things", List.of(
                        new ExtensionDescriptor.WorkflowParameter("p", "pid", "param"),
                        new ExtensionDescriptor.WorkflowParameter("q", "qid", null))),
                new ExtensionDescriptor.WorkflowFunction("proto", "x.Proto", "Condition", false, null, List.of()));
        assertThat(descriptor.getWidgets()).containsExactly(Map.entry("w", "instance:x.Widget"));
        assertThat(descriptor.getVelocityContext()).containsExactly(Map.entry("vk", "instance:x.Velocity"));
        assertThat(descriptor.getRenderingContext()).containsExactly(Map.entry("rk", "instance:x.Rendering"));
        assertThat(descriptor.getNavigationExtenderClasses()).containsExactly("x.NavImpl");
        assertThat(descriptor.getWarnings()).containsExactly("navigation extender service missing has no <construct class>, skipped");
        assertThat(descriptor.getRestartRequiredReasons()).isEmpty();
    }

    @Test
    void reportsWhatOnlyARestartApplies() {
        String hivemodule = """
                <module id="my.module" version="1.0.0">
                    <implementation service-id="some.Service"/>
                    <contribution configuration-id="unsupported.point"/>
                </module>
                """;

        ExtensionDescriptor descriptor = DescriptorParser.parse(null, xml(hivemodule), " my.GuiceModule ");

        assertThat(descriptor.getRestartRequiredReasons()).containsExactly(
                "<implementation service-id=\"some.Service\">: HiveMind service implementations and interceptors are built once at startup",
                "contribution to unsupported.point is not supported",
                "Guice-Modules header (my.GuiceModule): the Guice injector is built once at startup");
    }

    @Test
    void reportsContextValuesThatOnlyARestartCreates() {
        String hivemodule = """
                <module id="my.module" version="1.0.0">
                    <contribution configuration-id="com.polarion.alm.richpages.velocityContext">
                        <item key="kept" value="service:my.Service"/>
                        <item key="reloaded" value="instance:my.Tool"/>
                    </contribution>
                    <contribution configuration-id="com.polarion.alm.wiki.renderingContext">
                        <item key="wiki" value="bean:my.Bean"/>
                    </contribution>
                </module>
                """;

        ExtensionDescriptor descriptor = DescriptorParser.parse(null, xml(hivemodule), null);

        assertThat(descriptor.getVelocityContext()).containsOnlyKeys("kept", "reloaded");
        assertThat(descriptor.getRestartRequiredReasons()).containsExactly(
                "com.polarion.alm.richpages.velocityContext item 'kept' (service:my.Service): only instance: values are reloaded",
                "com.polarion.alm.wiki.renderingContext item 'wiki' (bean:my.Bean): only instance: values are reloaded");
    }

    @Test
    void readsJobUnitFactoriesAPlainConstructorBuilds() {
        String hivemodule = """
                <module id="my.module">
                    <service-point id="plainFactory" interface="com.polarion.platform.jobs.IJobUnitFactory">
                        <invoke-factory><construct class="my.PlainFactory"/></invoke-factory>
                    </service-point>
                    <service-point id="configuredFactory" interface="com.polarion.platform.jobs.IJobUnitFactory">
                        <invoke-factory><construct class="my.ConfiguredFactory"><set property="a" value="b"/></construct></invoke-factory>
                    </service-point>
                    <service-point id="initializedFactory" interface="com.polarion.platform.jobs.IJobUnitFactory">
                        <invoke-factory><construct class="my.InitializedFactory" initialize-method="init"/></invoke-factory>
                    </service-point>
                    <service-point id="guiceFactory" interface="com.polarion.platform.jobs.IJobUnitFactory">
                        <invoke-factory service-id="com.siemens.polarion.platform.guice.factory"/>
                    </service-point>
                    <contribution configuration-id="com.polarion.platform.jobs.configuration">
                        <jobUnitFactory name="plain.job" factory="my.module.plainFactory"/>
                        <jobUnitFactory name="configured.job" factory="configuredFactory"/>
                        <jobUnitFactory name="initialized.job" factory="initializedFactory"/>
                        <jobUnitFactory name="guice.job" factory="guiceFactory"/>
                        <workDir dir="/tmp/jobs"/>
                    </contribution>
                </module>
                """;

        ExtensionDescriptor descriptor = DescriptorParser.parse(null, xml(hivemodule), null);

        assertThat(descriptor.getJobUnitFactories()).containsExactly(Map.entry("plain.job", "my.PlainFactory"));
        assertThat(descriptor.getJobsNeedingRestart()).containsExactly("configured.job", "initialized.job", "guice.job");
        assertThat(descriptor.getRestartRequiredReasons()).hasSize(4)
                .anyMatch(r -> r.contains("job 'configured.job'"))
                .anyMatch(r -> r.contains("job 'initialized.job'"))
                .anyMatch(r -> r.contains("job 'guice.job'"))
                .anyMatch(r -> r.contains("workDir"));
    }

    @Test
    void ignoresABlankGuiceModulesHeader() {
        assertThat(DescriptorParser.parse(null, null, "  ").getRestartRequiredReasons()).isEmpty();
    }

    @Test
    void rejectsMalformedXml() {
        InputStream broken = xml("<plugin>");

        assertThatThrownBy(() -> DescriptorParser.parse(broken, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Cannot parse descriptor");
    }

    @Test
    void rejectsADoctype() {
        InputStream withDoctype = xml("<!DOCTYPE plugin [<!ENTITY x \"y\">]><plugin/>");

        assertThatThrownBy(() -> DescriptorParser.parse(withDoctype, null, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
