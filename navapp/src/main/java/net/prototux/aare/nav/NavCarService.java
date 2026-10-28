package net.prototux.aare.nav;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** Car App Library entry point for the Navigation SDK map. */
public final class NavCarService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // AARE is a test host; production would allow-list its signing certificate.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public androidx.car.app.Screen onCreateScreen(@NonNull Intent intent) {
                return new NavScreen(getCarContext());
            }
        };
    }
}
