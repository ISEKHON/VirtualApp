package com.lody.virtual.client.hook.proxies.view;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceLastPkgMethodProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;
import com.lody.virtual.helper.utils.ArrayUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import mirror.android.view.IAutoFillManager;

/**
 * @author 陈磊.
 *
 * Hooks the AutoFill manager service for virtual apps.
 *
 * In a virtual environment, autofill session creation will always fail because the
 * system_server performs a UID check: the host process UID doesn't match the virtual
 * app's ComponentName. On Android 9+ this causes the IResultReceiver to never be
 * signalled, leading to a 5-second SyncResultReceiver timeout on the main thread (ANR).
 *
 * The fix: intercept startSession/updateOrRestartSession, do NOT call through to the
 * real service, and instead signal the IResultReceiver with a NO_SESSION result so the
 * client-side AutofillManager immediately sees "no session" without blocking.
 */

public class AutoFillManagerStub extends BinderInvocationProxy {

    private static final String TAG = "AutoFillManagerStub";

    private static final String AUTO_FILL_NAME = "autofill";

    public AutoFillManagerStub() {
        super(IAutoFillManager.Stub.asInterface, AUTO_FILL_NAME);
    }

    @SuppressLint("WrongConstant")
    @Override
    public void inject() throws Throwable {
        super.inject();
        try {
            Object AutoFillManagerInstance = getContext().getSystemService(AUTO_FILL_NAME);
            if (AutoFillManagerInstance == null) {
                throw new NullPointerException("AutoFillManagerInstance is null.");
            }
            Object AutoFillManagerProxy = getInvocationStub().getProxyInterface();
            if (AutoFillManagerProxy == null) {
                throw new NullPointerException("AutoFillManagerProxy is null.");
            }
            Field AutoFillManagerServiceField = AutoFillManagerInstance.getClass().getDeclaredField("mService");
            AutoFillManagerServiceField.setAccessible(true);
            AutoFillManagerServiceField.set(AutoFillManagerInstance, AutoFillManagerProxy);
        } catch (Throwable tr) {
            Log.e(TAG, "AutoFillManagerStub inject error.", tr);
            // Don't return — still register method proxies via the binder hook path
        }

        addMethodProxy(new SafeAutofillSessionProxy("startSession"));
        addMethodProxy(new SafeAutofillSessionProxy("updateOrRestartSession"));
        addMethodProxy(new ReplaceLastPkgMethodProxy("isServiceEnabled"));
    }

    /**
     * Proxy for startSession / updateOrRestartSession that prevents the binder call
     * from reaching system_server (which would reject it due to UID mismatch) and
     * instead signals the IResultReceiver directly with a NO_SESSION result.
     *
     * This prevents the 5-second SyncResultReceiver timeout that causes ANR when
     * interacting with EditText fields in virtual apps (e.g. Twitter login, Facebook).
     */
    static class SafeAutofillSessionProxy extends StaticMethodProxy {

        /** AutofillManager.NO_SESSION = Integer.MIN_VALUE */
        private static final int NO_SESSION = Integer.MIN_VALUE;

        SafeAutofillSessionProxy(String name) {
            super(name);
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            // Do NOT call through to the real service — system_server will reject
            // the call due to UID mismatch and silently drop the IResultReceiver
            // callback, causing a 5-second main-thread timeout (ANR).

            // Find and signal the IResultReceiver argument (Android 9+) to prevent timeout.
            // The SyncResultReceiver on the client side is waiting for send() to be called.
            if (args != null) {
                for (int i = args.length - 1; i >= 0; i--) {
                    Object arg = args[i];
                    if (arg == null) continue;
                    try {
                        // Look for send(int, Bundle) — signature of IResultReceiver
                        Method sendMethod = arg.getClass().getMethod("send", int.class, Bundle.class);
                        sendMethod.setAccessible(true);
                        sendMethod.invoke(arg, NO_SESSION, null);
                        Log.d(TAG, getMethodName() + ": sent NO_SESSION to receiver for virtual app");
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // Not a receiver, try next arg
                    } catch (Throwable t) {
                        Log.w(TAG, getMethodName() + ": failed to signal receiver", t);
                    }
                }
            }

            // Return safe default based on method return type
            // Android 8: startSession returns int directly
            // Android 9+: startSession is void (result via IResultReceiver)
            Class<?> returnType = method.getReturnType();
            if (returnType == int.class) return NO_SESSION;
            if (returnType == long.class) return (long) NO_SESSION;
            if (returnType == boolean.class) return false;
            return null;
        }
    }
}
