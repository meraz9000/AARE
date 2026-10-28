package net.prototux.aare.nav;

import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.libraries.navigation.NavigationViewForAuto;
import com.google.android.libraries.navigation.Navigator;
import com.google.android.libraries.navigation.Waypoint;

import java.util.LinkedHashMap;
import java.util.Map;

/** Car screen that renders NavigationViewForAuto into host-provided Surfaces. */
public final class NavScreen extends Screen {
    private static final String TAG = "AareNav";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Surface, SurfaceSession> sessions = new LinkedHashMap<>();
    private SurfaceSession active;
    private boolean routeRequested;

    private final class SurfaceSession {
        final Surface surface;
        final int width;
        final int height;
        final VirtualDisplay display;
        final android.app.Presentation presentation;
        final NavigationViewForAuto view;
        GoogleMap map;

        SurfaceSession(SurfaceContainer container) {
            surface = container.getSurface();
            width = container.getWidth();
            height = container.getHeight();
            DisplayManager dm = (DisplayManager) getCarContext().getSystemService(
                    CarContext.DISPLAY_SERVICE);
            display = dm.createVirtualDisplay(
                    "AARE Nav map",
                    width,
                    height,
                    container.getDpi(),
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (display == null) {
                throw new IllegalStateException("createVirtualDisplay returned null");
            }
            presentation = new android.app.Presentation(
                    getCarContext(), display.getDisplay());
            presentation.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            view = new NavigationViewForAuto(getCarContext());
            view.onCreate(null);
            view.onStart();
            view.onResume();
            view.getMapAsync(googleMap -> {
                map = googleMap;
                googleMap.getUiSettings().setAllGesturesEnabled(true);
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(36.1540, -95.9928), 12f));
                Log.i(TAG, "map ready " + width + "x" + height
                        + " camera=" + googleMap.getCameraPosition().target
                        + " zoom=" + googleMap.getCameraPosition().zoom
                        + " type=" + googleMap.getMapType());
                requestDemoRouteOnce();
            });
            presentation.setContentView(view);
            try {
                presentation.show();
                Log.i(TAG, "presentation shown " + width + "x" + height);
            } catch (Throwable t) {
                Log.e(TAG, "Presentation.show failed " + width + "x" + height, t);
            }
        }

        void destroy() {
            view.onPause();
            view.onStop();
            view.onDestroy();
            presentation.dismiss();
            display.release();
            map = null;
        }
    }

    private final SurfaceCallback surfaceCallback = new SurfaceCallback() {
        @Override
        public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
            Log.i(TAG, "surface available " + container.getWidth() + "x"
                    + container.getHeight() + "@" + container.getDpi());
            main.post(() -> createSession(container));
        }

        @Override
        public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
            Log.i(TAG, "surface destroyed " + container.getWidth() + "x"
                    + container.getHeight());
            main.post(() -> destroySession(container.getSurface()));
        }

        @Override
        public void onScroll(float dx, float dy) {
            Log.i(TAG, "onScroll " + dx + "," + dy);
            main.post(() -> {
                if (active != null && active.map != null) {
                    active.map.moveCamera(CameraUpdateFactory.scrollBy(dx, dy));
                }
            });
        }

        @Override
        public void onScale(float focusX, float focusY, float scaleFactor) {
            main.post(() -> {
                if (active == null || active.map == null) return;
                active.map.moveCamera(CameraUpdateFactory.zoomBy(
                        scaleFactor - 1f, new Point((int) focusX, (int) focusY)));
            });
        }

        @Override
        public void onClick(float x, float y) {
            Log.i(TAG, "onClick " + x + "," + y);
            // Navigation SDK's map handles its own POI selection.
        }
    };

    public NavScreen(@NonNull CarContext carContext) {
        super(carContext);
        carContext.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
    }

    private void createSession(SurfaceContainer container) {
        Surface surface = container.getSurface();
        if (sessions.containsKey(surface)) return;
        try {
            SurfaceSession session = new SurfaceSession(container);
            sessions.put(surface, session);
            updateActive();
        } catch (Throwable t) {
            Log.e(TAG, "create session failed " + container.getWidth() + "x"
                    + container.getHeight(), t);
        }
    }

    private void destroySession(Surface surface) {
        SurfaceSession session = sessions.remove(surface);
        if (session == null) return;
        session.destroy();
        updateActive();
    }

    /** Pick the largest live surface; AARE may send a phone surface and the car surface. */
    private void updateActive() {
        SurfaceSession best = null;
        for (SurfaceSession candidate : sessions.values()) {
            if (best == null
                    || (long) candidate.width * candidate.height
                    > (long) best.width * best.height) {
                best = candidate;
            }
        }
        boolean changed = best != active;
        active = best;
        if (changed && best != null) {
            Log.i(TAG, "active surface " + best.width + "x" + best.height);
        }
    }

    private void requestDemoRouteOnce() {
        if (routeRequested) return;
        Navigator navigator = NavApplication.navigator();
        if (navigator == null) {
            Log.w(TAG, "navigator was not ready when the map appeared");
            return;
        }
        routeRequested = true;
        try {
            navigator.setDestination(Waypoint.builder()
                    .setLatLng(36.1540, -95.9928)
                    .setTitle("Tulsa")
                    .build());
            Log.i(TAG, "demo route requested");
        } catch (Throwable t) {
            Log.w(TAG, "route request failed", t);
        }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(Action.PAN)
                        .build())
                .build();
    }
}
