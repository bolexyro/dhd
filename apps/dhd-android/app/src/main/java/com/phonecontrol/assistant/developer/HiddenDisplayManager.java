package com.phonecontrol.assistant.developer;

import android.os.Build;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/** Reflection bridge keeps hidden display-manager classes out of the app's compile API. */
final class HiddenDisplayManager {
    private static final String SHELL_PACKAGE = "com.android.shell";

    private final Object service;
    private final Object callback;
    private final android.os.Binder callbackBinder;
    private final Method releaseMethod;
    private final Object windowService;
    private final Method setDisplayImePolicyMethod;
    private int displayId = -1;

    HiddenDisplayManager() throws Exception {
        if (Build.VERSION.SDK_INT != 36) {
            throw new UnsupportedOperationException(
                    "DHD native virtual displays currently require Android API 36; got "
                            + Build.VERSION.SDK_INT);
        }
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "display");
        if (binder == null) throw new IOException("Android display service is unavailable.");
        Class<?> stub = Class.forName("android.hardware.display.IDisplayManager$Stub");
        service = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Class<?> displayManager = Class.forName("android.hardware.display.IDisplayManager");
        Class<?> callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
        callbackBinder = new android.os.Binder();
        callback = Proxy.newProxyInstance(
                callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    if ("asBinder".equals(method.getName())) return callbackBinder;
                    if ("toString".equals(method.getName())) return "DhdVirtualDisplayCallback";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return null;
        });
        releaseMethod = displayManager.getMethod("releaseVirtualDisplay", callbackType);

        android.os.IBinder windowBinder = (android.os.IBinder) getService.invoke(null, "window");
        if (windowBinder == null) throw new IOException("Android window service is unavailable.");
        Class<?> windowStub = Class.forName("android.view.IWindowManager$Stub");
        windowService = windowStub.getMethod("asInterface", android.os.IBinder.class)
                .invoke(null, windowBinder);
        Class<?> windowManager = Class.forName("android.view.IWindowManager");
        setDisplayImePolicyMethod = windowManager.getMethod(
                "setDisplayImePolicy", int.class, int.class);
    }

    int createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface)
            throws Exception {
        Class<?> configBuilder = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Constructor<?> constructor = configBuilder.getConstructor(
                String.class, int.class, int.class, int.class);
        Object builder = constructor.newInstance(name, width, height, densityDpi);
        configBuilder.getMethod("setSurface", Surface.class).invoke(builder, surface);
        configBuilder.getMethod("setFlags", int.class).invoke(builder, displayFlags());
        Object config = configBuilder.getMethod("build").invoke(builder);
        Class<?> configType = Class.forName("android.hardware.display.VirtualDisplayConfig");
        Class<?> callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
        Class<?> projectionType = Class.forName("android.media.projection.IMediaProjection");
        // API 36's binder contract is exactly
        // (VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String).
        // The five-argument overload belongs to DisplayManagerInternal and is
        // not exposed by the display binder. Do not guess its null argument
        // order: accepting it would make a vendor mismatch look successful.
        Method create = Class.forName("android.hardware.display.IDisplayManager").getMethod(
                "createVirtualDisplay", configType, callbackType, projectionType, String.class);
        if (create.getReturnType() != Integer.TYPE) {
            throw new UnsupportedOperationException(
                    "DHD native display service returned an unsupported createVirtualDisplay signature.");
        }
        Object result = create.invoke(service, config, callback, null, SHELL_PACKAGE);
        displayId = ((Integer) result).intValue();
        if (displayId <= 0) throw new IOException("Android rejected the task virtual display.");
        // Android otherwise routes IME windows to the default display. The
        // local policy is part of the task-display contract; fail creation
        // if this privileged shell-side call is unavailable.
        setDisplayImePolicyMethod.invoke(windowService, displayId, 0 /* DISPLAY_IME_POLICY_LOCAL */);
        return displayId;
    }

    void releaseVirtualDisplay() {
        if (displayId <= 0) return;
        try { releaseMethod.invoke(service, callback); } catch (Throwable ignored) {}
        displayId = -1;
    }

    private Method findMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Method method : service.getClass().getMethods()) {
            if (!name.equals(method.getName())) continue;
            if (Arrays.equals(method.getParameterTypes(), parameterTypes)) return method;
        }
        throw new NoSuchMethodException(name);
    }

    private static int displayFlags() {
        // PUBLIC + OWN_CONTENT_ONLY + SUPPORTS_TOUCH + ROTATES_WITH_CONTENT +
        // DESTROY_CONTENT_ON_REMOVAL + TRUSTED + OWN_FOCUS +
        // STEAL_TOP_FOCUS_DISABLED. OWN_FOCUS is ignored by Android unless
        // TRUSTED is present, and without it IME focus falls back to display 0.
        return (1 << 0) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 8) |
                (1 << 10) | (1 << 14) | (1 << 16);
    }
}
