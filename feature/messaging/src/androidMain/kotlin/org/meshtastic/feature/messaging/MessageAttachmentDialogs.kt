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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
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
import org.meshtastic.core.resources.file_adjustment_summary
import org.meshtastic.core.resources.image_adjustment_duty_cycle
import org.meshtastic.core.resources.image_adjustment_duty_cycle_summary
import org.meshtastic.core.resources.image_adjustment_result_line_secondary
import org.meshtastic.core.resources.image_adjustment_milliseconds_value
import org.meshtastic.core.resources.send
import org.meshtastic.core.resources.position
import org.meshtastic.core.model.PrivateAppPayloadType
import org.meshtastic.feature.messaging.image.MIN_IMAGE_DUTY_CYCLE_PERCENT
import org.meshtastic.feature.messaging.image.buildChunkedPayloadPackets
import org.meshtastic.feature.messaging.image.buildPositionPayloadBytes
import org.meshtastic.feature.messaging.image.estimatePacketAirtimeMillis
import org.meshtastic.feature.messaging.image.estimateTransmissionMillisForChunks
import org.meshtastic.feature.messaging.image.formatDutyCyclePercent
import org.meshtastic.feature.messaging.image.interChunkDelayMillisForDutyCycle
import org.meshtastic.feature.messaging.image.maxDutyCyclePercentForRegion
import org.meshtastic.core.ui.util.rememberOpenMap
import org.meshtastic.proto.Config
import org.meshtastic.proto.Position
import java.util.Locale

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
    var chunks by remember(fileUri) { mutableStateOf<List<ByteArray>>(emptyList()) }
    var loadError by remember(fileUri) { mutableStateOf(false) }

    LaunchedEffect(fileUri) {
        val loaded =
            withContext(Dispatchers.IO) {
                val resolvedFileName = context.resolveDisplayName(uri = fileUri) ?: fileUri.lastPathSegment ?: "attachment"
                val fileBytes = context.readUriBytes(uri = fileUri)
                if (fileBytes == null) return@withContext null
                val payloadBytes = encodePrivateAppPayload(PrivateAppPayloadType.File, fileBytes, fileName = resolvedFileName)
                Triple(resolvedFileName, fileBytes, buildChunkedPayloadPackets(payloadBytes = payloadBytes))
            }
        if (loaded == null) {
            loadError = true
            return@LaunchedEffect
        }

        val (resolvedFileName, fileBytes, loadedChunks) = loaded
        fileName = resolvedFileName
        fileSizeText = Formatter.formatShortFileSize(context, fileBytes.size.toLong())
        chunks = loadedChunks
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
                            text = stringResource(Res.string.file_adjustment_summary, fileName!!, fileSizeText!!),
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                            if (chunks.isNotEmpty()) {
                                onSend(chunks, selectedChunkDelayMillis)
                            }
                        },
                        enabled = chunks.isNotEmpty(),
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
    onSend: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    val openMap = rememberOpenMap()
    val positionTitle = stringResource(Res.string.position)
    var latitudeState by rememberSaveable { mutableStateOf(currentLocation?.latitude?.toString().orEmpty()) }
    var longitudeState by rememberSaveable { mutableStateOf(currentLocation?.longitude?.toString().orEmpty()) }
    val parsedLatitude = latitudeState.toDoubleOrNull()
    val parsedLongitude = longitudeState.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.position)) },
        text = {
            Column {
                OutlinedTextField(
                    value = latitudeState,
                    onValueChange = { latitudeState = it },
                    label = { Text("Latitude") },
                )
                OutlinedTextField(
                    value = longitudeState,
                    onValueChange = { longitudeState = it },
                    label = { Text("Longitude") },
                )
                Text(
                    text = if (parsedLatitude != null && parsedLongitude != null) {
                        String.format(Locale.US, "Preview: %.6f, %.6f", parsedLatitude, parsedLongitude)
                    } else {
                        "Enter valid coordinates to preview"
                    },
                )
                TextButton(
                    onClick = {
                        currentLocation?.let { location ->
                            latitudeState = location.latitude.toString()
                            longitudeState = location.longitude.toString()
                        }
                    },
                ) {
                    Text("Use current location")
                }
                TextButton(
                    onClick = {
                        if (parsedLatitude != null && parsedLongitude != null) {
                            openMap(parsedLatitude, parsedLongitude, positionTitle)
                        }
                    },
                    enabled = parsedLatitude != null && parsedLongitude != null,
                ) {
                    Text("Open map")
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