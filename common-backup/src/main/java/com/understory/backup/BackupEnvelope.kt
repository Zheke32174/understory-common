package com.understory.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * On-disk container format shared by every Understory backup file.
 *
 * Layout (single file, network byte order):
 *
 *   +--------+-----------------------------------------------------+
 *   | offset | field                                               |
 *   +--------+-----------------------------------------------------+
 *   |   0    | magic bytes "USBE" (4)                              |
 *   |   4    | format version u8 (=[CURRENT_FORMAT_VERSION])       |
 *   |   5    | codec id u8 (see [BackupCodec.id])                  |
 *   |   6    | header length u16 (BE)                              |
 *   |   8    | header bytes (UTF-8 JSON)                           |
 *   |  ...   | payload length u32 (BE)                             |
 *   |  ...   | payload bytes (codec-specific opaque ciphertext)    |
 *   +--------+-----------------------------------------------------+
 *
 * The header is JSON because each codec needs to record its own params
 * (e.g. AES-GCM nonce, Argon2id salt, PGP recipient fingerprint, LUKS2
 * key-slot index) and JSON is the path of least friction. The header
 * is fed to the codec as Additional Authenticated Data (AAD) so any
 * tamper of metadata invalidates decryption — no separate signature
 * field required.
 *
 * Versioning:
 *   - [CURRENT_FORMAT_VERSION] bumps when the *envelope* changes
 *     (e.g. add a footer, switch from JSON to CBOR).
 *   - Each app's [BackupAdapter] owns its own payload schema version
 *     inside the cleartext payload.
 */
object BackupEnvelope {

    const val CURRENT_FORMAT_VERSION: Int = 1
    val MAGIC: ByteArray = byteArrayOf(0x55, 0x53, 0x42, 0x45) // "USBE"

    /** Cleartext header. Becomes JSON on disk and AAD into the codec. */
    data class Header(
        /** Reverse-DNS app id, e.g. `com.understory.aegis`. */
        val appId: String,
        /** Adapter-specific schema version of the cleartext payload. */
        val schemaVersion: Int,
        /** Wallclock millis at write time. Informational only. */
        val createdAtMs: Long,
        /** Optional human label set by the user at export time. */
        val label: String,
        /** Codec-specific public params (e.g. salt, kdf cost). */
        val codecParams: Map<String, String>,
    )

    /**
     * Write a complete envelope to [out]. The codec encrypts [plaintext]
     * with [headerJsonBytes] supplied as AAD; if any byte of the header
     * is later flipped, decryption fails authenticity.
     */
    fun write(
        out: OutputStream,
        codec: BackupCodec,
        header: Header,
        plaintext: ByteArray,
        codecKey: BackupCodec.KeyMaterial,
    ) {
        val headerJson = HeaderJson.encode(header).toByteArray(Charsets.UTF_8)
        require(headerJson.size <= 0xFFFF) { "header too large (${headerJson.size} > 65535)" }
        val ciphertext = codec.encrypt(plaintext, headerJson, codecKey)

        val dos = DataOutputStream(out)
        dos.write(MAGIC)
        dos.writeByte(CURRENT_FORMAT_VERSION)
        dos.writeByte(codec.id)
        dos.writeShort(headerJson.size)
        dos.write(headerJson)
        dos.writeInt(ciphertext.size)
        dos.write(ciphertext)
        dos.flush()
    }

    data class Parsed(
        val codecId: Int,
        val header: Header,
        val headerRaw: ByteArray,
        val ciphertext: ByteArray,
    )

    /**
     * Parse the envelope structure without decrypting. Use [decryptPayload]
     * (or the high-level [BackupCodec.decrypt]) to recover the plaintext.
     */
    fun parse(input: InputStream): Parsed {
        val dis = DataInputStream(input)
        val magic = ByteArray(4).also { dis.readFully(it) }
        require(magic.contentEquals(MAGIC)) { "not an Understory backup envelope" }
        val version = dis.readUnsignedByte()
        require(version == CURRENT_FORMAT_VERSION) {
            "unsupported envelope version $version (expected $CURRENT_FORMAT_VERSION)"
        }
        val codecId = dis.readUnsignedByte()
        val headerLen = dis.readUnsignedShort()
        val headerBytes = ByteArray(headerLen).also { dis.readFully(it) }
        val payloadLen = dis.readInt()
        require(payloadLen in 0..MAX_PAYLOAD_SIZE) {
            "implausible payload length $payloadLen (limit $MAX_PAYLOAD_SIZE)"
        }
        val payload = ByteArray(payloadLen).also { dis.readFully(it) }
        // Refuse trailing bytes after the declared payload. The class
        // contract is "envelope is one file, end-to-end"; appending data
        // a parser ignores is a smuggling channel that future codecs
        // shouldn't have to defend against individually.
        require(dis.read() == -1) { "trailing bytes after envelope payload" }
        val header = HeaderJson.decode(String(headerBytes, Charsets.UTF_8))
        return Parsed(codecId, header, headerBytes, payload)
    }

