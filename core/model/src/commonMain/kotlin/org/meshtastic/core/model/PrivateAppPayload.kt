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
package org.meshtastic.core.model

import org.meshtastic.proto.Position

enum class PrivateAppPayloadType(val code: Byte) {
    Image(1),
    Position(2),
    File(3),

    ;

    companion object {
        fun fromCode(code: Byte): PrivateAppPayloadType? = entries.firstOrNull { it.code == code }
    }
}

data class DecodedPrivateAppPayload(
    val type: PrivateAppPayloadType,
    val payload: ByteArray,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val position: Position? = null,
)

private const val FILE_NAME_LENGTH_BYTES = 2
private const val FILE_SIZE_LENGTH_BYTES = 8

fun encodePrivateAppPayload(type: PrivateAppPayloadType, payload: ByteArray, fileName: String? = null): ByteArray {
    val header =
        when (type) {
            PrivateAppPayloadType.Image, PrivateAppPayloadType.Position -> byteArrayOf(type.code)
            PrivateAppPayloadType.File -> {
                val fileNameBytes = fileName.orEmpty().encodeToByteArray()
                require(fileNameBytes.size <= UShort.MAX_VALUE.toInt()) { "File name too long" }
                byteArrayOf(type.code) +
                    byteArrayOf(
                        ((fileNameBytes.size ushr 8) and 0xFF).toByte(),
                        (fileNameBytes.size and 0xFF).toByte(),
                    ) +
                    fileNameBytes +
                    longToBytes(payload.size.toLong())
            }
        }
    return header + payload
}

fun decodePrivateAppPayload(payloadBytes: ByteArray): DecodedPrivateAppPayload? {
    if (payloadBytes.isEmpty()) {
        return null
    }

    val type = PrivateAppPayloadType.fromCode(payloadBytes[0]) ?: return null
    return when (type) {
        PrivateAppPayloadType.Image -> DecodedPrivateAppPayload(type = type, payload = payloadBytes.drop(1).toByteArray())
        PrivateAppPayloadType.Position -> {
            val positionBytes = payloadBytes.drop(1).toByteArray()
            val position = runCatching { Position.ADAPTER.decode(positionBytes) }.getOrNull()
            DecodedPrivateAppPayload(type = type, payload = positionBytes, position = position)
        }
        PrivateAppPayloadType.File -> {
            if (payloadBytes.size < 1 + FILE_NAME_LENGTH_BYTES) {
                return null
            }
            val fileNameLength = ((payloadBytes[1].toInt() and 0xFF) shl 8) or (payloadBytes[2].toInt() and 0xFF)
            val fileNameStart = 1 + FILE_NAME_LENGTH_BYTES
            val fileNameEnd = fileNameStart + fileNameLength
            if (fileNameEnd > payloadBytes.size) {
                return null
            }
            val fileName = payloadBytes.copyOfRange(fileNameStart, fileNameEnd).decodeToString()
            val fileSizeStart = fileNameEnd
            if (payloadBytes.size < fileSizeStart + FILE_SIZE_LENGTH_BYTES) {
                return DecodedPrivateAppPayload(type = type, payload = ByteArray(0), fileName = fileName)
            }
            val fileSizeEnd = fileSizeStart + FILE_SIZE_LENGTH_BYTES
            val fileSize = bytesToLong(payloadBytes, fileSizeStart)
            val filePayload = payloadBytes.copyOfRange(fileSizeEnd, payloadBytes.size)
            DecodedPrivateAppPayload(type = type, payload = filePayload, fileName = fileName, fileSize = fileSize)
        }
    }
}

private fun longToBytes(value: Long): ByteArray =
    byteArrayOf(
        ((value ushr 56) and 0xFF).toByte(),
        ((value ushr 48) and 0xFF).toByte(),
        ((value ushr 40) and 0xFF).toByte(),
        ((value ushr 32) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

private fun bytesToLong(bytes: ByteArray, startIndex: Int): Long {
    var result = 0L
    for (index in startIndex until startIndex + FILE_SIZE_LENGTH_BYTES) {
        result = (result shl 8) or (bytes[index].toLong() and 0xFF)
    }
    return result
}
