package com.lody.virtual.helper.compat;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Direct-reflection fallback for Android 14+ (API 34+) where mirror
 * classes may fail because PackageUserState or PackageParser inner
 * classes were removed or refactored by vendor ROMs.
 */
class PackageParserCompat34 {
    private static final String TAG = "PPC34";

    private static Constructor<?> sParserCtor;
    private static Method sParsePackage;
    private static Method sSetCallback;
    private static Constructor<?> sCallbackImplCtor;
    private static Method sGenerateActivityInfo;
    private static Method sGenerateApplicationInfo;
    private static Method sGeneratePackageInfo;
    private static Method sGenerateProviderInfo;
    private static Method sGenerateServiceInfo;
    private static Method sCollectCertificatesStatic;
    private static Object sDefaultUserState;

    private static boolean sInitialized = false;
    private static boolean sAvailable = false;

    static synchronized void init() {
        if (sInitialized) return;
        sInitialized = true;
        try {
            Class<?> parserClass = Class.forName("android.content.pm.PackageParser");

            // Constructor
            try {
                sParserCtor = parserClass.getDeclaredConstructor();
                sParserCtor.setAccessible(true);
            } catch (Exception e) {
                Log.w(TAG, "PackageParser has no default ctor", e);
            }

            // parsePackage(File, int)
            try {
                sParsePackage = parserClass.getDeclaredMethod("parsePackage", File.class, int.class);
                sParsePackage.setAccessible(true);
            } catch (Exception e) {
                Log.w(TAG, "parsePackage not found", e);
            }

            // setCallback - may not exist on Android 12+
            try {
                Class<?> callbackClass = Class.forName("android.content.pm.PackageParser$Callback");
                sSetCallback = parserClass.getDeclaredMethod("setCallback", callbackClass);
                sSetCallback.setAccessible(true);
            } catch (Exception e) {
                Log.d(TAG, "setCallback not available (expected on API 31+)");
            }

            // CallbackImpl - may not exist on Android 12+
            try {
                Class<?> callbackImplClass = Class.forName("android.content.pm.PackageParser$CallbackImpl");
                sCallbackImplCtor = callbackImplClass.getDeclaredConstructor(
                        android.content.pm.PackageManager.class);
                sCallbackImplCtor.setAccessible(true);
            } catch (Exception e) {
                Log.d(TAG, "CallbackImpl not available (expected on API 31+)");
            }

            // collectCertificates static method (Package, boolean) - P28+
            for (Method m : parserClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if ("collectCertificates".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && params[1] == boolean.class) {
                        sCollectCertificatesStatic = m;
                        m.setAccessible(true);
                    }
                }
            }

            // Find generateXxxInfo methods by name and parameter count.
            // We search by name+arity because PackageUserState class name
            // may differ between vendor ROMs.
            for (Method m : parserClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                int paramCount = m.getParameterTypes().length;
                switch (m.getName()) {
                    case "generateActivityInfo":
                        if (paramCount == 4) sGenerateActivityInfo = m;
                        break;
                    case "generateApplicationInfo":
                        if (paramCount == 3) sGenerateApplicationInfo = m;
                        break;
                    case "generatePackageInfo":
                        if (paramCount == 7) sGeneratePackageInfo = m;
                        break;
                    case "generateProviderInfo":
                        if (paramCount == 4) sGenerateProviderInfo = m;
                        break;
                    case "generateServiceInfo":
                        if (paramCount == 4) sGenerateServiceInfo = m;
                        break;
                }
            }

            // Discover and create default PackageUserState
            sDefaultUserState = discoverAndCreateUserState();

            sAvailable = sParserCtor != null;
            Log.i(TAG, "Initialized: available=" + sAvailable
                    + " userState=" + (sDefaultUserState != null ? sDefaultUserState.getClass().getName() : "null")
                    + " genActInfo=" + (sGenerateActivityInfo != null)
                    + " genAppInfo=" + (sGenerateApplicationInfo != null)
                    + " genPkgInfo=" + (sGeneratePackageInfo != null)
                    + " genSvcInfo=" + (sGenerateServiceInfo != null)
                    + " genPrvInfo=" + (sGenerateProviderInfo != null)
                    + " setCallback=" + (sSetCallback != null)
                    + " callbackImpl=" + (sCallbackImplCtor != null));
        } catch (Exception e) {
            Log.e(TAG, "PackageParser class not found!", e);
            sAvailable = false;
        }
    }

    static boolean isAvailable() {
        init();
        return sAvailable;
    }

    /**
     * Discover the PackageUserState class from method parameters
     * and create a default instance.
     */
    private static Object discoverAndCreateUserState() {
        // First, try to discover the class from generateApplicationInfo's 3rd parameter
        Class<?> userStateClass = null;
        if (sGenerateApplicationInfo != null) {
            userStateClass = sGenerateApplicationInfo.getParameterTypes()[2];
        } else if (sGenerateActivityInfo != null) {
            userStateClass = sGenerateActivityInfo.getParameterTypes()[2];
        }

        // If discovery failed, try known class names
        if (userStateClass == null) {
            String[] candidates = {
                    "android.content.pm.PackageUserState",
                    "com.android.server.pm.pkg.PackageUserState",
                    "com.android.server.pm.pkg.PackageUserStateInternal",
            };
            for (String name : candidates) {
                try {
                    userStateClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
        }

        if (userStateClass == null) {
            Log.w(TAG, "Cannot find PackageUserState class");
            return null;
        }
        Log.i(TAG, "Found PackageUserState class: " + userStateClass.getName());

        // Try default constructor
        try {
            Constructor<?> ctor = userStateClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object result = ctor.newInstance();
            Log.i(TAG, "Created PackageUserState via default constructor");
            return result;
        } catch (Exception e) {
            Log.d(TAG, "No default ctor: " + e.getMessage());
        }

        // Try DEFAULT static field
        try {
            Field f = userStateClass.getDeclaredField("DEFAULT");
            f.setAccessible(true);
            Object result = f.get(null);
            if (result != null) {
                Log.i(TAG, "Got PackageUserState from DEFAULT field");
                return result;
            }
        } catch (Exception ignored) {
        }

        // Try Unsafe.allocateInstance
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocInst = unsafeClass.getDeclaredMethod("allocateInstance", Class.class);
            Object result = allocInst.invoke(unsafe, userStateClass);
            if (result != null) {
                Log.i(TAG, "Created PackageUserState via Unsafe");
                return result;
            }
        } catch (Exception e) {
            Log.d(TAG, "Unsafe failed: " + e.getMessage());
        }

        // If it's an interface, create a Proxy that returns sensible defaults
        if (userStateClass.isInterface()) {
            try {
                Object proxy = Proxy.newProxyInstance(
                        userStateClass.getClassLoader(),
                        new Class[]{userStateClass},
                        (p, method, args) -> {
                            String name = method.getName();
                            Class<?> ret = method.getReturnType();
                            // isInstalled() should return true
                            if ("isInstalled".equals(name)) return true;
                            if (ret == boolean.class) return false;
                            if (ret == int.class) return 0;
                            if (ret == long.class) return 0L;
                            if (ret.isPrimitive()) return 0;
                            return null;
                        });
                Log.i(TAG, "Created PackageUserState proxy for interface");
                return proxy;
            } catch (Exception e) {
                Log.w(TAG, "Proxy creation failed", e);
            }
        }

        return null;
    }

    /**
     * Get the default PackageUserState object. Can be used by PackageParserCompat
     * when mirror-based sUserState is null.
     */
    static Object getUserState() {
        init();
        return sDefaultUserState;
    }

    static PackageParser createParser() {
        init();
        if (sParserCtor == null) return null;
        try {
            return (PackageParser) sParserCtor.newInstance();
        } catch (Exception e) {
            Log.e(TAG, "Cannot create PackageParser", e);
            return null;
        }
    }

    static PackageParser.Package parsePackage(PackageParser parser, File packageFile, int flags) throws Throwable {
        init();
        // Set callback if available (Android 9-11)
        if (sSetCallback != null && sCallbackImplCtor != null) {
            try {
                Object callback = sCallbackImplCtor.newInstance(
                        com.lody.virtual.client.core.VirtualCore.getPM());
                sSetCallback.invoke(parser, callback);
            } catch (Exception e) {
                Log.w(TAG, "setCallback failed (non-fatal)", e);
            }
        }
        if (sParsePackage == null) {
            throw new RuntimeException("parsePackage method not found");
        }
        try {
            return (PackageParser.Package) sParsePackage.invoke(parser, packageFile, flags);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static ActivityInfo generateActivityInfo(PackageParser.Activity activity, int flags, int userId) {
        if (sGenerateActivityInfo == null) return null;
        try {
            return (ActivityInfo) sGenerateActivityInfo.invoke(null, activity, flags, sDefaultUserState, userId);
        } catch (Exception e) {
            Log.w(TAG, "generateActivityInfo failed", e);
            return null;
        }
    }

    static ApplicationInfo generateApplicationInfo(PackageParser.Package p, int flags) {
        if (sGenerateApplicationInfo == null) return null;
        try {
            return (ApplicationInfo) sGenerateApplicationInfo.invoke(null, p, flags, sDefaultUserState);
        } catch (Exception e) {
            Log.w(TAG, "generateApplicationInfo failed", e);
            return null;
        }
    }

    static PackageInfo generatePackageInfo(PackageParser.Package p, int[] gids, int flags,
                                           long firstInstallTime, long lastUpdateTime) {
        if (sGeneratePackageInfo == null) return null;
        try {
            return (PackageInfo) sGeneratePackageInfo.invoke(null, p, gids, flags,
                    firstInstallTime, lastUpdateTime, null, sDefaultUserState);
        } catch (Exception e) {
            Log.w(TAG, "generatePackageInfo failed", e);
            return null;
        }
    }

    static ProviderInfo generateProviderInfo(PackageParser.Provider provider, int flags, int userId) {
        if (sGenerateProviderInfo == null) return null;
        try {
            return (ProviderInfo) sGenerateProviderInfo.invoke(null, provider, flags, sDefaultUserState, userId);
        } catch (Exception e) {
            Log.w(TAG, "generateProviderInfo failed", e);
            return null;
        }
    }

    static ServiceInfo generateServiceInfo(PackageParser.Service service, int flags, int userId) {
        if (sGenerateServiceInfo == null) return null;
        try {
            return (ServiceInfo) sGenerateServiceInfo.invoke(null, service, flags, sDefaultUserState, userId);
        } catch (Exception e) {
            Log.w(TAG, "generateServiceInfo failed", e);
            return null;
        }
    }

    static void collectCertificates(PackageParser parser, PackageParser.Package p) throws Throwable {
        if (sCollectCertificatesStatic != null) {
            try {
                sCollectCertificatesStatic.invoke(null, p, true);
                return;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
        // Try instance method fallback
        try {
            Class<?> parserClass = Class.forName("android.content.pm.PackageParser");
            for (Method m : parserClass.getDeclaredMethods()) {
                if ("collectCertificates".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    m.setAccessible(true);
                    m.invoke(parser, p, 0);
                    return;
                }
            }
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (Exception e) {
            Log.w(TAG, "collectCertificates fallback failed", e);
        }
    }
}
