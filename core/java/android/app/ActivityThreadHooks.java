package android.app;

import android.app.ActivityThread.AppBindData;
import android.content.Context;
import android.os.Bundle;

import com.android.internal.gmscompat.GmsHooks;

class ActivityThreadHooks {

    // called after the initial app context is constructed
    // ActivityThread.handleBindApplication
    static Bundle onBind(AppBindData appBindData) {
        AppGlobals.setInitialPackageId(appBindData.appInfo.ext().getPackageId());
        return null;
    }

    // called after ActivityThread instrumentation is inited, which happens before execution of any
    // of app's code
    // ActivityThread.handleBindApplication
    static void onBind2(Context appContext, Bundle appBindArgs) {
        // GosPackageState change callbacks (StorageScopes/ContactScopes) are not ported.
    }

    static void onGosPackageStateChanged(Bundle appBindArgs) {
        // no-op: StorageScopes/ContactScopes are not ported.
    }

    static Service instantiateService(String className) {
        Service res = null;
        if (res == null) {
            res = GmsHooks.maybeInstantiateService(className);
        }
        return res;
    }
}
