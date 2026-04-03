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
@file:Suppress("unused")

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
import co.touchlab.kermit.Logger
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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

    private val locationDebugLogger = Logger.withTag("LocationRepoDebug")

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
        val flowSessionId = System.identityHashCode(this)
        var closeReason = "awaitClose"
        var emittedCount = 0
        val hasFinePermission =
            ContextCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission =
            ContextCompat.checkSelfPermission(context, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasPermission = hasFinePermission || hasCoarsePermission

        locationDebugLogger.i {
            "[$flowSessionId] requestLocationUpdates started: hasFinePermission=$hasFinePermission " +
                "hasCoarsePermission=$hasCoarsePermission"
        }

        if (!hasPermission) {
            locationDebugLogger.w { "[$flowSessionId] Skipping location updates: missing location permission" }
            _receivingLocationUpdates.value = false
            closeReason = "missing_permission"
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
                } catch (e: Exception) {
                    locationDebugLogger.e(e) { "[$flowSessionId] addMslAltitudeToLocation() failed" }
                }
            }
            locationDebugLogger.i {
                "[$flowSessionId] Live location update: provider=${location.provider} " +
                    "time=${location.time} lat=${location.latitude} lon=${location.longitude}"
            }
            val result = trySend(location)
            emittedCount += 1
            if (emittedCount == 1) {
                locationDebugLogger.i { "[$flowSessionId] First trySend result: success=${result.isSuccess} closed=${result.isClosed}" }
            }
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

        locationDebugLogger.i { "[$flowSessionId] Selected providers for updates: $providerList" }
        if (providerList.isEmpty()) {
            locationDebugLogger.w { "[$flowSessionId] No providers available for location updates" }
        }

        val lastKnownLocationsByProvider =
            providerList.associateWith { provider -> runCatching { getLastKnownLocation(provider) }.getOrNull() }

        val lastKnownLocation = lastKnownLocationsByProvider.values.filterNotNull().maxByOrNull { location -> location.time }

        if (lastKnownLocation != null) {
            locationDebugLogger.i {
                "[$flowSessionId] Emitting cached location: provider=${lastKnownLocation.provider} " +
                    "time=${lastKnownLocation.time} lat=${lastKnownLocation.latitude} lon=${lastKnownLocation.longitude}"
            }
            val result = trySend(lastKnownLocation)
            emittedCount += 1
            if (emittedCount == 1) {
                locationDebugLogger.i {
                    "[$flowSessionId] First trySend result from cached location: success=${result.isSuccess} " +
                        "closed=${result.isClosed}"
                }
            }
        } else {
            locationDebugLogger.w {
                "[$flowSessionId] No cached last-known location available. " +
                    "perProvider=${lastKnownLocationsByProvider.mapValues { (_, value) -> value?.time }}"
            }
        }

        var startedLocationUpdates = false

        @Suppress("TooGenericExceptionCaught")
        try {
            providerList.forEach { provider ->
                locationDebugLogger.i { "[$flowSessionId] Registering location updates for provider=$provider" }
                LocationManagerCompat.requestLocationUpdates(
                    this@requestLocationUpdates,
                    provider,
                    locationRequest,
                    dispatchers.io.asExecutor(),
                    locationListener,
                )
                locationDebugLogger.i { "[$flowSessionId] Registered provider=$provider successfully" }
            }
            startedLocationUpdates = true
            locationDebugLogger.i {
                "[$flowSessionId] Location updates started with providers=$providerList " +
                    "intervalMs=$DEFAULT_INTERVAL_MS minDistanceM=$MIN_DISTANCE_METERS"
            }
            _receivingLocationUpdates.value = true
            analytics.track("location_start")
        } catch (e: SecurityException) {
            locationDebugLogger.w(e) { "[$flowSessionId] Location updates denied by platform permission checks" }
            _receivingLocationUpdates.value = false
            closeReason = "security_exception"
            close()
        } catch (e: Exception) {
            locationDebugLogger.w(e) { "[$flowSessionId] Unable to start location updates" }
            _receivingLocationUpdates.value = false
            closeReason = "start_exception"
            close()
        }

        awaitClose {
            _receivingLocationUpdates.value = false
            if (startedLocationUpdates) {
                locationDebugLogger.i { "[$flowSessionId] Stopping location requests" }
                analytics.track("location_stop")
            } else {
                locationDebugLogger.i { "[$flowSessionId] Flow closed before location updates started" }
            }
            locationDebugLogger.i {
                "[$flowSessionId] awaitClose teardown: reason=$closeReason emittedCount=$emittedCount"
            }

            LocationManagerCompat.removeUpdates(this@requestLocationUpdates, locationListener)
        }
    }

    /** Observable flow for location updates */
    @RequiresPermission(anyOf = [ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION])
    override fun getLocations(): Flow<Location> {
        val sessionId = System.nanoTime()
        val hasPermission = context.hasLocationPermission()
        locationDebugLogger.i { "[$sessionId] getLocations() called: hasLocationPermission=$hasPermission" }
        if (!hasPermission) {
            locationDebugLogger.w { "[$sessionId] Location permission missing; skipping location updates" }
            analytics.track("location_permission_missing")
            return emptyFlow()
        }

        return locationManager.value
            .requestLocationUpdates()
            .onStart { locationDebugLogger.i { "[$sessionId] getLocations flow subscription started" } }
            .onCompletion { cause ->
                locationDebugLogger.i { "[$sessionId] getLocations flow completed: cause=${cause?.javaClass?.simpleName ?: "normal"}" }
            }
    }
}
