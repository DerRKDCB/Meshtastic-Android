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

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import co.touchlab.kermit.Logger
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.close
import org.meshtastic.core.resources.decode_image_save_failed
import org.meshtastic.core.resources.decode_image_saved
import org.meshtastic.core.resources.image_timeline_chunk_progress
import org.meshtastic.core.resources.save
import org.meshtastic.proto.PortNum
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.Reaction
import org.meshtastic.feature.messaging.component.MessageItem
import org.meshtastic.feature.messaging.component.MessageStatusDialog
import org.meshtastic.feature.messaging.component.ReactionDialog
import org.meshtastic.feature.messaging.component.UnreadMessagesDivider

private data class TimelinePrivateImageRenderState(
    val imageByMessageUuid: Map<Long, TimelinePrivateImageMessageData>,
    val hiddenChunkMessageUuids: Set<Long>,
)

private data class PrivateImageDecodeCacheEntry(
    val bitmap: Bitmap?,
    val attemptedChunkCount: Int,
)

private data class TimelinePrivateImageMessageData(
    val bitmap: Bitmap?,
    val availableChunks: Int,
    val totalChunks: Int,
)

private val timelineImageLogger = Logger.withTag("MsgTimelineImage")

private fun isGzipPayload(inputBytes: ByteArray): Boolean {
    return inputBytes.size >= 2 && inputBytes[0] == 0x1f.toByte() && inputBytes[1] == 0x8b.toByte()
}

private fun gzipDataOffset(inputBytes: ByteArray): Int? {
    if (!isGzipPayload(inputBytes) || inputBytes.size < 10) {
        return null
    }

    val flags = inputBytes[3].toInt() and 0xFF
    var offset = 10

    if ((flags and 0x04) != 0) {
        if (offset + 2 > inputBytes.size) return null
        val extraLength = (inputBytes[offset].toInt() and 0xFF) or ((inputBytes[offset + 1].toInt() and 0xFF) shl 8)
        offset += 2 + extraLength
    }

    if ((flags and 0x08) != 0) {
        while (offset < inputBytes.size && inputBytes[offset] != 0.toByte()) {
            offset++
        }
        offset++
    }

    if ((flags and 0x10) != 0) {
        while (offset < inputBytes.size && inputBytes[offset] != 0.toByte()) {
            offset++
        }
        offset++
    }

    if ((flags and 0x02) != 0) {
        offset += 2
    }

    return offset.takeIf { it in 0..inputBytes.size }
}

private fun gunzipStrict(inputBytes: ByteArray): ByteArray? {
    if (!isGzipPayload(inputBytes)) {
        timelineImageLogger.d { "gunzipStrict skipped: not-gzip bytes=${inputBytes.size}" }
        return null
    }
    return runCatching {
        ByteArrayInputStream(inputBytes).use { byteInput ->
            GZIPInputStream(byteInput).use { gzipInput ->
                gzipInput.readBytes()
            }
        }
    }.getOrNull().also { output ->
        timelineImageLogger.d { "gunzipStrict result: success=${output != null} inputBytes=${inputBytes.size} outputBytes=${output?.size ?: 0}" }
    }
}

