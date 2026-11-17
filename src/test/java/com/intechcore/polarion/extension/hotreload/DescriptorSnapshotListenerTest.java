package com.intechcore.polarion.extension.hotreload;

import com.intechcore.polarion.extension.hotreload.service.RedeployService;
import jakarta.servlet.ServletContextEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

class DescriptorSnapshotListenerTest {

    @Test
    void takesTheSnapshotWhenTheWebappStarts() {
        try (MockedConstruction<RedeployService> services = mockConstruction(RedeployService.class)) {
            new DescriptorSnapshotListener().contextInitialized(mock(ServletContextEvent.class));

            assertThat(services.constructed()).hasSize(1);
            verify(services.constructed().get(0)).snapshotInstalledExtensions();
        }
    }
}
