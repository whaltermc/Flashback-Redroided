package com.whaltermc;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class FlashbackPreLaunch implements PreLaunchEntrypoint {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("flashback-redroided");

    @Override
    public void onPreLaunch() {
        NativeBootstrap.init();

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

        // Only skip if *our* interceptor is already installed. Another
        // mod may have wrapped the transformer too; in that case we wrap
        // theirs instead of silently skipping, so both keep working.
        if (Proxy.isProxyClass(original.getClass())
                && Proxy.getInvocationHandler(original)
                instanceof Interceptor) {
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

        Object wrapper =
                Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{iface},
                        new Interceptor(original)
                );

        setFieldValue(
                delegate,
                "mixinTransformer",
                wrapper
        );
    }

    /**
     * Wraps Mixin's transformer and remaps ImGui bindings in Moulberry
     * classes after Mixin has run.
     *
     * Every class loaded by every mod passes through here, so it must be
     * transparent: exceptions thrown by the wrapped transformer are
     * rethrown unchanged (not wrapped in InvocationTargetException /
     * UndeclaredThrowableException), and a remap failure never stops a
     * class from loading.
     */
    private static final class Interceptor implements InvocationHandler {

        private final Object target;
        private boolean remapFailureLogged;

        Interceptor(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(
                Object proxy,
                Method method,
                Object[] args
        ) throws Throwable {
            try {
                if ("transformClassBytes".equals(method.getName())
                        && args != null
                        && args.length >= 3
                        && args[2] instanceof byte[]) {

                    Object result = method.invoke(target, args);

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

                        try {
                            return FlashbackTransformer.transform(bytes);
                        } catch (Throwable t) {
                            if (!remapFailureLogged) {
                                remapFailureLogged = true;
                                LOGGER.warn(
                                        "ImGui remap failed for {}; "
                                                + "loading it unmodified",
                                        name,
                                        t
                                );
                            }
                            return result;
                        }
                    }

                    return result;
                }

                return method.invoke(target, args);

            } catch (InvocationTargetException e) {
                // Surface the real exception (e.g. a MixinTransformerError
                // from another mod) instead of hiding it.
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    private static boolean shouldRemap(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }

        return className.startsWith(
                "com.moulberry.flashback"
        ) || className.startsWith(
                "com.moulberry.");
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
