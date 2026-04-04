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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.Reaction
import org.meshtastic.feature.messaging.image.rememberTimelineImageRows
import org.meshtastic.feature.messaging.component.MessageStatusDialog
import org.meshtastic.feature.messaging.component.ReactionDialog
import org.meshtastic.feature.messaging.component.UnreadMessagesDivider

internal data class MessageListHandlers(
    val onUnreadChanged: (Long, Long) -> Unit,
    val onSendReaction: (String, Int) -> Unit,
    val onClickChip: (Node) -> Unit,
    val onDeleteMessages: (List<Long>) -> Unit,
    val onSendMessage: (String, String) -> Unit,
    val onReply: (Message?) -> Unit,
    val onLoadOlderImageChunks: () -> Unit,
)

internal data class MessageListPagedState(
    val nodes: List<Node>,
    val ourNode: Node?,
    val messages: List<Message>,
    val imageChunkMessages: List<Message>,
    val isLoadingMore: Boolean = false,
    val selectedIds: MutableState<Set<Long>>,
    val contactKey: String,
    val firstUnreadMessageUuid: Long? = null,
    val hasUnreadMessages: Boolean = false,
    val filteredCount: Int = 0,
    val showFiltered: Boolean = false,
    val filteringDisabled: Boolean = false,
)

