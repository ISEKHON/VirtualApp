package com.lody.virtual.helper.compat;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Activity;
import android.content.pm.PackageParser.Package;
import android.content.pm.PackageParser.Provider;
import android.content.pm.PackageParser.Service;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.os.VUserHandle;

import java.io.File;

import mirror.android.content.pm.PackageParserJellyBean;
import mirror.android.content.pm.PackageParserJellyBean17;
import mirror.android.content.pm.PackageParserLollipop;
import mirror.android.content.pm.PackageParserLollipop22;
import mirror.android.content.pm.PackageParserMarshmallow;
import mirror.android.content.pm.PackageParserNougat;
import mirror.android.content.pm.PackageParserP28;
import mirror.android.content.pm.PackageUserState;

import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.os.Build.VERSION_CODES.JELLY_BEAN_MR1;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static android.os.Build.VERSION_CODES.LOLLIPOP_MR1;
import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;

/**
 * @author Lody
 */

public class PackageParserCompat {

    private static final String TAG = "PackageParserCompat";
    public static final int[] GIDS = VirtualCore.get().getGids();
    private static final int API_LEVEL = Build.VERSION.SDK_INT;
    private static final int myUserId = VUserHandle.getUserId(Process.myUid());
    private static final Object sUserState = createUserState();

    /**
     * Whether we must use the API 34+ direct-reflection fallback because
     * the mirror-based PackageParserMarshmallow methods are broken
     * (e.g. PackageUserState class missing on vendor ROM).
     */
    private static final boolean sUseFallback34 = needsFallback34();

    private static boolean needsFallback34() {
        if (API_LEVEL < M) return false;
        try {
            // If the mirror generateApplicationInfo is null, mirrors are broken
            if (PackageParserMarshmallow.generateApplicationInfo == null) {
                Log.w(TAG, "Mirror generateApplicationInfo is null, using API34 fallback");
                return true;
            }
            return false;
        } catch (Throwable t) {
            // PackageParserMarshmallow.<clinit> may crash on Android 14+ when
            // android.content.pm.PackageUserState class is missing
            Log.w(TAG, "Mirror class init failed, using API34 fallback", t);
            return true;
        }
    }

