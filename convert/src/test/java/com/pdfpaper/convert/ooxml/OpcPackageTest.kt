package com.pdfpaper.convert.ooxml

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpcPackageTest {

    @Test
    fun `round trips parts`() {
        val pkg = OpcPackage()
        pkg.addXml(OpcPackage.CONTENT_TYPES, "<Types/>")
        pkg.add("ppt/media/image1.png", byteArrayOf(1, 2, 3))
        val read = OpcPackage.read(pkg.bytes())
        assertEquals(setOf("[Content_Types].xml", "ppt/media/image1.png"), read.keys)
        assertContentEquals(byteArrayOf(1, 2, 3), read["ppt/media/image1.png"])
    }

    @Test
    fun `writing is deterministic`() {
        fun build(): ByteArray {
            val pkg = OpcPackage()
            pkg.addXml("b.xml", "<b/>")
            pkg.addXml(OpcPackage.CONTENT_TYPES, "<Types/>")
            pkg.addXml("a.xml", "<a/>")
            return pkg.bytes()
        }
        assertContentEquals(build(), build())
    }

    @Test
    fun `content types part is written first`() {
        val pkg = OpcPackage()
        pkg.addXml("z.xml", "<z/>")
        pkg.addXml(OpcPackage.CONTENT_TYPES, "<Types/>")
        val first = readEntryNames(pkg.bytes()).first()
        assertEquals(OpcPackage.CONTENT_TYPES, first)
    }

    @Test
    fun `partBytes returns null for a missing part`() {
        assertNull(OpcPackage().partBytes("nope"))
    }

    @Test
    fun `part names are relative`() {
        val pkg = OpcPackage()
        val failed = try {
            pkg.add("/absolute.xml", ByteArray(0))
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(failed)
    }

    /** A ZIP built by hand, to prove [OpcPackage.read] is not reading its own output. */
    @Test
    fun `reads a zip produced elsewhere`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        val read = OpcPackage.read(out.toByteArray())
        assertEquals("hi", read["hello.txt"]?.toString(Charsets.UTF_8))
    }

    private fun readEntryNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return names
    }
}
