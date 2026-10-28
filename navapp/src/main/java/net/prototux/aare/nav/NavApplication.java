package net.prototux.aare.nav;

import android.app.Application;
import android.util.Log;

import com.google.android.libraries.navigation.NavigationApi;
import com.google.android.libraries.navigation.Navigator;

/** Owns the process-wide Navigation SDK Navigator. */
public final class NavApplication extends Application {
    private static final String TAG = "AareNav";
    private static Navigator navigator;

    public static Navigator navigator() {
        return navigator;
    }

    static void setNavigator(Navigator value) {
        navigator = value;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!BuildConfig.MAPS_API_KEY.isEmpty()) {
            NavigationApi.setApiKey(BuildConfig.MAPS_API_KEY);
            Log.i(TAG, "Navigation SDK key configured");
        } else {
            Log.w(TAG, "MAPS_API_KEY is empty; Navigation SDK cannot initialize");
        }

        // This returns an error until the user accepts the terms in MainActivity.
        // It is harmless here and lets the car screen start initializing in parallel.
        try {
            NavigationApi.getNavigatorNoToS(this, new NavigationApi.NavigatorListener() {
                @Override
                public void onNavigatorReady(Navigator value) {
                    navigator = value;
                    Log.i(TAG, "Navigator ready: " + NavigationApi.getNavSDKVersion());
                }

                @Override
                public void onError(int errorCode) {
                    Log.w(TAG, "Navigator not ready: " + errorCode);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "Navigator initialization failed", t);
        }
    }
}