    private static Object createUserState() {
        if (API_LEVEL < JELLY_BEAN_MR1) return null;
        // Try mirror constructor first (works on Android 4.2 - 12)
        if (PackageUserState.ctor != null) {
            return PackageUserState.ctor.newInstance();
        }
        // Android 13+: PackageUserState default constructor was removed
        // or class was removed entirely on some vendor ROMs.
        // Try the DEFAULT static field
        try {
            java.lang.reflect.Field defaultField = Class.forName("android.content.pm.PackageUserState")
                    .getDeclaredField("DEFAULT");
            defaultField.setAccessible(true);
            Object result = defaultField.get(null);
            if (result != null) return result;
        } catch (Exception ignored) {}
        // Fallback: allocate instance via Unsafe (skips constructor)
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            java.lang.reflect.Method allocateInstance = unsafeClass.getDeclaredMethod("allocateInstance", Class.class);
            return allocateInstance.invoke(unsafe, Class.forName("android.content.pm.PackageUserState"));
        } catch (Exception ignored) {}
        // Last resort: ask the API34 fallback to discover it from method signatures
        if (API_LEVEL >= M) {
            Object state = PackageParserCompat34.getUserState();
            if (state != null) {
                Log.i(TAG, "Got userState from PackageParserCompat34: " + state.getClass().getName());
                return state;
            }
        }
        Log.w(TAG, "createUserState: all attempts failed, sUserState will be null");
        return null;
    }


    public static PackageParser createParser(File packageFile) {
        if (API_LEVEL >= M) {
            // On Android 14+, prefer the API34 fallback path if mirrors are broken
            if (sUseFallback34) {
                PackageParser parser = PackageParserCompat34.createParser();
                if (parser != null) return parser;
            }
            try {
                // Use fallback if mirror ctor is null (PackageParser class or ctor hidden/missing)
                if (PackageParserMarshmallow.ctor == null) {
                    Log.w(TAG, "createParser: mirror ctor null, trying API34 fallback");
                    PackageParser parser = PackageParserCompat34.createParser();
                    if (parser != null) return parser;
                }
                return PackageParserMarshmallow.ctor.newInstance();
            } catch (Throwable t) {
                Log.w(TAG, "createParser: mirror ctor failed, trying API34 fallback", t);
                PackageParser parser = PackageParserCompat34.createParser();
                if (parser != null) return parser;
                // Last resort: try the default constructor directly
                try {
                    return new PackageParser();
                } catch (Exception ignored) {}
            }
            return null;
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return PackageParserLollipop22.ctor.newInstance();
        } else if (API_LEVEL >= LOLLIPOP) {
            return PackageParserLollipop.ctor.newInstance();
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            return PackageParserJellyBean17.ctor.newInstance(packageFile.getAbsolutePath());
        } else if (API_LEVEL >= JELLY_BEAN) {
            return PackageParserJellyBean.ctor.newInstance(packageFile.getAbsolutePath());
        } else {
            return mirror.android.content.pm.PackageParser.ctor.newInstance(packageFile.getAbsolutePath());
        }
    }

    public static Package parsePackage(PackageParser parser, File packageFile, int flags) throws Throwable {
        if (BuildCompat.isQ()) {
            // setCallback and CallbackImpl were removed in Android 12+.
            // Guard against null to avoid NPE on API 31+.
            if (PackageParserP28.setCallback != null
                    && PackageParserP28.CallbackImpl.ctor != null) {
                try {
                    PackageParserP28.setCallback.call(parser,
                            PackageParserP28.CallbackImpl.ctor.newInstance(VirtualCore.getPM()));
                } catch (Exception e) {
                    Log.w(TAG, "setCallback failed (non-fatal)", e);
                }
            } else if (sUseFallback34) {
                // Let PackageParserCompat34 handle setCallback if possible
                // (it's done inside parsePackage below)
            }
        }

        if (sUseFallback34) {
            return PackageParserCompat34.parsePackage(parser, packageFile, flags);
        }

        if (API_LEVEL >= M) {
            if (PackageParserMarshmallow.parsePackage == null) {
                return PackageParserCompat34.parsePackage(parser, packageFile, flags);
            }
            return PackageParserMarshmallow.parsePackage.callWithException(parser, packageFile, flags);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return PackageParserLollipop22.parsePackage.callWithException(parser, packageFile, flags);
        } else if (API_LEVEL >= LOLLIPOP) {
            return PackageParserLollipop.parsePackage.callWithException(parser, packageFile, flags);
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            return PackageParserJellyBean17.parsePackage.callWithException(parser, packageFile, null,
                    new DisplayMetrics(), flags);
        } else if (API_LEVEL >= JELLY_BEAN) {
            return PackageParserJellyBean.parsePackage.callWithException(parser, packageFile, null,
                    new DisplayMetrics(), flags);
        } else {
            return mirror.android.content.pm.PackageParser.parsePackage.callWithException(parser, packageFile, null,
                    new DisplayMetrics(), flags);
        }
    }

    public static ServiceInfo generateServiceInfo(Service service, int flags) {
        if (sUseFallback34) {
            return PackageParserCompat34.generateServiceInfo(service, flags, myUserId);
        }
        if (API_LEVEL >= M) {
            if (PackageParserMarshmallow.generateServiceInfo == null) {
                return PackageParserCompat34.generateServiceInfo(service, flags, myUserId);
            }
            return PackageParserMarshmallow.generateServiceInfo.call(service, flags, sUserState, myUserId);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return PackageParserLollipop22.generateServiceInfo.call(service, flags, sUserState, myUserId);
        } else if (API_LEVEL >= LOLLIPOP) {
            return PackageParserLollipop.generateServiceInfo.call(service, flags, sUserState, myUserId);
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            return PackageParserJellyBean17.generateServiceInfo.call(service, flags, sUserState, myUserId);
        } else if (API_LEVEL >= JELLY_BEAN) {
            return PackageParserJellyBean.generateServiceInfo.call(service, flags, false, 1, myUserId);
        } else {
            return mirror.android.content.pm.PackageParser.generateServiceInfo.call(service, flags);
        }
    }

    public static ApplicationInfo generateApplicationInfo(Package p, int flags) {
        if (sUseFallback34) {
            return PackageParserCompat34.generateApplicationInfo(p, flags);
        }
        if (API_LEVEL >= M) {
            if (PackageParserMarshmallow.generateApplicationInfo == null) {
                return PackageParserCompat34.generateApplicationInfo(p, flags);
            }
            return PackageParserMarshmallow.generateApplicationInfo.call(p, flags, sUserState);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return PackageParserLollipop22.generateApplicationInfo.call(p, flags, sUserState);
        } else if (API_LEVEL >= LOLLIPOP) {
            return PackageParserLollipop.generateApplicationInfo.call(p, flags, sUserState);
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            return PackageParserJellyBean17.generateApplicationInfo.call(p, flags, sUserState);
        } else if (API_LEVEL >= JELLY_BEAN) {
            return PackageParserJellyBean.generateApplicationInfo.call(p, flags, false, 1);
        } else {
            return mirror.android.content.pm.PackageParser.generateApplicationInfo.call(p, flags);
        }
    }

    public static ActivityInfo generateActivityInfo(Activity activity, int flags) {
        if (sUseFallback34) {
            return PackageParserCompat34.generateActivityInfo(activity, flags, myUserId);
        }
        if (API_LEVEL >= M) {
            if (PackageParserMarshmallow.generateActivityInfo == null) {
                return PackageParserCompat34.generateActivityInfo(activity, flags, myUserId);
            }
            return PackageParserMarshmallow.generateActivityInfo.call(activity, flags, sUserState, myUserId);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return PackageParserLollipop22.generateActivityInfo.call(activity, flags, sUserState, myUserId);
        } else if (API_LEVEL >= LOLLIPOP) {
            return PackageParserLollipop.generateActivityInfo.call(activity, flags, sUserState, myUserId);
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            return PackageParserJellyBean17.generateActivityInfo.call(activity, flags, sUserState, myUserId);
        } else if (API_LEVEL >= JELLY_BEAN) {
            return PackageParserJellyBean.generateActivityInfo.call(activity, flags, false, 1, myUserId);
        } else {
            return mirror.android.content.pm.PackageParser.generateActivityInfo.call(activity, flags);
        }
    }

    public static ProviderInfo generateProviderInfo(Provider provider, int flags) {
        if (sUseFallback34) {
            return PackageParserCompat34.generateProviderInfo(provider, flags, myUserId);
        }
        if (API_LEVEL >= M) {
            if (PackageParserMarshmallow.generateProviderInfo == null) {
                return PackageParserCompat34.generateProviderInfo(provider, flags, myUserId);
            }
            return PackageParserMarshmallow.generateProviderInfo.call(provider, flags, sUserState, myUserId);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            return PackageParserLollipop22.generateProviderInfo.call(provider, flags, sUserState, myUserId);
        } else if (API_LEVEL >= LOLLIPOP) {
            return PackageParserLollipop.generateProviderInfo.call(provider, flags, sUserState, myUserId);
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            return PackageParserJellyBean17.generateProviderInfo.call(provider, flags, sUserState, myUserId);
        } else if (API_LEVEL >= JELLY_BEAN) {
            return PackageParserJellyBean.generateProviderInfo.call(provider, flags, false, 1, myUserId);
        } else {
            return mirror.android.content.pm.PackageParser.generateProviderInfo.call(provider, flags);
        }
    }

    public static PackageInfo generatePackageInfo(Package p, int flags, long firstInstallTime, long lastUpdateTime) {
        if (sUseFallback34) {
            return PackageParserCompat34.generatePackageInfo(p, GIDS, flags, firstInstallTime, lastUpdateTime);
        }
        if (API_LEVEL >= M) {
            if (PackageParserMarshmallow.generatePackageInfo == null) {
                return PackageParserCompat34.generatePackageInfo(p, GIDS, flags, firstInstallTime, lastUpdateTime);
            }
            return PackageParserMarshmallow.generatePackageInfo.call(p, GIDS, flags, firstInstallTime, lastUpdateTime,
                    null, sUserState);
        } else if (API_LEVEL >= LOLLIPOP) {
            if (PackageParserLollipop22.generatePackageInfo != null) {
                return PackageParserLollipop22.generatePackageInfo.call(p, GIDS, flags, firstInstallTime, lastUpdateTime,
                        null, sUserState);
            } else {
                return PackageParserLollipop.generatePackageInfo.call(p, GIDS, flags, firstInstallTime, lastUpdateTime,
                        null, sUserState);
            }
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            return PackageParserJellyBean17.generatePackageInfo.call(p, GIDS, flags, firstInstallTime, lastUpdateTime,
                    null, sUserState);
        } else if (API_LEVEL >= JELLY_BEAN) {
            return PackageParserJellyBean.generatePackageInfo.call(p, GIDS, flags, firstInstallTime, lastUpdateTime,
                    null);
        } else {
            return mirror.android.content.pm.PackageParser.generatePackageInfo.call(p, GIDS, flags, firstInstallTime,
                    lastUpdateTime);
        }
    }

    public static void collectCertificates(PackageParser parser, Package p, int flags) throws Throwable {
        if (API_LEVEL >= 28) {
            if (PackageParserP28.collectCertificates != null) {
                PackageParserP28.collectCertificates.callWithException(p, true);
            } else {
                // Fallback: use direct reflection via PackageParserCompat34
                PackageParserCompat34.collectCertificates(parser, p);
            }
        } else if (API_LEVEL >= N) {
            PackageParserNougat.collectCertificates.callWithException(p, flags);
        } else if (API_LEVEL >= M) {
            PackageParserMarshmallow.collectCertificates.callWithException(parser, p, flags);
        } else if (API_LEVEL >= LOLLIPOP_MR1) {
            PackageParserLollipop22.collectCertificates.callWithException(parser, p, flags);
        } else if (API_LEVEL >= LOLLIPOP) {
            PackageParserLollipop.collectCertificates.callWithException(parser, p, flags);
        } else if (API_LEVEL >= JELLY_BEAN_MR1) {
            PackageParserJellyBean17.collectCertificates.callWithException(parser, p, flags);
        } else if (API_LEVEL >= JELLY_BEAN) {
            PackageParserJellyBean.collectCertificates.callWithException(parser, p, flags);
        } else {
            mirror.android.content.pm.PackageParser.collectCertificates.call(parser, p, flags);
        }
    }
}
