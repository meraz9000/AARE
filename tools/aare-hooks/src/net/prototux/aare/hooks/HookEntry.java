package net.prototux.aare.hooks;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.view.MotionEvent;

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
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.internal.policy.PhoneWindow", lpparam.classLoader,
                    "injectInputEvent", android.view.InputEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.view.InputEvent ev = (android.view.InputEvent) param.args[0];
                            if (ev instanceof MotionEvent) {
                                MotionEvent e = (MotionEvent) ev;
                                XposedBridge.log("AARE hooks: PhoneWindow.injectInputEvent action=" + e.getActionMasked()
                                        + " x=" + e.getX() + " y=" + e.getY());
                            }
                        }
                    });
            XposedBridge.log("AARE hooks: PhoneWindow.injectInputEvent installed");
        } catch (Throwable t) {
            XposedBridge.log("AARE hooks: PhoneWindow.injectInputEvent hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "android.view.ViewRootImpl", lpparam.classLoader,
                    "dispatchInputEvent", android.view.InputEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.view.InputEvent ev = (android.view.InputEvent) param.args[0];
                            if (ev instanceof MotionEvent) {
                                MotionEvent e = (MotionEvent) ev;
                                XposedBridge.log("AARE hooks: ViewRootImpl.dispatchInputEvent action=" + e.getActionMasked()
                                        + " x=" + e.getX() + " y=" + e.getY());
                            }
                        }
                    });
            XposedBridge.log("AARE hooks: ViewRootImpl.dispatchInputEvent installed");
        } catch (Throwable t) {
            XposedBridge.log("AARE hooks: ViewRootImpl.dispatchInputEvent hook failed: " + t);
        }
        new Thread(() -> {
            try {
                Thread.sleep(4000);
                Class<?> carUiInfo = XposedHelpers.findClassIfExists("com.google.android.gms.car.CarUiInfo", lpparam.classLoader);
                if (carUiInfo != null) {
                    for (java.lang.reflect.Constructor<?> c : carUiInfo.getDeclaredConstructors()) {
                        XposedBridge.hookAllConstructors(carUiInfo, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                XposedBridge.log("AARE hooks: CarUiInfo args=" + java.util.Arrays.toString(param.args));
                            }
                        });
                    }
                    XposedBridge.log("AARE hooks: CarUiInfo constructor installed");
                }
                Class<?> bemz = XposedHelpers.findClassIfExists("bemz", lpparam.classLoader);
                XposedBridge.log("AARE hooks: bemz class=" + bemz);
                Class<?> bemm = XposedHelpers.findClassIfExists("bemm", lpparam.classLoader);
                XposedBridge.log("AARE hooks: bemm class=" + bemm);
                Class<?> beiy = XposedHelpers.findClassIfExists("beiy", lpparam.classLoader);
                XposedBridge.log("AARE hooks: beiy class=" + beiy);
                if (bemz != null) {
                    XposedBridge.hookAllMethods(bemz, "c", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            MotionEvent e = (MotionEvent) param.args[0];
                            Object bejn = XposedHelpers.getObjectField(param.thisObject, "a");
                            boolean showing = bejn != null && ((android.app.Dialog) bejn).isShowing();
                            boolean j = bejn != null && XposedHelpers.getBooleanField(bejn, "j");
                            XposedBridge.log("AARE hooks: bemz.c action=" + e.getActionMasked()
                                    + " source=" + e.getSource() + " showing=" + showing + " j=" + j);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log("AARE hooks: bemz.c result=" + param.getResult());
                        }
                    });
                    XposedBridge.log("AARE hooks: bemz.c installed");
                }
                // The tx7 handler waits for the CarActivity input-focus flag (belz.E)
                // before posting the touch runnable. The SCVH path does not always set it,
                // which deadlocks the binder thread; set it for each posted bels.
                Class<?> bels = XposedHelpers.findClassIfExists("bels", lpparam.classLoader);
                if (bels != null) {
                    XposedBridge.hookAllConstructors(bels, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object belz = XposedHelpers.getObjectField(param.thisObject, "a");
                            if (belz != null) {
                                XposedHelpers.setBooleanField(belz, "E", true);
                                XposedBridge.log("AARE hooks: bels focus flag set");
                            }
                        }
                    });
                    XposedBridge.log("AARE hooks: bels focus hook installed");
                }
                // Modern Maps path: bemp.c() is a no-op because the SCVH child normally
                // receives input from InputDispatcher. AARE injects car touch as tx7, so
                // dispatch those events directly into the SCVH window's view tree.
                Class<?> bemp = XposedHelpers.findClassIfExists("bemp", lpparam.classLoader);
                if (bemp != null) {
                    XposedBridge.hookAllMethods(bemp, "c", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            MotionEvent e = (MotionEvent) param.args[0];
                            try {
                                Object window = XposedHelpers.getObjectField(param.thisObject, "h");
                                boolean handled = false;
                                if (window != null) {
                                    // bems.a is the decor; bems.b is the content FrameLayout that
                                    // actually contains the map view. Dispatch to b first.
                                    Object content = XposedHelpers.getObjectField(window, "b");
                                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                                        dumpView((android.view.View) XposedHelpers.getObjectField(window, "a"), 0);
                                        if (content instanceof android.view.View) {
                                            dumpView((android.view.View) content, 0);
                                        }
                                    }
                                    if (content instanceof android.view.View) {
                                        android.view.View contentView = (android.view.View) content;
                                        android.view.View mapSurface = findViewByClass(contentView, "bvtk");
                                        if (mapSurface != null) {
                                            handled = mapSurface.dispatchTouchEvent(e);
                                            XposedBridge.log("AARE hooks: bemp.c map surface handled=" + handled
                                                    + " size=" + mapSurface.getWidth() + "x" + mapSurface.getHeight());
                                        }
                                        if (!handled) {
                                            handled = contentView.dispatchTouchEvent(e);
                                        }
                                    }
                                    if (!handled) {
                                        handled = (Boolean) XposedHelpers.callMethod(
                                                window, "superDispatchTouchEvent", e);
                                    }
                                }
                                XposedBridge.log("AARE hooks: bemp.c action=" + e.getActionMasked()
                                        + " pointers=" + e.getPointerCount()
                                        + " x=" + e.getX() + " y=" + e.getY()
                                        + " handled=" + handled);
                            } catch (Throwable t) {
                                XposedBridge.log("AARE hooks: bemp.c failed: " + t);
                            }
                            param.setResult(null);
                        }
                    });
                    XposedBridge.log("AARE hooks: bemp.c installed");
                }
                if (bemm != null) {
                    XposedBridge.hookAllMethods(bemm, "c", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            MotionEvent e = (MotionEvent) param.args[0];
                            Object beiy = XposedHelpers.getObjectField(param.thisObject, "a");
                            boolean showing = beiy != null && ((android.app.Dialog) beiy).isShowing();
                            boolean j = beiy != null && XposedHelpers.getBooleanField(beiy, "j");
                            int displayId = -1;
                            try {
                                Object display = XposedHelpers.callMethod(beiy, "getDisplay");
                                displayId = (Integer) XposedHelpers.callMethod(display, "getDisplayId");
                                XposedHelpers.callMethod(e, "setDisplayId", displayId);
                            } catch (Throwable t) {
                                XposedBridge.log("AARE hooks: bemm display id failed: " + t);
                            }
                            boolean windowFocus = false;
                            int windowFlags = -1;
                            try {
                                Object window = XposedHelpers.callMethod(beiy, "getWindow");
                                android.view.Window w = (android.view.Window) window;
                                android.view.View decor = w.getDecorView();
                                windowFocus = decor.hasWindowFocus();
                                windowFlags = w.getAttributes().flags;
                                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                                    w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                                    w.setLocalFocus(true, true);
                                    XposedBridge.log("AARE hooks: decor=" + decor.getClass().getName()
                                            + " size=" + decor.getWidth() + "x" + decor.getHeight()
                                            + " attached=" + decor.isAttachedToWindow());
                                    dumpView(decor, 0);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("AARE hooks: bemm focus failed: " + t);
                            }
                            XposedBridge.log("AARE hooks: bemm.c action=" + e.getActionMasked()
                                    + " source=" + e.getSource() + " display=" + displayId + " showing=" + showing + " j=" + j
                                    + " windowFocus=" + windowFocus + " flags=" + windowFlags);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log("AARE hooks: bemm.c result=" + param.getResult());
                            try {
                                Object beiy = XposedHelpers.getObjectField(param.thisObject, "a");
                                Object window = XposedHelpers.callMethod(beiy, "getWindow");
                                android.view.View decor = ((android.view.Window) window).getDecorView();
                                android.view.View content = findViewByClass(decor, "qpy");
                                if (content != null) {
                                    MotionEvent copy = MotionEvent.obtain((MotionEvent) param.args[0]);
                                    boolean handled = content.dispatchTouchEvent(copy);
                                    copy.recycle();
                                    XposedBridge.log("AARE hooks: bemm.c qpy dispatch=" + handled);
                                } else {
                                    XposedBridge.log("AARE hooks: bemm.c qpy not found");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("AARE hooks: bemm.c qpy failed: " + t);
                            }
                        }
                    });
                    XposedBridge.log("AARE hooks: bemm.c installed");
                }
            } catch (Throwable t) {
                XposedBridge.log("AARE hooks: bemz.c hook failed: " + t);
            }
        }, "aare-hook-bemz").start();
    }

    private static android.view.View findViewByClass(android.view.View view, String simpleName) {
        if (view == null) return null;
        if (view.getClass().getSimpleName().equals(simpleName)) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = findViewByClass(g.getChildAt(i), simpleName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void dumpView(android.view.View view, int depth) {
        if (view == null || depth > 9) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(view.getClass().getName())
                .append(" ").append(view.getWidth()).append("x").append(view.getHeight())
                .append(" clickable=").append(view.isClickable())
                .append(" focusable=").append(view.isFocusable())
                .append(" attached=").append(view.isAttachedToWindow());
        XposedBridge.log("AARE hooks: view " + sb);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) dumpView(g.getChildAt(i), depth + 1);
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
