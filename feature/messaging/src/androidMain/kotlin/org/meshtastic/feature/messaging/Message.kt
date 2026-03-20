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
@file:Suppress("TooManyFunctions")

package org.meshtastic.feature.messaging

import android.content.ClipData
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.common.util.HomoglyphCharacterStringTransformer
import org.meshtastic.core.model.Channel
import org.meshtastic.core.model.ChannelOption
import org.meshtastic.core.database.entity.QuickChatAction
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.RegionInfo
import org.meshtastic.core.model.util.getChannel
import org.meshtastic.proto.Config
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
import org.meshtastic.core.resources.message_input_label
import org.meshtastic.core.resources.save
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.send
import org.meshtastic.core.resources.type_a_message
import org.meshtastic.core.resources.unknown_channel
import org.meshtastic.core.resources.attachment
import org.meshtastic.core.resources.attach_file
import org.meshtastic.core.resources.attach_image
import org.meshtastic.core.resources.image_adjustment_duty_cycle
import org.meshtastic.core.resources.image_adjustment_duty_cycle_summary
import org.meshtastic.core.resources.image_adjustment_result_line_primary
import org.meshtastic.core.resources.image_adjustment_result_line_secondary
import org.meshtastic.core.resources.image_adjustment_results
import org.meshtastic.core.resources.image_adjustment_milliseconds_value
import org.meshtastic.core.resources.image_adjustment_preview
import org.meshtastic.core.resources.image_adjustment_select_max_side_length
import org.meshtastic.core.resources.image_adjustment_max_transmission_time
import org.meshtastic.core.resources.image_adjustment_max_transmission_time_value
import org.meshtastic.core.ui.component.SharedContactDialog
import org.meshtastic.core.ui.component.smartScrollToIndex
import org.meshtastic.core.ui.theme.AppTheme
import org.meshtastic.feature.messaging.component.ActionModeTopBar
import org.meshtastic.feature.messaging.component.DeleteMessageDialog
import org.meshtastic.feature.messaging.component.MESSAGE_CHARACTER_LIMIT_BYTES
import org.meshtastic.feature.messaging.component.MessageMenuAction
import org.meshtastic.feature.messaging.component.MessageTopBar
import org.meshtastic.feature.messaging.component.QuickChatRow
import org.meshtastic.feature.messaging.component.ReplySnippet
import org.meshtastic.feature.messaging.component.ScrollToBottomFab
import java.nio.charset.StandardCharsets
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val ROUNDED_CORNER_PERCENT = 100
private const val MAX_LINES = 3
private const val IMAGE_HISTORY_SCAN_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
private const val IMAGE_CHUNK_MAX_LENGTH = 175
private const val MIN_IMAGE_DUTY_CYCLE_PERCENT = 0.1f
private const val DEFAULT_IMAGE_DUTY_CYCLE_PERCENT = 1.0f
private const val MAX_AUTO_IMAGE_JPEG_QUALITY = 90
private const val TRANSMISSION_TIME_SLIDER_STEPS = 10

private data class ModemAirtimeParams(
    val spreadFactor: Int,
    val codingRateDenominator: Int,
)

private data class DecodeImageUiState(
    val visible: Boolean = false,
    val imageId: String? = null,
    val foundChunks: Int = 0,
    val totalChunks: Int = 0,
    val isSearching: Boolean = false,
    val decodedBitmap: Bitmap? = null,
    val error: DecodeImageError? = null,
)

private sealed interface DecodeImageError {
    data object NoMatchingChunks : DecodeImageError

    data class MissingChunks(val found: Int, val total: Int) : DecodeImageError

    data object DecodeFailed : DecodeImageError
}

private data class ImageChunkScanResult(
    val partsByIndex: Map<Int, String>,
    val totalParts: Int,
)

private fun maxDutyCyclePercentForRegion(regionCode: Config.LoRaConfig.RegionCode): Float =
    when (regionCode) {
        Config.LoRaConfig.RegionCode.EU_433,
        Config.LoRaConfig.RegionCode.EU_868,
        Config.LoRaConfig.RegionCode.UA_433,
        -> 10.0f
        Config.LoRaConfig.RegionCode.UA_868 -> 1.0f
        else -> 100.0f
    }

