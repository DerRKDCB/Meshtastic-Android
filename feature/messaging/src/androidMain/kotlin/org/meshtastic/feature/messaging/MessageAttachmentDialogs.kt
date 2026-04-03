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
package org.meshtastic.feature.messaging

import android.content.Context
import android.location.Location
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.MotionEvent
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.model.encodePrivateAppPayload
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.file_attachment_actions
import org.meshtastic.core.resources.file_attachment_open_with_app
import org.meshtastic.core.resources.file_attachment_receiving
import org.meshtastic.core.resources.file_attachment_save_to_phone
import org.meshtastic.core.resources.file_adjustment_load_failed
import org.meshtastic.core.resources.file_adjustment_preview
import org.meshtastic.core.resources.file_adjustment_result_line_primary
import org.meshtastic.core.resources.file_adjustment_results
import org.meshtastic.core.resources.compression_size_summary
import org.meshtastic.core.resources.image_adjustment_duty_cycle
import org.meshtastic.core.resources.image_adjustment_duty_cycle_summary
import org.meshtastic.core.resources.image_adjustment_result_line_secondary
import org.meshtastic.core.resources.image_adjustment_milliseconds_value
import org.meshtastic.core.resources.latitude
import org.meshtastic.core.resources.send
import org.meshtastic.core.resources.position
import org.meshtastic.core.resources.position_current_location_settings_hint
import org.meshtastic.core.resources.position_use_current_location
import org.meshtastic.core.resources.longitude
import org.meshtastic.core.model.PrivateAppPayloadType
import org.meshtastic.feature.messaging.image.MIN_IMAGE_DUTY_CYCLE_PERCENT
import org.meshtastic.feature.messaging.image.buildChunkedPayloadPackets
import org.meshtastic.feature.messaging.image.buildPositionPayloadBytes
import org.meshtastic.feature.messaging.image.estimatePacketAirtimeMillis
import org.meshtastic.feature.messaging.image.estimateTransmissionMillisForChunks
import org.meshtastic.feature.messaging.image.formatDutyCyclePercent
import org.meshtastic.feature.messaging.image.interChunkDelayMillisForDutyCycle
import org.meshtastic.feature.messaging.image.maxDutyCyclePercentForRegion
import org.meshtastic.feature.messaging.image.gzipPayloadBytes
import org.meshtastic.proto.Config
import org.meshtastic.proto.Position
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.tileprovider.tilesource.TileSourceFactory

private const val MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L
private const val MAX_COMPRESSED_FILE_SIZE_BYTES = 1L * 1024L * 1024L
private const val SWITZERLAND_CENTER_LATITUDE = 46.8182
private const val SWITZERLAND_CENTER_LONGITUDE = 8.2275
private const val EUROPE_OVERVIEW_ZOOM_LEVEL = 5.0
private const val LOCAL_POSITION_ZOOM_LEVEL = 16.5

private enum class FileSizeLimitState {
    None,
    Raw,
    Compressed,
}

private data class LoadedFilePayload(
    val fileName: String,
    val fileSizeBytes: Long,
    val gzipSizeBytes: Long,
    val chunks: List<ByteArray>,
    val sizeLimitState: FileSizeLimitState,
)

