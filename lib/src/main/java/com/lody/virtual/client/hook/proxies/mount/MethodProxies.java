package com.lody.virtual.client.hook.proxies.mount;

import android.content.Context;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.os.VUserHandle;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * @author Lody
 */

class MethodProxies {

    /**
     * ThreadLocal flag to prevent recursive hook invocation when using
     * the host StorageManager as fallback.
     */
    private static final ThreadLocal<Boolean> sBypassHook = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Get StorageVolumes from the host context's StorageManager.
     * Uses a bypass flag to prevent our hook from intercepting the inner call.
     */
    private static StorageVolume[] getHostVolumes() {
        sBypassHook.set(Boolean.TRUE);
        try {
            Context ctx = VirtualCore.get().getContext();
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (Build.VERSION.SDK_INT >= 24) {
                List<StorageVolume> volumes = sm.getStorageVolumes();
                if (volumes != null && !volumes.isEmpty()) {
                    return volumes.toArray(new StorageVolume[0]);
                }
            }
            // Fallback: use getVolumeList() via reflection (hidden API)
            try {
                Method getVolumeListMethod = StorageManager.class.getDeclaredMethod("getVolumeList");
                getVolumeListMethod.setAccessible(true);
                StorageVolume[] vols = (StorageVolume[]) getVolumeListMethod.invoke(sm);
                if (vols != null && vols.length > 0) {
                    return vols;
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            android.util.Log.w("GetVolumeList", "getHostVolumes failed", t);
        } finally {
            sBypassHook.set(Boolean.FALSE);
        }
        return null;
    }

    static class GetVolumeList extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getVolumeList";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            if (args == null || args.length == 0) {
                return super.beforeCall(who, method, args);
            }
            if (args[0] instanceof Integer) {
                if (Build.VERSION.SDK_INT >= 33) {
                    // Android 13+: StorageManager passes userId (not uid) to
                    // IStorageManager.getVolumeList(). The server checks that
                    // UserHandle.getUserId(arg0) matches the calling process's userId.
                    // Pass the real userId (typically 0 for the main user).
                    args[0] = VUserHandle.getUserId(getRealUid());
                } else {
                    // Android 12 and below: parameter is the actual uid.
                    args[0] = getRealUid();
                }
            }
            MethodParameterUtils.replaceFirstAppPkg(args);
            return super.beforeCall(who, method, args);
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            // If bypass flag is set, don't intercept — just call the real method directly
            if (Boolean.TRUE.equals(sBypassHook.get())) {
                return super.call(who, method, args);
            }
            try {
                return super.call(who, method, args);
            } catch (Throwable e) {
                // method.invoke wraps SecurityException in InvocationTargetException.
                Throwable cause = e;
                while (cause != null) {
                    if (cause instanceof SecurityException) {
                        android.util.Log.w("GetVolumeList", "SecurityException in getVolumeList, using host fallback", e);
                        // Get volumes from the host context's StorageManager
                        StorageVolume[] hostVols = getHostVolumes();
                        if (hostVols != null) {
                            return hostVols;
                        }
                        android.util.Log.w("GetVolumeList", "Host fallback also failed, returning null");
                        return null;
                    }
                    cause = cause.getCause();
                }
                throw e;
            }
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            return result;
        }
    }

    static class Mkdirs extends MethodProxy {

        @Override
        public String getMethodName() {
            return "mkdirs";
        }

        @Override
        public boolean beforeCall(Object who, Method method, Object... args) {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return super.beforeCall(who, method, args);
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                return super.call(who, method, args);
            }
            String path;
            if (args.length == 1) {
                path = (String) args[0];
            } else {
                path = (String) args[1];
            }
            File file = new File(path);
            if (!file.exists() && !file.mkdirs()) {
                return -1;
            }
            return 0;
        }
    }
}
