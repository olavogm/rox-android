package com.grayfox.android.app.fragment;

import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentManager;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.github.clans.fab.FloatingActionButton;
import com.github.clans.fab.FloatingActionMenu;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.maps.DirectionsApi;
import com.google.maps.GeoApiContext;
import com.google.maps.model.DirectionsLeg;
import com.google.maps.model.DirectionsRoute;
import com.google.maps.model.TravelMode;

import com.grayfox.android.app.R;
import com.grayfox.android.app.task.NetworkAsyncTask;
import com.grayfox.android.app.util.Pair;
import com.grayfox.android.app.util.PicassoMarker;
import com.grayfox.android.app.widget.DragSortRecycler;
import com.grayfox.android.app.widget.PoiRouteAdapter;
import com.grayfox.android.client.PoisApi;
import com.grayfox.android.client.model.Poi;

import com.squareup.picasso.Picasso;

import roboguice.fragment.RoboFragment;
import roboguice.inject.InjectView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

public class RecommendedRouteFragment extends RoboFragment implements OnMapReadyCallback {

    private static final String MAP_FRAGMENT_TAG = "MAP_FRAGMENT";
    private static final String CURRENT_LOCATION_ARG = "CURRENT_LOCATION";
    private static final String SEED_ARG = "SEED";

    @InjectView(R.id.recalculating_route_progress_bar) private ProgressBar recalculatingRouteProgressBar;
    @InjectView(R.id.walking_directions_button)        private FloatingActionButton walkingDirectionsButton;
    @InjectView(R.id.transit_directions_button)        private FloatingActionButton transitDirectionsButton;
    @InjectView(R.id.driving_directions_button)        private FloatingActionButton drivingDirectionsButton;
    @InjectView(R.id.travel_distance_container)        private CardView travelDistanceContainer;
    @InjectView(R.id.bike_directions_button)           private FloatingActionButton bikeDirectionsButton;
    @InjectView(R.id.building_route_layout)            private LinearLayout buildingRouteLayout;
    @InjectView(R.id.travel_distance_text)             private TextView travelDistanceTextView;
    @InjectView(R.id.directions_menu)                  private FloatingActionMenu directionsMenu;
    @InjectView(R.id.route_container)                  private CardView routeContainer;
    @InjectView(R.id.route_list)                       private RecyclerView routeList;

    private boolean shouldRestoreRoute;
    private GoogleMap googleMap;
    private RecalculateRouteTask recalculateRouteTask;
    private RouteBuilderTask routeBuilderTask;
    private PoiRouteAdapter poiRouteAdapter;
    private DirectionsRoute route;
    private List<Poi> pois;
    private Animation verticalShowAnimation;
    private Animation verticalHideAnimation;

    public static RecommendedRouteFragment newInstance(Location currentLocation, Poi seed) {
        RecommendedRouteFragment fragment = new RecommendedRouteFragment();
        Bundle args = new Bundle();
        args.putParcelable(CURRENT_LOCATION_ARG, currentLocation);
        args.putSerializable(SEED_ARG, seed);
        fragment.setArguments(args);
        return fragment;
    }