@Suppress("LongMethod")
@Composable
internal fun FileAdjustmentDialog(
    fileUri: Uri,
    loraConfig: Config.LoRaConfig,
    onSend: (List<ByteArray>, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var selectedDutyCyclePercent by remember {
        mutableStateOf(MIN_IMAGE_DUTY_CYCLE_PERCENT.coerceAtMost(maxDutyCyclePercentForRegion(loraConfig.region)))
    }
    var fileName by remember(fileUri) { mutableStateOf<String?>(null) }
    var fileSizeText by remember(fileUri) { mutableStateOf<String?>(null) }
    var fileGzipSizeText by remember(fileUri) { mutableStateOf<String?>(null) }
    var fileSizeLimitState by remember(fileUri) { mutableStateOf(FileSizeLimitState.None) }
    var chunks by remember(fileUri) { mutableStateOf<List<ByteArray>>(emptyList()) }
    var loadError by remember(fileUri) { mutableStateOf(false) }

    LaunchedEffect(fileUri) {
        val loaded =
            withContext(Dispatchers.IO) {
                val resolvedFileName = context.resolveDisplayName(uri = fileUri) ?: fileUri.lastPathSegment ?: "attachment"
                val fileBytes = context.readUriBytes(uri = fileUri) ?: return@withContext null
                if (fileBytes.size.toLong() > MAX_FILE_SIZE_BYTES) {
                    return@withContext LoadedFilePayload(
                        fileName = resolvedFileName,
                        fileSizeBytes = fileBytes.size.toLong(),
                        gzipSizeBytes = -1L,
                        chunks = emptyList(),
                        sizeLimitState = FileSizeLimitState.Raw,
                    )
                }
                val payloadBytes = encodePrivateAppPayload(PrivateAppPayloadType.File, fileBytes, fileName = resolvedFileName)
                val gzipSizeBytes = gzipPayloadBytes(payloadBytes).size
                if (gzipSizeBytes.toLong() > MAX_COMPRESSED_FILE_SIZE_BYTES) {
                    return@withContext LoadedFilePayload(
                        fileName = resolvedFileName,
                        fileSizeBytes = fileBytes.size.toLong(),
                        gzipSizeBytes = gzipSizeBytes.toLong(),
                        chunks = emptyList(),
                        sizeLimitState = FileSizeLimitState.Compressed,
                    )
                }
                val loadedChunks = buildChunkedPayloadPackets(payloadBytes = payloadBytes)
                LoadedFilePayload(
                    fileName = resolvedFileName,
                    fileSizeBytes = fileBytes.size.toLong(),
                    gzipSizeBytes = gzipSizeBytes.toLong(),
                    chunks = loadedChunks,
                    sizeLimitState = FileSizeLimitState.None,
                )
            }
        if (loaded == null) {
            loadError = true
            return@LaunchedEffect
        }

        fileName = loaded.fileName
        fileSizeText = Formatter.formatShortFileSize(context, loaded.fileSizeBytes)
        fileGzipSizeText = if (loaded.gzipSizeBytes >= 0L) Formatter.formatShortFileSize(context, loaded.gzipSizeBytes) else null
        fileSizeLimitState = loaded.sizeLimitState
        chunks = loaded.chunks
    }

    val regionMaxDutyCyclePercent = maxDutyCyclePercentForRegion(loraConfig.region)
    val boundedDutyCyclePercent = selectedDutyCyclePercent.coerceIn(MIN_IMAGE_DUTY_CYCLE_PERCENT, regionMaxDutyCyclePercent)
    val packetAirtimeMillis = estimatePacketAirtimeMillis(chunks.maxOfOrNull { it.size } ?: 0, loraConfig)
    val totalAirtimeMillis = chunks.size * packetAirtimeMillis
    val estimatedTransmissionMillis = estimateTransmissionMillisForChunks(chunks, boundedDutyCyclePercent, loraConfig)
    val estimatedTransmissionSeconds = estimatedTransmissionMillis / 1000
    val estimatedTransmissionTimeText = DateUtils.formatElapsedTime(estimatedTransmissionSeconds.toLong())
    val selectedChunkDelayMillis =
        interChunkDelayMillisForDutyCycle(
            boundedDutyCyclePercent,
            packetAirtimeMillis,
        )
    val intervalText = stringResource(Res.string.image_adjustment_milliseconds_value, selectedChunkDelayMillis)
    val packetAirtimeText = stringResource(Res.string.image_adjustment_milliseconds_value, packetAirtimeMillis)
    val totalAirtimeText = stringResource(Res.string.image_adjustment_milliseconds_value, totalAirtimeMillis)
    val actualDutyPercent =
        if (estimatedTransmissionMillis > 0) {
            (totalAirtimeMillis * 100f) / estimatedTransmissionMillis.toFloat()
        } else {
            0f
        }
    val actualDutyPercentText = formatDutyCyclePercent(actualDutyPercent)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f).padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.file_adjustment_preview))

                when {
                    loadError -> Text(text = stringResource(Res.string.file_adjustment_load_failed))
                    fileName != null && fileSizeText != null -> {
                        Text(
                            text = fileName!!,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        fileGzipSizeText?.let { gzipSizeText ->
                            Text(
                                text = stringResource(Res.string.compression_size_summary, fileSizeText!!, gzipSizeText),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        when (fileSizeLimitState) {
                            FileSizeLimitState.Raw -> {
                                Text(
                                    text = "${fileName!!} is ${fileSizeText!!} and exceeds the 10 MB limit before compression.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            FileSizeLimitState.Compressed -> {
                                Text(
                                    text = "File must be 1 MB or smaller after compression.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            FileSizeLimitState.None -> Unit
                        }
                    }
                }

                Text(stringResource(Res.string.image_adjustment_duty_cycle))
                Text(
                    stringResource(
                        Res.string.image_adjustment_duty_cycle_summary,
                        formatDutyCyclePercent(boundedDutyCyclePercent),
                        formatDutyCyclePercent(regionMaxDutyCyclePercent),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = boundedDutyCyclePercent,
                    onValueChange = { value -> selectedDutyCyclePercent = value },
                    valueRange = MIN_IMAGE_DUTY_CYCLE_PERCENT..regionMaxDutyCyclePercent,
                )

                Text(stringResource(Res.string.file_adjustment_results))
                Text(
                    stringResource(
                        Res.string.file_adjustment_result_line_primary,
                        chunks.size,
                        estimatedTransmissionTimeText,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        Res.string.image_adjustment_result_line_secondary,
                        intervalText,
                        packetAirtimeText,
                        totalAirtimeText,
                        actualDutyPercentText,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row {
                    Button(onClick = onCancel) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = {
                            if (chunks.isNotEmpty() && fileSizeLimitState == FileSizeLimitState.None) {
                                onSend(chunks, selectedChunkDelayMillis)
                            }
                        },
                        enabled = chunks.isNotEmpty() && fileSizeLimitState == FileSizeLimitState.None,
                    ) {
                        Text(stringResource(Res.string.send))
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
internal fun PositionShareDialog(
    currentLocation: Location?,
    canUseCurrentLocation: Boolean,
    onRequestCurrentLocationPermission: () -> Unit,
    onSend: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    var latitudeState by rememberSaveable { mutableStateOf(currentLocation?.latitude?.toString().orEmpty()) }
    var longitudeState by rememberSaveable { mutableStateOf(currentLocation?.longitude?.toString().orEmpty()) }
    var hasSeededInitialLocation by remember { mutableStateOf(false) }
    var hasSelectedMapLocation by rememberSaveable { mutableStateOf(false) }
    var isTrackingCurrentLocation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentLocation) {
        if (!hasSeededInitialLocation && currentLocation != null) {
            latitudeState = currentLocation.latitude.toString()
            longitudeState = currentLocation.longitude.toString()
            hasSeededInitialLocation = true
        }
    }

    LaunchedEffect(currentLocation, isTrackingCurrentLocation) {
        if (isTrackingCurrentLocation && currentLocation != null) {
            latitudeState = currentLocation.latitude.toString()
            longitudeState = currentLocation.longitude.toString()
            hasSeededInitialLocation = true
            hasSelectedMapLocation = false
        }
    }

    val parsedLatitude = latitudeState.toDoubleOrNull()
    val parsedLongitude = longitudeState.toDoubleOrNull()
    val isUsingFallbackDefaultLocation = parsedLatitude == null && parsedLongitude == null && currentLocation == null
    val selectedLatitude = parsedLatitude ?: currentLocation?.latitude ?: SWITZERLAND_CENTER_LATITUDE
    val selectedLongitude = parsedLongitude ?: currentLocation?.longitude ?: SWITZERLAND_CENTER_LONGITUDE
    val initialZoomLevel = if (isUsingFallbackDefaultLocation) EUROPE_OVERVIEW_ZOOM_LEVEL else LOCAL_POSITION_ZOOM_LEVEL
    val isLocationResolved =
        (parsedLatitude != null && parsedLongitude != null) ||
            hasSelectedMapLocation ||
            (canUseCurrentLocation && currentLocation != null)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.position)) },
        text = {
            Column {
                PositionPickerMap(
                    modifier = Modifier,
                    latitude = selectedLatitude,
                    longitude = selectedLongitude,
                    initialZoomLevel = initialZoomLevel,
                    seedCoordinates = currentLocation != null || (parsedLatitude != null && parsedLongitude != null),
                    isTrackingCurrentLocation = isTrackingCurrentLocation,
                    onCoordinatesSelected = { latitude, longitude ->
                        latitudeState = latitude.toString()
                        longitudeState = longitude.toString()
                    },
                    onMapSelectionConfirmed = { _, _ ->
                        hasSelectedMapLocation = true
                    },
                    onUserMovedMap = {
                        isTrackingCurrentLocation = false
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.82f)) {
                        OutlinedTextField(
                            value = latitudeState,
                            onValueChange = {
                                latitudeState = it
                            },
                            label = { Text(stringResource(Res.string.latitude)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = longitudeState,
                            onValueChange = {
                                longitudeState = it
                            },
                            label = { Text(stringResource(Res.string.longitude)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (canUseCurrentLocation && !isLocationResolved) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = 20.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        if (!canUseCurrentLocation) {
                            onRequestCurrentLocationPermission()
                        } else {
                            isTrackingCurrentLocation = true
                            currentLocation?.let { location ->
                                latitudeState = location.latitude.toString()
                                longitudeState = location.longitude.toString()
                                hasSeededInitialLocation = true
                                hasSelectedMapLocation = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.position_use_current_location))
                }
                if (!canUseCurrentLocation) {
                    Text(
                        text = stringResource(Res.string.position_current_location_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (parsedLatitude != null && parsedLongitude != null) {
                        val meshPosition =
                            Position(
                                latitude_i = org.meshtastic.core.model.Position.degI(parsedLatitude),
                                longitude_i = org.meshtastic.core.model.Position.degI(parsedLongitude),
                                time = (System.currentTimeMillis() / 1000L).toInt(),
                            )
                        val payloadBytes = buildPositionPayloadBytes(meshPosition.encode())
                        onSend(payloadBytes)
                    }
                    onCancel()
                },
                enabled = parsedLatitude != null && parsedLongitude != null,
            ) {
                Text(stringResource(Res.string.send))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Composable
private fun PositionPickerMap(
    modifier: Modifier = Modifier,
    latitude: Double,
    longitude: Double,
    initialZoomLevel: Double,
    seedCoordinates: Boolean,
    isTrackingCurrentLocation: Boolean,
    onCoordinatesSelected: (Double, Double) -> Unit,
    onMapSelectionConfirmed: (Double, Double) -> Unit,
    onUserMovedMap: () -> Unit,
) {
    val context = LocalContext.current
    val latestOnCoordinatesSelected by rememberUpdatedState(onCoordinatesSelected)
    val latestOnMapSelectionConfirmed by rememberUpdatedState(onMapSelectionConfirmed)
    val latestOnUserMovedMap by rememberUpdatedState(onUserMovedMap)
    var userDraggedMap by remember { mutableStateOf(false) }
    val mapView = remember {
        MapView(context).apply {
            Configuration.getInstance().userAgentValue = context.packageName
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setFlingEnabled(false)
            minZoomLevel = 1.0
            controller.setZoom(initialZoomLevel)
        }
    }
    val pinDrawable = remember(mapView) { Marker(mapView).icon }

    fun updateSelectedPosition() {
        val center = mapView.projection.currentCenter
        latestOnCoordinatesSelected(center.latitude, center.longitude)
        latestOnMapSelectionConfirmed(center.latitude, center.longitude)
    }

    LaunchedEffect(latitude, longitude, isTrackingCurrentLocation) {
        val geoPoint = GeoPoint(latitude, longitude)
        mapView.controller.setCenter(geoPoint)
        if (seedCoordinates) {
            latestOnCoordinatesSelected(geoPoint.latitude, geoPoint.longitude)
        }
        mapView.invalidate()
    }

    LaunchedEffect(initialZoomLevel) {
        mapView.controller.setZoom(initialZoomLevel)
        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(440.dp)
            .clipToBounds(),
    ) {
        AndroidView(
            factory = {
                mapView.apply {
                    controller.setCenter(GeoPoint(latitude, longitude))
                    if (seedCoordinates) {
                        latestOnCoordinatesSelected(latitude, longitude)
                    }
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_MOVE -> userDraggedMap = true
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                if (userDraggedMap) {
                                    latestOnUserMovedMap()
                                }
                                userDraggedMap = false
                            }
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            updateSelectedPosition()
                        }
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.invalidate()
            },
        )

        AndroidView(
            factory = {
                ImageView(it).apply {
                    setImageDrawable(pinDrawable)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            },
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp),
            update = { imageView ->
                imageView.setImageDrawable(pinDrawable)
                pinDrawable?.let { imageView.translationY = -it.intrinsicHeight / 2f }
            },
        )
    }
}

@Composable
internal fun FileAttachmentActionDialog(
    fileNameText: String,
    fileSizeText: String?,
    isComplete: Boolean,
    onSaveToPhone: () -> Unit,
    onOpenWithApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.file_attachment_actions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = fileNameText, style = MaterialTheme.typography.titleMedium)
                fileSizeText?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                if (!isComplete) {
                    Text(text = stringResource(Res.string.file_attachment_receiving), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSaveToPhone,
                enabled = isComplete,
            ) {
                Text(stringResource(Res.string.file_attachment_save_to_phone))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onOpenWithApp,
                    enabled = isComplete,
                ) {
                    Text(stringResource(Res.string.file_attachment_open_with_app))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

private fun Context.resolveDisplayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)
        } else {
            null
        }
    }

private fun Context.readUriBytes(uri: Uri): ByteArray? =
    contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }