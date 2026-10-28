package net.prototux.aare.nav;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide latest turn-by-turn snapshot from the Navigation SDK feed. */
public final class NavInfoRepository {
    public interface Listener {
        void onNavInfo(@NonNull NavInfo navInfo);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    @Nullable private static volatile NavInfo current;

    private NavInfoRepository() {}

    public static void addListener(@NonNull Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(@NonNull Listener listener) {
        LISTENERS.remove(listener);
    }

    @Nullable
    public static NavInfo current() {
        return current;
    }

    static void publish(@NonNull NavInfo navInfo) {
        current = navInfo;
        for (Listener listener : LISTENERS) {
            listener.onNavInfo(navInfo);
        }
    }
}
