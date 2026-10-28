package net.prototux.aare.nav;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.libraries.navigation.NavigationApi;
import com.google.android.libraries.navigation.Navigator;
import com.google.android.libraries.navigation.Waypoint;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.LatLng;

/** Phone-side setup: terms acceptance, Navigator init, and a demo destination. */
public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "AareNav";
    private TextView status;
    private MapView mapView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AARE Nav");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText(BuildConfig.MAPS_API_KEY.isEmpty()
                ? getString(R.string.status_key_missing)
                : "Navigation SDK key configured. Accept the terms, then connect from AARE.");
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, pad / 2, 0, pad);
        root.addView(status);

        Button terms = new Button(this);
        terms.setText(R.string.terms_button);
        terms.setOnClickListener(v -> showTerms());
        root.addView(terms, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button demo = new Button(this);
        demo.setText(R.string.demo_button);
        demo.setOnClickListener(v -> setDemoDestination());
        root.addView(demo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mapView = new MapView(this);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(map -> {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(36.1540, -95.9928), 12f));
            Log.i(TAG, "phone map ready");
        });
        root.addView(mapView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 700));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }

    private void showTerms() {
        if (BuildConfig.MAPS_API_KEY.isEmpty()) {
            status.setText(R.string.status_key_missing);
            return;
        }
        NavigationApi.showTermsAndConditionsDialog(this, "AARE Nav", accepted -> {
            status.setText(accepted ? "Terms accepted. Initializing Navigator…" : "Terms declined.");
            if (!accepted) return;
            NavigationApi.getNavigator(this, new NavigationApi.NavigatorListener() {
                @Override
                public void onNavigatorReady(Navigator navigator) {
                    NavApplication.setNavigator(navigator);
                    status.setText("Navigator ready: " + NavigationApi.getNavSDKVersion());
                }

                @Override
                public void onError(int errorCode) {
                    status.setText("Navigator error: " + errorCode);
                    Log.w(TAG, "Navigator error " + errorCode);
                }
            });
        });
    }

    private void setDemoDestination() {
        Navigator navigator = NavApplication.navigator();
        if (navigator == null) {
            status.setText("Navigator is not ready. Accept the terms first.");
            return;
        }
        Waypoint tulsa = Waypoint.builder()
                .setLatLng(36.1540, -95.9928)
                .setTitle("Tulsa")
                .build();
        navigator.setDestination(tulsa);
        status.setText("Demo destination set: Tulsa");
    }
}
