package net.prototux.aare.hooks;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * On-device replacement for the Frida test harness.
 *
 * <p>Maps checks {@code CAPTURE_VIDEO_OUTPUT} against the caller's UID inside its own process.
 * The hook grants that one signature permission only to AARE's UID. Waze's public Car App
 * Library host validator is allowed only for AARE. Everything is scoped by Vector to the two
 * target packages; no other app is affected.
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String AARE = "net.prototux.aare";
    private static final String CAPTURE = "android.permission.CAPTURE_VIDEO_OUTPUT";
    private static final AtomicBoolean GRANT_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean WAKE_LOGGED = new AtomicBoolean();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if ("com.google.android.apps.maps".equals(lpparam.packageName)) {
            hookMapsPermission(lpparam);
        } else if ("com.waze".equals(lpparam.packageName)) {
            hookWazeValidator(lpparam);
        }
    }

    private void hookMapsPermission(LoadPackageParam lpparam) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) return;
                Object arg = param.args[0];
                if (!CAPTURE.equals(arg)) return;
                if (!isAareCaller()) return;
                param.setResult(PackageManager.PERMISSION_GRANTED);
                if (GRANT_LOGGED.compareAndSet(false, true)) {
                    XposedBridge.log("AARE hooks: granted " + CAPTURE + " to AARE uid " + Binder.getCallingUid());
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.ContextImpl", lpparam.classLoader,
                    "checkCallingPermission", String.class, hook);
            XposedBridge.log("AARE hooks: ContextImpl.checkCallingPermission installed");
        } catch (Throwable t) {
            XposedBridge.log("AARE hooks: ContextImpl hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.ContextWrapper", lpparam.classLoader,
                    "checkCallingPermission", String.class, hook);
            XposedBridge.log("AARE hooks: ContextWrapper.checkCallingPermission installed");
        } catch (Throwable t) {
            XposedBridge.log("AARE hooks: ContextWrapper hook failed: " + t);
        }
    }

    private static boolean isAareCaller() {
        int caller = Binder.getCallingUid();
        if (caller == 0) return false; // root/development shells are not AARE
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return false;
        try {
            int aareUid = ctx.getPackageManager().getPackageUid(AARE, 0);
            return caller == aareUid;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookWazeValidator(LoadPackageParam lpparam) {
        try {
            Class<?> hostInfo = XposedHelpers.findClass("androidx.car.app.HostInfo", lpparam.classLoader);
            Class<?> validator = XposedHelpers.findClass("androidx.car.app.validation.HostValidator", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(validator, "isValidHost", hostInfo, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                    if (WAKE_LOGGED.compareAndSet(false, true)) {
                        XposedBridge.log("AARE hooks: Waze HostValidator allows AARE");
                    }
                }
            });
            XposedBridge.log("AARE hooks: Waze HostValidator.isValidHost installed");
        } catch (Throwable t) {
            XposedBridge.log("AARE hooks: Waze validator hook failed: " + t);
        }
    }
}
