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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageChunkParserTest {

    @Test
    fun parseImageChunk_parsesCompactFormat() {
        val parsed = parseImageChunk("IMG:image123|2/5|abcDEF012+/=")

        assertNotNull(parsed)
        assertEquals("image123", parsed.imageId)
        assertEquals(2, parsed.partIndex)
        assertEquals(5, parsed.totalParts)
        assertEquals("abcDEF012+/=", parsed.payload)
    }

    @Test
    fun parseImageChunk_rejectsInvalidFormat() {
        assertNull(parseImageChunk("IMG:image123|PART:2/5|DATA:abcDEF012+/="))
        assertNull(parseImageChunk("IMG:image123|PART:2/5|abcDEF012+/="))
        assertNull(parseImageChunk("IMG:image123|0/5|abcDEF012+/="))
        assertNull(parseImageChunk("IMG:image123|6/5|abcDEF012+/="))
        assertNull(parseImageChunk("IMG:image123|2/5|"))
    }
}
