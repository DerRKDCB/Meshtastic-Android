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
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.dp
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
import org.meshtastic.core.resources.attach_file
import org.meshtastic.core.resources.attach_image
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.message_input_label
import org.meshtastic.core.resources.position
import org.meshtastic.core.resources.send
import org.meshtastic.core.resources.type_a_message
import org.meshtastic.core.resources.unknown_channel
import org.meshtastic.core.resources.attachment
import org.meshtastic.core.ui.component.SharedContactDialog
import org.meshtastic.core.ui.component.smartScrollToIndex
import org.meshtastic.core.ui.theme.AppTheme
import org.meshtastic.core.ui.util.rememberOpenMap
import org.meshtastic.feature.messaging.component.ActionModeTopBar
import org.meshtastic.feature.messaging.component.DeleteMessageDialog
import org.meshtastic.feature.messaging.component.MESSAGE_CHARACTER_LIMIT_BYTES
import org.meshtastic.feature.messaging.component.MessageMenuAction
import org.meshtastic.feature.messaging.component.MessageTopBar
import org.meshtastic.feature.messaging.component.QuickChatRow
import org.meshtastic.feature.messaging.component.ReplySnippet
import org.meshtastic.feature.messaging.component.ScrollToBottomFab
import org.meshtastic.feature.messaging.image.DEFAULT_IMAGE_DUTY_CYCLE_PERCENT
import org.meshtastic.feature.messaging.image.MIN_IMAGE_DUTY_CYCLE_PERCENT
import org.meshtastic.feature.messaging.image.ImageAdjustmentDialog
import org.meshtastic.feature.messaging.image.maxDutyCyclePercentForRegion
import java.nio.charset.StandardCharsets

private const val ROUNDED_CORNER_PERCENT = 100
private const val MAX_LINES = 3

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
    val timelineMessages by viewModel.getMessagesFlow(contactKey).collectAsStateWithLifecycle(initialValue = emptyList())
    val imageChunkMessages by viewModel.imageChunkMessages.collectAsStateWithLifecycle()
    val contactSettings by viewModel.contactSettings.collectAsStateWithLifecycle(initialValue = emptyMap())
    val homoglyphEncodingEnabled by viewModel.homoglyphEncodingEnabled.collectAsStateWithLifecycle(initialValue = false)
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle(initialValue = null)

    // UI State managed within this Composable
    var replyingToPacketId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var sharedContact by rememberSaveable { mutableStateOf<Node?>(null) }
    val selectedMessageIds = rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val messageInputState = rememberTextFieldState(message)
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
                    currentLocation = currentLocation,
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
                    timelineMessages = timelineMessages,
                    imageChunkMessages = imageChunkMessages,
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
                    onLoadOlderImageChunks = viewModel::requestOlderImageChunks,
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
    currentLocation: Location? = null,
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
    var showPositionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isEnabled, isSendingChunks) {
        if (!isEnabled && !isSendingChunks) {
            showAttachmentMenu = false
        }
    }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }

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
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedFileUri = uri
    }
    val openMap = rememberOpenMap()

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
                text = { Text(stringResource(Res.string.position)) },
                onClick = {
                    showAttachmentMenu = false
                    showPositionDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.attach_file)) },
                onClick = {
                    showAttachmentMenu = false
                    filePickerLauncher.launch("*/*")
                }
            )
        }
    }

    selectedFileUri?.let { uri ->
        FileAdjustmentDialog(
            fileUri = uri,
            loraConfig = loraConfig,
            onSend = { chunks, delayMillis ->
                selectedFileUri = null
                if (viewModel != null) {
                    viewModel.sendChunkedPayloadChunks(
                        chunks = chunks,
                        contactKey = contactKey,
                        delayMillis = delayMillis,
                    )
                }
            },
            onCancel = { selectedFileUri = null },
        )
    }

    if (showPositionDialog) {
        PositionShareDialog(
            currentLocation = currentLocation,
            onSend = { payloadBytes ->
                if (viewModel != null) {
                    viewModel.sendPrivateAppPayload(payload = payloadBytes, contactKey = contactKey)
                }
            },
            onCancel = { showPositionDialog = false },
        )
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
