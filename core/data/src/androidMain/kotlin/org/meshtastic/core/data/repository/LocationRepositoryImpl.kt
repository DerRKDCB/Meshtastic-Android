/*
 * Copyright (c) 2025-2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.meshtastic.core.data.repository

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.annotation.Single
import org.meshtastic.core.common.hasLocationPermission
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.repository.Location
import org.meshtastic.core.repository.LocationRepository
import org.meshtastic.core.repository.PlatformAnalytics

@Single
class LocationRepositoryImpl(
    private val context: Application,
    private val locationManager: Lazy<LocationManager>,
    private val analytics: PlatformAnalytics,
    private val dispatchers: CoroutineDispatchers,
) : LocationRepository {

    companion object {
        private const val DEFAULT_INTERVAL_MS = 30_000L
        private const val MIN_DISTANCE_METERS = 0f
        private const val API_LEVEL_31 = 31
    }

    /** Status of whether the app is actively subscribed to location changes. */
    private val _receivingLocationUpdates: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val receivingLocationUpdates: StateFlow<Boolean>
        get() = _receivingLocationUpdates

    @RequiresPermission(anyOf = [ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION])
    private fun LocationManager.requestLocationUpdates(): Flow<Location> = callbackFlow {
        val hasFinePermission =
            ContextCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission =
            ContextCompat.checkSelfPermission(context, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPermission = hasFinePermission || hasCoarsePermission

        if (!hasPermission) {
            _receivingLocationUpdates.value = false
            close()
            return@callbackFlow
        }

        val locationRequest =
            LocationRequestCompat.Builder(DEFAULT_INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .build()

        val locationListener = LocationListenerCompat { location ->
            if (location.hasAltitude() && !LocationCompat.hasMslAltitude(location)) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    AltitudeConverterCompat.addMslAltitudeToLocation(context, location)
                } catch (_: Exception) {}
            }
            trySend(location)
        }

        val providerList = buildList {
            val providers = allProviders
            if (Build.VERSION.SDK_INT >= API_LEVEL_31 && LocationManager.FUSED_PROVIDER in providers) {
                add(LocationManager.FUSED_PROVIDER)
            } else {
                if (LocationManager.GPS_PROVIDER in providers) add(LocationManager.GPS_PROVIDER)
                if (LocationManager.NETWORK_PROVIDER in providers) add(LocationManager.NETWORK_PROVIDER)
            }
        }

        val lastKnownLocationsByProvider =
            providerList.associateWith { provider -> runCatching { getLastKnownLocation(provider) }.getOrNull() }

        val lastKnownLocation = lastKnownLocationsByProvider.values.filterNotNull().maxByOrNull { location -> location.time }

        if (lastKnownLocation != null) {
            trySend(lastKnownLocation)
        }

        var startedLocationUpdates = false

        @Suppress("TooGenericExceptionCaught")
        try {
            providerList.forEach { provider ->
                LocationManagerCompat.requestLocationUpdates(
                    this@requestLocationUpdates,
                    provider,
                    locationRequest,
                    dispatchers.io.asExecutor(),
                    locationListener,
                )
            }
            startedLocationUpdates = true
            _receivingLocationUpdates.value = true
            analytics.track("location_start")
        } catch (_: SecurityException) {
            _receivingLocationUpdates.value = false
            close()
        } catch (_: Exception) {
            _receivingLocationUpdates.value = false
            close()
        }

        awaitClose {
            _receivingLocationUpdates.value = false
            if (startedLocationUpdates) {
                analytics.track("location_stop")
            }

            LocationManagerCompat.removeUpdates(this@requestLocationUpdates, locationListener)
        }
    }

    /** Observable flow for location updates */
    @RequiresPermission(anyOf = [ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION])
    override fun getLocations(): Flow<Location> {
        val hasPermission = context.hasLocationPermission()
        if (!hasPermission) {
            analytics.track("location_permission_missing")
            return emptyFlow()
        }

        return locationManager.value.requestLocationUpdates()
    }
}
