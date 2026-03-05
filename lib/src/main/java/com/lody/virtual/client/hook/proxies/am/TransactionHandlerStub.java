package com.lody.virtual.client.hook.proxies.am;

import android.app.ClientTransactionHandler;
import android.app.TransactionHandlerProxy;
import android.util.Log;

import com.lody.virtual.client.interfaces.IInjector;

import java.lang.reflect.Field;

import mirror.android.app.ActivityThread;

/**
 * @author weishu
 * @date 2018/8/7.
 */
public class TransactionHandlerStub implements IInjector {
    private static final String TAG = "TransactionHandlerStub";

    @Override
    public void inject() throws Throwable {
        Log.i(TAG, "inject transaction handler.");
        Object activityThread = ActivityThread.currentActivityThread.call();
        Object transactionExecutor = ActivityThread.mTransactionExecutor.get(activityThread);
        if (transactionExecutor == null) {
            Log.e(TAG, "mTransactionExecutor is null on API " + android.os.Build.VERSION.SDK_INT
                    + " — activity interception will not work!");
            return;
        }

        // Try to find mTransactionHandler field (may be renamed on future API levels)
        Field mTransactionHandlerField = null;
        try {
            mTransactionHandlerField = transactionExecutor.getClass().getDeclaredField("mTransactionHandler");
        } catch (NoSuchFieldException e) {
            // Android 16+ may rename this field — try alternatives
            for (Field f : transactionExecutor.getClass().getDeclaredFields()) {
                if (ClientTransactionHandler.class.isAssignableFrom(f.getType())) {
                    mTransactionHandlerField = f;
                    Log.w(TAG, "Using alternative field: " + f.getName() + " for transaction handler");
                    break;
                }
            }
        }

        if (mTransactionHandlerField == null) {
            Log.e(TAG, "Cannot find transaction handler field in " + transactionExecutor.getClass().getName()
                    + " on API " + android.os.Build.VERSION.SDK_INT);
            return;
        }

        mTransactionHandlerField.setAccessible(true);
        ClientTransactionHandler original = (ClientTransactionHandler) mTransactionHandlerField.get(transactionExecutor);
        TransactionHandlerProxy proxy = new TransactionHandlerProxy(original);

        mTransactionHandlerField.set(transactionExecutor, proxy);
        Log.i(TAG, "executor's handler: " + mTransactionHandlerField.get(transactionExecutor));
    }

    @Override
    public boolean isEnvBad() {
        return false;
    }
}
