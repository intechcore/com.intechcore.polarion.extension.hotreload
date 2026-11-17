package com.intechcore.polarion.extension.hotreload.rest;

import ch.sbb.polarion.extension.generic.rest.controller.info.ExtensionInfoApiController;
import ch.sbb.polarion.extension.generic.rest.controller.settings.NamedSettingsApiController;
import com.intechcore.polarion.extension.hotreload.rest.controller.HotReloadApiController;
import com.intechcore.polarion.extension.hotreload.rest.controller.HotReloadInternalController;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extension registers no settings yet, so generic must not publish its settings endpoints.
 */
class HotReloadRestApplicationTest {

    @Test
    void servesTheGenericAndRescanEndpointsWithoutSettings() {
        Set<Class<?>> classes = new HotReloadRestApplication().getClasses();

        assertThat(classes)
                .contains(ExtensionInfoApiController.class, HotReloadApiController.class, HotReloadInternalController.class)
                .doesNotContain(NamedSettingsApiController.class);
    }
}
