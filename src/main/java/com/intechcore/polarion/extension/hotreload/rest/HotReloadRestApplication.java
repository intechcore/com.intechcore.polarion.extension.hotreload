package com.intechcore.polarion.extension.hotreload.rest;

import ch.sbb.polarion.extension.generic.rest.GenericRestApplication;
import com.intechcore.polarion.extension.hotreload.rest.controller.HotReloadApiController;
import com.intechcore.polarion.extension.hotreload.rest.controller.HotReloadInternalController;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The REST application of the extension: the generic endpoints plus the rescan endpoints.
 */
public class HotReloadRestApplication extends GenericRestApplication {

    @Override
    @NotNull
    protected Set<Class<?>> getExtensionControllerClasses() {
        return Set.of(HotReloadApiController.class, HotReloadInternalController.class);
    }
}