private fun modemParamsForPreset(modemPreset: Config.LoRaConfig.ModemPreset): ModemAirtimeParams =
    when (modemPreset) {
        Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW -> ModemAirtimeParams(spreadFactor = 12, codingRateDenominator = 8)
        Config.LoRaConfig.ModemPreset.LONG_SLOW -> ModemAirtimeParams(spreadFactor = 12, codingRateDenominator = 8)
        Config.LoRaConfig.ModemPreset.LONG_FAST -> ModemAirtimeParams(spreadFactor = 11, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.LONG_MODERATE -> ModemAirtimeParams(spreadFactor = 10, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.LONG_TURBO -> ModemAirtimeParams(spreadFactor = 11, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.MEDIUM_SLOW -> ModemAirtimeParams(spreadFactor = 10, codingRateDenominator = 7)
        Config.LoRaConfig.ModemPreset.MEDIUM_FAST -> ModemAirtimeParams(spreadFactor = 9, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.SHORT_SLOW -> ModemAirtimeParams(spreadFactor = 8, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.SHORT_FAST -> ModemAirtimeParams(spreadFactor = 7, codingRateDenominator = 5)
        Config.LoRaConfig.ModemPreset.SHORT_TURBO -> ModemAirtimeParams(spreadFactor = 7, codingRateDenominator = 5)
    }

private fun bandwidthKhz(loraConfig: Config.LoRaConfig): Double {
    if (!loraConfig.use_preset) {
        return when (loraConfig.bandwidth) {
            31 -> 31.25
            62 -> 62.5
            200 -> 203.125
            400 -> 406.25
            800 -> 812.5
            1600 -> 1625.0
            else -> loraConfig.bandwidth.toDouble()
        }
    }

    val baseBandwidthMHz = ChannelOption.from(loraConfig.modem_preset)?.bandwidth ?: ChannelOption.DEFAULT.bandwidth
    val regionScale = if (RegionInfo.fromRegionCode(loraConfig.region)?.wideLora == true) 3.25 else 1.0
    return baseBandwidthMHz.toDouble() * regionScale * 1000.0
}

private fun estimatePacketAirtimeMillis(payloadBytes: Int, loraConfig: Config.LoRaConfig): Int {
    if (payloadBytes <= 0) {
        return 0
    }

    val modemParams =
        if (loraConfig.use_preset) {
            modemParamsForPreset(loraConfig.modem_preset)
        } else {
            ModemAirtimeParams(
                spreadFactor = loraConfig.spread_factor.coerceIn(7, 12),
                codingRateDenominator = loraConfig.coding_rate.coerceIn(5, 8),
            )
        }

    val sf = modemParams.spreadFactor
    val codingRateTerm = (modemParams.codingRateDenominator - 4).coerceIn(1, 4)
    val bwKhz = bandwidthKhz(loraConfig)
    if (bwKhz <= 0.0) {
        return 0
    }

    val lowDataRateOptimization = if (sf >= 11 && bwKhz <= 125.0) 1 else 0
    val symbolDurationSeconds = (2.0.pow(sf.toDouble())) / (bwKhz * 1000.0)
    val preambleSymbols = 8.0 + 4.25

    val numerator = (8.0 * payloadBytes) - (4.0 * sf) + 28.0 + 16.0
    val denominator = 4.0 * (sf - (2 * lowDataRateOptimization))
    val payloadSymbolSteps = ceil((numerator / denominator).coerceAtLeast(0.0))
    val payloadSymbols = 8.0 + (payloadSymbolSteps * (codingRateTerm + 4))

    val airtimeSeconds = (preambleSymbols + payloadSymbols) * symbolDurationSeconds
    return (airtimeSeconds * 1000.0).roundToInt().coerceAtLeast(1)
}

private fun interChunkDelayMillisForDutyCycle(
    dutyCyclePercent: Float,
    packetAirtimeMillis: Int,
): Int {
    if (packetAirtimeMillis <= 0) {
        return 0
    }
    val dutyFraction = (dutyCyclePercent / 100f).coerceIn(0.001f, 1f)
    val cycleMillis = packetAirtimeMillis / dutyFraction
    return (cycleMillis - packetAirtimeMillis).roundToInt().coerceAtLeast(0)
}

private fun estimateTransmissionMillisForChunks(
    chunks: List<String>,
    dutyCyclePercent: Float,
    loraConfig: Config.LoRaConfig,
): Int {
    if (chunks.isEmpty()) {
        return 0
    }
    val packetPayloadBytes = chunks.maxOfOrNull { it.encodeToByteArray().size } ?: 0
    val packetAirtimeMillis = estimatePacketAirtimeMillis(packetPayloadBytes, loraConfig)
    val delayMillis = interChunkDelayMillisForDutyCycle(dutyCyclePercent, packetAirtimeMillis)
    return (chunks.size * packetAirtimeMillis) + ((chunks.size - 1).coerceAtLeast(0) * delayMillis)
}

private fun formatDutyCyclePercent(value: Float): String {
    val roundedTenths = (value * 10f).roundToInt() / 10f
    return if (roundedTenths % 1f == 0f) {
        "${roundedTenths.toInt()}%"
    } else {
        "$roundedTenths%"
    }
}

private fun normalizeTransmissionSliderPosition(seconds: Float, min: Float, max: Float): Float {
    if (max <= min) {
        return 0f
    }
    val normalizedSeconds = ((seconds - min) / (max - min)).coerceIn(0f, 1f)
    return sqrt(normalizedSeconds)
}

private fun transmissionSecondsFromSliderPosition(position: Float, min: Float, max: Float): Float {
    if (max <= min) {
        return min
    }
    val clampedPosition = position.coerceIn(0f, 1f)
    return min + (clampedPosition * clampedPosition * (max - min))
}

private fun snapTransmissionSliderPosition(position: Float): Float {
    val stepCount = TRANSMISSION_TIME_SLIDER_STEPS - 1
    if (stepCount <= 0) {
        return position.coerceIn(0f, 1f)
    }
    val snappedStep = (position.coerceIn(0f, 1f) * stepCount).roundToInt().coerceIn(0, stepCount)
    return snappedStep / stepCount.toFloat()
}

/**
 * The main screen for displaying and sending messages to a contact or channel.
 *
 * @param contactKey A unique key identifying the contact or channel.
 * @param message An optional message to pre-fill in the input field.
 * @param viewModel The [MessageViewModel] instance for handling business logic and state.
 * @param navigateToNodeDetails Callback to navigate to a node's detail screen.
 * @param onNavigateBack Callback to navigate back from this screen.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Due to multiple states and event handling
@Composable
fun MessageScreen(
    contactKey: String,
    message: String,
    viewModel: MessageViewModel,
    navigateToNodeDetails: (Int) -> Unit,
    navigateToQuickChatOptions: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboard.current
    val focusManager = LocalFocusManager.current

    val nodes by viewModel.nodeList.collectAsStateWithLifecycle()
    val ourNode by viewModel.ourNodeInfo.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val quickChatActions by viewModel.quickChatActions.collectAsStateWithLifecycle(initialValue = emptyList())
    val isSendingChunks by viewModel.isSendingChunks.collectAsStateWithLifecycle()
    val pagedMessages = viewModel.getMessagesFromPaged(contactKey).collectAsLazyPagingItems()
    val contactSettings by viewModel.contactSettings.collectAsStateWithLifecycle(initialValue = emptyMap())
    val homoglyphEncodingEnabled by viewModel.homoglyphEncodingEnabled.collectAsStateWithLifecycle(initialValue = false)

    // UI State managed within this Composable
    var replyingToPacketId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var sharedContact by rememberSaveable { mutableStateOf<Node?>(null) }
    val selectedMessageIds = rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val messageInputState = rememberTextFieldState(message)
    var decodeImageUiState by remember { mutableStateOf(DecodeImageUiState()) }
    val showQuickChat by viewModel.showQuickChat.collectAsStateWithLifecycle()
    val filteredCount by viewModel.filteredCount.collectAsStateWithLifecycle()
    val showFiltered by viewModel.showFiltered.collectAsStateWithLifecycle()
    val filteringDisabled = contactSettings[contactKey]?.filteringDisabled ?: false

    // Prevent the message TextField from stealing focus when the screen opens
    LaunchedEffect(contactKey) { focusManager.clearFocus() }

    // Derived state, memoized for performance
    val channelInfo =
        remember(contactKey, channels) {
            val index = contactKey.firstOrNull()?.digitToIntOrNull()
            val id = contactKey.substring(1)
            val name = index?.let { channels.getChannel(it)?.name } // channels can be null initially
            Triple(index, id, name)
        }
    val (channelIndex, nodeId, rawChannelName) = channelInfo
    val unknownChannelText = stringResource(Res.string.unknown_channel)
    val channelName = rawChannelName ?: unknownChannelText

    val title =
        remember(nodeId, channelName, viewModel) {
            when (nodeId) {
                DataPacket.ID_BROADCAST -> channelName
                else -> viewModel.getUser(nodeId).long_name
            }
        }

    val isMismatchKey =
        remember(channelIndex, nodeId, viewModel) {
            channelIndex == DataPacket.PKC_CHANNEL_INDEX && viewModel.getNode(nodeId).mismatchKey
        }

    val inSelectionMode by remember { derivedStateOf { selectedMessageIds.value.isNotEmpty() } }

    val listState = rememberLazyListState()

    // Track unread messages using lightweight metadata queries
    val hasUnreadMessages by viewModel.hasUnreadMessages.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val firstUnreadMessageUuid by viewModel.firstUnreadMessageUuid.collectAsStateWithLifecycle()

    var hasPerformedInitialScroll by rememberSaveable(contactKey) { mutableStateOf(false) }

    // Find the index of the first unread message in the paged list
    val firstUnreadIndex by
        remember(pagedMessages.itemCount, firstUnreadMessageUuid) {
            derivedStateOf {
                firstUnreadMessageUuid?.let { uuid ->
                    pagedMessages.itemSnapshotList.indexOfFirst { it?.uuid == uuid }.takeIf { it != -1 }
                }
            }
        }

    // Scroll to first unread message on initial load
    LaunchedEffect(
        hasPerformedInitialScroll,
        firstUnreadIndex,
        pagedMessages.itemCount,
        hasUnreadMessages,
        firstUnreadMessageUuid,
    ) {
        if (hasPerformedInitialScroll || pagedMessages.itemCount == 0) return@LaunchedEffect
        if (hasUnreadMessages == null) return@LaunchedEffect // Wait for DB state to initialize

        if (hasUnreadMessages == true) {
            if (firstUnreadMessageUuid == null) return@LaunchedEffect // Wait for UUID query

            val index = firstUnreadIndex
            if (index != null) {
                val targetIndex = (index - (UnreadUiDefaults.VISIBLE_CONTEXT_COUNT - 1)).coerceAtLeast(0)
                listState.smartScrollToIndex(coroutineScope = coroutineScope, targetIndex = targetIndex)
                hasPerformedInitialScroll = true
            } else {
                // The first unread message is deeper than the currently loaded pages.
                // Scroll to the end of the loaded items to trigger the next page load.
                // This will re-trigger this LaunchedEffect until we find the message.
                listState.scrollToItem(pagedMessages.itemCount - 1)
            }
        } else {
            // If no unread messages, just scroll to bottom (most recent)
            listState.scrollToItem(0)
            hasPerformedInitialScroll = true
        }
    }

    val onEvent: (MessageScreenEvent) -> Unit =
        remember(viewModel, contactKey, messageInputState, ourNode) {
            fun handle(event: MessageScreenEvent) {
                when (event) {
                    is MessageScreenEvent.SendMessage -> {
                        viewModel.sendMessage(event.text, contactKey, event.replyingToPacketId)
                        if (event.replyingToPacketId != null) replyingToPacketId = null
                        messageInputState.clearText()
                    }

                    is MessageScreenEvent.SendReaction ->
                        viewModel.sendReaction(event.emoji, event.messageId, contactKey)

                    is MessageScreenEvent.DeleteMessages -> {
                        viewModel.deleteMessages(event.ids)
                        selectedMessageIds.value = emptySet()
                        showDeleteDialog = false
                    }

                    is MessageScreenEvent.ClearUnreadCount ->
                        viewModel.clearUnreadCount(contactKey, event.messageUuid, event.lastReadTimestamp)

                    is MessageScreenEvent.NodeDetails -> navigateToNodeDetails(event.node.num)

                    is MessageScreenEvent.SetTitle -> viewModel.setTitle(event.title)
                    is MessageScreenEvent.NavigateToNodeDetails -> navigateToNodeDetails(event.nodeNum)
                    MessageScreenEvent.NavigateBack -> onNavigateBack()
                    is MessageScreenEvent.CopyToClipboard -> {
                        coroutineScope.launch {
                            clipboardManager.setClipEntry(
                                androidx.compose.ui.platform.ClipEntry(ClipData.newPlainText(event.text, event.text)),
                            )
                        }
                        selectedMessageIds.value = emptySet()
                    }
                    is MessageScreenEvent.DecodeImage -> {
                        val seedChunk = parseImageChunk(event.message.text)
                        if (seedChunk == null) {
                            decodeImageUiState =
                                DecodeImageUiState(visible = true, isSearching = false, error = DecodeImageError.DecodeFailed)
                            return
                        }

                        decodeImageUiState =
                            DecodeImageUiState(
                                visible = true,
                                imageId = seedChunk.imageId,
                                foundChunks = 1,
                                totalChunks = seedChunk.totalParts,
                                isSearching = true,
                            )

                        coroutineScope.launch {
                            runCatching {
                                val allMessages = viewModel.getMessagesFlow(contactKey, limit = null).first()
                                val scanResult =
                                    scanImageChunks(
                                        seedChunk = seedChunk,
                                        messages = allMessages,
                                        onProgress = { found, total ->
                                            decodeImageUiState =
                                                decodeImageUiState.copy(
                                                    foundChunks = found,
                                                    totalChunks = total,
                                                    isSearching = true,
                                                    error = null,
                                                )
                                        },
                                    )

                                val foundChunks = scanResult.partsByIndex.size
                                val totalChunks = scanResult.totalParts
                                val missingCount = (1..totalChunks).count { index -> !scanResult.partsByIndex.containsKey(index) }

                                if (foundChunks == 0) {
                                    decodeImageUiState =
                                        decodeImageUiState.copy(
                                            isSearching = false,
                                            decodedBitmap = null,
                                            error = DecodeImageError.NoMatchingChunks,
                                            foundChunks = foundChunks,
                                            totalChunks = totalChunks,
                                        )
                                    return@runCatching
                                }

                                if (missingCount > 0) {
                                    decodeImageUiState =
                                        decodeImageUiState.copy(
                                            isSearching = false,
                                            decodedBitmap = null,
                                            error = DecodeImageError.MissingChunks(foundChunks, totalChunks),
                                            foundChunks = foundChunks,
                                            totalChunks = totalChunks,
                                        )
                                    return@runCatching
                                }

                                val decodedBitmap =
                                    withContext(Dispatchers.Default) {
                                        decodeBitmapFromChunks(
                                            partsByIndex = scanResult.partsByIndex,
                                            totalParts = scanResult.totalParts,
                                        )
                                    }

                                decodeImageUiState =
                                    when {
                                        decodedBitmap != null -> {
                                            decodeImageUiState.copy(
                                                isSearching = false,
                                                decodedBitmap = decodedBitmap,
                                                error = null,
                                                foundChunks = foundChunks,
                                                totalChunks = totalChunks,
                                            )
                                        }

                                        else -> {
                                            decodeImageUiState.copy(
                                                isSearching = false,
                                                decodedBitmap = null,
                                                error = DecodeImageError.DecodeFailed,
                                                foundChunks = foundChunks,
                                                totalChunks = totalChunks,
                                            )
                                        }
                                    }
                            }.onFailure {
                                decodeImageUiState =
                                    decodeImageUiState.copy(
                                        isSearching = false,
                                        decodedBitmap = null,
                                        error = DecodeImageError.DecodeFailed,
                                    )
                            }
                        }
                    }
                }
            }

            ::handle
        }

    if (showDeleteDialog) {
        DeleteMessageDialog(
            count = selectedMessageIds.value.size,
            onConfirm = { onEvent(MessageScreenEvent.DeleteMessages(selectedMessageIds.value.toList())) },
            onDismiss = { showDeleteDialog = false },
        )
    }

    sharedContact?.let { contact -> SharedContactDialog(contact = contact, onDismiss = { sharedContact = null }) }

    if (decodeImageUiState.visible) {
        DecodeImageDialog(
            state = decodeImageUiState,
            onDismiss = { decodeImageUiState = DecodeImageUiState() },
        )
    }

    val originalMessage by
        remember(replyingToPacketId, pagedMessages.itemCount) {
            derivedStateOf {
                replyingToPacketId?.let { id -> pagedMessages.itemSnapshotList.firstOrNull { it?.packetId == id } }
            }
        }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (inSelectionMode) {
                ActionModeTopBar(
                    selectedCount = selectedMessageIds.value.size,
                    onAction = { action ->
                        when (action) {
                            MessageMenuAction.ClipboardCopy -> {
                                val copiedText =
                                    (0 until pagedMessages.itemCount)
                                        .mapNotNull { pagedMessages[it] }
                                        .filter { it.uuid in selectedMessageIds.value }
                                        .joinToString("\n") { it.text }
                                onEvent(MessageScreenEvent.CopyToClipboard(copiedText))
                            }

                            MessageMenuAction.Delete -> showDeleteDialog = true
                            MessageMenuAction.Dismiss -> selectedMessageIds.value = emptySet()
                            MessageMenuAction.SelectAll -> {
                                // Note: Select All is disabled with pagination since we don't have
                                // access to the full message list. This would need to be reworked
                                // to select all currently loaded items instead.
                                selectedMessageIds.value =
                                    if (selectedMessageIds.value.size == pagedMessages.itemCount) {
                                        emptySet()
                                    } else {
                                        (0 until pagedMessages.itemCount).mapNotNull { pagedMessages[it]?.uuid }.toSet()
                                    }
                            }
                        }
                    },
                )
            } else {
                MessageTopBar(
                    title = title,
                    channelIndex = channelIndex,
                    mismatchKey = isMismatchKey,
                    onNavigateBack = { onEvent(MessageScreenEvent.NavigateBack) },
                    channels = channels,
                    channelIndexParam = channelIndex,
                    showQuickChat = showQuickChat,
                    onToggleQuickChat = viewModel::toggleShowQuickChat,
                    onNavigateToQuickChatOptions = navigateToQuickChatOptions,
                    filteringDisabled = filteringDisabled,
                    onToggleFilteringDisabled = {
                        viewModel.setContactFilteringDisabled(contactKey, !filteringDisabled)
                    },
                    filteredCount = filteredCount,
                    showFiltered = showFiltered,
                    onToggleShowFiltered = viewModel::toggleShowFiltered,
                )
            }
        },
        bottomBar = {
            Column {
                AnimatedVisibility(visible = showQuickChat) {
                    QuickChatRow(
                        enabled = connectionState.isConnected(),
                        actions = quickChatActions,
                        onClick = { action ->
                            handleQuickChatAction(
                                action = action,
                                messageInputState = messageInputState,
                                onSendMessage = { text -> onEvent(MessageScreenEvent.SendMessage(text)) },
                            )
                        },
                    )
                }
                ReplySnippet(
                    originalMessage = originalMessage,
                    onClearReply = { replyingToPacketId = null },
                    ourNode = ourNode,
                )
                MessageInput(
                    isEnabled = connectionState.isConnected(),
                    isSendingChunks = isSendingChunks,
                    isHomoglyphEncodingEnabled = homoglyphEncodingEnabled,
                    loraConfig = channels.lora_config ?: Channel.default.loraConfig,
                    textFieldState = messageInputState,
                    onSendMessage = {
                        val messageText = messageInputState.text.toString().trim { it.isWhitespace() }
                        if (messageText.isNotEmpty()) {
                            onEvent(MessageScreenEvent.SendMessage(messageText, replyingToPacketId))
                        }
                    },
                    onSendChunk = { chunk -> onEvent(MessageScreenEvent.SendMessage(chunk, null)) },
                    onStopSendingChunks = viewModel::stopSendingChunks,
                    viewModel = viewModel,
                    contactKey = contactKey
                )
            }
        },
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues).focusable()) {
            MessageListPaged(
                modifier = Modifier.fillMaxSize(),
                listState = listState,
                state =
                MessageListPagedState(
                    nodes = nodes,
                    ourNode = ourNode,
                    messages = pagedMessages,
                    selectedIds = selectedMessageIds,
                    contactKey = contactKey,
                    firstUnreadMessageUuid = firstUnreadMessageUuid,
                    hasUnreadMessages = hasUnreadMessages == true,
                    filteredCount = filteredCount,
                    showFiltered = showFiltered,
                    filteringDisabled = filteringDisabled,
                ),
                handlers =
                MessageListHandlers(
                    onUnreadChanged = { messageUuid, timestamp ->
                        onEvent(MessageScreenEvent.ClearUnreadCount(messageUuid, timestamp))
                    },
                    onSendReaction = { emoji, id -> onEvent(MessageScreenEvent.SendReaction(emoji, id)) },
                    onClickChip = { onEvent(MessageScreenEvent.NodeDetails(it)) },
                    onDeleteMessages = { viewModel.deleteMessages(it) },
                    onSendMessage = { text, key -> viewModel.sendMessage(text, key) },
                    onReply = { message -> replyingToPacketId = message?.packetId },
                    onDecodeImage = { onEvent(MessageScreenEvent.DecodeImage(it)) },
                ),
                quickEmojis = viewModel.frequentEmojis,
            )
            // Show FAB if we can scroll towards the newest messages (index 0).
            if (listState.canScrollBackward) {
                ScrollToBottomFab(coroutineScope, listState, unreadCount)
            }
        }
    }
}

private fun scanImageChunks(
    seedChunk: ImageChunk,
    messages: List<Message>,
    onProgress: (foundChunks: Int, totalChunks: Int) -> Unit,
): ImageChunkScanResult {
    var totalParts = seedChunk.totalParts
    val partsByIndex = mutableMapOf(seedChunk.partIndex to seedChunk.payload)
    var earliestFoundChunkTime: Long? = null
    var searchLowerBoundInclusive = Long.MIN_VALUE

    onProgress(partsByIndex.size, totalParts)

    for (historyMessage in messages) {
        if (earliestFoundChunkTime != null && historyMessage.receivedTime < searchLowerBoundInclusive) {
            break
        }

        val parsedChunk = parseImageChunk(historyMessage.text) ?: continue
        if (parsedChunk.imageId != seedChunk.imageId) continue

        if (parsedChunk.totalParts > totalParts) {
            totalParts = parsedChunk.totalParts
        }

        if (earliestFoundChunkTime == null || historyMessage.receivedTime < earliestFoundChunkTime) {
            earliestFoundChunkTime = historyMessage.receivedTime
            searchLowerBoundInclusive = historyMessage.receivedTime - IMAGE_HISTORY_SCAN_WINDOW_MILLIS
        }

        val wasAdded = partsByIndex.putIfAbsent(parsedChunk.partIndex, parsedChunk.payload) == null
        if (wasAdded) {
            onProgress(partsByIndex.size, totalParts)
        }

        if (partsByIndex.size >= totalParts) {
            break
        }
    }

    return ImageChunkScanResult(partsByIndex = partsByIndex, totalParts = totalParts)
}

private fun decodeBitmapFromChunks(partsByIndex: Map<Int, String>, totalParts: Int): Bitmap? {
    if (totalParts <= 0 || partsByIndex.isEmpty()) return null
    if ((1..totalParts).any { partIndex -> !partsByIndex.containsKey(partIndex) }) return null
    val payload =
        (1..totalParts)
            .mapNotNull { partIndex -> partsByIndex[partIndex] }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), "")
    if (payload.isEmpty()) return null
    val payloadBytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
    val imageBytes = maybeGunzip(payloadBytes)
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun maybeGzip(inputBytes: ByteArray, isEnabled: Boolean): ByteArray {
    if (!isEnabled) return inputBytes
    return runCatching {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(inputBytes)
        }
        outputStream.toByteArray()
    }.getOrDefault(inputBytes)
}

private fun maybeGunzip(inputBytes: ByteArray): ByteArray {
    if (inputBytes.size < 2) return inputBytes
    val isGzip = inputBytes[0] == 0x1f.toByte() && inputBytes[1] == 0x8b.toByte()
    if (!isGzip) return inputBytes
    return runCatching {
        ByteArrayInputStream(inputBytes).use { byteInput ->
            GZIPInputStream(byteInput).use { gzipInput ->
                gzipInput.readBytes()
            }
        }
    }.getOrDefault(inputBytes)
}

private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap, imageId: String?): Boolean {
    val now = System.currentTimeMillis()
    val displayName =
        if (imageId.isNullOrBlank()) {
            "meshtastic_$now.jpg"
        } else {
            "meshtastic_${imageId}_$now.jpg"
        }
    val values =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Meshtastic")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        } ?: false
    }
        .getOrElse {
            resolver.delete(uri, null, null)
            false
        }
        .also { success ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pendingValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, pendingValues, null, null)
            }
            if (!success) {
                resolver.delete(uri, null, null)
            }
        }
}

private fun buildImageChunks(
    imageId: String,
    jpegBytes: ByteArray,
    zipCompressionEnabled: Boolean = true,
    maxChunkLength: Int = IMAGE_CHUNK_MAX_LENGTH,
): List<String> {
    val payloadBytes = maybeGzip(jpegBytes, zipCompressionEnabled)
    val base64 = Base64.encodeToString(payloadBytes, Base64.NO_WRAP)
    if (base64.isEmpty()) return emptyList()

    var totalPartsGuess = 1
    repeat(6) {
        val chunks = mutableListOf<String>()
        var cursor = 0
        var partIndex = 1

        while (cursor < base64.length) {
            val header = "IMG:$imageId|$partIndex/$totalPartsGuess|"
            val maxDataLength = maxChunkLength - header.length
            if (maxDataLength <= 0) return emptyList()

            val nextCursor = (cursor + maxDataLength).coerceAtMost(base64.length)
            chunks += "$header${base64.substring(cursor, nextCursor)}"
            cursor = nextCursor
            partIndex++
        }

        val actualParts = chunks.size
        if (actualParts == totalPartsGuess) {
            return chunks
        }
        totalPartsGuess = actualParts
    }

    return emptyList()
}

private fun decodeBitmapFromOutgoingChunks(chunks: List<String>): Bitmap? {
    if (chunks.isEmpty()) return null
    val parsedChunks = chunks.mapNotNull(::parseImageChunk)
    if (parsedChunks.size != chunks.size) return null

    val firstChunk = parsedChunks.firstOrNull() ?: return null
    val imageId = firstChunk.imageId
    val totalParts = firstChunk.totalParts
    val partsByIndex = mutableMapOf<Int, String>()

    parsedChunks.forEach { chunk ->
        if (chunk.imageId != imageId || chunk.totalParts != totalParts) {
            return null
        }
        partsByIndex[chunk.partIndex] = chunk.payload
    }

    return decodeBitmapFromChunks(partsByIndex = partsByIndex, totalParts = totalParts)
}

@Composable
private fun DecodeImageDialog(state: DecodeImageUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val dialogScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var saveResultMessageRes by remember { mutableStateOf<org.jetbrains.compose.resources.StringResource?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
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
                                            saveBitmapToGallery(context, state.decodedBitmap, state.imageId)
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

/**
 * Handles a quick chat action, either appending its message to the input field or sending it directly.
 *
 * @param action The [QuickChatAction] to handle.
 * @param messageInputState The [TextFieldState] of the message input field.
 * @param onSendMessage Lambda to call when a message needs to be sent.
 */
private fun handleQuickChatAction(
    action: QuickChatAction,
    messageInputState: TextFieldState,
    onSendMessage: (String) -> Unit,
) {
    org.meshtastic.feature.messaging.component.handleQuickChatAction(
        action = action,
        currentText = messageInputState.text.toString(),
        onUpdateText = { newText -> messageInputState.setTextAndPlaceCursorAtEnd(newText) },
        onSendMessage = onSendMessage,
    )
}

/**
 * Dialog for adjusting image size before sending.
 */
@Composable
private fun ImageAdjustmentDialog(
    imageUri: Uri,
    loraConfig: Config.LoRaConfig,
    selectedSize: Int,
    selectedMaxTransmissionTimeSeconds: Float,
    selectedDutyCyclePercent: Float,
    onSizeChange: (Int) -> Unit,
    onMaxTransmissionTimeChange: (Float) -> Unit,
    onDutyCycleChange: (Float) -> Unit,
    onSend: (List<String>, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val sizes = listOf(32, 64, 128, 256, 512)
    var scaledBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedJpegQuality by remember { mutableStateOf(0) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var chunks by remember { mutableStateOf<List<String>>(emptyList()) }
    val regionMaxDutyCyclePercent = maxDutyCyclePercentForRegion(loraConfig.region)
    val boundedDutyCyclePercent = selectedDutyCyclePercent.coerceIn(MIN_IMAGE_DUTY_CYCLE_PERCENT, regionMaxDutyCyclePercent)
    var minTransmissionSeconds by remember { mutableStateOf(0f) }
    var maxTransmissionSeconds by remember { mutableStateOf(0f) }

    val selectedChunkDelayMillis =
        interChunkDelayMillisForDutyCycle(
            boundedDutyCyclePercent,
            estimatePacketAirtimeMillis(chunks.maxOfOrNull { it.encodeToByteArray().size } ?: 0, loraConfig),
        )

    val packetAirtimeMillis = estimatePacketAirtimeMillis(chunks.maxOfOrNull { it.encodeToByteArray().size } ?: 0, loraConfig)
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

    fun buildChunksForQuality(bitmap: Bitmap, quality: Int): List<String> {
        val imageId = UUID.randomUUID().toString().take(8)
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), outputStream)
        return buildImageChunks(imageId = imageId, jpegBytes = outputStream.toByteArray(), zipCompressionEnabled = true)
    }

    LaunchedEffect(imageUri, selectedSize) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                val original: Bitmap? = BitmapFactory.decodeStream(input)
                original?.let {
                    val ratio = minOf(selectedSize.toFloat() / it.width, selectedSize.toFloat() / it.height)
                    val newWidth = (it.width * ratio).toInt()
                    val newHeight = (it.height * ratio).toInt()
                    scaledBitmap = Bitmap.createScaledBitmap(it, newWidth, newHeight, true)
                }
            }
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
        withContext(Dispatchers.IO) {
            val chunksAtQuality0 = buildChunksForQuality(bitmap, 0)
            val chunksAtQuality90 = buildChunksForQuality(bitmap, MAX_AUTO_IMAGE_JPEG_QUALITY)
            val transmissionSecondsAt0 =
                estimateTransmissionMillisForChunks(chunksAtQuality0, boundedDutyCyclePercent, loraConfig) / 1000f
            val transmissionSecondsAt90 =
                estimateTransmissionMillisForChunks(chunksAtQuality90, boundedDutyCyclePercent, loraConfig) / 1000f
            val minSeconds = minOf(transmissionSecondsAt0, transmissionSecondsAt90)
            val maxSeconds = maxOf(transmissionSecondsAt0, transmissionSecondsAt90)

            minTransmissionSeconds = minSeconds
            maxTransmissionSeconds = maxSeconds

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
            if (selectedMaxTransmissionTimeSeconds != snappedSelectedSeconds) {
                onMaxTransmissionTimeChange(snappedSelectedSeconds)
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

            selectedJpegQuality = bestQuality
            chunks = bestChunks
            previewBitmap = decodeBitmapFromOutgoingChunks(bestChunks)
        }
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)
        ) {
            val sizeOptionsScrollState = rememberScrollState()
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
                    modifier = Modifier.fillMaxWidth().horizontalScroll(sizeOptionsScrollState),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sizes.forEach { size ->
                        if (selectedSize == size) {
                            Button(onClick = { onSizeChange(size) }) {
                                Text("$size px")
                            }
                        } else {
                            OutlinedButton(onClick = { onSizeChange(size) }) {
                                Text("$size px")
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

/**
 * The text input field for composing messages.
 *
 * @param isEnabled Whether the input field should be enabled.
 * @param textFieldState The [TextFieldState] managing the input's text.
 * @param modifier The modifier for this composable.
 * @param maxByteSize The maximum allowed size of the message in bytes.
 * @param onSendMessage Callback invoked when the send button is pressed or send IME action is triggered.
 */
@Suppress("LongMethod") // Due to multiple parts of the OutlinedTextField
@Composable
private fun MessageInput(
    isEnabled: Boolean,
    isSendingChunks: Boolean = false,
    isHomoglyphEncodingEnabled: Boolean,
    loraConfig: Config.LoRaConfig,
    textFieldState: TextFieldState,
    modifier: Modifier = Modifier,
    maxByteSize: Int = MESSAGE_CHARACTER_LIMIT_BYTES,
    onSendMessage: () -> Unit,
    viewModel: MessageViewModel?,
    contactKey: String,
    onSendChunk: ((String) -> Unit)? = null,
    onStopSendingChunks: () -> Unit = {},
) {
    val currentTextRaw = textFieldState.text.toString()

    val currentText =
        if (isHomoglyphEncodingEnabled) {
            HomoglyphCharacterStringTransformer.optimizeUtf8StringWithHomoglyphs(currentTextRaw)
        } else {
            currentTextRaw
        }

    val currentByteLength =
        remember(currentText) {
            // Recalculate only when text changes
            currentText.toByteArray(StandardCharsets.UTF_8).size
        }

    val isOverLimit = currentByteLength > maxByteSize
    val canSend = !isOverLimit && currentText.isNotEmpty() && isEnabled

    var showAttachmentMenu by remember { mutableStateOf(false) }

    LaunchedEffect(isEnabled, isSendingChunks) {
        if (!isEnabled && !isSendingChunks) {
            showAttachmentMenu = false
        }
    }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    var selectedSize by remember { mutableStateOf(32) }
    var selectedMaxTransmissionTimeSeconds by remember { mutableStateOf(0f) }
    var selectedDutyCyclePercent by remember {
        mutableStateOf(
            DEFAULT_IMAGE_DUTY_CYCLE_PERCENT.coerceAtMost(maxDutyCyclePercentForRegion(loraConfig.region))
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImageUri = uri
    }

    OutlinedTextField(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        state = textFieldState,
        lineLimits = TextFieldLineLimits.MultiLine(1, MAX_LINES),
        label = { Text(stringResource(Res.string.message_input_label)) },
        enabled = isEnabled,
        shape = RoundedCornerShape(ROUNDED_CORNER_PERCENT.toFloat()),
        isError = isOverLimit,
        placeholder = { Text(stringResource(Res.string.type_a_message)) },
        leadingIcon = {
            if (isSendingChunks) {
                IconButton(onClick = { showAttachmentMenu = true }, enabled = true) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(
                    onClick = {
                        if (isEnabled) {
                            showAttachmentMenu = true
                        }
                    },
                    enabled = isEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = stringResource(Res.string.attachment),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        supportingText = {
            if (isEnabled) { // Only show supporting text if input is enabled
                Text(
                    text = "$currentByteLength/$maxByteSize",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                    if (isOverLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }
        },
        // Direct byte limiting via inputTransformation in TextFieldState is complex.
        // The current approach (show error, disable send) is generally preferred for UX.
        // If strict real-time byte trimming is required, it needs careful handling of
        // cursor position and multi-byte characters, likely outside simple inputTransformation.
        trailingIcon = {
            IconButton(onClick = { if (canSend) onSendMessage() }, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.Send,
                    contentDescription = stringResource(Res.string.send),
                )
            }
        },
    )

    DropdownMenu(
        expanded = showAttachmentMenu && (isEnabled || isSendingChunks),
        onDismissRequest = { showAttachmentMenu = false }
    ) {
        if (isSendingChunks) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.cancel)) },
                onClick = {
                    showAttachmentMenu = false
                    onStopSendingChunks()
                }
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_image)) },
                onClick = {
                    showAttachmentMenu = false
                    imagePickerLauncher.launch("image/*")
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_file)) },
                onClick = { showAttachmentMenu = false }
            )
        }
    }

    val coroutineScope = rememberCoroutineScope()

    selectedImageUri?.let { uri ->
        ImageAdjustmentDialog(
            imageUri = uri,
            loraConfig = loraConfig,
            selectedSize = selectedSize,
            selectedMaxTransmissionTimeSeconds = selectedMaxTransmissionTimeSeconds,
            selectedDutyCyclePercent = selectedDutyCyclePercent,
            onSizeChange = { selectedSize = it },
            onMaxTransmissionTimeChange = { selectedMaxTransmissionTimeSeconds = it },
            onDutyCycleChange = {
                selectedDutyCyclePercent =
                    it.coerceIn(MIN_IMAGE_DUTY_CYCLE_PERCENT, maxDutyCyclePercentForRegion(loraConfig.region))
            },
            onSend = { chunks, delayMillis ->
                selectedImageUri = null
                if (viewModel != null) {
                    viewModel.sendMessageChunks(
                        chunks = chunks,
                        contactKey = contactKey,
                        delayMillis = delayMillis,
                    )
                } else {
                    coroutineScope.launch {
                        if (onSendChunk != null && chunks.isNotEmpty()) {
                            chunks.forEachIndexed { index, chunk ->
                                onSendChunk(chunk)
                                if (index < chunks.lastIndex) {
                                    kotlinx.coroutines.delay(delayMillis.toLong())
                                }
                            }
                        }
                    }
                }
            },
            onCancel = { selectedImageUri = null }
        )
    }
}

@PreviewLightDark
@Composable
private fun MessageInputPreview() {
    AppTheme {
        Surface {
            Column(modifier = Modifier.padding(8.dp)) {
                val dummyContactKey = "preview"
                MessageInput(
                    isEnabled = true,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState = rememberTextFieldState("Hello"),
                    onSendMessage = {},
                    viewModel = null,
                    contactKey = dummyContactKey
                )
                Spacer(Modifier.size(16.dp))
                MessageInput(
                    isEnabled = false,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState = rememberTextFieldState("Disabled"),
                    onSendMessage = {},
                    viewModel = null,
                    contactKey = dummyContactKey
                )
                Spacer(Modifier.size(16.dp))
                MessageInput(
                    isEnabled = true,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState =
                    rememberTextFieldState(
                        "A very long message that might exceed the byte limit " +
                            "and cause an error state display for the user to see clearly.",
                    ),
                    onSendMessage = {},
                    maxByteSize = 50, // Test with a smaller limit
                    viewModel = null,
                    contactKey = dummyContactKey
                )
                Spacer(Modifier.size(16.dp))
                // Test Japanese characters (multi-byte)
                MessageInput(
                    isEnabled = true,
                    isHomoglyphEncodingEnabled = false,
                    loraConfig = Channel.default.loraConfig,
                    textFieldState = rememberTextFieldState("こんにちは世界"), // Hello World in Japanese
                    onSendMessage = {},
                    maxByteSize = 10,
                    viewModel = null,
                    contactKey = dummyContactKey
                    // Each char is 3 bytes, so "こん" (6 bytes) is ok, "こんに" (9 bytes) is ok, "こんにち"
                    // (12 bytes) is over
                )
            }
        }
    }
}
