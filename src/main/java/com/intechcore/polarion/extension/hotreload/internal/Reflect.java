package com.intechcore.polarion.extension.hotreload.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Access to Polarion internals that are not part of the published API.
 * <p>
 * Classes are loaded through their owning bundle, so the package does not have to be exported to this bundle.
 * All bundle classes live in unnamed modules, which is why {@code setAccessible} works on them.
 */
public final class Reflect {

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            boolean.class, Boolean.class, int.class, Integer.class, long.class, Long.class,
            double.class, Double.class, float.class, Float.class, short.class, Short.class,
            byte.class, Byte.class, char.class, Character.class);

    private Reflect() {
    }

    @NotNull
    public static Class<?> loadClass(@NotNull String bundleSymbolicName, @NotNull String className) {
        Bundle bundle = findBundle(bundleSymbolicName);
        try {
            return bundle.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class " + className + " not found in bundle " + bundleSymbolicName, e);
        }
    }

    @NotNull
    public static Bundle findBundle(@NotNull String bundleSymbolicName) {
        Bundle self = FrameworkUtil.getBundle(Reflect.class);
        for (Bundle bundle : self.getBundleContext().getBundles()) {
            if (bundleSymbolicName.equals(bundle.getSymbolicName()) && bundle.getState() != Bundle.UNINSTALLED) {
                return bundle;
            }
        }
        throw new IllegalStateException("Bundle " + bundleSymbolicName + " not found");
    }

    @Nullable
    public static Object call(@NotNull Object target, @NotNull String methodName, Object... args) {
        return invoke(target.getClass(), target, methodName, args);
    }

    @Nullable
    public static Object callStatic(@NotNull Class<?> type, @NotNull String methodName, Object... args) {
        return invoke(type, null, methodName, args);
    }

    @NotNull
    public static Object newInstance(@NotNull Class<?> type, Object... args) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (matches(constructor.getParameterTypes(), args)) {
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(args);
                } catch (InvocationTargetException e) {
                    throw unwrap(e);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot instantiate " + type.getName(), e);
                }
            }
        }
        throw new IllegalStateException("No matching constructor in " + type.getName());
    }

    @Nullable
    public static Object getField(@NotNull Object target, @NotNull String fieldName) {
        Field field = findField(target.getClass(), fieldName);
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field " + fieldName, e);
        }
    }

    public static void setField(@NotNull Object target, @NotNull String fieldName, @Nullable Object value) {
        Field field = findField(target.getClass(), fieldName);
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write field " + fieldName, e);
        }
    }

    public static boolean hasField(@NotNull Object target, @NotNull String fieldName) {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the object behind HiveMind proxies. The outer singleton proxy holds the service in {@code _inner}
     * once it was used; before that, {@code _inner} is an inner proxy that builds it through {@code _serviceModel}.
     * For a service point whose "interface" is a class, Polarion's patched service model adds a bridge proxy
     * that holds the service in {@code _service}.
     */
    @NotNull
    public static Object unwrapHiveMindProxy(@NotNull Object service) {
        Object current = service;
        for (int i = 0; i < 5; i++) {
            if (hasField(current, "_serviceModel")) {
                current = call(getField(current, "_serviceModel"), "getActualServiceImplementation");
            } else if (hasField(current, "_inner")) {
                current = getField(current, "_inner");
            } else if (hasField(current, "_service") && getField(current, "_service") != null) {
                current = getField(current, "_service");
            } else {
                return current;
            }
        }
        return current;
    }

    @Nullable
    private static Object invoke(@NotNull Class<?> type, @Nullable Object target, @NotNull String methodName, Object... args) {
        Method method = findMethod(type, methodName, args);
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + type.getName() + "." + methodName, e);
        }
    }

    @NotNull
    private static Method findMethod(@NotNull Class<?> type, @NotNull String methodName, Object... args) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && matches(method.getParameterTypes(), args)) {
                    return method;
                }
            }
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
            queue.addAll(List.of(current.getInterfaces()));
        }
        throw new IllegalStateException("Method " + methodName + " with " + args.length + " argument(s) not found in " + type.getName());
    }

    @NotNull
    private static Field findField(@NotNull Class<?> type, @NotNull String fieldName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // continue with the superclass
            }
        }
        throw new IllegalStateException("Field " + fieldName + " not found in " + type.getName());
    }

    private static boolean matches(@NotNull Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            Class<?> parameterType = parameterTypes[i].isPrimitive() ? BOXES.get(parameterTypes[i]) : parameterTypes[i];
            if (args[i] == null ? parameterTypes[i].isPrimitive() : !parameterType.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    @NotNull
    private static RuntimeException unwrap(@NotNull InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(cause);
    }
}
