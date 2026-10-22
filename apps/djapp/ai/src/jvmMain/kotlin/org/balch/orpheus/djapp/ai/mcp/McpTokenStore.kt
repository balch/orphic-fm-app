package org.balch.orpheus.djapp.ai.mcp

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What `mcp.json` holds: where the server listens and the bearer token it wants. */
@Serializable
data class McpEndpoint(val url: String, val token: String, val port: Int)

/**
 * The bearer token, kept in `<dir>/mcp.json` so a `claude mcp add` stays valid across launches.
 * [publish] rewrites the file with this launch's port.
 */
internal class McpTokenStore(private val dir: File) {

    private val file = File(dir, FILE_NAME)

    val token: String by lazy { readToken() ?: newToken() }

    fun publish(port: Int): McpEndpoint {
        val endpoint = McpEndpoint(url = "http://127.0.0.1:$port/mcp", token = token, port = port)
        Files.createDirectories(dir.toPath())
        val tmp = dir.toPath().resolve("$FILE_NAME.tmp")
        Files.deleteIfExists(tmp)
        createOwnerOnly(tmp)
        Files.write(tmp, json.encodeToString(McpEndpoint.serializer(), endpoint).toByteArray())
        try {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return endpoint
    }

    private fun readToken(): String? = runCatching {
        json.decodeFromString(McpEndpoint.serializer(), file.readText()).token
    }.getOrNull()?.takeIf { it.matches(TOKEN) }

    private fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** The token is a credential for the live app, so the file is owner-only from the moment it exists. */
    private fun createOwnerOnly(path: Path) {
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(path)
            path.toFile().run {
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
            }
        }
    }

    companion object {
        const val FILE_NAME = "mcp.json"
        private val TOKEN = Regex("[0-9a-f]{64}")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** `~/.config/orpheus-dj`, beside `settings.json` and `ai-vibes`. */
        fun defaultDir(): File = File(System.getProperty("user.home"), ".config/orpheus-dj")
    }
}
