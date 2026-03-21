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
import co.touchlab.kermit.Logger
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
import java.io.ByteArrayOutputStream
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import org.meshtastic.core.database.entity.QuickChatAction
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.Node
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
import org.meshtastic.feature.messaging.image.DEFAULT_IMAGE_DUTY_CYCLE_PERCENT
import org.meshtastic.feature.messaging.image.MAX_AUTO_IMAGE_JPEG_QUALITY
import org.meshtastic.feature.messaging.image.MIN_IMAGE_DUTY_CYCLE_PERCENT
import org.meshtastic.feature.messaging.image.TRANSMISSION_TIME_SLIDER_STEPS
import org.meshtastic.feature.messaging.image.buildChunkedPayloadPackets
import org.meshtastic.feature.messaging.image.decodeBitmapFromChunks
import org.meshtastic.feature.messaging.image.decodeBitmapFromOutgoingChunkedPayloads
import org.meshtastic.feature.messaging.image.estimatePacketAirtimeMillis
import org.meshtastic.feature.messaging.image.estimateTransmissionMillisForChunks
import org.meshtastic.feature.messaging.image.formatDutyCyclePercent
import org.meshtastic.feature.messaging.image.interChunkDelayMillisForDutyCycle
import org.meshtastic.feature.messaging.image.maxDutyCyclePercentForRegion
import org.meshtastic.feature.messaging.image.normalizeTransmissionSliderPosition
import org.meshtastic.feature.messaging.image.scanImageChunks
import org.meshtastic.feature.messaging.image.snapTransmissionSliderPosition
import org.meshtastic.feature.messaging.image.transmissionSecondsFromSliderPosition
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

private const val ROUNDED_CORNER_PERCENT = 100
private const val MAX_LINES = 3
private val imagePipelineLogger = Logger.withTag("MsgImagePipeline")

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
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run {
        imagePipelineLogger.w { "saveBitmapToGallery failed: insert returned null imageId=$imageId" }
        return false
    }
    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        } ?: false
    }
        .getOrElse {
            imagePipelineLogger.e(it) { "saveBitmapToGallery exception: imageId=$imageId uri=$uri" }
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
            imagePipelineLogger.d {
                "saveBitmapToGallery result: success=$success imageId=$imageId uri=$uri width=${bitmap.width} height=${bitmap.height}"
            }
        }
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
        val outputStream = ByteArrayOutputStream()
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
            shape = RoundedCornerShape(8.dp),
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
        imagePipelineLogger.d { "imagePicker result: selected=${uri != null} uri=$uri" }
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
                    imagePipelineLogger.d { "imagePicker launch requested" }
                    imagePickerLauncher.launch("image/*")
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_file)) },
                onClick = { showAttachmentMenu = false }
            )
        }
    }

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
                imagePipelineLogger.d {
                    "imageSend confirmed: chunks=${chunks.size} delayMillis=$delayMillis totalChunkBytes=${chunks.sumOf { it.size }} contactKey=$contactKey"
                }
                selectedImageUri = null
                if (viewModel != null) {
                    viewModel.sendChunkedPayloadChunks(
                        chunks = chunks,
                        contactKey = contactKey,
                        delayMillis = delayMillis,
                    )
                }
            },
            onCancel = {
                imagePipelineLogger.d { "imageSend cancelled in adjustment dialog uri=$uri" }
                selectedImageUri = null
            }
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
