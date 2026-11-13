package io.legado.web.service

import io.legado.web.auth.BadRequestException
import io.legado.web.auth.NotFoundException
import io.legado.web.auth.TooManyRequestsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Public content-addressed icons. A fixed global capacity preserves old install URLs without eviction. */
class PwaIconService(dataDir: File) {
    data class IconDto(val id: String)

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_ICONS = 256
        private val HASH = Regex("[0-9a-f]{64}")
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val storageLock = Any()
    }

    private val root = File(dataDir, "pwa-icons")

    suspend fun add(bytes: ByteArray): IconDto = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_BYTES) throw BadRequestException("Icon exceeds 2 MiB")
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            throw BadRequestException("Expected a 512x512 PNG")
        }
        // Ignore all input metadata, and inspect dimensions before allocating any decoded pixel buffer.
        val canonical = try {
            MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                val reader = ImageIO.getImageReadersByFormatName("PNG").next()
                try {
                    reader.setInput(input, true, true)
                    if (reader.getWidth(0) != 512 || reader.getHeight(0) != 512) {
                        throw BadRequestException("Expected a 512x512 PNG")
                    }
                    val decoded = reader.read(0)
                    val clean = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
                    val graphics = clean.createGraphics()
                    try {
                        graphics.drawImage(decoded, 0, 0, null)
                    } finally {
                        graphics.dispose()
                        decoded.flush()
                    }
                    try {
                        ByteArrayOutputStream().use { output ->
                            check(ImageIO.write(clean, "PNG", output))
                            output.toByteArray()
                        }
                    } finally {
                        clean.flush()
                    }
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: IOException) {
            throw BadRequestException("Invalid PNG")
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("Invalid PNG")
        }
        check(canonical.size <= MAX_BYTES)
        val id = MessageDigest.getInstance("SHA-256").digest(canonical)
            .joinToString("") { "%02x".format(it) }
        synchronized(storageLock) {
            Files.createDirectories(root.toPath())
            FileChannel.open(File(root, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val target = File(root, "$id.png")
                    if (!target.isFile) {
                        val count = root.listFiles { file -> file.name.endsWith(".png") }?.size ?: 0
                        if (count >= MAX_ICONS) throw TooManyRequestsException("Icon storage is full")
                        val tmp = Files.createTempFile(root.toPath(), ".upload-", ".tmp")
                        try {
                            Files.write(tmp, canonical)
                            Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } finally {
                            Files.deleteIfExists(tmp)
                        }
                    }
                }
            }
        }
        IconDto(id)
    }

    suspend fun get(id: String): File = withContext(Dispatchers.IO) {
        if (!HASH.matches(id)) throw NotFoundException("Icon not found")
        File(root, "$id.png").also { if (!it.isFile) throw NotFoundException("Icon not found") }
    }
}
