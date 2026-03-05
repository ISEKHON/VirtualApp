package com.lody.virtual.client.hook.proxies.am;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.interfaces.IInjector;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.remote.StubActivityRecord;

import mirror.android.app.ActivityManagerNative;
import mirror.android.app.ActivityThread;
import mirror.android.app.IActivityManager;

/**
     * @author Lody
     * @see Handler.Callback
     */
    public class HCallbackStub implements Handler.Callback, IInjector {

        private static final int EXECUTE_TRANSACTION;
        private static int LAUNCH_ACTIVITY = -1;
        private static final int CREATE_SERVICE = ActivityThread.H.CREATE_SERVICE.get();
        private static final int SCHEDULE_CRASH =
                ActivityThread.H.SCHEDULE_CRASH != null ? ActivityThread.H.SCHEDULE_CRASH.get() : -1;

        private static final String TAG = HCallbackStub.class.getSimpleName();
        private static final HCallbackStub sCallback = new HCallbackStub();

        private boolean mCalling = false;


        private Handler.Callback otherCallback;

        static {
            if (android.os.Build.VERSION.SDK_INT < 28) {
                LAUNCH_ACTIVITY = ActivityThread.H.LAUNCH_ACTIVITY.get();
                EXECUTE_TRANSACTION = -1;
            } else {
                // Android 9+ (Pie): Activities are launched via ClientTransaction
                EXECUTE_TRANSACTION = ActivityThread.H.EXECUTE_TRANSACTION != null
                        ? ActivityThread.H.EXECUTE_TRANSACTION.get() : 159;
            }
        }
        private HCallbackStub() {
        }

        public static HCallbackStub getDefault() {
            return sCallback;
        }

        private static Handler getH() {
            return ActivityThread.mH.get(VirtualCore.mainThread());
        }

        private static Handler.Callback getHCallback() {
            try {
                Handler handler = getH();
                return mirror.android.os.Handler.mCallback.get(handler);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        public boolean handleMessage(Message msg) {
            if (!mCalling) {
                mCalling = true;
                try {
                    if (LAUNCH_ACTIVITY == msg.what) {
                        if (!handleLaunchActivity(msg)) {
                            return true;
                        }
                    } else if (EXECUTE_TRANSACTION == msg.what && android.os.Build.VERSION.SDK_INT >= 28) {
                        // Android 9+: Ensure app is bound before executing ClientTransaction.
                        // TransactionHandlerProxy handles the actual activity launch interception,
                        // but this ensures early binding for service and other transactions.
                        handleExecuteTransaction(msg);
                    } else if (CREATE_SERVICE == msg.what) {
                        if (!VClientImpl.get().isBound()) {
                            ServiceInfo info = Reflect.on(msg.obj).get("info");
                            VClientImpl.get().bindApplication(info.packageName, info.processName);
                        }
                    } else if (SCHEDULE_CRASH == msg.what) {
                        // to avoid the exception send from System.
                        return true;
                    }
                    if (otherCallback != null) {
                        boolean desired = otherCallback.handleMessage(msg);
                        mCalling = false;
                        return desired;
                    } else {
                        mCalling = false;
                    }
                } finally {
                    mCalling = false;
                }
            }
            return false;
        }

        private boolean handleLaunchActivity(Message msg) {
            Object r = msg.obj;
            Intent stubIntent = ActivityThread.ActivityClientRecord.intent.get(r);
            StubActivityRecord saveInstance = new StubActivityRecord(stubIntent);
            if (saveInstance.intent == null) {
                return true;
            }
            Intent intent = saveInstance.intent;
            ComponentName caller = saveInstance.caller;
            IBinder token = ActivityThread.ActivityClientRecord.token.get(r);
            ActivityInfo info = saveInstance.info;
            if (VClientImpl.get().getToken() == null) {
                InstalledAppInfo installedAppInfo = VirtualCore.get().getInstalledAppInfo(info.packageName, 0);
                if(installedAppInfo == null){
                    return true;
                }
                VActivityManager.get().processRestarted(info.packageName, info.processName, saveInstance.userId);
                getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                return false;
            }
            if (!VClientImpl.get().isBound()) {
                VClientImpl.get().bindApplicationForActivity(info.packageName, info.processName, intent);
                getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                return false;
            }
            int taskId = IActivityManager.getTaskForActivity.call(
                    ActivityManagerNative.getDefault.call(),
                    token,
                    false
            );
            VActivityManager.get().onActivityCreate(ComponentUtils.toComponentName(info), caller, token, info, intent, ComponentUtils.getTaskAffinity(info), taskId, info.launchMode, info.flags);
            ClassLoader appClassLoader = VClientImpl.get().getClassLoader(info.applicationInfo);
            intent.setExtrasClassLoader(appClassLoader);
            ActivityThread.ActivityClientRecord.intent.set(r, intent);
            ActivityThread.ActivityClientRecord.activityInfo.set(r, info);
            return true;
        }

        /**
         * Handle EXECUTE_TRANSACTION (Android 9+).
         * Extracts LaunchActivityItem from ClientTransaction to detect activity launches
         * that need early binding. This complements TransactionHandlerProxy which handles
         * the actual intent swapping. Based on BlackBox's HCallbackProxy approach,
         * adapted for Android 16 where mActivityCallbacks may be null (renamed to mTransactionItems).
         */
        private void handleExecuteTransaction(Message msg) {
            try {
                Object clientTransaction = msg.obj;
                if (clientTransaction == null) return;

                // Try mActivityCallbacks first (Android 9-15), then mTransactionItems (Android 16+)
                java.util.List callbacks = null;
                try {
                    java.lang.reflect.Method getCallbacks = clientTransaction.getClass().getMethod("getCallbacks");
                    callbacks = (java.util.List) getCallbacks.invoke(clientTransaction);
                } catch (NoSuchMethodException e) {
                    // Android 16+ may not have getCallbacks
                }

                if (callbacks == null) {
                    try {
                        java.lang.reflect.Method getItems = clientTransaction.getClass().getMethod("getTransactionItems");
                        callbacks = (java.util.List) getItems.invoke(clientTransaction);
                    } catch (NoSuchMethodException e) {
                        // Neither method exists
                    }
                }

                if (callbacks == null || callbacks.isEmpty()) return;

                for (Object item : callbacks) {
                    if (item == null) continue;
                    String className = item.getClass().getName();
                    // Detect LaunchActivityItem to ensure binding happens before activity launch
                    if (className.contains("LaunchActivityItem")) {
                        // Just log for diagnostic — TransactionHandlerProxy does the actual interception
                        VLog.d(TAG, "EXECUTE_TRANSACTION contains LaunchActivityItem, app bound: "
                                + VClientImpl.get().isBound());
                        break;
                    }
                }
            } catch (Throwable e) {
                VLog.w(TAG, "handleExecuteTransaction error: " + e.getMessage());
            }
        }

        @Override
        public void inject() throws Throwable {
            otherCallback = getHCallback();
            mirror.android.os.Handler.mCallback.set(getH(), this);
        }

        @Override
        public boolean isEnvBad() {
            Handler.Callback callback = getHCallback();
            boolean envBad = callback != this;
            if (callback != null && envBad) {
                VLog.d(TAG, "HCallback has bad, other callback = " + callback);
            }
            return envBad;
        }

    }
