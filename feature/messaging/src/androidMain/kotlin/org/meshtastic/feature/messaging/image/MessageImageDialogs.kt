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
package org.meshtastic.feature.messaging.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.decode_image
import org.meshtastic.core.resources.decode_image_chunks_found
import org.meshtastic.core.resources.decode_image_failed
import org.meshtastic.core.resources.decode_image_id
import org.meshtastic.core.resources.decode_image_missing_chunks
import org.meshtastic.core.resources.decode_image_no_matching_chunks
import org.meshtastic.core.resources.decode_image_save_failed
import org.meshtastic.core.resources.decode_image_saved
import org.meshtastic.core.resources.decode_image_searching
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.image_adjustment_duty_cycle
import org.meshtastic.core.resources.image_adjustment_duty_cycle_summary
import org.meshtastic.core.resources.image_adjustment_max_transmission_time
import org.meshtastic.core.resources.image_adjustment_max_transmission_time_value
import org.meshtastic.core.resources.image_adjustment_milliseconds_value
import org.meshtastic.core.resources.image_adjustment_preview
import org.meshtastic.core.resources.image_adjustment_result_line_primary
import org.meshtastic.core.resources.image_adjustment_result_line_secondary
import org.meshtastic.core.resources.image_adjustment_results
import org.meshtastic.core.resources.image_adjustment_select_max_side_length
import org.meshtastic.core.resources.save
import org.meshtastic.core.resources.send
import org.meshtastic.proto.Config
import kotlin.math.roundToInt

private val imagePipelineLogger = Logger.withTag("MsgImagePipeline")

internal data class DecodeImageUiState(
    val visible: Boolean = false,
    val imageId: String? = null,
    val foundChunks: Int = 0,
    val totalChunks: Int = 0,
    val isSearching: Boolean = false,
    val decodedBitmap: Bitmap? = null,
    val error: DecodeImageError? = null,
)

internal sealed interface DecodeImageError {
    data object NoMatchingChunks : DecodeImageError

    data class MissingChunks(val found: Int, val total: Int) : DecodeImageError

    data object DecodeFailed : DecodeImageError
}

