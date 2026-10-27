package net.prototux.probe;

import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A protocol-compatible stand-in for a GMS CarActivityService. It implements the same
 * ICarProjection Binder surface as Maps, but does not have Maps' signature-permission gate.
 * Used to prove the host's setup, DrawingSpec and Surface handoff end to end on this phone.
 */
public final class MockProjectionService extends Service {
    private static final String TAG = "MockProjection";
    private static final String DESCRIPTOR = "com.google.android.gms.car.ICarProjection";

    private final Handler main = new Handler(Looper.getMainLooper());
    private VirtualDisplay display;
    private Presentation presentation;
    private IBinder callback;
    private int frames;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                data.enforceInterface(DESCRIPTOR);
                Log.i(TAG, "transaction " + code);
                switch (code) {
                    case 1: {
                        callback = data.readStrongBinder();
                        IBinder car = data.readStrongBinder();
                        Log.i(TAG, "onSetup callback=" + callback + " car=" + car);
                        if (callback != null) {
                            Parcel cb = Parcel.obtain();
                            try {
                                cb.writeInt(11); // connected
                                callback.transact(23, cb, null, 0);
                            } finally {
                                cb.recycle();
                            }
                        }
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    case 26: {
                        int displayId = data.readInt();
                        int regionId = data.readInt();
                        DrawingSpec spec = readDrawingSpec(data);
                        Intent intent = data.readInt() != 0 ? Intent.CREATOR.createFromParcel(data) : null;
                        android.content.res.Configuration config = data.readInt() != 0
                                ? android.content.res.Configuration.CREATOR.createFromParcel(data) : null;
                        Bundle extras = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                        Log.i(TAG, "onProjectionStart display=" + displayId + " region=" + regionId
                                + " spec=" + spec + " intent=" + intent);
                        startPresentation(spec);
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    case 9: {
                        int displayId = data.readInt();
                        Log.i(TAG, "onProjectionStop display=" + displayId);
                        stopPresentation();
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    default:
                        if (reply != null) reply.writeNoException();
                        return true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "transaction " + code + " failed", t);
                if (reply != null) {
                    reply.writeException(t instanceof Exception ? (Exception) t : new RuntimeException(t));
                }
                return true;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind " + intent);
        return binder;
    }

    @Override
    public void onDestroy() {
        stopPresentation();
        super.onDestroy();
    }

    private void startPresentation(DrawingSpec spec) {
        if (spec == null || spec.surface == null) {
            Log.e(TAG, "no surface in DrawingSpec");
            return;
        }
        main.post(() -> {
            stopPresentation();
            try {
                DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
                display = dm.createVirtualDisplay("mock-projection",
                        spec.width, spec.height, spec.dpi, spec.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                Display d = display.getDisplay();
                Context displayContext = createDisplayContext(d);
                presentation = new Presentation(displayContext, d);
                presentation.setContentView(makeView(displayContext));
                presentation.show();
                Log.i(TAG, "presentation shown on display " + d.getDisplayId()
                        + " " + spec.width + "x" + spec.height + "@" + spec.dpi);
            } catch (Throwable t) {
                Log.e(TAG, "could not start presentation", t);
            }
        });
    }

    private View makeView(Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF0B3D2E);
        TextView text = new TextView(context);
        text.setText("Mock CarActivityService\nICarProjection → DrawingSpec → Presentation");
        text.setTextColor(Color.WHITE);
        text.setTextSize(32);
        text.setGravity(Gravity.CENTER);
        root.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void stopPresentation() {
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable ignored) {
            }
            presentation = null;
        }
        if (display != null) {
            display.release();
            display = null;
        }
    }

    /** Reads the SafeParcelable wire format written by com.google.android.gms.car.DrawingSpec. */
    private static DrawingSpec readDrawingSpec(Parcel p) {
        int header = p.readInt();
        if (header != (20293 | 0xFFFF0000)) {
            throw new IllegalArgumentException("bad SafeParcelable header: " + Integer.toHexString(header));
        }
        int size = p.readInt();
        int start = p.dataPosition();
        int end = start + size;
        DrawingSpec spec = new DrawingSpec();
        while (p.dataPosition() < end) {
            int tag = p.readInt();
            int id = tag & 0xFFFF;
            int type = tag & 0xFFFF0000;
            if (type == 0x00040000) {
                int value = p.readInt();
                switch (id) {
                    case 1: spec.width = value; break;
                    case 2: spec.height = value; break;
                    case 3: spec.dpi = value; break;
                    case 6: spec.flags = value; break;
                    case 7: spec.displayId = value; break;
                    default: break;
                }
            } else if (type == 0xFFFF0000) {
                int fieldStart = p.dataPosition();
                int fieldSize = p.readInt();
                int fieldEnd = fieldStart + 4 + fieldSize;
                switch (id) {
                    case 4: spec.surface = Surface.CREATOR.createFromParcel(p); break;
                    case 5: spec.insets = Rect.CREATOR.createFromParcel(p); break;
                    case 8: spec.bundle = Bundle.CREATOR.createFromParcel(p); break;
                    default: break;
                }
                p.setDataPosition(fieldEnd);
            } else {
                throw new IllegalArgumentException("unknown field type 0x" + Integer.toHexString(type));
            }
        }
        p.setDataPosition(end);
        return spec;
    }

    private static final class DrawingSpec {
        int width;
        int height;
        int dpi;
        Surface surface;
        Rect insets;
        int flags;
        int displayId;
        Bundle bundle;

        @Override
        public String toString() {
            return "DrawingSpec{" + width + "x" + height + "@" + dpi
                    + ", surface=" + surface + ", insets=" + insets
                    + ", displayId=" + displayId + "}";
        }
    }
}
