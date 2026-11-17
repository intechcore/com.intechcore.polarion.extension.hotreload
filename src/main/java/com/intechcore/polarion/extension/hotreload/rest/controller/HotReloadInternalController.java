package com.intechcore.polarion.extension.hotreload.rest.controller;

import com.intechcore.polarion.extension.hotreload.service.RedeployService;
import com.intechcore.polarion.extension.hotreload.service.RescanReport;
import com.polarion.platform.core.PlatformContext;
import com.polarion.platform.security.ISecurityService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Session-authenticated endpoints for the administration page. Restricted to global administrators, because a
 * rescan swaps code in the running server.
 */
@Singleton
@Hidden
@Path("/internal")
public class HotReloadInternalController {

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
    public RescanReport rescan() {
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
