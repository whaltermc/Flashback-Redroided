// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            installTransformer();

            LOGGER.info(
                    "Installed Flashback ImGui binding interceptor " +
                    "(imgui.moulberry90 -> imgui.moulberry92)"
            );

        } catch (Throwable t) {
            LOGGER.error(
                    "Failed to install ImGui binding interceptor - " +
                    "Flashback UI will likely crash on Android",
                    t
            );
        }
    }

    private static void installTransformer() throws Exception {
        ClassLoader cl =
                Thread.currentThread().getContextClassLoader();

        Class<?> knotClClass = Class.forName(
                "net.fabricmc.loader.impl.launch.knot.KnotClassLoader",
                false,
                cl
        );

        if (!knotClClass.isInstance(cl)) {
            LOGGER.warn(
                    "ClassLoader is not KnotClassLoader ({}), " +
                    "skipping remapper",
                    cl.getClass().getName()
            );
            return;
        }

        Object delegate =
                getFieldValue(cl, "delegate");

        Object original = null;

        try {
            original =
                    getFieldValue(delegate, "mixinTransformer");
        } catch (Throwable ignored) {
        }

        if (original == null) {
            Method init =
                    findMethod(
                            delegate.getClass(),
                            "initializeTransformers"
                    );

            init.setAccessible(true);

            try {
                init.invoke(delegate);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (!(e.getCause()
                        instanceof IllegalStateException)) {
                    throw e;
                }
            }

            original =
                    getFieldValue(
                            delegate,
                            "mixinTransformer"
                    );
        }

        if (original == null) {
            throw new IllegalStateException(
                    "mixinTransformer is still null after init attempt"
            );
        }

        if (Proxy.isProxyClass(original.getClass())) {
            LOGGER.info(
                    "Flashback ImGui remapper already installed, skipping"
            );
            return;
        }

        Class<?> iface = Class.forName(
                "org.spongepowered.asm.mixin.transformer.IMixinTransformer",
                false,
                cl
        );

        final Object target = original;

        InvocationHandler handler = (proxy, method, args) -> {

            if ("transformClassBytes".equals(method.getName())
                    && args != null
                    && args.length >= 3
                    && args[2] instanceof byte[]) {

                Object result =
                        method.invoke(target, args);

                byte[] bytes =
                        result instanceof byte[]
                                ? (byte[]) result
                                : (byte[]) args[2];

                String name =
                        args[0] != null
                                ? args[0].toString()
                                : "";

                if (shouldRemap(name)
                        && bytes != null
                        && bytes.length > 0) {

                    return ClassPatcher.transform(bytes);
                }

                return result;
            }

            return method.invoke(target, args);
        };

        Object wrapper =
                Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{iface},
                        handler
                );

        setFieldValue(
                delegate,
                "mixinTransformer",
                wrapper
        );
    }

    private static boolean shouldRemap(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }

        return className.startsWith(
                "com.moulberry.flashback"
        ) || className.startsWith(
                "com.moulberry."
        );
    }

    private static Object getFieldValue(
            Object instance,
            String name
    ) throws Exception {

        Field f =
                findField(
                        instance.getClass(),
                        name
                );

        f.setAccessible(true);

        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(
                            f.getDeclaringClass(),
                            MethodHandles.lookup()
                    );

            return lookup
                    .unreflectGetter(f)
                    .invoke(instance);

        } catch (Throwable ignored) {
            return f.get(instance);
        }
    }

    private static void setFieldValue(
            Object instance,
            String name,
            Object value
    ) throws Exception {

        Field f =
                findField(
                        instance.getClass(),
                        name
                );

        f.setAccessible(true);

        try {
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(
                            f.getDeclaringClass(),
                            MethodHandles.lookup()
                    );

            lookup
                    .unreflectSetter(f)
                    .invoke(instance, value);

        } catch (Throwable ignored) {
            f.set(instance, value);
        }
    }

    private static Field findField(
            Class<?> clazz,
            String name
    ) throws NoSuchFieldException {

        for (Class<?> c = clazz;
             c != null;
             c = c.getSuperclass()) {

            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }

        throw new NoSuchFieldException(
                name + " in " + clazz.getName()
        );
    }

    private static Method findMethod(
            Class<?> clazz,
            String name,
            Class<?>... params
    ) throws NoSuchMethodException {

        for (Class<?> c = clazz;
             c != null;
             c = c.getSuperclass()) {

            try {
                return c.getDeclaredMethod(
                        name,
                        params
                );
            } catch (NoSuchMethodException ignored) {
            }
        }

        throw new NoSuchMethodException(
                name + " in " + clazz.getName()
        );
    }
}