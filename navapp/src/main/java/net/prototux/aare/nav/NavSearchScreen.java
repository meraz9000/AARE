package net.prototux.aare.nav;

import android.graphics.Color;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.CarLocation;
import androidx.car.app.model.CarText;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Metadata;
import androidx.car.app.model.Place;
import androidx.car.app.model.PlaceMarker;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.AutocompleteSessionToken;
import com.google.android.libraries.places.api.model.Place.Field;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Destination search backed by the Places SDK's autocomplete API. */
public final class NavSearchScreen extends Screen {
    private static final String TAG = "AareNavSearch";
    private static final List<Field> PLACE_FIELDS = Collections.unmodifiableList(Arrays.asList(
            Field.ID, Field.NAME, Field.ADDRESS, Field.LAT_LNG));

    private final PlacesClient places;
    private final AutocompleteSessionToken session = AutocompleteSessionToken.newInstance();
    private final @NonNull OnPlaceSelected onSelected;

    private String query = "";
    private List<AutocompletePrediction> predictions = Collections.emptyList();
    private boolean loading;
    private boolean submitted;

    public interface OnPlaceSelected {
        void onSelected(@NonNull String name, @NonNull LatLng latLng);
    }

    public NavSearchScreen(@NonNull CarContext context, @NonNull OnPlaceSelected onSelected) {
        super(context);
        this.onSelected = onSelected;
        places = Places.isInitialized() ? Places.createClient(context) : null;
    }

    private final SearchTemplate.SearchCallback searchCallback =
            new SearchTemplate.SearchCallback() {
                @Override
                public void onSearchTextChanged(@NonNull String text) {
                    query = text;
                    submitted = false;
                    fetchPredictions();
                }

                @Override
                public void onSearchSubmitted(@NonNull String text) {
                    query = text;
                    submitted = true;
                    fetchPredictions();
                }
            };

    private void fetchPredictions() {
        if (places == null) {
            CarToast.makeText(getCarContext(), "Places SDK is not configured",
                    CarToast.LENGTH_SHORT).show();
            return;
        }
        if (query.trim().length() < 2) {
            predictions = Collections.emptyList();
            loading = false;
            invalidate();
            return;
        }
        loading = true;
        invalidate();
        FindAutocompletePredictionsRequest request =
                FindAutocompletePredictionsRequest.builder()
                        .setQuery(query)
                        .setSessionToken(session)
                        .build();
        places.findAutocompletePredictions(request).addOnCompleteListener(task -> {
            loading = false;
            if (task.isSuccessful() && task.getResult() != null) {
                predictions = task.getResult().getAutocompletePredictions();
            } else {
                predictions = Collections.emptyList();
                android.util.Log.w(TAG, "autocomplete failed", task.getException());
            }
            if (!predictions.isEmpty()) submitted = false;
            invalidate();
        });
    }

    private void select(AutocompletePrediction prediction) {
        if (places == null) return;
        FetchPlaceRequest request = FetchPlaceRequest.builder(
                prediction.getPlaceId(), PLACE_FIELDS)
                .setSessionToken(session)
                .build();
        places.fetchPlace(request).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                android.util.Log.w(TAG, "fetch place failed", task.getException());
                CarToast.makeText(getCarContext(), "Could not load destination",
                        CarToast.LENGTH_SHORT).show();
                return;
            }
            com.google.android.libraries.places.api.model.Place place =
                    task.getResult().getPlace();
            if (place.getLatLng() == null) {
                CarToast.makeText(getCarContext(), "Destination has no coordinates",
                        CarToast.LENGTH_SHORT).show();
                return;
            }
            onSelected.onSelected(
                    place.getDisplayName() == null ? prediction.getPrimaryText(null).toString()
                            : place.getDisplayName(),
                    place.getLatLng());
            getScreenManager().pop();
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ItemList.Builder list = new ItemList.Builder();
        if (!predictions.isEmpty()) {
            for (AutocompletePrediction prediction : predictions) {
                String title = prediction.getPrimaryText(null).toString();
                String subtitle = prediction.getSecondaryText(null).toString();
                Place place = new Place.Builder(
                        CarLocation.create(0, 0)).build();
                Row.Builder row = new Row.Builder()
                        .setTitle(title)
                        .addText(subtitle)
                        .setMetadata(new Metadata.Builder().setPlace(place).build())
                        .setOnClickListener(() -> select(prediction));
                list.addItem(row.build());
            }
        } else if (!loading && query.trim().length() >= 2) {
            list.setNoItemsMessage(submitted ? "No results" : "Keep typing");
        }
        SearchTemplate.Builder builder = new SearchTemplate.Builder(searchCallback)
                .setHeaderAction(androidx.car.app.model.Action.BACK)
                .setSearchHint("Search for a destination")
                .setInitialSearchText(query)
                .setShowKeyboardByDefault(true);
        // SearchTemplate forbids setting a list while the template is loading.
        if (loading) {
            builder.setLoading(true);
        } else {
            builder.setItemList(list.build());
        }
        return builder.build();
    }
}
