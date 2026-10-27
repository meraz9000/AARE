package net.prototux.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * A minimal host for the legacy GMS Car projected-activity protocol.
 *
 * This is the client side of com.google.android.gms.car.ICarProjection. It binds to an app's
 * exported CarActivityService, performs onSetup (transaction 1), then onProjectionStart
 * (transaction 26) with a Surface that the app renders into via a Presentation.
 *
 * On a stock device the service rejects onSetup unless the caller is Google-signed or holds
 * android.permission.CAPTURE_VIDEO_OUTPUT. That check is inside the app, not the manifest.
 */
public final class ProjectedHost {
    private static final String TAG = "ProjectedHost";

    public static final String MAPS = "com.google.android.apps.maps";
    public static final String MAPS_SERVICE = "com.google.android.apps.gmm.car.GmmCarProjectionService";
    public static final ComponentName MAPS_COMPONENT = new ComponentName(MAPS, MAPS_SERVICE);
    public static final String PROJECTION_DESCRIPTOR = "com.google.android.gms.car.ICarProjection";
    public static final String CAR_DESCRIPTOR = "com.google.android.gms.car.ICar";
    public static final String CALLBACK_DESCRIPTOR = "com.google.android.gms.car.ICarProjectionCallback";

    public interface Listener {
        void onLog(String line);
        void onFrame(Bitmap bitmap, int index);
    }

    private final Context context;
    private final ComponentName component;
    private final Listener listener;
    private final int width;
    private final int height;
    private final int densityDpi;

