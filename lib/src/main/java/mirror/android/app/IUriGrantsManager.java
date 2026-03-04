package mirror.android.app;

import android.os.IBinder;
import android.os.IInterface;

import mirror.MethodParams;
import mirror.RefClass;
import mirror.RefStaticMethod;

public class IUriGrantsManager {

    public static Class<?> TYPE = RefClass.load(IUriGrantsManager.class,
            "android.app.IUriGrantsManager");

    public static class Stub {
        public static Class<?> TYPE = RefClass.load(Stub.class,
                "android.app.IUriGrantsManager$Stub");
        @MethodParams(IBinder.class)
        public static RefStaticMethod<IInterface> asInterface;
    }
}
