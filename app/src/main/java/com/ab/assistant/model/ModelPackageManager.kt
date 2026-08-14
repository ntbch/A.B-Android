package com.ab.assistant.model

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

enum class ModelPackageState { MISSING, DOWNLOADING, VERIFYING, READY, BROKEN, UPDATING }

data class ModelPackageFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(name in ModelFiles.requiredFileNames) { "Unknown model file." }
        require(bytes > 0) { "Model file size must be positive." }
        require(SHA256.matches(sha256)) { "Model hash must be a SHA-256 hex string." }
    }

    private companion object {
        val SHA256 = Regex("^[a-fA-F0-9]{64}$")
    }
}

/** The trusted release manifest is supplied by the app/update channel, never downloaded with the files. */
data class ModelPackageManifest(
    val version: String,
    val files: List<ModelPackageFile>,
) {
    init {
        require(version.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) { "Invalid model version." }
        require(files.map(ModelPackageFile::name).toSet() == ModelFiles.requiredFileNames.toSet()) {
            "Manifest must cover exactly the model bundle."
        }
        require(files.size == ModelFiles.requiredFileNames.size) { "Manifest contains duplicate model files." }
    }

    val totalBytes: Long get() = files.sumOf(ModelPackageFile::bytes)
}

data class ModelPackageStatus(
    val state: ModelPackageState,
    val directory: File?,
    val reason: String? = null,
)

/** Implementations must resume at [resumeOffset] and write only [destination]. */
fun interface ModelPackageDownloader {
    fun download(file: ModelPackageFile, destination: File, resumeOffset: Long): Boolean
}

/**
 * Owns only a verified, atomically published text-model directory. A caller supplies the
 * release manifest and downloader so network policy remains outside this storage boundary.
 */
class ModelPackageManager(
    private val storageCandidates: List<File>,
) {
    @Volatile private var current = ModelPackageStatus(ModelPackageState.MISSING, null)

    fun status(): ModelPackageStatus = current

    fun selectStorage(manifest: ModelPackageManifest): File? = storageCandidates.firstOrNull { base ->
        (base.exists() || base.mkdirs()) && base.isDirectory && base.canWrite() &&
            base.usableSpace >= manifest.totalBytes * REQUIRED_FREE_SPACE_MULTIPLIER
    }

    fun inspect(manifest: ModelPackageManifest, smokeLoad: ((File) -> Boolean)? = null): ModelPackageStatus = synchronized(this) {
        storageCandidates.firstOrNull { base ->
            base.isDirectory && verifyDirectory(ModelFiles.directory(base), manifest, smokeLoad).state == ModelPackageState.READY
        }?.let { return@synchronized current }
        val base = selectStorage(manifest) ?: return@synchronized publish(ModelPackageState.BROKEN, null, "No writable storage with enough free space.")
        verifyDirectory(ModelFiles.directory(base), manifest, smokeLoad)
    }

    fun downloadAndInstall(
        manifest: ModelPackageManifest,
        downloader: ModelPackageDownloader,
        smokeLoad: ((File) -> Boolean)? = null,
    ): ModelPackageStatus = synchronized(this) {
        val base = selectStorage(manifest)
            ?: return@synchronized publish(ModelPackageState.BROKEN, null, "No writable storage with enough free space.")
        val target = ModelFiles.directory(base)
        val staging = File(target.parentFile, ".${target.name}.partial")
        if (staging.exists() && !staging.isDirectory) {
            staging.delete()
        }
        if (!staging.exists() && !staging.mkdirs()) {
            return@synchronized publish(ModelPackageState.BROKEN, target, "Cannot create partial model directory.")
        }
        publish(ModelPackageState.DOWNLOADING, staging)
        for (entry in manifest.files) {
            val partial = File(staging, entry.name)
            val resumeOffset = partial.takeIf(File::isFile)?.length() ?: 0L
            if (!downloader.download(entry, partial, resumeOffset)) {
                return@synchronized publish(ModelPackageState.BROKEN, staging, "Download failed for ${entry.name}.")
            }
            if (!matches(partial, entry)) {
                return@synchronized publish(ModelPackageState.BROKEN, staging, "Integrity check failed for ${entry.name}.")
            }
        }
        publish(ModelPackageState.VERIFYING, staging)
        val verified = verifyDirectory(staging, manifest, smokeLoad)
        if (verified.state != ModelPackageState.READY) return@synchronized verified
        File(staging, VERSION_FILE).writeText(manifest.version)
        publish(ModelPackageState.UPDATING, staging)
        val backup = File(target.parentFile, ".${target.name}.backup")
        if (backup.exists()) deleteRecursively(backup)
        if (target.exists() && !target.renameTo(backup)) {
            return@synchronized publish(ModelPackageState.BROKEN, target, "Cannot prepare model update.")
        }
        if (!staging.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            return@synchronized publish(ModelPackageState.BROKEN, target, "Cannot publish verified model package.")
        }
        if (backup.exists()) deleteRecursively(backup)
        publish(ModelPackageState.READY, target)
    }

    /** Removes only manager-owned interrupted download or rollback directories. */
    fun cleanupPartials(manifest: ModelPackageManifest): ModelPackageStatus = synchronized(this) {
        val base = selectStorage(manifest) ?: return@synchronized current
        val target = ModelFiles.directory(base)
        deleteRecursively(File(target.parentFile, ".${target.name}.partial"))
        deleteRecursively(File(target.parentFile, ".${target.name}.backup"))
        inspect(manifest)
    }

    private fun verifyDirectory(
        directory: File,
        manifest: ModelPackageManifest,
        smokeLoad: ((File) -> Boolean)?,
    ): ModelPackageStatus {
        if (!directory.isDirectory) return publish(ModelPackageState.MISSING, directory, "Model package is not installed.")
        if (ModelFiles.requiredFileNames.any { fileName -> !File(directory, fileName).isFile }) {
            return publish(ModelPackageState.BROKEN, directory, "Model package is incomplete.")
        }
        val incorrect = manifest.files.firstOrNull { !matches(File(directory, it.name), it) }
        if (incorrect != null) return publish(ModelPackageState.BROKEN, directory, "Integrity check failed for ${incorrect.name}.")
        if (smokeLoad != null && !smokeLoad(directory)) {
            return publish(ModelPackageState.BROKEN, directory, "MNN smoke load failed.")
        }
        return publish(ModelPackageState.READY, directory)
    }

    private fun matches(file: File, entry: ModelPackageFile): Boolean =
        file.isFile && file.length() == entry.bytes && sha256(file).equals(entry.sha256, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun publish(state: ModelPackageState, directory: File?, reason: String? = null): ModelPackageStatus =
        ModelPackageStatus(state, directory, reason).also { current = it }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }

    private companion object {
        const val VERSION_FILE = ".ab-model-version"
        const val HASH_BUFFER_SIZE = 8 * 1024
        const val REQUIRED_FREE_SPACE_MULTIPLIER = 2L
    }
}