private fun gunzipBestEffortPartial(inputBytes: ByteArray): ByteArray? {
    val dataOffset = gzipDataOffset(inputBytes) ?: return null
    val inflater = Inflater(true)
    return runCatching {
        inflater.setInput(inputBytes, dataOffset, inputBytes.size - dataOffset)
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val inflated = inflater.inflate(buffer)
                if (inflated > 0) {
                    output.write(buffer, 0, inflated)
                } else {
                    break
                }
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull().also {
        timelineImageLogger.d {
            "gunzipBestEffortPartial result: success=${it != null} inputBytes=${inputBytes.size} outputBytes=${it?.size ?: 0} dataOffset=$dataOffset"
        }
        inflater.end()
    }
}

private fun decodeBitmapBestEffort(payloadBytes: ByteArray): Bitmap? {
    if (payloadBytes.isEmpty()) {
        timelineImageLogger.d { "decodeBitmapBestEffort skipped: empty payload" }
        return null
    }

    val candidates = mutableListOf<ByteArray>()
    candidates += payloadBytes

    gunzipStrict(payloadBytes)?.let { candidates += it }
    gunzipBestEffortPartial(payloadBytes)?.let { candidates += it }

    candidates.forEach { candidateBytes ->
        BitmapFactory.decodeByteArray(candidateBytes, 0, candidateBytes.size)?.let {
            timelineImageLogger.d {
                "decodeBitmapBestEffort decoded direct: payloadBytes=${payloadBytes.size} candidateBytes=${candidateBytes.size}"
            }
            return it
        }

        val withEoi = candidateBytes + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        BitmapFactory.decodeByteArray(withEoi, 0, withEoi.size)?.let {
            timelineImageLogger.d {
                "decodeBitmapBestEffort decoded withEOI: payloadBytes=${payloadBytes.size} candidateBytes=${candidateBytes.size}"
            }
            return it
        }
    }

    timelineImageLogger.d {
        "decodeBitmapBestEffort failed: payloadBytes=${payloadBytes.size} candidates=${candidates.size}"
    }
    return null
}

private fun buildPrivateImageRenderState(
    messages: List<Message>,
    decodeCache: MutableMap<String, PrivateImageDecodeCacheEntry>,
): TimelinePrivateImageRenderState {
    data class ChunkMessage(val message: Message, val payloadId: Int, val index: Int, val count: Int, val bytes: ByteArray)

    val chunkMessages =
        messages.mapNotNull { message ->
            val payloadId = message.privatePayloadId
            val chunkIndex = message.privateChunkIndex
            val chunkCount = message.privateChunkCount
            val chunkBytes = message.privateChunkBytes
            if (
                message.dataType == PortNum.PRIVATE_APP.value &&
                    payloadId != null &&
                    chunkIndex != null &&
                    chunkCount != null &&
                    chunkBytes != null
            ) {
                ChunkMessage(message, payloadId, chunkIndex, chunkCount, chunkBytes)
            } else {
                null
            }
        }

    if (chunkMessages.isEmpty()) {
        timelineImageLogger.d { "buildPrivateImageRenderState: no chunk messages in snapshot=${messages.size}" }
        return TimelinePrivateImageRenderState(emptyMap(), emptySet())
    }

    val imageByMessageUuid = mutableMapOf<Long, TimelinePrivateImageMessageData>()
    val hiddenChunkMessageUuids = mutableSetOf<Long>()

    chunkMessages.groupBy { it.payloadId }.values.forEach { group ->
        val expectedCount = group.firstOrNull()?.count ?: return@forEach
        val chunksByIndex = group.associateBy { it.index }
        val availableChunks = chunksByIndex.size
        val payloadId = group.firstOrNull()?.payloadId ?: return@forEach
        val senderNum = group.firstOrNull()?.message?.node?.num ?: 0
        val cacheKey = "$senderNum:$payloadId"
        val cachedEntry = decodeCache[cacheKey]

        val shouldRetryDecode = cachedEntry == null || availableChunks > cachedEntry.attemptedChunkCount
        val bitmap =
            if (shouldRetryDecode) {
                timelineImageLogger.d {
                    "decode attempt: cacheKey=$cacheKey payloadId=$payloadId availableChunks=$availableChunks expectedCount=$expectedCount cachedAttempted=${cachedEntry?.attemptedChunkCount ?: 0}"
                }
                val payloadBytes =
                    ByteArrayOutputStream().use { output ->
                        chunksByIndex.keys.sorted()
                            .mapNotNull { chunkIndex -> chunksByIndex[chunkIndex]?.bytes }
                            .forEach { chunkBytes -> output.write(chunkBytes) }
                        output.toByteArray()
                    }
                decodeBitmapBestEffort(payloadBytes).also { decodedBitmap ->
                    decodeCache[cacheKey] =
                        PrivateImageDecodeCacheEntry(
                            bitmap = decodedBitmap,
                            attemptedChunkCount = availableChunks,
                        )
                    timelineImageLogger.d {
                        "decode stored: cacheKey=$cacheKey decoded=${decodedBitmap != null} payloadBytes=${payloadBytes.size} availableChunks=$availableChunks expectedCount=$expectedCount"
                    }
                }
            } else {
                timelineImageLogger.d {
                    "decode cache hit: cacheKey=$cacheKey bitmap=${cachedEntry.bitmap != null} attemptedChunkCount=${cachedEntry.attemptedChunkCount}"
                }
                cachedEntry.bitmap
            }
        val renderedMessage = group.maxByOrNull { it.index }?.message
        if (renderedMessage != null) {
            imageByMessageUuid[renderedMessage.uuid] =
                TimelinePrivateImageMessageData(
                    bitmap = bitmap,
                    availableChunks = availableChunks,
                    totalChunks = expectedCount,
                )
            group.filter { it.message.uuid != renderedMessage.uuid }.forEach { hiddenChunkMessageUuids += it.message.uuid }
        }
    }

    timelineImageLogger.d {
        "buildPrivateImageRenderState summary: messages=${messages.size} chunkMessages=${chunkMessages.size} renderedImages=${imageByMessageUuid.size} hiddenRows=${hiddenChunkMessageUuids.size}"
    }

    return TimelinePrivateImageRenderState(imageByMessageUuid, hiddenChunkMessageUuids)
}

internal data class MessageListHandlers(
    val onUnreadChanged: (Long, Long) -> Unit,
    val onSendReaction: (String, Int) -> Unit,
    val onClickChip: (Node) -> Unit,
    val onDeleteMessages: (List<Long>) -> Unit,
    val onSendMessage: (String, String) -> Unit,
    val onReply: (Message?) -> Unit,
    val onDecodeImage: (Message) -> Unit,
)

internal data class MessageListPagedState(
    val nodes: List<Node>,
    val ourNode: Node?,
    val messages: LazyPagingItems<Message>,
    val selectedIds: MutableState<Set<Long>>,
    val contactKey: String,
    val firstUnreadMessageUuid: Long? = null,
    val hasUnreadMessages: Boolean = false,
    val filteredCount: Int = 0,
    val showFiltered: Boolean = false,
    val filteringDisabled: Boolean = false,
)

private fun MutableState<Set<Long>>.toggle(uuid: Long) {
    value =
        if (value.contains(uuid)) {
            value - uuid
        } else {
            value + uuid
        }
}

@Composable
internal fun MessageListPaged(
    state: MessageListPagedState,
    handlers: MessageListHandlers,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    quickEmojis: List<String> = emptyList(),
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val inSelectionMode by remember { derivedStateOf { state.selectedIds.value.isNotEmpty() } }

    // Optimization: Pre-calculate map for O(1) lookup in list items to avoid O(N) linear search during scrolling.
    val nodeMap = remember(state.nodes) { state.nodes.associateBy { it.num } }

    var showStatusDialog by remember { mutableStateOf<Message?>(null) }
    showStatusDialog?.let { message ->
        MessageStatusDialog(
            message = message,
            nodes = state.nodes,
            ourNode = state.ourNode,
            resendOption = message.status?.equals(MessageStatus.ERROR) ?: false,
            onResend = {
                handlers.onDeleteMessages(listOf(message.uuid))
                handlers.onSendMessage(message.text, state.contactKey)
                showStatusDialog = null
            },
            onDismiss = { showStatusDialog = null },
        )
    }

    var showReactionDialog by remember { mutableStateOf<List<Reaction>?>(null) }
    showReactionDialog?.let { reactions ->
        ReactionDialog(
            reactions = reactions,
            myId = state.ourNode?.user?.id,
            onDismiss = { showReactionDialog = null },
            onResend = { reaction ->
                handlers.onSendReaction(reaction.emoji, reaction.replyId)
                showReactionDialog = null
            },
            nodes = state.nodes,
            ourNode = state.ourNode,
        )
    }

    val coroutineScope = rememberCoroutineScope()
    var expandedImage by remember { mutableStateOf<Bitmap?>(null) }
    var saveResultMessageRes by remember { mutableStateOf<org.jetbrains.compose.resources.StringResource?>(null) }

    expandedImage?.let { bitmap ->
        Dialog(onDismissRequest = { expandedImage = null }) {
            Surface(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(16.dp)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Expanded image",
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                    )

                    saveResultMessageRes?.let { messageRes ->
                        Text(text = stringResource(messageRes))
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val success = withContext(Dispatchers.IO) { saveBitmapToGallery(context, bitmap) }
                                        timelineImageLogger.d {
                                            "expanded image save requested: success=$success width=${bitmap.width} height=${bitmap.height}"
                                        }
                                        saveResultMessageRes =
                                            if (success) {
                                                Res.string.decode_image_saved
                                            } else {
                                                Res.string.decode_image_save_failed
                                            }
                                    }
                                },
                            ) {
                                Text(stringResource(Res.string.save))
                            }
                            TextButton(onClick = { expandedImage = null }) {
                                Text(stringResource(Res.string.close))
                            }
                        }
                    }
                }
            }
        }
    }

    // Disable auto-scroll when any dialog is open to prevent list jumping
    val hasDialogOpen = showStatusDialog != null || showReactionDialog != null

    // Track unread count based on scroll position
    UpdateUnreadCountPaged(listState = listState, messages = state.messages, onUnreadChange = handlers.onUnreadChanged)

    // Auto-scroll to bottom when new messages arrive
    AutoScrollToBottomPaged(
        listState = listState,
        messages = state.messages,
        hasUnreadMessages = state.hasUnreadMessages,
        hasDialogOpen = hasDialogOpen,
    )

    MessageListPagedContent(
        listState = listState,
        state = state,
        nodeMap = nodeMap,
        handlers = handlers,
        inSelectionMode = inSelectionMode,
        coroutineScope = coroutineScope,
        haptics = haptics,
        onShowStatusDialog = { showStatusDialog = it },
        onShowReactions = { showReactionDialog = it },
        onInlineImageClick = { bitmap ->
            timelineImageLogger.d { "inline image click: width=${bitmap.width} height=${bitmap.height}" }
            expandedImage = bitmap
            saveResultMessageRes = null
        },
        modifier = modifier,
        quickEmojis = quickEmojis,
    )
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun MessageListPagedContent(
    listState: LazyListState,
    state: MessageListPagedState,
    nodeMap: Map<Int, Node>,
    handlers: MessageListHandlers,
    inSelectionMode: Boolean,
    coroutineScope: CoroutineScope,
    haptics: HapticFeedback,
    onShowStatusDialog: (Message) -> Unit,
    onShowReactions: (List<Reaction>) -> Unit,
    onInlineImageClick: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    quickEmojis: List<String>,
) {
    val privateImageDecodeCache = remember { mutableStateMapOf<String, PrivateImageDecodeCacheEntry>() }

    val privateImageRenderState by
        remember(state.messages.itemCount) {
            derivedStateOf {
                val snapshotMessages =
                    state.messages.itemSnapshotList.items
                        .filterNotNull()
                buildPrivateImageRenderState(snapshotMessages, privateImageDecodeCache)
            }
        }

    // Calculate unread divider position using snapshot to avoid side-effects and improve performance
    // Optimized: Use full snapshot index to correctly match LazyColumn index range
    val unreadDividerIndex by
        remember(state.messages.itemCount, state.firstUnreadMessageUuid) {
            derivedStateOf {
                val uuid = state.firstUnreadMessageUuid ?: return@derivedStateOf null
                state.messages.itemSnapshotList.indexOfFirst { it?.uuid == uuid }.takeIf { it != -1 }
            }
        }

    // Disable animations during scroll to prevent jank/stutter
    val enableAnimations by remember { derivedStateOf { !listState.isScrollInProgress } }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(
                count = state.messages.itemCount,
                key = state.messages.itemKey { it.uuid },
                contentType = state.messages.itemContentType { "message" },
            ) { index ->
                val message = state.messages[index]
                val visuallyPrevMessage = if (index < state.messages.itemCount - 1) state.messages[index + 1] else null
                val visuallyNextMessage = if (index > 0) state.messages[index - 1] else null

                val hasSamePrev =
                    if (message != null && visuallyPrevMessage != null) {
                        visuallyPrevMessage.fromLocal == message.fromLocal &&
                            (message.fromLocal || visuallyPrevMessage.node.num == message.node.num)
                    } else {
                        false
                    }

                val hasSameNext =
                    if (message != null && visuallyNextMessage != null) {
                        visuallyNextMessage.fromLocal == message.fromLocal &&
                            (message.fromLocal || visuallyNextMessage.node.num == message.node.num)
                    } else {
                        false
                    }

                if (message != null && message.uuid !in privateImageRenderState.hiddenChunkMessageUuids) {
                    val isFirstUnread = state.hasUnreadMessages && unreadDividerIndex == index
                    val itemModifier = if (enableAnimations) Modifier.animateItem() else Modifier

                    if (isFirstUnread) {
                        // Wrap in Column to prevent overlapping of divider and message item
                        // Apply animation to the container Column once
                        Column(modifier = itemModifier) {
                            UnreadMessagesDivider()
                            RenderPagedChatMessageRow(
                                message = message,
                                inlineImageData = privateImageRenderState.imageByMessageUuid[message.uuid],
                                state = state,
                                nodeMap = nodeMap,
                                handlers = handlers,
                                inSelectionMode = inSelectionMode,
                                coroutineScope = coroutineScope,
                                haptics = haptics,
                                listState = listState,
                                onShowStatusDialog = onShowStatusDialog,
                                onShowReactions = onShowReactions,
                                onInlineImageClick = onInlineImageClick,
                                showUserName = !hasSamePrev,
                                hasSamePrev = hasSamePrev,
                                hasSameNext = hasSameNext,
                                quickEmojis = quickEmojis,
                            )
                        }
                    } else {
                        RenderPagedChatMessageRow(
                            message = message,
                            inlineImageData = privateImageRenderState.imageByMessageUuid[message.uuid],
                            state = state,
                            nodeMap = nodeMap,
                            handlers = handlers,
                            inSelectionMode = inSelectionMode,
                            coroutineScope = coroutineScope,
                            haptics = haptics,
                            listState = listState,
                            onShowStatusDialog = onShowStatusDialog,
                            onShowReactions = onShowReactions,
                            onInlineImageClick = onInlineImageClick,
                            modifier = itemModifier,
                            showUserName = !hasSamePrev,
                            hasSamePrev = hasSamePrev,
                            hasSameNext = hasSameNext,
                            quickEmojis = quickEmojis,
                        )
                    }
                }
            }

            // Loading indicator at the end (top when reversed) when loading more items
            state.messages.apply {
                when {
                    loadState.append is LoadState.Loading -> {
                        item(key = "append_loading", contentType = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun RenderPagedChatMessageRow(
    message: Message,
    inlineImageData: TimelinePrivateImageMessageData?,
    state: MessageListPagedState,
    nodeMap: Map<Int, Node>,
    handlers: MessageListHandlers,
    inSelectionMode: Boolean,
    coroutineScope: CoroutineScope,
    haptics: HapticFeedback,
    listState: LazyListState,
    onShowStatusDialog: (Message) -> Unit,
    onShowReactions: (List<Reaction>) -> Unit,
    onInlineImageClick: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    showUserName: Boolean,
    hasSamePrev: Boolean,
    hasSameNext: Boolean,
    quickEmojis: List<String>,
) {
    val ourNode = state.ourNode ?: return
    val selected by
        remember(message.uuid, state.selectedIds.value) {
            derivedStateOf { state.selectedIds.value.contains(message.uuid) }
        }
    val node = nodeMap[message.node.num] ?: message.node
    val inlineImageBitmap = inlineImageData?.bitmap?.asImageBitmap()
    val inlineImageChunkInfoText =
        inlineImageData?.let {
            stringResource(Res.string.image_timeline_chunk_progress, it.availableChunks, it.totalChunks)
        }

    MessageItem(
        modifier = modifier,
        node = node,
        ourNode = ourNode,
        message = message,
        inlineImageBitmap = inlineImageBitmap,
        inlineImageChunkInfoText = inlineImageChunkInfoText,
        selected = selected,
        inSelectionMode = inSelectionMode,
        onClick = { if (inSelectionMode) state.selectedIds.toggle(message.uuid) },
        onLongClick = {
            if (inSelectionMode) {
                state.selectedIds.toggle(message.uuid)
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        onSelect = { state.selectedIds.toggle(message.uuid) },
        onDelete = { handlers.onDeleteMessages(listOf(message.uuid)) },
        onClickChip = handlers.onClickChip,
        onStatusClick = { onShowStatusDialog(message) },
        onReply = { handlers.onReply(message) },
        emojis = message.emojis,
        showUserName = showUserName,
        sendReaction = { emoji ->
            val hasReacted =
                message.emojis.any { reaction ->
                    (
                        reaction.user.id == ourNode.user.id ||
                            reaction.user.id == org.meshtastic.core.model.DataPacket.ID_LOCAL
                        ) && reaction.emoji == emoji
                }
            if (!hasReacted) {
                handlers.onSendReaction(emoji, message.packetId)
            }
        },
        onShowReactions = { onShowReactions(message.emojis) },
        onNavigateToOriginalMessage = {
            coroutineScope.launch {
                // Note: With pagination, we can't guarantee the original message is loaded
                // Optimized: Use snapshot to find index to avoid side-effects during search
                val targetIndex =
                    state.messages.itemSnapshotList.indexOfFirst { it?.packetId == message.replyId }.takeIf { it != -1 }

                if (targetIndex != null) {
                    listState.animateScrollToItem(index = targetIndex)
                }
            }
        },
        onDecodeImage = { handlers.onDecodeImage(message) },
        onInlineImageClick = {
            inlineImageData?.bitmap?.let(onInlineImageClick)
        },
        hasSamePrev = hasSamePrev,
        hasSameNext = hasSameNext,
        quickEmojis = quickEmojis,
    )
}

private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap): Boolean {
    val now = System.currentTimeMillis()
    val displayName = "meshtastic_$now.jpg"
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
        timelineImageLogger.w { "timeline save failed: insert returned null" }
        return false
    }
    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        } ?: false
    }
        .getOrElse {
            timelineImageLogger.e(it) { "timeline save exception uri=$uri" }
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
            timelineImageLogger.d {
                "timeline save result: success=$success uri=$uri width=${bitmap.width} height=${bitmap.height}"
            }
        }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun AutoScrollToBottomPaged(
    listState: LazyListState,
    messages: LazyPagingItems<Message>,
    hasUnreadMessages: Boolean,
    hasDialogOpen: Boolean = false,
    itemThreshold: Int = 3,
) = with(listState) {
    // Cache whether we were at the bottom - only update when not actively scrolling
    // This prevents stuttering while still tracking position for auto-scroll
    var cachedAtBottom by remember { mutableStateOf(true) }

    val isCurrentlyAtBottom by
        remember(hasUnreadMessages, hasDialogOpen) {
            derivedStateOf {
                if (hasDialogOpen) {
                    false
                } else {
                    val isAtBottom =
                        firstVisibleItemIndex == 0 &&
                            firstVisibleItemScrollOffset <= UnreadUiDefaults.AUTO_SCROLL_BOTTOM_OFFSET_TOLERANCE
                    val isNearBottom = firstVisibleItemIndex <= itemThreshold
                    isAtBottom || (!hasUnreadMessages && isNearBottom)
                }
            }
        }

    // Update cached position only when scroll is idle to prevent stuttering
    LaunchedEffect(isScrollInProgress) {
        if (!isScrollInProgress) {
            cachedAtBottom = isCurrentlyAtBottom
        }
    }

    // Consolidated scroll logic to prevent race conditions
    // Fixes issue where multiple scroll operations could trigger simultaneously
    // by unifying all scroll triggers into a single LaunchedEffect
    LaunchedEffect(messages.itemCount) {
        // Use cached position (captured when scroll was idle) to decide if we should auto-scroll
        // This prevents race conditions where new message renders before we check position
        if (cachedAtBottom && messages.itemCount > 0) {
            scrollToItem(0)
            // Update cache immediately after scrolling
            cachedAtBottom = true
        }
    }
}

private fun findFirstVisibleUnreadMessage(messages: LazyPagingItems<Message>, visibleIndex: Int): Message? {
    val snapshot = messages.itemSnapshotList
    if (visibleIndex >= snapshot.size) return null
    val firstVisibleUnreadIndex =
        (visibleIndex until snapshot.size).firstOrNull { i ->
            val msg = snapshot[i]
            msg != null && !msg.read && !msg.fromLocal
        }
    return firstVisibleUnreadIndex?.let { snapshot[it] }
}

private fun findLastUnreadMessageIndex(messages: LazyPagingItems<Message>): Int? {
    val snapshot = messages.itemSnapshotList
    return (0 until snapshot.size).lastOrNull { i ->
        val msg = snapshot[i]
        msg != null && !msg.read && !msg.fromLocal
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun UpdateUnreadCountPaged(
    listState: LazyListState,
    messages: LazyPagingItems<Message>,
    onUnreadChange: (Long, Long) -> Unit,
) {
    val currentOnUnreadChange by rememberUpdatedState(onUnreadChange)
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    // Track lifecycle state changes
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> isResumed = true
                    Lifecycle.Event.ON_PAUSE -> isResumed = false
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Track remote message count to restart effect when remote messages change
    // This fixes race condition when sending/receiving messages during debounce period
    // Optimized: Use itemSnapshotList instead of iterating through indices
    val remoteMessageCount by
        remember(messages.itemCount) { derivedStateOf { messages.itemSnapshotList.items.count { !it.fromLocal } } }

    // Mark messages as read after debounce period
    // Handles both scrolling cases and when all unread messages are visible without scrolling
    // Effect restarts when isResumed changes, so returning from background will restart the debounce
    LaunchedEffect(remoteMessageCount, listState, isResumed) {
        snapshotFlow {
            // Emit when scroll stops OR when at initial position (covers no-scroll case)
            // Include isResumed in the snapshot so lifecycle changes trigger new emissions
            if (listState.isScrollInProgress || !isResumed) {
                null // Scrolling in progress or not resumed, don't emit
            } else {
                listState.firstVisibleItemIndex // Emit current position when not scrolling and resumed
            }
        }
            .debounce(timeoutMillis = UnreadUiDefaults.SCROLL_DEBOUNCE_MILLIS)
            .collectLatest { index ->
                // Only mark messages as read if we have a valid index (screen is visible and not scrolling)
                if (index != null) {
                    val lastUnreadIndex = findLastUnreadMessageIndex(messages)
                    // If we're at/past the oldest unread, mark the first visible unread message
                    // Since newer messages have HIGHER timestamps, marking a newer message's timestamp
                    // will batch-mark all older messages via SQL: WHERE received_time <= timestamp
                    if (lastUnreadIndex != null && index <= lastUnreadIndex) {
                        val firstVisibleUnread = findFirstVisibleUnreadMessage(messages, index)
                        firstVisibleUnread?.let { currentOnUnreadChange(it.uuid, it.receivedTime) }
                    }
                }
            }
    }
}
