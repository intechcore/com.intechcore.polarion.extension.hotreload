package com.intechcore.polarion.extension.hotreload.internal;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ReflectTest {

    interface Greeter {
        default String greet() {
            return "hello";
        }
    }

    static class Base implements Greeter {
        @SuppressWarnings("unused")
        private String inherited = "base";

        @SuppressWarnings("unused")
        private int twice(int value) {
            return value * 2;
        }
    }

    static class Target extends Base {
        @SuppressWarnings("unused")
        private String name;
        @SuppressWarnings("unused")
        private int number;

        @SuppressWarnings("unused")
        private Target(String name, int number) {
            this.name = name;
            this.number = number;
        }

        @SuppressWarnings("unused")
        private String describe(String prefix) {
            return prefix + name + number;
        }

        @SuppressWarnings("unused")
        private static String shout(String text) {
            return text.toUpperCase();
        }

        @SuppressWarnings("unused")
        private void failUnchecked() {
            throw new UnsupportedOperationException("unchecked");
        }

        @SuppressWarnings("unused")
        private void failChecked() throws IOException {
            throw new IOException("checked");
        }
    }

    static class Failing {
        @SuppressWarnings("unused")
        Failing(String reason) throws IOException {
            throw new IOException(reason);
        }
    }

    static class SingletonProxy {
        @SuppressWarnings("unused")
        private final Object _inner;

        SingletonProxy(Object inner) {
            _inner = inner;
        }
    }

    static class InnerProxy {
        @SuppressWarnings("unused")
        private final ServiceModel _serviceModel;

        InnerProxy(ServiceModel serviceModel) {
            _serviceModel = serviceModel;
        }
    }

    static class ServiceModel {
        private final Object service;

        ServiceModel(Object service) {
            this.service = service;
        }

        @SuppressWarnings("unused")
        Object getActualServiceImplementation() {
            return service;
        }
    }

    static class Bridge {
        @SuppressWarnings("unused")
        private final Object _service;

        Bridge(Object service) {
            _service = service;
        }
    }

    static class Endless {
        @SuppressWarnings("unused")
        private Endless _inner;
    }

    private static Target target() {
        return (Target) Reflect.newInstance(Target.class, "a", 1);
    }

    @Test
    void createsInstancesThroughPrivateConstructorsWithBoxedArguments() {
        assertThat(Reflect.call(target(), "describe", "> ")).isEqualTo("> a1");
    }

    @Test
    void rejectsArgumentsNoConstructorTakes() {
        assertThatThrownBy(() -> Reflect.newInstance(Target.class, "a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No matching constructor");
        assertThatThrownBy(() -> Reflect.newInstance(Target.class, "a", "not a number"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Reflect.newInstance(Target.class, "a", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrapsACheckedExceptionOfAConstructor() {
        assertThatThrownBy(() -> Reflect.newInstance(Failing.class, "broken"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void callsMethodsOfSuperclassesInterfacesAndStatics() {
        Target target = target();

        assertThat(Reflect.call(target, "twice", 21)).isEqualTo(42);
        assertThat(Reflect.call(target, "greet")).isEqualTo("hello");
        assertThat(Reflect.callStatic(Target.class, "shout", "loud")).isEqualTo("LOUD");
        assertThat(Reflect.call(target, "describe", (Object) null)).isEqualTo("nulla1");
    }

    @Test
    void reportsAMissingMethod() {
        Target target = target();

        assertThatThrownBy(() -> Reflect.call(target, "missing", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Method missing with 1 argument(s) not found");
    }

    @Test
    void passesUncheckedExceptionsThroughAndWrapsCheckedOnes() {
        Target target = target();

        assertThatThrownBy(() -> Reflect.call(target, "failUnchecked"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("unchecked");
        assertThatThrownBy(() -> Reflect.call(target, "failChecked"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void readsAndWritesFieldsUpTheHierarchy() {
        Target target = target();

        Reflect.setField(target, "name", "b");
        Reflect.setField(target, "inherited", "changed");

        assertThat(Reflect.getField(target, "name")).isEqualTo("b");
        assertThat(Reflect.getField(target, "inherited")).isEqualTo("changed");
        assertThat(Reflect.hasField(target, "inherited")).isTrue();
        assertThat(Reflect.hasField(target, "missing")).isFalse();
        assertThatThrownBy(() -> Reflect.getField(target, "missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Field missing not found");
    }

    @Test
    void unwrapsEveryKindOfHiveMindProxy() {
        Object service = new Object();
        Object proxied = new SingletonProxy(new InnerProxy(new ServiceModel(new Bridge(service))));

        assertThat(Reflect.unwrapHiveMindProxy(proxied)).isSameAs(service);
        assertThat(Reflect.unwrapHiveMindProxy(service)).isSameAs(service);
        assertThat(Reflect.unwrapHiveMindProxy(new Bridge(null))).isInstanceOf(Bridge.class);
    }

    @Test
    void stopsUnwrappingAfterFiveLevels() {
        Endless first = new Endless();
        first._inner = first;

        assertThat(Reflect.unwrapHiveMindProxy(first)).isSameAs(first);
    }

    @Test
    void loadsClassesThroughTheOwningBundle() throws ClassNotFoundException {
        Bundle owner = mock(Bundle.class);
        when(owner.getSymbolicName()).thenReturn("owner");
        when(owner.getState()).thenReturn(Bundle.ACTIVE);
        doReturnClass(owner, "x.Known", String.class);
        when(owner.loadClass("x.Unknown")).thenThrow(new ClassNotFoundException("x.Unknown"));
        Bundle uninstalled = mock(Bundle.class);
        when(uninstalled.getSymbolicName()).thenReturn("gone");
        when(uninstalled.getState()).thenReturn(Bundle.UNINSTALLED);

        try (MockedStatic<FrameworkUtil> frameworkUtil = mockStatic(FrameworkUtil.class)) {
            Bundle self = mock(Bundle.class);
            BundleContext context = mock(BundleContext.class);
            when(self.getBundleContext()).thenReturn(context);
            when(context.getBundles()).thenReturn(new Bundle[]{uninstalled, owner});
            frameworkUtil.when(() -> FrameworkUtil.getBundle(Reflect.class)).thenReturn(self);

            assertThat(Reflect.loadClass("owner", "x.Known")).isEqualTo(String.class);
            assertThatThrownBy(() -> Reflect.loadClass("owner", "x.Unknown"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Class x.Unknown not found in bundle owner");
            assertThatThrownBy(() -> Reflect.findBundle("gone"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Bundle gone not found");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void doReturnClass(Bundle bundle, String name, Class<?> type) throws ClassNotFoundException {
        when(bundle.loadClass(name)).thenReturn((Class) type);
    }
}
