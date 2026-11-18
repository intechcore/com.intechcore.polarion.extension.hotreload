package com.intechcore.polarion.extension.hotreload.rest.controller;

import com.intechcore.polarion.extension.hotreload.service.RedeployService;
import com.intechcore.polarion.extension.hotreload.service.RescanReport;
import com.polarion.platform.core.PlatformContext;
import com.polarion.platform.security.ISecurityService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Session-authenticated endpoints for the administration page. Restricted to global administrators, because a
 * rescan swaps code in the running server.
 * <p>
 * The session cookie goes with every request, also with a form another site submits. So the rescan also requires
 * the {@value #REQUESTED_WITH} header: a cross-site request can only set it after a CORS preflight, which Polarion
 * does not allow.
 */
@Singleton
@Hidden
@Path("/internal")
public class HotReloadInternalController {

    static final String REQUESTED_WITH = "X-Requested-With";
    static final String XML_HTTP_REQUEST = "XMLHttpRequest";

    private final RedeployService redeployService = new RedeployService();

    @GET
    @Path("/changes")
    @Produces(MediaType.APPLICATION_JSON)
    public RescanReport changes() {
        checkAdmin();
        return redeployService.rescan(false);
    }

    @POST
    @Path("/rescan")
    @Produces(MediaType.APPLICATION_JSON)
    public RescanReport rescan(@HeaderParam(REQUESTED_WITH) String requestedWith) {
        if (!XML_HTTP_REQUEST.equals(requestedWith)) {
            throw new ForbiddenException("The request needs the header " + REQUESTED_WITH + ": " + XML_HTTP_REQUEST);
        }
        return applyRescan();
    }

    /**
     * The rescan without the header check, for the token-authenticated twin: a bearer token does not go with a
     * cross-site request by itself.
     */
    RescanReport applyRescan() {
        checkAdmin();
        return redeployService.rescan(true);
    }

    private static void checkAdmin() {
        ISecurityService securityService = PlatformContext.getPlatform().lookupService(ISecurityService.class);
        String user = securityService.getCurrentUser();
        if (user == null || !securityService.getRolesForUser(user).contains("admin")) {
            throw new ForbiddenException("Only global administrators can redeploy extensions");
        }
    }
}
