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
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Distance;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Step;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo;
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo;
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
    private int routeAttempts;
    private RoutingInfo routingInfo;

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

    private final NavInfoRepository.Listener navInfoListener = this::onNavInfo;

    private void onNavInfo(@NonNull NavInfo navInfo) {
        StepInfo stepInfo = navInfo.getCurrentStep();
        if (stepInfo == null) return;
        Step.Builder step = new Step.Builder()
                .setRoad(stepInfo.getFullRoadName() == null ? "" : stepInfo.getFullRoadName())
                .setCue(stepInfo.getFullInstructionText() == null
                        ? "" : stepInfo.getFullInstructionText())
                .setManeuver(buildManeuver(stepInfo));
        Distance distance = buildDistance(navInfo.getDistanceToCurrentStepMeters());
        RoutingInfo info = new RoutingInfo.Builder()
                .setCurrentStep(step.build(), distance)
                .build();
        main.post(() -> {
            routingInfo = info;
            invalidate();
        });
    }

    private static Maneuver buildManeuver(StepInfo stepInfo) {
        Maneuver.Builder builder = new Maneuver.Builder(maneuverType(stepInfo.getManeuver()));
        if (stepInfo.getManeuverBitmap() != null) {
            builder.setIcon(new CarIcon.Builder(
                    IconCompat.createWithBitmap(stepInfo.getManeuverBitmap())).build());
        }
        return builder.build();
    }

    private static Distance buildDistance(Integer meters) {
        int value = meters == null ? 0 : Math.max(0, meters);
        if (value >= 1000) {
            return Distance.create(value / 1000.0, Distance.UNIT_KILOMETERS);
        }
        return Distance.create(value, Distance.UNIT_METERS);
    }

    private static int maneuverType(int maneuver) {
        switch (maneuver) {
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.DEPART:
                return Maneuver.TYPE_DEPART;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.DESTINATION:
                return Maneuver.TYPE_DESTINATION;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.DESTINATION_LEFT:
                return Maneuver.TYPE_DESTINATION_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.DESTINATION_RIGHT:
                return Maneuver.TYPE_DESTINATION_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.STRAIGHT:
                return Maneuver.TYPE_STRAIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_LEFT:
                return Maneuver.TYPE_TURN_NORMAL_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_RIGHT:
                return Maneuver.TYPE_TURN_NORMAL_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_KEEP_LEFT:
                return Maneuver.TYPE_KEEP_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_KEEP_RIGHT:
                return Maneuver.TYPE_KEEP_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_SLIGHT_LEFT:
                return Maneuver.TYPE_TURN_SLIGHT_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_SLIGHT_RIGHT:
                return Maneuver.TYPE_TURN_SLIGHT_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_SHARP_LEFT:
                return Maneuver.TYPE_TURN_SHARP_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_SHARP_RIGHT:
                return Maneuver.TYPE_TURN_SHARP_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_U_TURN_CLOCKWISE:
                return Maneuver.TYPE_U_TURN_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.TURN_U_TURN_COUNTERCLOCKWISE:
                return Maneuver.TYPE_U_TURN_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.MERGE_LEFT:
                return Maneuver.TYPE_MERGE_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.MERGE_RIGHT:
                return Maneuver.TYPE_MERGE_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.FORK_LEFT:
                return Maneuver.TYPE_FORK_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.FORK_RIGHT:
                return Maneuver.TYPE_FORK_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.ON_RAMP_LEFT:
                return Maneuver.TYPE_ON_RAMP_NORMAL_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.ON_RAMP_RIGHT:
                return Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.OFF_RAMP_LEFT:
                return Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.OFF_RAMP_RIGHT:
                return Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.ROUNDABOUT_CLOCKWISE:
                return Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.ROUNDABOUT_COUNTERCLOCKWISE:
                return Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW;
            case com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.NAME_CHANGE:
                return Maneuver.TYPE_NAME_CHANGE;
            default:
                return Maneuver.TYPE_STRAIGHT;
        }
    }

    private void openSearch() {
        getScreenManager().push(new NavSearchScreen(
                getCarContext(),
                (name, latLng) -> navigateTo(name, latLng)));
    }

    private void navigateTo(@NonNull String name, @NonNull LatLng latLng) {
        Navigator navigator = NavApplication.navigator();
        if (navigator == null) {
            CarToast.makeText(getCarContext(), "Navigator is not ready",
                    CarToast.LENGTH_SHORT).show();
            return;
        }
        navigator.stopGuidance();
        navigator.clearDestinations();
        try {
            com.google.android.libraries.navigation.ListenableResultFuture<
                    Navigator.RouteStatus> route = navigator.setDestination(
                    Waypoint.builder()
                            .setLatLng(latLng.latitude, latLng.longitude)
                            .setTitle(name)
                            .build());
            route.setOnResultListener(status -> {
                Log.i(TAG, "search route status=" + status);
                if (status == Navigator.RouteStatus.OK) {
                    navigator.startGuidance();
                    invalidate();
                } else {
                    CarToast.makeText(getCarContext(), "No route: " + status,
                            CarToast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "search route request failed", t);
        }
    }

    private void cancelNavigation() {
        Navigator navigator = NavApplication.navigator();
        if (navigator != null) {
            navigator.stopGuidance();
            navigator.clearDestinations();
        }
        routingInfo = null;
        invalidate();
        CarToast.makeText(getCarContext(), "Navigation cancelled", CarToast.LENGTH_SHORT).show();
    }

    private boolean isNavigating() {
        Navigator navigator = NavApplication.navigator();
        return navigator != null && navigator.isGuidanceRunning();
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
        NavInfoRepository.addListener(navInfoListener);
        NavInfo current = NavInfoRepository.current();
        if (current != null) onNavInfo(current);
        getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_DESTROY) {
                NavInfoRepository.removeListener(navInfoListener);
            }
        });
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

    /**
     * Pick the newest live surface. AARE can leave the previous (larger) surface attached when
     * the split layout swaps panes; picking the largest left the map rendering into a detached
     * 2170-wide surface and the new 1476-wide pane showed black. The host creates the new pane's
     * surface last, so the most recently inserted session is the visible one.
     */
    private void updateActive() {
        SurfaceSession best = null;
        for (SurfaceSession candidate : sessions.values()) {
            best = candidate;
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
            if (routeAttempts++ < 20) {
                main.postDelayed(this::requestDemoRouteOnce, 1000);
            } else {
                Log.w(TAG, "navigator never became ready");
            }
            return;
        }
        routeRequested = true;
        try {
            com.google.android.libraries.navigation.ListenableResultFuture<
                    Navigator.RouteStatus> route = navigator.setDestination(
                    Waypoint.builder()
                            .setLatLng(36.1540, -95.9928)
                            .setTitle("Tulsa")
                            .build());
            Log.i(TAG, "demo route requested");
            route.setOnResultListener(status -> {
                Log.i(TAG, "demo route status=" + status);
                if (status == Navigator.RouteStatus.OK) {
                    navigator.startGuidance();
                    Log.i(TAG, "startGuidance requested");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "route request failed", t);
        }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ActionStrip.Builder actions = new ActionStrip.Builder()
                .addAction(new Action.Builder()
                        .setTitle("Search")
                        .setIcon(new CarIcon.Builder(IconCompat.createWithResource(
                                getCarContext(), android.R.drawable.ic_menu_search)).build())
                        .setOnClickListener(this::openSearch)
                        .build())
                .addAction(Action.PAN);
        if (isNavigating()) {
            actions.addAction(new Action.Builder()
                    .setTitle("Cancel")
                    .setIcon(CarIcon.ERROR)
                    .setOnClickListener(this::cancelNavigation)
                    .build());
        }
        ActionStrip strip = actions.build();
        NavigationTemplate.Builder builder = new NavigationTemplate.Builder()
                .setActionStrip(strip)
                .setMapActionStrip(new ActionStrip.Builder()
                        .addAction(Action.PAN)
                        .build());
        if (routingInfo != null) builder.setNavigationInfo(routingInfo);
        return builder.build();
    }
}
