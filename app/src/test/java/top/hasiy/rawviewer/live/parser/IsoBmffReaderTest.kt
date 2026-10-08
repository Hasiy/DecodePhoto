/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class IsoBmffReaderTest {

    @Test
    fun hasFileTypeBox_validBox_returnsTrue() {
        withTemporaryFile(box("ftyp", "isom".toByteArray(StandardCharsets.US_ASCII))) { file ->
            assertTrue(IsoBmffReader.hasFileTypeBox(file, 0L, file.length()))
        }
    }

    @Test
    fun hasFileTypeBox_outOfRange_returnsFalse() {
        withTemporaryFile(ByteArray(16)) { file ->
            assertFalse(IsoBmffReader.hasFileTypeBox(file, 8L, 32L))
        }
    }

    @Test
    fun readAppleContentIdentifier_mdtaMetadata_returnsIdentifier() {
        val identifier = "01234567-89AB-CDEF-0123-456789ABCDEF"
        val key = "com.apple.quicktime.content.identifier".toByteArray(StandardCharsets.UTF_8)
        val keyEntry = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(key.size + 8)
                data.writeBytes("mdta")
                data.write(key)
            }
        }.toByteArray()
        val keys = fullBox("keys", intBytes(1) + keyEntry)
        val data = box("data", ByteArray(8) + identifier.toByteArray(StandardCharsets.UTF_8))
        val item = indexedBox(1, data)
        val itemList = box("ilst", item)
        val meta = fullBox("meta", keys + itemList)
        val movie = box("moov", meta)

        withTemporaryFile(movie) { file ->
            assertEquals(identifier, IsoBmffReader.readAppleContentIdentifier(file))
        }
    }

    private fun withTemporaryFile(bytes: ByteArray, assertion: (File) -> Unit) {
        val file = File.createTempFile("isobmff_reader_", ".bin")
        try {
            file.writeBytes(bytes)
            assertion(file)
        } finally {
            file.delete()
        }
    }

    private fun fullBox(type: String, payload: ByteArray): ByteArray = box(type, ByteArray(4) + payload)

    private fun box(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(payload.size + 8)
            data.write(type.toByteArray(StandardCharsets.ISO_8859_1))
            data.write(payload)
        }
    }.toByteArray()

    private fun indexedBox(index: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(payload.size + 8)
            data.writeInt(index)
            data.write(payload)
        }
    }.toByteArray()

    private fun intBytes(value: Int): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data -> data.writeInt(value) }
    }.toByteArray()
}
