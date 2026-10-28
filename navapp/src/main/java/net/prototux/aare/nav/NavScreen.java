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

/** Car screen that renders NavigationViewForAuto into the host-provided Surface. */
public final class NavScreen extends Screen {
    private static final String TAG = "AareNav";
    private final Handler main = new Handler(Looper.getMainLooper());

    private VirtualDisplay virtualDisplay;
    private android.app.Presentation presentation;
    private NavigationViewForAuto navigationView;
    private GoogleMap map;

    private final SurfaceCallback surfaceCallback = new SurfaceCallback() {
        @Override
        public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
            Log.i(TAG, "surface " + container.getWidth() + "x" + container.getHeight()
                    + "@" + container.getDpi());
            main.post(() -> createPresentation(container));
        }

        @Override
        public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
            main.post(NavScreen.this::destroyPresentation);
        }

        @Override
        public void onScroll(float dx, float dy) {
            main.post(() -> {
                if (map != null) map.moveCamera(CameraUpdateFactory.scrollBy(dx, dy));
            });
        }

        @Override
        public void onScale(float focusX, float focusY, float scaleFactor) {
            main.post(() -> {
                if (map == null) return;
                map.moveCamera(CameraUpdateFactory.zoomBy(
                        scaleFactor - 1f, new Point((int) focusX, (int) focusY)));
            });
        }

        @Override
        public void onClick(float x, float y) {
            // Navigation SDK's map handles its own POI selection; no synthetic map tap.
        }
    };

    public NavScreen(@NonNull CarContext carContext) {
        super(carContext);
        carContext.getCarService(AppManager.class).setSurfaceCallback(surfaceCallback);
    }

    private void createPresentation(@NonNull SurfaceContainer container) {
        if (presentation != null) return;
        Surface surface = container.getSurface();
        DisplayManager dm = (DisplayManager) getCarContext().getSystemService(
                CarContext.DISPLAY_SERVICE);
        virtualDisplay = dm.createVirtualDisplay(
                "AARE Nav map",
                container.getWidth(),
                container.getHeight(),
                container.getDpi(),
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        if (virtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay failed");
            return;
        }

        presentation = new android.app.Presentation(
                getCarContext(), virtualDisplay.getDisplay());
        presentation.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));

        navigationView = new NavigationViewForAuto(getCarContext());
        navigationView.onCreate(null);
        navigationView.onStart();
        navigationView.onResume();
        navigationView.getMapAsync(googleMap -> {
            map = googleMap;
            googleMap.getUiSettings().setAllGesturesEnabled(true);
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(36.1540, -95.9928), 12f));
            Log.i(TAG, "map ready camera=" + googleMap.getCameraPosition().target
                    + " zoom=" + googleMap.getCameraPosition().zoom
                    + " type=" + googleMap.getMapType());
            Navigator navigator = NavApplication.navigator();
            if (navigator != null) {
                try {
                    navigator.setDestination(Waypoint.builder()
                            .setLatLng(36.1540, -95.9928)
                            .setTitle("Tulsa")
                            .build());
                    Log.i(TAG, "demo route requested");
                } catch (Throwable t) {
                    Log.w(TAG, "route request failed", t);
                }
            } else {
                Log.w(TAG, "navigator was not ready when the map appeared");
            }
        });

        presentation.setContentView(navigationView);
        try {
            presentation.show();
            Log.i(TAG, "presentation shown");
        } catch (Throwable t) {
            Log.e(TAG, "Presentation.show failed", t);
        }
    }

    private void destroyPresentation() {
        if (navigationView != null) {
            navigationView.onPause();
            navigationView.onStop();
            navigationView.onDestroy();
            navigationView = null;
        }
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        map = null;
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
