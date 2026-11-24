package com.tvremote.atv

import com.tvremote.proto.ProtoFraming
import com.tvremote.proto.ProtoMessage
import com.tvremote.proto.ProtoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingException(message: String) : IOException(message)

/**
 * Pairing with an Android TV / Google TV ("Polo" protocol, TLS on port 6467).
 *
 * 1. [begin] connects and asks the TV to show a 6-character code.
 * 2. [finish] sends a secret derived from that code and both certificates.
 */
class AtvPairing(
    private val host: String,
    private val identity: AtvIdentity,
    private val clientName: String,
) : Closeable {

    private var socket: SSLSocket? = null

    suspend fun begin() = withContext(Dispatchers.IO) {
        val s = identity.createSslContext().socketFactory.createSocket() as SSLSocket
        socket = s
        s.connect(InetSocketAddress(host, PAIRING_PORT), 5_000)
        s.soTimeout = 10_000
        s.startHandshake()
        exchange(envelope(F_PAIRING_REQUEST) { string(1, "atvremote"); string(2, clientName) }, F_PAIRING_REQUEST_ACK)
        exchange(
            envelope(F_OPTIONS) {
                message(1) { int(1, ENCODING_HEX); int(2, CODE_LENGTH) } // input_encodings
                int(3, ROLE_INPUT) // preferred_role
            },
            F_OPTIONS,
        )
        exchange(
            envelope(F_CONFIGURATION) {
                message(1) { int(1, ENCODING_HEX); int(2, CODE_LENGTH) }
                int(2, ROLE_INPUT)
            },
            F_CONFIGURATION_ACK,
        )
        // The TV is now showing the code.
    }

    /** Throws [PairingException] if the code is wrong; the session stays open so the user can retry. */
    suspend fun finish(code: String) = withContext(Dispatchers.IO) {
        val s = socket ?: throw PairingException("Pairing was not started")
        val clean = code.trim().uppercase()
        if (clean.length != CODE_LENGTH || clean.any { it !in HEX }) {
            throw PairingException("The code has 6 characters (0-9 and A-F)")
        }
        val serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
        val secret = computeSecret(identity.publicKey, serverKey, clean)
            ?: throw PairingException("That code doesn't match. Check the TV screen and try again.")
        exchange(envelope(F_SECRET) { bytes(1, secret) }, F_SECRET_ACK)
        close()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun exchange(payload: ByteArray, expectedField: Int) {
        val s = socket ?: throw PairingException("Not connected")
        ProtoFraming.write(s.outputStream, payload)
        val reply = ProtoMessage.parse(ProtoFraming.read(s.inputStream))
        when (val status = reply.int(F_STATUS)) {
            STATUS_OK -> Unit
            STATUS_BAD_SECRET -> throw PairingException("The TV rejected the code. Try pairing again.")
            STATUS_BAD_CONFIGURATION -> throw PairingException("The TV doesn't support this pairing method.")
            else -> throw PairingException("The TV returned a pairing error ($status).")
        }
        if (!reply.has(expectedField)) throw PairingException("Unexpected reply from the TV.")
    }

    private fun envelope(field: Int, block: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter()
            .int(F_PROTOCOL_VERSION, 2)
            .int(F_STATUS, STATUS_OK)
            .message(field, block)
            .toByteArray()

    companion object {
        const val PAIRING_PORT = 6467
        private const val CODE_LENGTH = 6
        private const val HEX = "0123456789ABCDEF"
        private const val ENCODING_HEX = 3
        private const val ROLE_INPUT = 1

        private const val F_PROTOCOL_VERSION = 1
        private const val F_STATUS = 2
        private const val F_PAIRING_REQUEST = 10
        private const val F_PAIRING_REQUEST_ACK = 11
        private const val F_OPTIONS = 20
        private const val F_CONFIGURATION = 30
        private const val F_CONFIGURATION_ACK = 31
        private const val F_SECRET = 40
        private const val F_SECRET_ACK = 41

        private const val STATUS_OK = 200
        private const val STATUS_BAD_CONFIGURATION = 401
        private const val STATUS_BAD_SECRET = 402

        /**
         * SHA-256 over client modulus, client exponent, server modulus, server exponent and the last
         * 4 hex digits of the code. The first 2 hex digits of the code are a checksum: they must equal
         * the first byte of the hash. Returns null when the checksum doesn't match (a typo).
         */
        fun computeSecret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(client.modulus.unsignedBytes())
                update(client.publicExponent.unsignedBytes())
                update(server.modulus.unsignedBytes())
                update(server.publicExponent.unsignedBytes())
                update(hexToBytes(code.substring(2)))
            }.digest()
            return if (digest[0] == code.substring(0, 2).toInt(16).toByte()) digest else null
        }

        private fun BigInteger.unsignedBytes(): ByteArray {
            val bytes = toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