@Composable
internal fun DecodeImageDialog(state: DecodeImageUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dialogScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var saveResultMessageRes by remember { mutableStateOf<org.jetbrains.compose.resources.StringResource?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = stringResource(Res.string.decode_image), style = MaterialTheme.typography.titleMedium)

                state.imageId?.let { imageId ->
                    Text(
                        text = stringResource(Res.string.decode_image_id, imageId),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                if (state.isSearching) {
                    val progress =
                        if (state.totalChunks > 0) {
                            (state.foundChunks.toFloat() / state.totalChunks.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    if (state.totalChunks > 0) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                Text(
                    text =
                        if (state.totalChunks > 0) {
                            stringResource(
                                Res.string.decode_image_chunks_found,
                                state.foundChunks,
                                state.totalChunks,
                            )
                        } else {
                            stringResource(Res.string.decode_image_searching)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )

                state.error?.let { error ->
                    val errorText =
                        when (error) {
                            DecodeImageError.NoMatchingChunks -> stringResource(Res.string.decode_image_no_matching_chunks)
                            is DecodeImageError.MissingChunks ->
                                stringResource(Res.string.decode_image_missing_chunks, error.found, error.total)
                            DecodeImageError.DecodeFailed -> stringResource(Res.string.decode_image_failed)
                        }
                    Text(text = errorText, color = MaterialTheme.colorScheme.error)
                }

                state.decodedBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(Res.string.decode_image),
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                    )
                }

                saveResultMessageRes?.let { messageRes ->
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (state.decodedBitmap != null) {
                        TextButton(
                            onClick = {
                                if (isSaving) return@TextButton
                                isSaving = true
                                saveResultMessageRes = null
                                dialogScope.launch {
                                    val success =
                                        withContext(Dispatchers.IO) {
                                            saveBitmapToGallery(context, state.decodedBitmap, state.imageId, logTag = "MsgImagePipeline")
                                        }
                                    saveResultMessageRes =
                                        if (success) {
                                            Res.string.decode_image_saved
                                        } else {
                                            Res.string.decode_image_save_failed
                                        }
                                    isSaving = false
                                }
                            },
                            enabled = !isSaving,
                        ) {
                            Text(stringResource(Res.string.save))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.close))
                    }
                }
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
internal fun ImageAdjustmentDialog(
    imageUri: Uri,
    loraConfig: Config.LoRaConfig,
    selectedSize: Int,
    selectedMaxTransmissionTimeSeconds: Float,
    selectedDutyCyclePercent: Float,
    onSizeChange: (Int) -> Unit,
    onMaxTransmissionTimeChange: (Float) -> Unit,
    onDutyCycleChange: (Float) -> Unit,
    onSend: (List<ByteArray>, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val sizes = listOf(32, 64, 128, 256, 512)
    var scaledBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedJpegQuality by remember { mutableStateOf(0) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var chunks by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var scaledBitmapRequestId by remember(imageUri) { mutableStateOf(0) }
    var previewComputationRequestId by remember(imageUri) { mutableStateOf(0) }
    val regionMaxDutyCyclePercent = maxDutyCyclePercentForRegion(loraConfig.region)
    val boundedDutyCyclePercent = selectedDutyCyclePercent.coerceIn(MIN_IMAGE_DUTY_CYCLE_PERCENT, regionMaxDutyCyclePercent)
    var minTransmissionSeconds by remember { mutableStateOf(0f) }
    var maxTransmissionSeconds by remember { mutableStateOf(0f) }

    val selectedChunkDelayMillis =
        interChunkDelayMillisForDutyCycle(
            boundedDutyCyclePercent,
            estimatePacketAirtimeMillis(chunks.maxOfOrNull { it.size } ?: 0, loraConfig),
        )

    val packetAirtimeMillis = estimatePacketAirtimeMillis(chunks.maxOfOrNull { it.size } ?: 0, loraConfig)
    val totalAirtimeMillis = chunks.size * packetAirtimeMillis
    val estimatedTransmissionMillis = estimateTransmissionMillisForChunks(chunks, boundedDutyCyclePercent, loraConfig)
    val estimatedTransmissionSeconds = estimatedTransmissionMillis / 1000
    val estimatedTransmissionTimeText = DateUtils.formatElapsedTime(estimatedTransmissionSeconds.toLong())
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
    val selectedTransmissionSliderPosition =
        if (maxTransmissionSeconds > 0f) {
            snapTransmissionSliderPosition(
                normalizeTransmissionSliderPosition(
                    selectedMaxTransmissionTimeSeconds.coerceIn(minTransmissionSeconds, maxTransmissionSeconds),
                    minTransmissionSeconds,
                    maxTransmissionSeconds,
                )
            )
        } else {
            0f
        }
    val boundedSelectedMaxTransmissionTimeSeconds =
        transmissionSecondsFromSliderPosition(
            selectedTransmissionSliderPosition,
            minTransmissionSeconds,
            maxTransmissionSeconds,
        )
    val selectedMaxTransmissionTimeText =
        DateUtils.formatElapsedTime(boundedSelectedMaxTransmissionTimeSeconds.roundToInt().toLong())

    fun buildChunksForQuality(bitmap: Bitmap, quality: Int): List<ByteArray> {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), outputStream)
        return buildChunkedPayloadPackets(jpegBytes = outputStream.toByteArray(), zipCompressionEnabled = true).also { builtChunks ->
            imagePipelineLogger.d {
                "buildChunksForQuality result: quality=$quality chunks=${builtChunks.size} maxChunkBytes=${builtChunks.maxOfOrNull { it.size } ?: 0}"
            }
        }
    }

    LaunchedEffect(imageUri, selectedSize) {
        val requestId = ++scaledBitmapRequestId
        imagePipelineLogger.d {
            "imageImport decode start: requestId=$requestId uri=$imageUri selectedSize=$selectedSize"
        }
        try {
            val computedScaledBitmap =
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(imageUri)?.use { input ->
                        val original: Bitmap? = BitmapFactory.decodeStream(input)
                        original?.let {
                            val ratio = minOf(selectedSize.toFloat() / it.width, selectedSize.toFloat() / it.height)
                            val newWidth = (it.width * ratio).toInt()
                            val newHeight = (it.height * ratio).toInt()
                            Bitmap.createScaledBitmap(it, newWidth, newHeight, true)
                        }
                    }
                }
            if (requestId == scaledBitmapRequestId) {
                scaledBitmap = computedScaledBitmap
                imagePipelineLogger.d {
                    "imageImport decode finished: requestId=$requestId hasBitmap=${computedScaledBitmap != null} width=${computedScaledBitmap?.width ?: 0} height=${computedScaledBitmap?.height ?: 0}"
                }
            } else {
                imagePipelineLogger.d { "imageImport decode stale result dropped: requestId=$requestId latest=$scaledBitmapRequestId" }
            }
        } catch (cancellation: CancellationException) {
            imagePipelineLogger.d { "imageImport decode cancelled: requestId=$requestId" }
            throw cancellation
        }
    }

    LaunchedEffect(
        scaledBitmap,
        selectedSize,
        boundedDutyCyclePercent,
        regionMaxDutyCyclePercent,
        loraConfig,
        selectedMaxTransmissionTimeSeconds,
    ) {
        val bitmap = scaledBitmap ?: return@LaunchedEffect
        val requestId = ++previewComputationRequestId
        imagePipelineLogger.d {
            "previewComputation start: requestId=$requestId width=${bitmap.width} height=${bitmap.height} selectedSize=$selectedSize duty=$boundedDutyCyclePercent maxSeconds=$selectedMaxTransmissionTimeSeconds"
        }
        try {
            data class PreviewComputationResult(
                val minSeconds: Float,
                val maxSeconds: Float,
                val snappedSelectedSeconds: Float,
                val bestQuality: Int,
                val bestChunks: List<ByteArray>,
                val decodedPreview: Bitmap?,
            )

            val result =
                withContext(Dispatchers.IO) {
                    val chunksAtQuality0 = buildChunksForQuality(bitmap, 0)
                    val chunksAtQuality90 = buildChunksForQuality(bitmap, MAX_AUTO_IMAGE_JPEG_QUALITY)
                    val transmissionSecondsAt0 =
                        estimateTransmissionMillisForChunks(chunksAtQuality0, boundedDutyCyclePercent, loraConfig) / 1000f
                    val transmissionSecondsAt90 =
                        estimateTransmissionMillisForChunks(chunksAtQuality90, boundedDutyCyclePercent, loraConfig) / 1000f
                    val minSeconds = minOf(transmissionSecondsAt0, transmissionSecondsAt90)
                    val maxSeconds = maxOf(transmissionSecondsAt0, transmissionSecondsAt90)

                    val snappedSelectedSeconds =
                        if (selectedMaxTransmissionTimeSeconds <= 0f) {
                            minSeconds
                        } else {
                            transmissionSecondsFromSliderPosition(
                                snapTransmissionSliderPosition(
                                    normalizeTransmissionSliderPosition(
                                        selectedMaxTransmissionTimeSeconds.coerceIn(minSeconds, maxSeconds),
                                        minSeconds,
                                        maxSeconds,
                                    )
                                ),
                                minSeconds,
                                maxSeconds,
                            )
                        }

                    val targetSeconds =
                        if (selectedMaxTransmissionTimeSeconds <= 0f) {
                            minSeconds
                        } else {
                            transmissionSecondsFromSliderPosition(
                                snapTransmissionSliderPosition(
                                    normalizeTransmissionSliderPosition(
                                        selectedMaxTransmissionTimeSeconds.coerceIn(minSeconds, maxSeconds),
                                        minSeconds,
                                        maxSeconds,
                                    )
                                ),
                                minSeconds,
                                maxSeconds,
                            )
                        }

                    var bestQuality = 0
                    var bestChunks = chunksAtQuality0
                    for (quality in MAX_AUTO_IMAGE_JPEG_QUALITY downTo 0) {
                        val candidateChunks =
                            when (quality) {
                                0 -> chunksAtQuality0
                                MAX_AUTO_IMAGE_JPEG_QUALITY -> chunksAtQuality90
                                else -> buildChunksForQuality(bitmap, quality)
                            }
                        val candidateSeconds =
                            estimateTransmissionMillisForChunks(candidateChunks, boundedDutyCyclePercent, loraConfig) / 1000f
                        if (candidateSeconds <= targetSeconds) {
                            bestQuality = quality
                            bestChunks = candidateChunks
                            break
                        }
                    }

                    PreviewComputationResult(
                        minSeconds = minSeconds,
                        maxSeconds = maxSeconds,
                        snappedSelectedSeconds = snappedSelectedSeconds,
                        bestQuality = bestQuality,
                        bestChunks = bestChunks,
                        decodedPreview = decodeBitmapFromOutgoingChunkedPayloads(bestChunks),
                    )
                }

            if (requestId != previewComputationRequestId) {
                imagePipelineLogger.d {
                    "previewComputation stale result dropped: requestId=$requestId latest=$previewComputationRequestId"
                }
                return@LaunchedEffect
            }

            minTransmissionSeconds = result.minSeconds
            maxTransmissionSeconds = result.maxSeconds

            if (selectedMaxTransmissionTimeSeconds != result.snappedSelectedSeconds) {
                onMaxTransmissionTimeChange(result.snappedSelectedSeconds)
            }

            selectedJpegQuality = result.bestQuality
            chunks = result.bestChunks
            previewBitmap = result.decodedPreview
            imagePipelineLogger.d {
                "previewComputation applied: requestId=$requestId quality=${result.bestQuality} chunks=${result.bestChunks.size} decodedPreview=${result.decodedPreview != null} minSeconds=${result.minSeconds} maxSeconds=${result.maxSeconds}"
            }
        } catch (cancellation: CancellationException) {
            imagePipelineLogger.d { "previewComputation cancelled: requestId=$requestId" }
            throw cancellation
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                previewBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(Res.string.image_adjustment_preview),
                        modifier = Modifier.fillMaxWidth().height(400.dp)
                    )
                }

                Spacer(modifier = Modifier.size(16.dp))

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
                    onValueChange = { value ->
                        onDutyCycleChange(value)
                    },
                    valueRange = MIN_IMAGE_DUTY_CYCLE_PERCENT..regionMaxDutyCyclePercent,
                )

                Text(stringResource(Res.string.image_adjustment_select_max_side_length))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sizes.forEach { size ->
                        if (selectedSize == size) {
                            Button(
                                onClick = { onSizeChange(size) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("$size px", textAlign = TextAlign.Center)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSizeChange(size) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("$size px", textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                Text(stringResource(Res.string.image_adjustment_max_transmission_time))
                Text(
                    stringResource(
                        Res.string.image_adjustment_max_transmission_time_value,
                        selectedMaxTransmissionTimeText,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (maxTransmissionSeconds > 0f) {
                    Slider(
                        value = selectedTransmissionSliderPosition,
                        onValueChange = { value ->
                            onMaxTransmissionTimeChange(
                                transmissionSecondsFromSliderPosition(
                                    snapTransmissionSliderPosition(value),
                                    minTransmissionSeconds,
                                    maxTransmissionSeconds,
                                )
                            )
                        },
                        steps = TRANSMISSION_TIME_SLIDER_STEPS - 2,
                        valueRange = 0f..1f,
                    )
                }

                Spacer(modifier = Modifier.size(8.dp))

                Text(stringResource(Res.string.image_adjustment_results))
                Text(
                    stringResource(
                        Res.string.image_adjustment_result_line_primary,
                        chunks.size,
                        "$selectedJpegQuality%",
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
                    Button(onClick = { onSend(chunks, selectedChunkDelayMillis) }) {
                        Text(stringResource(Res.string.send))
                    }
                }
            }
        }
    }
}
