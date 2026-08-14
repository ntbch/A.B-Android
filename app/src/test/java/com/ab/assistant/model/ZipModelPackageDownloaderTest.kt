package com.ab.assistant.model

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ZipModelPackageDownloaderTest {
    @Test
    fun importsOnlyExpectedArchiveEntryAndRejectsWrongLength() {
        val root = Files.createTempDirectory("ab-zip-model").toFile()
        val zip = File(root, "model.zip")
        val content = "verified".toByteArray()
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("bundle/config.json"))
            output.write(content)
            output.closeEntry()
        }
        val entry = ModelPackageFile("config.json", content.size.toLong(), sha256(content))
        val destination = File(root, "copy/config.json")

        val imported = ZipModelPackageDownloader { zip.inputStream() }.download(entry, destination, 0)

        assertEquals(true, imported)
        assertEquals("verified", destination.readText())
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte) }
}
