package com.routeassistant.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Location access isolated here. Never crashes on denial / missing GPS. */
object LocationHelper {

    data class LatLng(val lat: Double, val lng: Double)

    @SuppressLint("MissingPermission")
    suspend fun lastKnownLocation(context: Context): AppResult<LatLng> {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location: Location? = suspendCancellableCoroutine { cont ->
                client.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
            if (location != null) {
                AppResult.Ok(LatLng(location.latitude, location.longitude))
            } else {
                AppResult.Err("No last-known location. Turn on GPS or enter a start address.")
            }
        } catch (e: SecurityException) {
            AppResult.Err("Location permission not granted.", e)
        } catch (e: Exception) {
            AppResult.Err("Could not read location: ${e.message}", e)
        }
    }
}
