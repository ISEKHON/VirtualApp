package mirror.android.app;

import android.content.Intent;
import android.os.IBinder;

import mirror.RefClass;
import mirror.RefMethod;
import mirror.MethodParams;

/**
 * Mirror for IApplicationThread on Android 12+ (API 31+).
 * scheduleBindService gained an extra long bindSeq parameter in Android 12.
 */
public class IApplicationThreadS {
    public static Class<?> TYPE = RefClass.load(IApplicationThreadS.class, "android.app.IApplicationThread");

    @MethodParams({IBinder.class, Intent.class, boolean.class, int.class, long.class})
    public static RefMethod<Void> scheduleBindService;
}