    /** Convenience: parse + decrypt with the given [codec] + [key]. */
    fun decryptPayload(
        parsed: Parsed,
        codec: BackupCodec,
        key: BackupCodec.KeyMaterial,
    ): ByteArray {
        require(parsed.codecId == codec.id) {
            "envelope codec id ${parsed.codecId} ≠ codec ${codec.id} (${codec.name})"
        }
        return codec.decrypt(parsed.ciphertext, parsed.headerRaw, key)
    }

    /**
     * 256 MiB cap. Prevents a corrupt or hostile envelope from triggering
     * an OOM via a giant length field. Real backups are kilobytes-to-
     * megabytes; the limit is generous.
     */
    private const val MAX_PAYLOAD_SIZE = 256 * 1024 * 1024
}

/**
 * Tiny hand-rolled JSON for [BackupEnvelope.Header]. No dependency on
 * org.json (which is on Android but not on plain JVM unit tests) and
 * no kotlinx.serialization (avoids the kotlin-reflect bloat). Strict:
 * we control both writer and reader, so we don't need to handle every
 * pathological JSON producer.
 */
private object HeaderJson {

    fun encode(h: BackupEnvelope.Header): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"appId\":").append(quote(h.appId)).append(',')
        sb.append("\"schemaVersion\":").append(h.schemaVersion).append(',')
        sb.append("\"createdAtMs\":").append(h.createdAtMs).append(',')
        sb.append("\"label\":").append(quote(h.label)).append(',')
        sb.append("\"codecParams\":{")
        h.codecParams.entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append(quote(e.key)).append(':').append(quote(e.value))
        }
        sb.append("}}")
        return sb.toString()
    }

    fun decode(s: String): BackupEnvelope.Header {
        val r = JsonReader(s)
        r.expectChar('{')
        var appId = ""
        var schema = 0
        var created = 0L
        var label = ""
        val params = mutableMapOf<String, String>()
        while (true) {
            val key = r.readString()
            r.expectChar(':')
            when (key) {
                "appId" -> appId = r.readString()
                "schemaVersion" -> schema = r.readNumber().toInt()
                "createdAtMs" -> created = r.readNumber()
                "label" -> label = r.readString()
                "codecParams" -> {
                    r.expectChar('{')
                    if (!r.peekIs('}')) {
                        while (true) {
                            val k = r.readString()
                            r.expectChar(':')
                            params[k] = r.readString()
                            if (r.peekIs(',')) { r.expectChar(','); continue }
                            break
                        }
                    }
                    r.expectChar('}')
                }
                else -> throw IllegalArgumentException("unexpected header field: $key")
            }
            if (r.peekIs(',')) { r.expectChar(','); continue }
            break
        }
        r.expectChar('}')
        return BackupEnvelope.Header(appId, schema, created, label, params.toMap())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) when (c) {
            '\\', '"' -> { sb.append('\\'); sb.append(c) }
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            in '\u0000'..'\u001f' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }
}

private class JsonReader(private val s: String) {
    private var i = 0

    fun expectChar(c: Char) {
        skipWs()
        require(i < s.length && s[i] == c) {
            "expected '$c' at $i, got '${if (i < s.length) s[i] else "<eof>"}'"
        }
        i++
    }

    fun peekIs(c: Char): Boolean { skipWs(); return i < s.length && s[i] == c }

    fun readString(): String {
        skipWs()
        require(i < s.length && s[i] == '"') { "expected string at $i" }
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\') {
                i++
                require(i < s.length) { "unterminated escape" }
                when (val esc = s[i]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        require(i + 4 < s.length) { "truncated \\u escape" }
                        sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("unsupported escape \\$esc")
                }
                i++
            } else {
                sb.append(s[i]); i++
            }
        }
        require(i < s.length) { "unterminated string" }
        i++
        return sb.toString()
    }

    fun readNumber(): Long {
        skipWs()
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && s[i].isDigit()) i++
        require(i > start) { "expected number at $start" }
        return s.substring(start, i).toLong()
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}

/** Read everything from [input] into a [ByteArray]. */
internal fun InputStream.readAllBytesCompat(): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = this.read(buf); if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
