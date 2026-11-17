package com.intechcore.polarion.extension.hotreload.inject;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextReplaceTest {

    /**
     * Defines {@link Helper} itself, as the class loader of an extension bundle does.
     */
    private static final class OldBundleLoader extends ClassLoader {

        OldBundleLoader() {
            super(ContextReplaceTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(Helper.class.getName())) {
                return super.loadClass(name, resolve);
            }
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    private final ClassLoader oldLoader = new OldBundleLoader();

    @Test
    void keepsNonInstanceItemOfOldClassLoader() {
        Map<String, Object> current = Map.of("helper", oldHelper(), "tool", new Object(), "other", "x");

        Map<String, Object> next = ContributionInjector.replace(current,
                Map.of("helper", "object:" + Helper.class.getName(), "tool", "instance:com.foo.Tool"), oldLoader, Map.of());

        assertThat(next).containsOnlyKeys("helper", "other");
    }

    @Test
    void removesUndeclaredObjectOfOldClassLoaderAndAddsNewEntries() {
        Map<String, Object> current = Map.of("stale", oldHelper(), "other", "x");

        Map<String, Object> next = ContributionInjector.replace(current, Map.of(), oldLoader, Map.of("tool", "new"));

        assertThat(next).containsOnlyKeys("other", "tool");
    }

    @Test
    void returnsNullWhenNothingChanges() {
        assertThat(ContributionInjector.replace(Map.of("other", "x"), Map.of(), oldLoader, Map.of())).isNull();
    }

    private Object oldHelper() {
        try {
            Object helper = oldLoader.loadClass(Helper.class.getName()).getDeclaredConstructor().newInstance();
            assertThat(helper.getClass().getClassLoader()).isSameAs(oldLoader);
            return helper;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class Helper {
    }
}
