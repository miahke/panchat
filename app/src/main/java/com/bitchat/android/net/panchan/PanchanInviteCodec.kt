package com.bitchat.android.net.panchan

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class PanchanTransport(val wire: Int) { QWDTT(1), CUPS(2) }

data class PanchanInvite(
    val issuedAt: Long,
    val expiresAt: Long,
    val invitation: ByteArray,
    val transport: PanchanTransport,
    val name: String,
    val configuration: String,
) {
    override fun toString() = "PanchanInvite([redacted])"
}

/** Configuration signature only; server admission must separately consume each invitation once. */
object PanchanInviteCodec {
    private const val PREFIX = "panchan://v1/"
    private const val MAGIC = 0x50434831
    private val DOMAIN = "PANCHAN-INVITE-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun fingerprint(key: PublicKey): String {
        requireP256(key)
        return MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun encode(invite: PanchanInvite, privateKey: PrivateKey, issuer: PublicKey): String {
        require(invite.transport==PanchanTransport.QWDTT) { "Removed transport" }
        requireP256(issuer)
        validate(invite)
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC); out.writeLong(invite.issuedAt); out.writeLong(invite.expiresAt)
                out.write(invite.invitation); out.writeByte(invite.transport.wire)
                val name = invite.name.toByteArray(Charsets.UTF_8)
                val configuration = invite.configuration.toByteArray(Charsets.UTF_8)
                out.writeShort(name.size); out.write(name)
                out.writeInt(configuration.size); out.write(configuration)
            }
        }.toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey); update(DOMAIN); update(payload)
        }.sign()
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(issuer); update(DOMAIN); update(payload); verify(signature)
        }) { "Issuer key mismatch" }
        return PREFIX + listOf(payload, issuer.encoded, signature).joinToString(".") { encoder.encodeToString(it) }
    }

    fun decode(link: String, trustedFingerprint: String, now: Long): PanchanInvite {
        try {
            require(link.length <= 45000 && link.startsWith(PREFIX))
            require(trustedFingerprint.matches(Regex("[0-9a-f]{64}")))
            val parts = link.removePrefix(PREFIX).split('.')
            require(parts.size == 3)
            val payload = component(parts[0], 33000)
            val spki = component(parts[1], 256)
            val signature = component(parts[2], 80)
            val issuer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            requireP256(issuer)
            require(MessageDigest.isEqual(fingerprint(issuer).toByteArray(Charsets.US_ASCII), trustedFingerprint.toByteArray(Charsets.US_ASCII)))
            require(Signature.getInstance("SHA256withECDSA").run {
                initVerify(issuer); update(DOMAIN); update(payload); verify(signature)
            })
            val invite = DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC)
                val issued = input.readLong(); val expires = input.readLong()
                val id = ByteArray(32).also { input.readFully(it) }
                val route = input.readUnsignedByte()
                val transport = PanchanTransport.entries.singleOrNull { it.wire == route }
                require(transport != null)
                val name = readText(input, input.readUnsignedShort(), 128)
                val config = readText(input, input.readInt(), 32768)
                require(input.read() == -1)
                PanchanInvite(issued, expires, id, transport, name, config)
            }
            validate(invite)
            require(now >= 0 && invite.issuedAt <= now + 300 && now < invite.expiresAt)
            return invite
        } catch (_: Exception) { throw IllegalArgumentException("Invalid or untrusted Panchan invitation") }
    }

    private fun validate(invite: PanchanInvite) {
        require(invite.issuedAt >= 0 && invite.expiresAt > invite.issuedAt && invite.expiresAt - invite.issuedAt <= 604800)
        require(invite.invitation.size == 32)
        require(invite.name.toByteArray(Charsets.UTF_8).size in 1..128 && invite.name.none { it.isISOControl() })
        require(invite.configuration.toByteArray(Charsets.UTF_8).size in 1..32768)
    }
    private fun component(value: String, limit: Int): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = decoder.decode(value)
        require(bytes.size <= limit && encoder.encodeToString(bytes) == value)
        return bytes
    }
    private fun readText(input: DataInputStream, size: Int, maximum: Int): String {
        require(size in 1..maximum)
        val bytes = ByteArray(size).also { input.readFully(it) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun requireP256(key: PublicKey) {
        require(key is ECPublicKey)
        val params = key.params
        require(params.curve == p256.curve && params.generator == p256.generator && params.order == p256.order && params.cofactor == p256.cofactor)
    }
}
