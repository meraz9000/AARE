package net.prototux.probe;

import android.app.Activity;
import android.os.IBinder;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full host probe: onSetup, ICar stub, onProjectionStart, and a Surface for the app's
 * Presentation. On stock Android the service rejects onSetup before any of this runs.
 */
public final class ProbeActivity extends Activity {
    private static final String TAG = "ProjectionProbe";

    private TextView output;
    private ImageView frame;
    private final StringBuilder lines = new StringBuilder();
    private ProjectedHost host;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        output = new TextView(this);
        output.setTextSize(12);
        output.setTextColor(Color.BLACK);
        frame = new ImageView(this);
        root.addView(output);
        root.addView(frame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 360));
        setContentView(root);

        log("uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid());
        log("CAPTURE_VIDEO_OUTPUT granted="
                + checkSelfPermission("android.permission.CAPTURE_VIDEO_OUTPUT"));
        log("Maps installed=" + isInstalled(ProjectedHost.MAPS));

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        boolean mock = getIntent().getBooleanExtra("mock", false);
        android.content.ComponentName component = mock
                ? new android.content.ComponentName(this, MockProjectionService.class)
                : ProjectedHost.MAPS_COMPONENT;
        log("hosting " + component.flattenToShortString());
        if (mock) log("using local MockProjectionService: protocol self-test");
        host = new ProjectedHost(this, component, 1280, 720, metrics.densityDpi, new ProjectedHost.Listener() {
            @Override
            public void onLog(String line) {
                log(line);
            }

            @Override
            public void onFrame(Bitmap bitmap, int index) {
                runOnUiThread(() -> frame.setImageBitmap(bitmap));
            }
        });
        host.start();
    }


    @Override
    protected void onDestroy() {
        if (host != null) host.stop();
        super.onDestroy();
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void log(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  " + message;
        Log.i(TAG, line);
        runOnUiThread(() -> {
            lines.append(line).append('\n');
            output.setText(lines.toString());
        });
    }
}
