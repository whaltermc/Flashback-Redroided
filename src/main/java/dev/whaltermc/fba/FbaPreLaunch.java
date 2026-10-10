// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.whaltermc.fba.utils.FileActionsPatch;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class FbaPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("flashback_android");

    @Override
    public void onPreLaunch() {
        NativeLoader.init();

        try {
            FileActionsPatch.select();
        } catch (Throwable t) {
            LOGGER.warn("FBA file actions could not be selected, continuing without them", t);
        }

        try {
            installTransformer();
            LOGGER.info("Initializing FBA");
        } catch (Throwable t) {
            // Non-fatal: the class patcher is a best-effort patch.
            // Flashback will still load; UI may look wrong on some devices.
            LOGGER.warn("FBA class patcher could not be installed, continuing without it", t);
        }
    }

    private static void installTransformer() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Tolerate different launcher implementations — just skip if not Knot.
        Class<?> knotClClass;
        try {
            knotClClass = Class.forName(
                    "net.fabricmc.loader.impl.launch.knot.KnotClassLoader", false, cl);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("KnotClassLoader not found, skipping class patcher");
            return;
        }

        if (!knotClClass.isInstance(cl)) {
            LOGGER.debug("Unexpected class loader ({}), skipping class patcher",
                    cl.getClass().getName());
            return;
        }

        Object delegate = getFieldValueSilent(cl, "delegate");
        if (delegate == null) {
            LOGGER.debug("KnotClassLoader delegate is null, skipping class patcher");
            return;
        }

        Object original = resolveTransformer(delegate);
        if (original == null) {
            LOGGER.debug("mixinTransformer unavailable, skipping class patcher");
            return;
        }

        // Already wrapped (by us or another mod using the same proxy approach) — skip.
        if (Proxy.isProxyClass(original.getClass())) {
            LOGGER.debug("FBA already initialized, skipping");
            return;
        }

        Class<?> iface;
        try {
            iface = Class.forName(
                    "org.spongepowered.asm.mixin.transformer.IMixinTransformer", false, cl);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("IMixinTransformer not found, skipping class patcher");
            return;
        }

        final Object target = original;

        InvocationHandler handler = (proxy, method, args) -> {
            if ("transformClassBytes".equals(method.getName())
                    && args != null && args.length >= 3
                    && args[2] instanceof byte[]) {

                Object result = method.invoke(target, args);

                byte[] bytes = result instanceof byte[]
                        ? (byte[]) result
                        : (byte[]) args[2];

                String name = args[0] != null ? args[0].toString() : "";

                if (shouldPatch(name) && bytes != null && bytes.length > 0) {
                    try {
                        return ClassPatcher.transform(bytes);
                    } catch (Throwable t) {
                        // Never kill class loading — return original bytes on any failure.
                        LOGGER.debug("Class patch skipped for {}: {}", name, t.getMessage());
                        return result;
                    }
                }

                return result;
            }
            return method.invoke(target, args);
        };

        Object wrapper = Proxy.newProxyInstance(cl, new Class<?>[]{iface}, handler);
        setFieldValue(delegate, "mixinTransformer", wrapper);
    }

    /** Read mixinTransformer field, initialising transformers first if needed. */
    private static Object resolveTransformer(Object delegate) {
        Object t = getFieldValueSilent(delegate, "mixinTransformer");
        if (t != null) return t;
        try {
            Method init = findMethod(delegate.getClass(), "initializeTransformers");
            init.setAccessible(true);
            init.invoke(delegate);
        } catch (Throwable ignored) {}
        return getFieldValueSilent(delegate, "mixinTransformer");
    }

    /**
     * Only patch Flashback's own classes.
     * Never transforms other mods that happen to share the com.moulberry prefix.
     */
    private static boolean shouldPatch(String className) {
        if (className == null || className.isEmpty()) return false;
        return className.replace('/', '.').startsWith("com.moulberry.flashback.");
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Object getFieldValueSilent(Object instance, String name) {
        try {
            return getFieldValue(instance, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getFieldValue(Object instance, String name) throws Exception {
        Field f = findField(instance.getClass(), name);
        f.setAccessible(true);
        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup());
            return lookup.unreflectGetter(f).invoke(instance);
        } catch (Throwable ignored) {
            return f.get(instance);
        }
    }

    private static void setFieldValue(Object instance, String name, Object value) throws Exception {
        Field f = findField(instance.getClass(), name);
        f.setAccessible(true);
        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup());
            lookup.unreflectSetter(f).invoke(instance, value);
        } catch (Throwable ignored) {
            f.set(instance, value);
        }
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name + " in " + clazz.getName());
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name, params); }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name + " in " + clazz.getName());
    }
}