internal fun MutableState<Set<Long>>.toggle(uuid: Long) {
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
    val inSelectionMode by remember { derivedStateOf { state.selectedIds.value.isNotEmpty() } }
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

    // Disable auto-scroll when any dialog is open to prevent list jumping
    val hasDialogOpen = showStatusDialog != null || showReactionDialog != null

    // Track unread count based on scroll position
    UpdateUnreadCountPaged(listState = listState, messages = state.messages, onUnreadChange = handlers.onUnreadChanged)

    // Only request older image chunks when the user scrolls back into older timeline history.
    LazyLoadOlderImageChunks(
        listState = listState,
        messageCount = state.messages.size,
        onLoadOlderImageChunks = handlers.onLoadOlderImageChunks,
    )

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
    modifier: Modifier = Modifier,
    quickEmojis: List<String>,
) {
    val messages = state.messages

    val timelineImageRowsResult = rememberTimelineImageRows(
        contactKey = state.contactKey,
        displayedMessages = messages,
        imageChunkMessages = state.imageChunkMessages,
    )
    val loadedImageRows = timelineImageRowsResult.rows
    val onInlineImageClick = timelineImageRowsResult.onInlineImageClick

    // Calculate unread divider position using snapshot to avoid side-effects and improve performance
    // Optimized: Use full snapshot index to correctly match LazyColumn index range
    val unreadDividerIndex by
        remember(messages, state.firstUnreadMessageUuid) {
            derivedStateOf {
                val uuid = state.firstUnreadMessageUuid ?: return@derivedStateOf null
                messages.indexOfFirst { it.uuid == uuid }.takeIf { it != -1 }
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
                count = messages.size,
                key = { index -> messages[index].uuid },
                contentType = { "message" },
            ) { index ->
                val message = messages[index]
                val visuallyPrevMessage = if (index < messages.size - 1) messages[index + 1] else null
                val visuallyNextMessage = if (index > 0) messages[index - 1] else null

                val hasSamePrev =
                    if (visuallyPrevMessage != null) {
                        visuallyPrevMessage.fromLocal == message.fromLocal &&
                            (message.fromLocal || visuallyPrevMessage.node.num == message.node.num)
                    } else {
                        false
                    }

                val hasSameNext =
                    if (visuallyNextMessage != null) {
                        visuallyNextMessage.fromLocal == message.fromLocal &&
                            (message.fromLocal || visuallyNextMessage.node.num == message.node.num)
                    } else {
                        false
                    }

                if (message.uuid !in loadedImageRows.hiddenChunkMessageUuids) {
                    val isFirstUnread = state.hasUnreadMessages && unreadDividerIndex == index
                    val itemModifier = if (enableAnimations) Modifier.animateItem() else Modifier

                    if (isFirstUnread) {
                        Column(modifier = itemModifier) {
                            UnreadMessagesDivider()
                            RenderPagedChatMessageRow(
                                message = message,
                                inlineImageData = loadedImageRows.imageByMessageUuid[message.uuid],
                                inlineAttachmentLabel = loadedImageRows.attachmentLabelByMessageUuid[message.uuid],
                                inlineAttachmentPayload = loadedImageRows.attachmentPayloadByMessageUuid[message.uuid],
                                state = state,
                                nodeMap = nodeMap,
                                handlers = handlers,
                                inSelectionMode = inSelectionMode,
                                coroutineScope = coroutineScope,
                                haptics = haptics,
                                listState = listState,
                                messages = messages,
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
                            inlineImageData = loadedImageRows.imageByMessageUuid[message.uuid],
                            inlineAttachmentLabel = loadedImageRows.attachmentLabelByMessageUuid[message.uuid],
                            inlineAttachmentPayload = loadedImageRows.attachmentPayloadByMessageUuid[message.uuid],
                            state = state,
                            nodeMap = nodeMap,
                            handlers = handlers,
                            inSelectionMode = inSelectionMode,
                            coroutineScope = coroutineScope,
                            haptics = haptics,
                            listState = listState,
                            messages = messages,
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
            if (state.isLoadingMore) {
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


@Suppress("CyclomaticComplexMethod")
@Composable
private fun AutoScrollToBottomPaged(
    listState: LazyListState,
    messages: List<Message>,
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
    val newestMessageUuid by remember(messages) { derivedStateOf { messages.firstOrNull()?.uuid } }
    LaunchedEffect(newestMessageUuid) {
        // Use cached position (captured when scroll was idle) to decide if we should auto-scroll
        // This prevents race conditions where new message renders before we check position
        if (cachedAtBottom && messages.isNotEmpty()) {
            scrollToItem(0)
            // Update cache immediately after scrolling
            cachedAtBottom = true
        }
    }
}

private fun findFirstVisibleUnreadMessage(messages: List<Message>, visibleIndex: Int): Message? {
    if (visibleIndex >= messages.size) return null
    val firstVisibleUnreadIndex =
        (visibleIndex until messages.size).firstOrNull { i ->
            val msg = messages[i]
            !msg.read && !msg.fromLocal
        }
    return firstVisibleUnreadIndex?.let { messages[it] }
}

private fun findLastUnreadMessageIndex(messages: List<Message>): Int? {
    return (0 until messages.size).lastOrNull { i ->
        val msg = messages[i]
        !msg.read && !msg.fromLocal
    }
}

@Composable
private fun LazyLoadOlderImageChunks(
    listState: LazyListState,
    messageCount: Int,
    onLoadOlderImageChunks: () -> Unit,
) {
    val currentMessageCount by rememberUpdatedState(messageCount)
    LaunchedEffect(listState) {
        snapshotFlow {
            val currentCount = currentMessageCount
            val hasScrolledBack = listState.firstVisibleItemIndex > 0
            val oldestVisibleIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            val nearOldestVisibleEdge =
                oldestVisibleIndex >=
                    (currentCount - ImageChunkUiDefaults.OLDER_HISTORY_PREFETCH_THRESHOLD).coerceAtLeast(0)
            hasScrolledBack && nearOldestVisibleEdge
        }
            .distinctUntilChanged()
            .collectLatest { shouldLoadOlder ->
                if (shouldLoadOlder) {
                    onLoadOlderImageChunks()
                }
            }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun UpdateUnreadCountPaged(
    listState: LazyListState,
    messages: List<Message>,
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
    val remoteMessageCount by
        remember(messages) { derivedStateOf { messages.count { !it.fromLocal } } }

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

private object ImageChunkUiDefaults {
    const val OLDER_HISTORY_PREFETCH_THRESHOLD = 30
}

