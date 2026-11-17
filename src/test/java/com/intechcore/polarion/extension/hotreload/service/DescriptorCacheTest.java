package com.intechcore.polarion.extension.hotreload.service;

import com.intechcore.polarion.extension.hotreload.descriptor.ExtensionDescriptor;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DescriptorCacheTest {

    @Test
    void keysDescriptorsByBundleId() {
        Bundle bundle = mock(Bundle.class);
        when(bundle.getBundleId()).thenReturn(4711L);
        ExtensionDescriptor descriptor = new ExtensionDescriptor();

        DescriptorCache.put(bundle, descriptor);
        assertThat(DescriptorCache.get(bundle)).isSameAs(descriptor);

        DescriptorCache.remove(bundle);
        assertThat(DescriptorCache.get(bundle)).isNull();
    }
}
