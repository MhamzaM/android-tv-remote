package com.tvremote.atv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a minimal self-signed X.509 certificate by hand (DER encoding), so we don't need
 * BouncyCastle. Android TV only cares that the same certificate is presented every time.
 */
internal object SelfSignedCert {
    private val OID_SHA256_WITH_RSA = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
    )
    private val OID_COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val DER_NULL = byteArrayOf(0x05, 0x00)
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun create(keyPair: KeyPair, commonName: String, validYears: Int = 20): X509Certificate {
        val algorithm = seq(OID_SHA256_WITH_RSA, DER_NULL)
        val name = seq(set(seq(OID_COMMON_NAME, tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + validYears * 365L * 24 * 3600 * 1000)
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)

        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))), // version: v3
            tlv(0x02, serial.toByteArray()),
            algorithm,
            name, // issuer
            seq(time(notBefore), time(notAfter)),
            name, // subject
            keyPair.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, algorithm, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun time(date: Date): ByteArray {
        val year = Calendar.getInstance(UTC).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(date)
        return tlv(tag, text.toByteArray(Charsets.US_ASCII))
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts))

    private fun concat(parts: Array<out ByteArray>): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
}
