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
            hookTouchLog(lpparam);
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

    /**
     * Debug: logs every touch the Maps process receives at the root of each of its windows,
     * so a VirtualTouchscreen event can be followed into Maps' embedded car window.
     * adb logcat -s AareHooksTouch
     */
    private void hookTouchLog(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.view.View", lpparam.classLoader,
                    "dispatchPointerEvent", android.view.MotionEvent.class, new XC_MethodHook() {
                        private int moves;

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.view.MotionEvent e = (android.view.MotionEvent) param.args[0];
                            if (e == null) return;
                            int action = e.getActionMasked();
                            if (action == android.view.MotionEvent.ACTION_MOVE && (moves++ % 5) != 0) return;
                            android.view.View v = (android.view.View) param.thisObject;
                            Object handled = param.getResult();
                            android.util.Log.i("AareHooksTouch", String.format(java.util.Locale.US,
                                    "%s x=%.1f y=%.1f raw=%.1f,%.1f ptrs=%d display=%d src=0x%x tool=%d view=%s %dx%d attachedDisplay=%d handled=%s",
                                    android.view.MotionEvent.actionToString(action),
                                    e.getX(), e.getY(), e.getRawX(), e.getRawY(), e.getPointerCount(),
                                    XposedHelpers.callMethod(e, "getDisplayId"), e.getSource(), e.getToolType(0),
                                    v.getClass().getName(), v.getWidth(), v.getHeight(),
                                    v.getDisplay() != null ? v.getDisplay().getDisplayId() : -1,
                                    handled));
                        }
                    });
            // Earlier stage: every event arriving on any of this process's input channels.
            java.util.Set<?> rx = XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.view.InputEventReceiver", lpparam.classLoader),
                    "dispatchInputEvent", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object ev = param.args.length > 1 ? param.args[1] : null;
                            if (!(ev instanceof android.view.MotionEvent)) return;
                            android.view.MotionEvent e = (android.view.MotionEvent) ev;
                            int a = e.getActionMasked();
                            if (a == android.view.MotionEvent.ACTION_MOVE) return;
                            android.util.Log.i("AareHooksTouch", String.format(java.util.Locale.US,
                                    "receiver %s %s x=%.1f y=%.1f display=%s",
                                    param.thisObject.getClass().getName(),
                                    android.view.MotionEvent.actionToString(a), e.getX(), e.getY(),
                                    XposedHelpers.callMethod(e, "getDisplayId")));
                        }
                    });
            // Why ViewRootImpl would drop it before any view sees it.
            Class<?> stage = XposedHelpers.findClass("android.view.ViewRootImpl$InputStage", lpparam.classLoader);
            XposedBridge.hookAllMethods(stage, "shouldDropInputEvent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    Object root = XposedHelpers.getSurroundingThis(param.thisObject);
                    StringBuilder b = new StringBuilder("DROPPED by ").append(param.thisObject.getClass().getSimpleName());
                    for (String f : new String[]{"mView", "mAdded", "mStopped", "mPausedForTransition", "mIsAmbientMode"}) {
                        try {
                            Object v = XposedHelpers.getObjectField(root, f);
                            b.append(' ').append(f).append('=').append(v instanceof android.view.View ? v.getClass().getName() : v);
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        Object ai = XposedHelpers.getObjectField(root, "mAttachInfo");
                        b.append(" hasWindowFocus=").append(XposedHelpers.getObjectField(ai, "mHasWindowFocus"));
                    } catch (Throwable ignored) {
                    }
                    try {
                        b.append(" title=").append(XposedHelpers.callMethod(root, "getTitle"));
                    } catch (Throwable ignored) {
                    }
                    android.util.Log.i("AareHooksTouch", b.toString());
                }
            });
            // ViewGroup.dispatchTouchEvent is virtual and overridden all over, so the boot image
            // cannot inline it the way it can the final View.dispatchPointerEvent.
            java.util.Set<?> vg = XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.view.ViewGroup", lpparam.classLoader),
                    "dispatchTouchEvent", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            android.view.View v = (android.view.View) param.thisObject;
                            if (v.getParent() instanceof android.view.View) return; // roots only
                            android.view.MotionEvent e = (android.view.MotionEvent) param.args[0];
                            int a = e.getActionMasked();
                            if (a == android.view.MotionEvent.ACTION_MOVE) return;
                            android.util.Log.i("AareHooksTouch", String.format(java.util.Locale.US,
                                    "root %s %s x=%.1f y=%.1f raw=%.1f,%.1f display=%s viewDisplay=%d %dx%d handled=%s",
                                    v.getClass().getName(), android.view.MotionEvent.actionToString(a),
                                    e.getX(), e.getY(), e.getRawX(), e.getRawY(),
                                    XposedHelpers.callMethod(e, "getDisplayId"),
                                    v.getDisplay() != null ? v.getDisplay().getDisplayId() : -1,
                                    v.getWidth(), v.getHeight(), param.getResult()));
                            if (a == android.view.MotionEvent.ACTION_DOWN) {
                                android.util.Log.i("AareHooksTouch", "target chain: " + touchChain(v));
                            }
                        }
                    });
            int deopt = 0;
            try {
                java.lang.reflect.Method d = XposedBridge.class.getMethod("deoptimizeMethod", java.lang.reflect.Member.class);
                for (String cls : new String[]{"android.view.ViewRootImpl", "android.view.ViewRootImpl$ViewPostImeInputStage",
                        "android.view.ViewRootImpl$InputStage", "android.view.ViewRootImpl$WindowInputEventReceiver",
                        "android.view.InputEventReceiver"}) {
                    for (java.lang.reflect.Method m : XposedHelpers.findClass(cls, lpparam.classLoader).getDeclaredMethods()) {
                        try { d.invoke(null, m); deopt++; } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("AareHooksTouch", "deoptimizeMethod unavailable: " + t);
            }
            android.util.Log.i("AareHooksTouch", "hooks: receiver=" + rx.size() + " viewGroup.dispatchTouchEvent=" + vg.size() + " deoptimized=" + deopt);
            android.util.Log.i("AareHooksTouch", "touch log installed in " + lpparam.processName);
        } catch (Throwable t) {
            android.util.Log.w("AareHooksTouch", "touch log hook failed", t);
        }
    }

    /** Follows ViewGroup.mFirstTouchTarget from a root down to the view that took the gesture. */
    private static String touchChain(android.view.View root) {
        StringBuilder b = new StringBuilder();
        android.view.View v = root;
        for (int depth = 0; v != null && depth < 40; depth++) {
            b.append(depth == 0 ? "" : " > ").append(v.getClass().getName());
            try {
                if (v.getId() != android.view.View.NO_ID) {
                    b.append('#').append(v.getResources().getResourceEntryName(v.getId()));
                }
            } catch (Throwable ignored) {
            }
            b.append(String.format(java.util.Locale.US, "[%dx%d%s]", v.getWidth(), v.getHeight(),
                    v.getVisibility() == android.view.View.VISIBLE ? "" : " vis=" + v.getVisibility()));
            if (!(v instanceof android.view.ViewGroup)) {
                StringBuilder sup = new StringBuilder();
                for (Class<?> c = v.getClass().getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    sup.append(sup.length() == 0 ? "" : " < ").append(c.getName());
                }
                b.append(" leaf extends ").append(sup).append(" clickable=").append(v.isClickable())
                        .append(" enabled=").append(v.isEnabled()).append(" alpha=").append(v.getAlpha());
                try {
                    Object li = XposedHelpers.getObjectField(v, "mListenerInfo");
                    if (li != null) {
                        Object otl = XposedHelpers.getObjectField(li, "mOnTouchListener");
                        if (otl != null) b.append(" onTouchListener=").append(otl.getClass().getName());
                    }
                } catch (Throwable ignored) {
                }
                break;
            }
            Object target;
            try {
                target = XposedHelpers.getObjectField(v, "mFirstTouchTarget");
            } catch (Throwable t) {
                break;
            }
            if (target == null) {
                b.append(" (handled itself)");
                break;
            }
            v = (android.view.View) XposedHelpers.getObjectField(target, "child");
        }
        return b.toString();
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
                    if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                    try {
                        Object host = param.args[0];
                        Object name = XposedHelpers.callMethod(host, "getPackageName");
                        if (!AARE.equals(name)) return;
                        param.setResult(true);
                        if (WAKE_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log("AARE hooks: Waze HostValidator allows AARE");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("AARE hooks: Waze host check failed: " + t);
                    }
                }
            });
            XposedBridge.log("AARE hooks: Waze HostValidator.isValidHost installed");
        } catch (Throwable t) {
            XposedBridge.log("AARE hooks: Waze validator hook failed: " + t);
        }
    }
}
