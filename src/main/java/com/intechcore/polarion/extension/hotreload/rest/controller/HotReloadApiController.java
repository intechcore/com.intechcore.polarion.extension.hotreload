package com.intechcore.polarion.extension.hotreload.rest.controller;

import ch.sbb.polarion.extension.generic.rest.filter.AuthenticationFilter;
import ch.sbb.polarion.extension.generic.rest.filter.Secured;
import com.intechcore.polarion.extension.hotreload.service.RescanReport;
import com.polarion.platform.core.PlatformContext;
import com.polarion.platform.security.ISecurityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import javax.security.auth.Subject;
import java.security.PrivilegedAction;

/**
 * Token-authenticated twin of {@link HotReloadInternalController}, for scripts and agents.
 */
@Singleton
@Secured
@Path("/api")
@Tag(name = "Hot Reload")
public class HotReloadApiController {

    private final HotReloadInternalController internalController = new HotReloadInternalController();

    @GET
    @Path("/changes")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Lists the differences between the extensions folder and the running extensions without applying them")
    public RescanReport changes(@Context ContainerRequestContext requestContext) {
        return asTokenUser(requestContext, internalController::changes);
    }

    @POST
    @Path("/rescan")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Applies the differences between the extensions folder and the running extensions")
    public RescanReport rescan(@Context ContainerRequestContext requestContext) {
        return asTokenUser(requestContext, internalController::rescan);
    }

    // RequestContextUtil.getUserSubject() of generic needs Spring's RequestContextListener, which this web.xml does not register.
    private static RescanReport asTokenUser(ContainerRequestContext requestContext, PrivilegedAction<RescanReport> action) {
        Subject subject = (Subject) requestContext.getProperty(AuthenticationFilter.USER_SUBJECT);
        return PlatformContext.getPlatform().lookupService(ISecurityService.class).doAsUser(subject, action);
    }
}
