package com.um.feri.cs.pora.mapkotlinexample

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import co.beeline.gpx.Gpx
import co.beeline.gpx.Route
import co.beeline.gpx.RoutePoint
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.um.feri.cs.pora.mapkotlinexample.databinding.ActivityMainBinding
import com.um.feri.cs.pora.mapkotlinexample.location.LocationProviderChangedReceiver
import com.um.feri.cs.pora.mapkotlinexample.location.MyEventLocationSettingsChange
import io.reactivex.Observable
import io.reactivex.Single
import io.ticofab.androidgpxparser.parser.GPXParser
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import timber.log.Timber
import java.io.*
import java.util.*


class MainActivity : AppCompatActivity() {
    private var activityResultLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var fusedLocationClient: FusedLocationProviderClient //https://developer.android.com/training/location/retrieve-current
    private var lastLocation: Location? = null
    private var locationCallback: LocationCallback
    private var locationRequest: LocationRequest
    private var requestingLocationUpdates = false

    companion object {
        val REQUEST_CHECK_SETTINGS = 20202
    }

    init {
        locationRequest = LocationRequest.create()
            .apply { //https://stackoverflow.com/questions/66489605/is-constructor-locationrequest-deprecated-in-google-maps-v2
                interval = 1000 //can be much higher
                fastestInterval = 500
                smallestDisplacement = 10f //10m
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                maxWaitTime = 1000
            }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                locationResult ?: return
                for (location in locationResult.locations) {
                    // Update UI with location data
                    updateLocation(location)
                }
            }
        }

        this.activityResultLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            var allAreGranted = true
            for (b in result.values) {
                allAreGranted = allAreGranted && b
            }

            Timber.d("Permissions granted $allAreGranted")
            if (allAreGranted) {
                initCheckLocationSettings()
                //initMap() if settings are ok
            }
        }
    }

    private lateinit var binding: ActivityMainBinding
    val rnd = Random()
    lateinit var map: MapView
    var startPoint: GeoPoint = GeoPoint(46.55951, 15.63970);
    lateinit var mapController: IMapController
    var marker: Marker? = null
    var path1: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree()) //Init report type
        }
        val br: BroadcastReceiver = LocationProviderChangedReceiver()
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(br, filter)

        //LocalBroadcastManager.getInstance(this).registerReceiver(locationProviderChange)
        Configuration.getInstance()
            .load(applicationContext, this.getPreferences(Context.MODE_PRIVATE))
        binding = ActivityMainBinding.inflate(layoutInflater) //ADD THIS LINE

        map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        mapController = map.controller
        setContentView(binding.root)
        val appPerms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET
        )
        activityResultLauncher.launch(appPerms)
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (requestingLocationUpdates) {
            requestingLocationUpdates = false
            stopLocationUpdates()
        }
        binding.map.onPause()
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this);
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMsg(status: MyEventLocationSettingsChange) {
        if (status.on) {
            initMap()
        } else {
            Timber.i("Stop something")
        }
    }

    fun initLocation() { //call in create
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        readLastKnownLocation()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() { //onResume
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() { //onPause
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    //https://developer.android.com/training/location/retrieve-current
    @SuppressLint("MissingPermission") //permission are checked before
    fun readLastKnownLocation() {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                location?.let { updateLocation(it) }
            }
    }

    fun initCheckLocationSettings() {
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task: Task<LocationSettingsResponse> = client.checkLocationSettings(builder.build())
        task.addOnSuccessListener { locationSettingsResponse ->
            Timber.d("Settings Location IS OK")
            MyEventLocationSettingsChange.globalState = true //default
            initMap()
            // All location settings are satisfied. The client can initialize
            // location requests here.
            // ...
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                Timber.d("Settings Location addOnFailureListener call settings")
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    exception.startResolutionForResult(
                        this@MainActivity,
                        REQUEST_CHECK_SETTINGS
                    )
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                    Timber.d("Settings Location sendEx??")
                }
            }
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Timber.d("Settings onActivityResult for $requestCode result $resultCode")
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == RESULT_OK) {
                initMap()
            }
        }
    }

    fun updateLocation(newLocation: Location) {
        lastLocation = newLocation
        //GUI, MAP TODO
        binding.tvLat.setText(newLocation.latitude.toString())
        binding.tvLon.setText(newLocation.longitude.toString())
        //var currentPoint: GeoPoint = GeoPoint(newLocation.latitude, newLocation.longitude);
        startPoint.longitude = newLocation.longitude
        startPoint.latitude = newLocation.latitude
        mapController.setCenter(startPoint)
        getPositionMarker().position = startPoint
        map.invalidate()

    }

    fun initMap() {
        initLocation()
        if (!requestingLocationUpdates) {
            requestingLocationUpdates = true
            startLocationUpdates()
        }
        mapController.setZoom(18.5)
        mapController.setCenter(startPoint);
        map.invalidate()
    }

    private fun getPath(): Polyline {
        if (path1 == null) {
            path1 = Polyline()
            path1!!.outlinePaint.color = Color.RED
            path1!!.outlinePaint.strokeWidth = 10f
            path1!!.addPoint(startPoint.clone())
            map.overlayManager.add(path1)
        }
        return path1!!
    }

    private fun getPositionMarker(): Marker {
        if (marker == null) {
            marker = Marker(map)
            marker!!.title = "Here I am"
            marker!!.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker!!.icon = ContextCompat.getDrawable(this, R.drawable.ic_position);
            map.overlays.add(marker)
        }
        return marker!!
    }

    fun clearMap(view: View?) {
        marker = null
        path1 = null

        map.overlayManager.clear()

        updateLocation(lastLocation!!)
    }

    fun addRandomPointToPath(view: View?) {
        //Polyline path = new Polyline();
        startPoint.latitude = startPoint.latitude + (rnd.nextDouble() - 0.5) * 0.001
        startPoint.longitude = startPoint.longitude + (rnd.nextDouble() - 0.5) * 0.001
        getPath().addPoint(startPoint.clone())
        map.invalidate()
    }

    fun savePathToGPX(view: View?) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_TITLE, "${Date().time}.gpx")
        }

        gpxSaveDirectoryPicker.launch(intent)
    }

    private val gpxSaveDirectoryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val gpx = Gpx(
                    creator = "Roman Drahan",
//            metadata = Metadata(name = "Example"),
                    routes = Observable.fromArray(Route(
                        points = Observable.fromIterable(
                            getPath().actualPoints.map { RoutePoint(it.latitude, it.longitude, ele = it.altitude) }
                        )
                    ))
                )

                val tempFile = File("${applicationContext.cacheDir}/ukropsoft.gpx")

                val writer: Single<Writer> = gpx.writeTo(FileWriter(tempFile, false))

                writer.subscribe { _ ->
                    val inputStream = contentResolver.openInputStream(Uri.fromFile(tempFile))!!
                    val outputStream = contentResolver.openOutputStream(uri)!!

                    inputStream.copyTo(outputStream)

                    inputStream.close()
                    outputStream.flush()
                    tempFile.delete()

                    val dialogBuilder = AlertDialog.Builder(this)
                    dialogBuilder.setMessage("Saved")
                    dialogBuilder.show();
                }
            }
        }
    }

    fun loadPathFromGPX(view: View?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/gpx+xml",
                    "application/octet-stream"
                )
            )
        }

        gpxLoadFilePicker.launch(intent)
    }

    private val gpxLoadFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.openInputStream(uri).let { inputStream ->
                    val parsedRoutes = GPXParser().parse(inputStream).routes

                    if (parsedRoutes.isNotEmpty()) {
                        clearMap(null)

                        parsedRoutes.forEach { route ->
                            route.routePoints.forEach { routePoint ->
                                getPath().addPoint(
                                    GeoPoint(
                                        routePoint.latitude,
                                        routePoint.longitude,
                                        routePoint.elevation
                                    )
                                )
                            }
                        }

                        map.invalidate()

                        val dialogBuilder = AlertDialog.Builder(this)
                        dialogBuilder.setMessage("Loaded")
                        dialogBuilder.show();
                    } else {
                        val dialogBuilder = AlertDialog.Builder(this)
                        dialogBuilder.setMessage("There's no routes in file")
                        dialogBuilder.show();
                    }
                }
            }
        }
    }
}
