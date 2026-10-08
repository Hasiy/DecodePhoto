/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
package top.hasiy.rawviewer.live.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoXmpParserTest {

    @Test
    fun parse_standardContainer_returnsVideoDescriptor() {
        val result = MotionPhotoXmpParser.parse(STANDARD_XMP)

        requireNotNull(result)
        assertEquals(12_345L, result.videoLength)
        assertEquals(1_500_000L, result.presentationTimestampUs)
        assertEquals(1, result.version)
        assertFalse(result.isLegacy)
    }

    @Test
    fun parse_customNamespacePrefixes_returnsVideoDescriptor() {
        val customPrefixes = STANDARD_XMP
            .replace("Camera:", "Cam:")
            .replace("xmlns:Camera=", "xmlns:Cam=")
            .replace("Container:", "Directory:")
            .replace("xmlns:Container=", "xmlns:Directory=")
            .replace("Item:", "Entry:")
            .replace("xmlns:Item=", "xmlns:Entry=")

        val result = MotionPhotoXmpParser.parse(customPrefixes)

        requireNotNull(result)
        assertEquals(12_345L, result.videoLength)
        assertFalse(result.isLegacy)
    }

    @Test
    fun parse_legacyMicroVideo_returnsLegacyDescriptor() {
        val result = MotionPhotoXmpParser.parse(LEGACY_XMP)

        requireNotNull(result)
        assertEquals(9_876L, result.videoLength)
        assertEquals(700_000L, result.presentationTimestampUs)
        assertTrue(result.isLegacy)
    }

    @Test
    fun parse_motionFlagWithoutVideoItem_returnsNull() {
        val withoutVideo = STANDARD_XMP.replace("Item:Semantic=\"MotionPhoto\"", "Item:Semantic=\"Other\"")

        assertNull(MotionPhotoXmpParser.parse(withoutVideo))
    }

    @Test
    fun parse_nonPositiveVideoLength_returnsNull() {
        assertNull(MotionPhotoXmpParser.parse(STANDARD_XMP.replace("12345", "0")))
        assertNull(MotionPhotoXmpParser.parse(LEGACY_XMP.replace("9876", "-1")))
    }

    @Test
    fun parse_documentTypeDeclaration_returnsNull() {
        val unsafeXmp = "<!DOCTYPE x [<!ENTITY probe SYSTEM \"file:///tmp/probe\">]>$STANDARD_XMP"

        assertNull(MotionPhotoXmpParser.parse(unsafeXmp))
    }

    private companion object {
        val STANDARD_XMP = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    Camera:MotionPhoto="1"
                    Camera:MotionPhotoVersion="1"
                    Camera:MotionPhotoPresentationTimestampUs="1500000">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" /></rdf:li>
                      <rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="12345" /></rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val LEGACY_XMP = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    GCamera:MicroVideo="1"
                    GCamera:MicroVideoVersion="1"
                    GCamera:MicroVideoOffset="9876"
                    GCamera:MicroVideoPresentationTimestampUs="700000" />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }
}
