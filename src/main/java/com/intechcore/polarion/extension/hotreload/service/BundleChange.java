package com.intechcore.polarion.extension.hotreload.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;

import java.nio.file.Path;

/**
 * One difference between the jars in the extensions folder and the bundles installed in the running framework.
 */
public record BundleChange(@NotNull Type type, @NotNull String symbolicName, @Nullable Path jar, @Nullable Bundle bundle) {

    public enum Type {
        /** a jar whose symbolic name is not installed yet */
        INSTALL,
        /** the installed bundle's jar file was rewritten after the bundle was installed */
        UPDATE,
        /** the jar for an installed symbolic name now has another file name, e.g. a version bump */
        REPLACE,
        /** an installed extension bundle whose jar is gone */
        UNINSTALL
    }

    @Override
    @NotNull
    public String toString() {
        return type + " " + symbolicName + (jar != null ? " (" + jar.getFileName() + ")" : "");
    }
}