    public RecommendedRouteFragment() {
        pois = new ArrayList<>();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recommended_route, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        FragmentManager fragmentManager = getChildFragmentManager();
        SupportMapFragment fragment = (SupportMapFragment) fragmentManager.findFragmentByTag(MAP_FRAGMENT_TAG);
        if (fragment == null) {
            fragment = SupportMapFragment.newInstance();
            fragmentManager.beginTransaction()
                    .replace(R.id.map_container, fragment, MAP_FRAGMENT_TAG)
                    .commit();
        }
        directionsMenu.setIconAnimated(false);
        walkingDirectionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                directionsMenu.close(true);
                if (routeBuilderTask != null && !routeBuilderTask.isActive())
                    recalculateRoute(TravelMode.WALKING);
            }
        });
        transitDirectionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                directionsMenu.close(true);
                if (routeBuilderTask != null && !routeBuilderTask.isActive()) recalculateRoute(TravelMode.TRANSIT);
            }
        });
        drivingDirectionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                directionsMenu.close(true);
                if (routeBuilderTask != null && !routeBuilderTask.isActive()) recalculateRoute(TravelMode.DRIVING);
            }
        });
        bikeDirectionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                directionsMenu.close(true);
                if (routeBuilderTask != null && !routeBuilderTask.isActive()) recalculateRoute(TravelMode.BICYCLING);
            }
        });
        routeList.setLayoutManager(new LinearLayoutManager(getActivity()));
        routeList.setItemAnimator(null);
        poiRouteAdapter = new PoiRouteAdapter(getCurrentLocationArg());
        poiRouteAdapter.setOnDeleteItemListener(new PoiRouteAdapter.OnDeleteItemListener() {
            @Override
            public void onDelete(Poi poi, int position) {
                onDeletePoiInRoute(position);
            }
        });
        routeList.setAdapter(poiRouteAdapter);
        final DragSortRecycler dragSortRecycler = new DragSortRecycler();
        dragSortRecycler.setViewHandleId(R.id.reorder_icon);
        dragSortRecycler.setOnItemMovedListener(new DragSortRecycler.OnItemMovedListener() {
            @Override
            public void onItemMoved(int from, int to) {
                onReorderPoisInRoute(from, to);
            }
        });
        routeList.addItemDecoration(dragSortRecycler);
        routeList.addOnItemTouchListener(dragSortRecycler);
        verticalShowAnimation = AnimationUtils.loadAnimation(getActivity(), R.anim.vertical_show);
        verticalHideAnimation = AnimationUtils.loadAnimation(getActivity(), R.anim.vertical_hide);
        fragment.getMapAsync(this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        shouldRestoreRoute = false;
        if (savedInstanceState == null) {
            Poi seed = getSeedArg();
            pois.add(seed);
            poiRouteAdapter.add(seed);
            routeBuilderTask = new RouteBuilderTask()
                    .seed(getSeedArg())
                    .origin(getCurrentLocationArg())
                    .travelMode(getCurrentTravelMode());
            routeBuilderTask.request();
        } else {
            if (routeBuilderTask != null && routeBuilderTask.isActive()) onPreExecuteRouteBuilderTask();
            if (recalculateRouteTask != null && recalculateRouteTask.isActive()) onPreExcecuteRecalculateRouteTask();
            else shouldRestoreRoute = true;
            restorePois();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (recalculateRouteTask != null && recalculateRouteTask.isActive()) recalculateRouteTask.cancel(true);
        if (routeBuilderTask != null && routeBuilderTask.isActive()) routeBuilderTask.cancel(true);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.googleMap = googleMap;
        routeContainer.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                RecommendedRouteFragment.this.googleMap.setPadding(0, 0, 0, routeContainer.getHeight());
                routeContainer.getViewTreeObserver().removeGlobalOnLayoutListener(this);
            }
        });
        googleMap.getUiSettings().setMapToolbarEnabled(false);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        googleMap.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                toggleRouteContainer();
            }
        });
        showCurrentLocationInMap();
        for (Poi poi : pois) addPoiMarker(poi);
        if (shouldRestoreRoute) {
            if (route != null) onAcquireRoute(route);
            onCompleteRouteBuilderTask();
        }
    }

    private void showCurrentLocationInMap() {
        Location currentLocation = getCurrentLocationArg();
        LatLng latLng = new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude());
        googleMap.addMarker(new MarkerOptions()
                .position(latLng)
                .title(getString(R.string.current_location))
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_map_location)));
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 13f));
    }

    private void restorePois() {
        poiRouteAdapter.set(pois.toArray(new Poi[pois.size()]));
        poiRouteAdapter.notifyDataSetChanged();
    }

    private void onPreExecuteRouteBuilderTask() {
        travelDistanceContainer.setVisibility(View.GONE);
        buildingRouteLayout.setVisibility(View.VISIBLE);
        routeList.setVisibility(View.GONE);
    }

    private void onAcquireNextPois(Poi[] nextPois) {
        pois.addAll(Arrays.asList(nextPois));
        if (nextPois != null && nextPois.length > 0) {
            poiRouteAdapter.add(nextPois);
            poiRouteAdapter.notifyDataSetChanged();
            for (Poi poi : nextPois) addPoiMarker(poi);
        }
    }

    private void onAcquireRoute(DirectionsRoute route) {
        this.route = route;
        if (route != null) {
            double totalDistance = 0f;
            for (DirectionsLeg leg : route.legs) totalDistance += leg.distance.inMeters;
            travelDistanceTextView.setText(getString(R.string.travel_distance, totalDistance / 1_000f));
            travelDistanceContainer.setVisibility(View.VISIBLE);
            List<com.google.maps.model.LatLng> polyline = route.overviewPolyline.decodePath();
            PolylineOptions pathOptions = new PolylineOptions().color(getResources().getColor(R.color.accent));
            for (com.google.maps.model.LatLng point : polyline) pathOptions.add(new LatLng(point.lat, point.lng));
            googleMap.addPolyline(pathOptions);
        } else {
            Toast.makeText(getActivity().getApplicationContext(),
                    R.string.unavailable_route, Toast.LENGTH_SHORT).show();
        }
    }

    private void onCompleteRouteBuilderTask() {
        buildingRouteLayout.setVisibility(View.GONE);
        routeList.setVisibility(View.VISIBLE);
    }

    private void onPreExcecuteRecalculateRouteTask() {
        travelDistanceContainer.setVisibility(View.GONE);
        googleMap.clear();
        showCurrentLocationInMap();
        for (Poi poi : pois) addPoiMarker(poi);
        recalculatingRouteProgressBar.setVisibility(View.VISIBLE);
    }

    private void recalculateRoute(TravelMode travelMode) {
        saveCurrentTravelMode(travelMode);
        recalculateRouteTask = new RecalculateRouteTask()
                .origin(getCurrentLocationArg())
                .pois(pois)
                .travelMode(travelMode);
        recalculateRouteTask.request();
    }

    private void onCompleteRecalculateRoute() {
        recalculatingRouteProgressBar.setVisibility(View.GONE);
    }

    private void addPoiMarker(Poi poi) {
        if (poi != null) {
            Marker marker = googleMap.addMarker(new MarkerOptions()
                    .position(new LatLng(poi.getLocation().getLatitude(), poi.getLocation().getLongitude()))
                    .title(poi.getName()));
            Picasso.with(getActivity())
                    .load(poi.getCategories()[0].getIconUrl())
                    .placeholder(R.drawable.ic_generic_category)
                    .into(new PicassoMarker(marker, R.drawable.ic_map_pin_light_blue, getActivity()));
        }
    }

    private void onDeletePoiInRoute(int position) {
        int poiPosition = position-1;
        pois.remove(poiPosition);
        recalculateRoute(getCurrentTravelMode());
    }

    private void onReorderPoisInRoute(int from, int to) {
        if (to > 0) {
            int poiFromPosition = from-1;
            int poiToPosition = to-1;
            Poi removed = pois.remove(poiFromPosition);
            pois.add(poiToPosition, removed);
            poiRouteAdapter.move(from, to);
            poiRouteAdapter.notifyDataSetChanged();
            recalculateRoute(getCurrentTravelMode());
        }
    }

    private Location getCurrentLocationArg() {
        return (Location) getArguments().getParcelable(CURRENT_LOCATION_ARG);
    }

    private Poi getSeedArg() {
        return (Poi) getArguments().getSerializable(SEED_ARG);
    }

    private TravelMode getCurrentTravelMode() {
        String travelModeStr = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext())
                .getString(getString(R.string.travel_mode_key), TravelMode.DRIVING.name());
        return TravelMode.valueOf(travelModeStr);
    }

    private void saveCurrentTravelMode(TravelMode selectedTravelMode) {
        PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext())
                .edit()
                .putString(getString(R.string.travel_mode_key), selectedTravelMode.name())
                .commit();
    }

    private void toggleRouteContainer() {
        if (routeContainer.getVisibility() == View.VISIBLE) {
            routeContainer.startAnimation(verticalHideAnimation);
            routeContainer.setVisibility(View.GONE);
            googleMap.setPadding(0, 0, 0, 0);
        } else {
            routeContainer.startAnimation(verticalShowAnimation);
            routeContainer.setVisibility(View.VISIBLE);
            googleMap.setPadding(0, 0, 0, routeContainer.getHeight());
        }
    }

    private class RouteBuilderTask extends NetworkAsyncTask<Pair<Poi[], DirectionsRoute>> {

        @Inject private GeoApiContext geoApiContext;
        @Inject private PoisApi poisApi;

        private Poi seed;
        private Location origin;
        private TravelMode travelMode;

        private RouteBuilderTask() {
            super(getActivity().getApplicationContext());
        }

        private RouteBuilderTask seed(Poi seed) {
            this.seed = seed;
            return this;
        }

        private RouteBuilderTask origin(Location origin) {
            this.origin = origin;
            return this;
        }

        private RouteBuilderTask travelMode(TravelMode travelMode) {
            this.travelMode = travelMode;
            return this;
        }

        @Override
        protected void onPreExecute() throws Exception {
            super.onPreExecute();
            onPreExecuteRouteBuilderTask();
        }

        @Override
        public Pair<Poi[], DirectionsRoute> call() throws Exception {
            Poi[] nextPois = poisApi.awaitRoute(seed.getFoursquareId());
            List<Poi> pois = new ArrayList<>();
            pois.add(seed);
            pois.addAll(Arrays.asList(nextPois));
            String[] waypoints = new String[pois.size()-1];
            for (int i = 0; i < waypoints.length; i++) waypoints[i] = toGoogleMapsServicesLatLng(pois.get(i).getLocation());
            DirectionsRoute[] routes = DirectionsApi.newRequest(geoApiContext)
                    .origin(toGoogleMapsServicesLatLng(origin))
                    .destination(toGoogleMapsServicesLatLng(pois.get(pois.size()-1).getLocation()))
                    .mode(travelMode)
                    .waypoints(waypoints)
                    .await();
            return new Pair(nextPois, routes == null || routes.length == 0 ? null : routes[0]);
        }

        @Override
        protected void onSuccess(Pair<Poi[], DirectionsRoute> objects) throws Exception {
            super.onSuccess(objects);
            onAcquiredNextPois(objects._0);
            onAcquiredRoute(objects._1);
        }

        private void onAcquiredNextPois(Poi[] nextPois) {
            onAcquireNextPois(nextPois);
        }

        private void onAcquiredRoute(DirectionsRoute route) {
            onAcquireRoute(route);
        }

        @Override
        protected void onFinally() throws RuntimeException {
            super.onFinally();
            onCompleteRouteBuilderTask();
        }

        private String toGoogleMapsServicesLatLng(Location location) {
            return String.valueOf(location.getLatitude()) + ',' + location.getLongitude();
        }

        private String toGoogleMapsServicesLatLng(com.grayfox.android.client.model.Location location) {
            return String.valueOf(location.getLatitude()) + ',' + location.getLongitude();
        }
    }

    private class RecalculateRouteTask extends NetworkAsyncTask<DirectionsRoute> {

        @Inject private GeoApiContext geoApiContext;

        private List<Poi> pois;
        private Location origin;
        private TravelMode travelMode;

        private RecalculateRouteTask() {
            super(getActivity().getApplicationContext());
        }

        private RecalculateRouteTask pois(List<Poi> pois) {
            this.pois = pois;
            return this;
        }

        private RecalculateRouteTask origin(Location origin) {
            this.origin = origin;
            return this;
        }

        private RecalculateRouteTask travelMode(TravelMode travelMode) {
            this.travelMode = travelMode;
            return this;
        }

        @Override
        protected void onPreExecute() throws Exception {
            super.onPreExecute();
            onPreExcecuteRecalculateRouteTask();
        }

        @Override
        public DirectionsRoute call() throws Exception {
            String[] waypoints = new String[pois.size()-1];
            for (int i = 0; i < waypoints.length; i++) waypoints[i] = toGoogleMapsServicesLatLng(pois.get(i).getLocation());
            DirectionsRoute[] routes = DirectionsApi.newRequest(geoApiContext)
                    .origin(toGoogleMapsServicesLatLng(origin))
                    .destination(toGoogleMapsServicesLatLng(pois.get(pois.size()-1).getLocation()))
                    .mode(travelMode)
                    .waypoints(waypoints)
                    .await();
            return routes == null || routes.length == 0 ? null : routes[0];
        }

        @Override
        protected void onSuccess(DirectionsRoute route) throws Exception {
            super.onSuccess(route);
            onAcquireRoute(route);
        }

        @Override
        protected void onFinally() throws RuntimeException {
            super.onFinally();
            onCompleteRecalculateRoute();
        }

        private String toGoogleMapsServicesLatLng(Location location) {
            return String.valueOf(location.getLatitude()) + ',' + location.getLongitude();
        }

        private String toGoogleMapsServicesLatLng(com.grayfox.android.client.model.Location location) {
            return String.valueOf(location.getLatitude()) + ',' + location.getLongitude();
        }
    }
}