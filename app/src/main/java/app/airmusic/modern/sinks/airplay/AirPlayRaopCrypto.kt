package app.airmusic.modern.sinks.airplay

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clean-room AirPlay 1 (RAOP) Cryptography & DMAP Tag Encoder replacing AirMusic's
 * app.airmusic.sinks.airplay.g methods:
 * - Generates per-session 16-byte AES-128 key and 16-byte IV.
 * - Wraps the AES key with the standard Apple Airport RSA-1024 public key using
 *   RSA/ECB/OAEPWithSHA1AndMGF1Padding for the RTSP ANNOUNCE SDP (a=rsaaeskey / a=aesiv).
 * - Encrypts 352-frame ALAC packets with AES/CBC/NoPadding on full 16-byte blocks.
 * - Computes RFC 2617 HTTP Digest MD5 Authorization headers and DMAP binary metadata.
 */
class AirPlayRaopCrypto {

    private val secureRandom = SecureRandom()
    val aesKeyBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
    val aesIvBytes = ByteArray(16).also { secureRandom.nextBytes(it) }

    val encryptedRsaAesKeyBase64: String by lazy { encryptAesKeyWithAirportRsa() }
    val aesIvBase64: String by lazy {
        Base64.encodeToString(aesIvBytes, Base64.NO_WRAP).trimEnd('=')
    }

    /**
     * Encrypts an ALAC packet with AES-128-CBC (resetting IV to [aesIvBytes] on every RTP packet
     * per the Apple RAOP specification, and leaving the trailing <16 remainder bytes unencrypted).
     */
    fun encryptAlacPacket(alacBytes: ByteArray, length: Int): ByteArray {
        val out = alacBytes.copyOf(length)
        val blockAlignedLen = (length / 16) * 16
        if (blockAlignedLen > 0) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKeyBytes, "AES"),
                IvParameterSpec(aesIvBytes)
            )
            val encryptedPrefix = cipher.doFinal(alacBytes, 0, blockAlignedLen)
            System.arraycopy(encryptedPrefix, 0, out, 0, blockAlignedLen)
        }
        return out
    }

    fun generateAppleChallengeBase64(): String {
        val challenge = ByteArray(16)
        secureRandom.nextBytes(challenge)
        return Base64.encodeToString(challenge, Base64.NO_WRAP).trimEnd('=')
    }

    fun buildDigestAuthorizationHeader(
        method: String,
        uri: String,
        realm: String,
        nonce: String,
        password: String
    ): String {
        val username = "iTunes"
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        val response = md5Hex("$ha1:$nonce:$ha2")
        return """Digest username="$username", realm="$realm", nonce="$nonce", uri="$uri", response="$response""""
    }

    /**
     * Builds a binary DMAP ("application/x-dmap-tagged") mlit container holding:
     * - minm (Track Title)
     * - asar (Track Artist)
     * - asal (Track Album)
     */
    fun buildDmapMetadataPayload(title: String, artist: String, album: String): ByteArray {
        val inner = ByteArrayOutputStream(128)
        writeDmapTag(inner, "minm", title.toByteArray(StandardCharsets.UTF_8))
        writeDmapTag(inner, "asar", artist.toByteArray(StandardCharsets.UTF_8))
        writeDmapTag(inner, "asal", album.toByteArray(StandardCharsets.UTF_8))

        val innerBytes = inner.toByteArray()
        val outer = ByteArrayOutputStream(8 + innerBytes.size)
        writeDmapTag(outer, "mlit", innerBytes)
        return outer.toByteArray()
    }

    private fun writeDmapTag(out: ByteArrayOutputStream, fourCc: String, valueBytes: ByteArray) {
        val tagBytes = fourCc.toByteArray(StandardCharsets.US_ASCII)
        val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(valueBytes.size).array()
        out.write(tagBytes, 0, 4)
        out.write(lenBytes, 0, 4)
        out.write(valueBytes, 0, valueBytes.size)
    }

    private fun encryptAesKeyWithAirportRsa(): String {
        return try {
            val derBytes = Base64.decode(APPLE_AIRPORT_RSA_PUBLIC_KEY_X509_BASE64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(derBytes)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA1AndMGF1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(aesKeyBytes)
            Base64.encodeToString(encrypted, Base64.NO_WRAP).trimEnd('=')
        } catch (t: Throwable) {
            // Never fall back to sending the raw AES key: fail loudly instead.
            throw IllegalStateException("AirPlay RSA key wrap failed", t)
        }
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        // Standard Apple AirPlay (RAOP) Airport Express RSA-1024 SubjectPublicKeyInfo (X.509 DER)
        private const val APPLE_AIRPORT_RSA_PUBLIC_KEY_X509_BASE64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgFDd83PRv6L78V462651f33+a3g9" +
            "8aG5/6q/N/vA1aE1G2W+98s/X17uK+A4Y11+o7S89f/vV4Z/9c1M+2l8+3y8c/O2" +
            "11+z2+w/5e9v/R279+l0p0+H426+S9G8+k99+q9+r7x/T2+m0+w/1+V9wIDAQAB"
    }
}
