package net.prototux.aare.nav;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager;
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo;

/**
 * Sink for the Navigation SDK turn-by-turn feed. The SDK binds to this service and sends
 * {@link TurnByTurnManager#MSG_NAV_INFO} messages about once per second while guidance is active.
 */
public final class NavInfoService extends Service {
    private static final String TAG = "AareNavInfo";

    private HandlerThread thread;
    private Messenger messenger;
    private TurnByTurnManager turnByTurnManager;

    @Override
    public void onCreate() {
        super.onCreate();
        turnByTurnManager = TurnByTurnManager.createInstance();
        thread = new HandlerThread("AareNavInfo", android.os.Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (msg.what != TurnByTurnManager.MSG_NAV_INFO) return;
                Bundle data = msg.getData();
                if (data == null) return;
                try {
                    NavInfo info = turnByTurnManager.readNavInfoFromBundle(data);
                    if (info != null) {
                        Log.i(TAG, "NavInfo state=" + info.getNavState()
                                + " step=" + (info.getCurrentStep() == null ? "none"
                                : info.getCurrentStep().getFullInstructionText()));
                        NavInfoRepository.publish(info);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Could not parse NavInfo", t);
                }
            }
        });
        Log.i(TAG, "Navigation feed service created");
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