    private ImageReader reader;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private IBinder service;
    private ServiceConnection connection;
    private int frameCount;

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 23) {
                try {
                    int state = data.readInt();
                    log("callback: connection state " + state);
                } catch (Throwable t) {
                    log("callback: bad state parcel: " + t);
                }
            } else {
                log("callback: transaction " + code);
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    /**
     * The app calls back into this object for CarInfo, CarUiInfo, connection-listener
     * registration and feature flags. Returning null/empty values lets the app start with a
     * plain fullscreen Presentation; it does not try to use a car-specific projection service.
     */
    private final Binder car = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: // getCarInfo(): CarInfo or null
                case 2: // getCarUiInfo(): CarUiInfo or null
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(0);
                    }
                    return true;
                case 3: // isConnected(): boolean
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(1);
                    }
                    return true;
                case 5: // registerCarConnectionListener(ICarConnectionListener)
                case 6: // unregisterCarConnectionListener(ICarConnectionListener)
                    try {
                        data.readStrongBinder();
                    } catch (Throwable ignored) {
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                case 7:  // getCarSensor(): ICarSensor
                case 17: // getCarMessage(): ICarMessage
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeStrongBinder(null);
                    }
                    return true;
                case 19: // hasFeature(String, int): boolean
                case 71: // hasClientFeature(String, int): boolean
                    try {
                        data.readString();
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(0);
                    }
                    return true;
                case 55: // getFeatureSet(String, List<String>): List<String>
                    try {
                        data.readString();
                        data.createStringArrayList();
                    } catch (Throwable ignored) {
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeStringList(new ArrayList<>());
                    }
                    return true;
                case 68: // getIntFeature(String, int): int
                    try {
                        data.readString();
                        data.readInt();
                    } catch (Throwable ignored) {
                    }
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(0);
                    }
                    return true;
                default:
                    log("ICar: unhandled transaction " + code);
                    if (reply != null) reply.writeNoException();
                    return true;
            }
        }
    };

    public ProjectedHost(Context context, int width, int height, int densityDpi, Listener listener) {
        this(context, MAPS_COMPONENT, width, height, densityDpi, listener);
    }

    public ProjectedHost(Context context, ComponentName component, int width, int height,
                         int densityDpi, Listener listener) {
        this.context = context.getApplicationContext();
        this.component = component;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.listener = listener;
    }

    public void start() {
        if (reader != null) return;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        frameThread = new HandlerThread("projected-frames");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        reader.setOnImageAvailableListener(this::onImage, frameHandler);

        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service = binder;
                log("bound " + name.flattenToShortString());
                new Thread(ProjectedHost.this::setup, "projected-setup").start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                log("service disconnected");
                service = null;
            }

            @Override
            public void onBindingDied(ComponentName name) {
                log("binding died");
                service = null;
            }

            @Override
            public void onNullBinding(ComponentName name) {
                log("null binding");
            }
        };

        Intent intent = new Intent().setComponent(component);
        log("binding " + intent.getComponent().flattenToShortString());
        boolean ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        log("bindService=" + ok);
    }

    private void setup() {
        IBinder binder = service;
        if (binder == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROJECTION_DESCRIPTOR);
            data.writeStrongBinder(callback);
            data.writeStrongBinder(car);
            log("onSetup (tx 1)");
            boolean handled = binder.transact(1, data, reply, 0);
            reply.readException();
            log("onSetup accepted, handled=" + handled);
        } catch (Throwable t) {
            log("onSetup rejected: " + t.getClass().getName() + ": " + t.getMessage());
            Log.e(TAG, "onSetup failed", t);
            return;
        } finally {
            data.recycle();
            reply.recycle();
        }

        // The app posts the setup work to its main looper; give it a moment before the start
        // transaction, which is also posted and would otherwise race its ICar registration.
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }

        data = Parcel.obtain();
        reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROJECTION_DESCRIPTOR);
            data.writeInt(0); // CarDisplayId (main display)
            data.writeInt(0); // CarRegionId
            writeDrawingSpec(data, width, height, densityDpi, reader.getSurface(), new Rect(0, 0, width, height), 0);

            Intent appIntent = new Intent(Intent.ACTION_MAIN).setPackage(MAPS);
            Bundle projectionConfig = new Bundle();
            projectionConfig.putBoolean("CANCEL_PENDING_LIFECYCLE_EVENTS_FOR_FINISH_ENABLED", false);
            appIntent.putExtra("PROJECTION_CONFIGURATION", projectionConfig);
            data.writeInt(1);
            appIntent.writeToParcel(data, 0);

            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            data.writeInt(1);
            configuration.writeToParcel(data, 0);

            Bundle extras = new Bundle();
            extras.putBoolean("AARE_HOST", true);
            data.writeInt(1);
            extras.writeToParcel(data, 0);

            log("onProjectionStart (tx 26) " + width + "x" + height + "@" + densityDpi);
            boolean handled = binder.transact(26, data, reply, 0);
            reply.readException();
            log("onProjectionStart accepted, handled=" + handled);
        } catch (Throwable t) {
            log("onProjectionStart rejected: " + t.getClass().getName() + ": " + t.getMessage());
            Log.e(TAG, "onProjectionStart failed", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void onImage(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            int index = ++frameCount;
            log("frame " + index + ": " + image.getWidth() + "x" + image.getHeight()
                    + " stride=" + rowStride + " format=" + image.getFormat());
            listener.onFrame(cropped, index);
            if (index == 1) {
                File out = new File(context.getExternalFilesDir(null), "projected-first-frame.png");
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    log("saved " + out);
                } catch (Throwable t) {
                    log("could not save frame: " + t);
                }
            }
        } catch (Throwable t) {
            log("frame read failed: " + t);
        } finally {
            if (image != null) image.close();
        }
    }

    public void stop() {
        try {
            if (service != null) {
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(PROJECTION_DESCRIPTOR);
                    data.writeInt(0);
                    service.transact(9, data, null, 0); // onProjectionStop(displayId)
                } catch (Throwable ignored) {
                } finally {
                    data.recycle();
                }
            }
        } finally {
            if (connection != null) {
                try {
                    context.unbindService(connection);
                } catch (Throwable ignored) {
                }
                connection = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            if (frameThread != null) {
                frameThread.quitSafely();
                frameThread = null;
            }
            service = null;
        }
    }

    private void log(String line) {
        Log.i(TAG, line);
        listener.onLog(line);
    }

    /**
     * DrawingSpec is a GMS SafeParcelable, not a normal Parcelable. It has no public class in
     * our APK, so write the exact wire layout used by com.google.android.gms.car.DrawingSpec:
     *   header 20293, size, then fields 1..8 with SafeParcelable type tags.
     */
    private static void writeDrawingSpec(Parcel parcel, int width, int height, int dpi,
                                         Surface surface, Rect insets, int displayId) {
        int start = parcel.dataPosition();
        parcel.writeInt(20293 | 0xFFFF0000);
        parcel.writeInt(0);
        parcel.writeInt(1 | 0x00040000);
        parcel.writeInt(width);
        parcel.writeInt(2 | 0x00040000);
        parcel.writeInt(height);
        parcel.writeInt(3 | 0x00040000);
        parcel.writeInt(dpi);
        writeParcelableField(parcel, 4, surface, 0);
        writeParcelableField(parcel, 5, insets, 0);
        parcel.writeInt(6 | 0x00040000);
        parcel.writeInt(0);
        parcel.writeInt(7 | 0x00040000);
        parcel.writeInt(displayId);
        // field 8 (Bundle) omitted (null)
        int end = parcel.dataPosition();
        parcel.setDataPosition(start + 4);
        parcel.writeInt(end - (start + 8));
        parcel.setDataPosition(end);
    }

    private static void writeParcelableField(Parcel parcel, int id, android.os.Parcelable value, int flags) {
        int start = parcel.dataPosition();
        parcel.writeInt(id | 0xFFFF0000);
        parcel.writeInt(0);
        value.writeToParcel(parcel, flags);
        int end = parcel.dataPosition();
        parcel.setDataPosition(start + 4);
        parcel.writeInt(end - (start + 8));
        parcel.setDataPosition(end);
    }
}
