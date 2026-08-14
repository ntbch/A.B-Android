package com.ab.assistant.model

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Imports only manifest-listed files from a user-selected archive; zip paths are never trusted. */
class ZipModelPackageDownloader(
    private val openArchive: () -> InputStream?,
) : ModelPackageDownloader {
    override fun download(file: ModelPackageFile, destination: File, resumeOffset: Long): Boolean {
        if (resumeOffset !in 0..file.bytes) return false
        val stream = openArchive() ?: return false
        stream.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val matches = !entry.isDirectory && (entry.name == file.name || entry.name.endsWith("/${file.name}"))
                    if (matches) return copyEntry(zip, destination, resumeOffset, file.bytes)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return false
    }

    private fun copyEntry(input: InputStream, destination: File, resumeOffset: Long, expectedBytes: Long): Boolean {
        destination.parentFile?.mkdirs()
        var skip = resumeOffset
        val buffer = ByteArray(BUFFER_SIZE)
        while (skip > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), skip).toInt())
            if (read <= 0) return false
            skip -= read
        }
        var remaining = expectedBytes - resumeOffset
        FileOutputStream(destination, resumeOffset > 0).use { output ->
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) return false
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        return input.read() == -1
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
