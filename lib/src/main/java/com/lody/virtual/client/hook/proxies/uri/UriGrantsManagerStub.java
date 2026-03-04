package com.lody.virtual.client.hook.proxies.uri;

import android.annotation.TargetApi;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;

import mirror.android.app.IUriGrantsManager;

/**
 * Hook for IUriGrantsManager service ("uri_grants"), introduced in Android 10 (Q).
 *
 * On Android 10+, URI permission methods moved from IActivityManager to IUriGrantsManager.
 * Without this hook, calls like getUriPermissions() use the virtual app's package name,
 * which the real system doesn't recognize as belonging to VirtualApp's UID, causing
 * SecurityException: "Calling uid X does not own package Y".
 */
@TargetApi(Build.VERSION_CODES.Q)
public class UriGrantsManagerStub extends BinderInvocationProxy {

    private static final String URI_GRANTS_SERVICE = "uri_grants";

    public UriGrantsManagerStub() {
        super(IUriGrantsManager.Stub.asInterface, URI_GRANTS_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        // ParceledListSlice<UriPermission> getUriPermissions(String packageName, int callingUid, boolean persistedOnly)
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getUriPermissions"));
        // ParceledListSlice<GrantedUriPermission> getGrantedUriPermissions(String packageName, int userId)
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getGrantedUriPermissions"));
        // void takePersistableUriPermission(Uri uri, int modeFlags, String toPackage, int userId)
        addMethodProxy(new ReplaceCallingPkgMethodProxy("takePersistableUriPermission"));
        // void releasePersistableUriPermission(Uri uri, int modeFlags, String toPackage, int userId)
        addMethodProxy(new ReplaceCallingPkgMethodProxy("releasePersistableUriPermission"));
    }
}
