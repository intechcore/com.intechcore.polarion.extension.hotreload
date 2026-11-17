package com.intechcore.polarion.extension.hotreload.rest.controller;

import ch.sbb.polarion.extension.generic.rest.filter.AuthenticationFilter;
import com.intechcore.polarion.extension.hotreload.service.RedeployService;
import com.intechcore.polarion.extension.hotreload.service.RescanReport;
import com.polarion.platform.core.IPlatform;
import com.polarion.platform.core.PlatformContext;
import com.polarion.platform.security.ISecurityService;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import javax.security.auth.Subject;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotReloadControllersTest {

    private static final RescanReport DRY_RUN = new RescanReport(false, List.of("INSTALL a"), List.of(), List.of(), List.of());
    private static final RescanReport APPLIED = new RescanReport(true, List.of("INSTALL a"), List.of("a: installed"), List.of(), List.of());

    private MockedStatic<PlatformContext> platformContext;
    private MockedConstruction<RedeployService> redeployServices;
    private ISecurityService securityService;

    @BeforeEach
    void setUp() {
        securityService = mock(ISecurityService.class);
        IPlatform platform = mock(IPlatform.class);
        when(platform.lookupService(ISecurityService.class)).thenReturn(securityService);
        platformContext = mockStatic(PlatformContext.class);
        platformContext.when(PlatformContext::getPlatform).thenReturn(platform);
        redeployServices = mockConstruction(RedeployService.class, (service, context) -> {
            when(service.rescan(false)).thenReturn(DRY_RUN);
            when(service.rescan(true)).thenReturn(APPLIED);
        });
    }

    @AfterEach
    void tearDown() {
        redeployServices.close();
        platformContext.close();
    }

    private void currentUser(String user, Collection<String> roles) {
        when(securityService.getCurrentUser()).thenReturn(user);
        when(securityService.getRolesForUser(user)).thenReturn(roles);
    }

    @Test
    void internalEndpointsServeAGlobalAdministrator() {
        currentUser("admin", List.of("user", "admin"));
        HotReloadInternalController controller = new HotReloadInternalController();

        assertThat(controller.changes()).isSameAs(DRY_RUN);
        assertThat(controller.rescan("XMLHttpRequest")).isSameAs(APPLIED);
    }

    @Test
    void internalRescanRejectsARequestWithoutTheRequestedWithHeader() {
        currentUser("admin", List.of("admin"));
        HotReloadInternalController controller = new HotReloadInternalController();

        assertThatThrownBy(() -> controller.rescan(null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("The request needs the header X-Requested-With: XMLHttpRequest");
        assertThatThrownBy(() -> controller.rescan("fetch")).isInstanceOf(ForbiddenException.class);
        verify(redeployServices.constructed().get(0), never()).rescan(true);
    }

    @Test
    void internalEndpointsRejectOtherUsers() {
        currentUser("john", List.of("user"));
        HotReloadInternalController controller = new HotReloadInternalController();

        assertThatThrownBy(controller::changes).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.rescan("XMLHttpRequest")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void internalEndpointsRejectAMissingUser() {
        when(securityService.getCurrentUser()).thenReturn(null);

        assertThatThrownBy(new HotReloadInternalController()::changes).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiEndpointsRunAsTheTokenUser() {
        currentUser("admin", List.of("admin"));
        Subject subject = new Subject();
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        when(requestContext.getProperty(AuthenticationFilter.USER_SUBJECT)).thenReturn(subject);
        when(securityService.doAsUser(eq(subject), any(PrivilegedAction.class)))
                .thenAnswer(invocation -> ((PrivilegedAction<?>) invocation.getArgument(1)).run());
        HotReloadApiController controller = new HotReloadApiController();

        assertThat(controller.changes(requestContext)).isSameAs(DRY_RUN);
        assertThat(controller.rescan(requestContext)).isSameAs(APPLIED);
        verify(securityService, times(2)).doAsUser(eq(subject), any(PrivilegedAction.class));
    }
}
