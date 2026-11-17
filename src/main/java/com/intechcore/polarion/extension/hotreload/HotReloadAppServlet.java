package com.intechcore.polarion.extension.hotreload;

import ch.sbb.polarion.extension.generic.GenericUiServlet;

import java.io.Serial;

/**
 * Serves the React single-page app from its own webapp context ({@code hotreload-app}). The admin
 * extenders in hivemodule.xml open it as
 * {@code /polarion/hotreload-app/ui/app/index.html?feature=<id>}; everything else about the
 * request handling comes from the generic servlet.
 */
public class HotReloadAppServlet extends GenericUiServlet {

    @Serial
    private static final long serialVersionUID = 2741699502747533530L;

    public HotReloadAppServlet() {
        super("hotreload-app");
    }
}
