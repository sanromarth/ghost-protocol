package com.ghostprotocol.introduction

import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.data.Contact
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import android.util.Base64

/**
 * Parsed Introduction Envelope payload (Opcode 0x50).
 */
data class IntroductionEnvelope(
    val introducedEd25519Pub: ByteArray,
    val introducedX25519Pub: ByteArray,
    val introducedName: String,
    val voucherContactId: String,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IntroductionEnvelope
        return introducedEd25519Pub.contentEquals(other.introducedEd25519Pub) &&
                introducedX25519Pub.contentEquals(other.introducedX25519Pub) &&
                introducedName == other.introducedName &&
                voucherContactId == other.voucherContactId &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = introducedEd25519Pub.contentHashCode()
        result = 31 * result + introducedX25519Pub.contentHashCode()
        result = 31 * result + introducedName.hashCode()
        result = 31 * result + voucherContactId.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Pure Kotlin protocol codec for GHOST v0.3.6 Contact Introductions (Trust Web).
 *
 * Wire Format (147 + N bytes):
 * [0..0]       1B   Magic marker (Opcode 0x50)
 * [1..32]      32B  Introduced person's Ed25519 public key (Bob)
 * [33..64]     32B  Introduced person's X25519 public key (Bob)
 * [65..66]     2B   Name length (N), big-endian uint16
 * [67..66+N]   NB   Introduced person's name (UTF-8)
 * [67+N..82+N] 16B  Voucher's contact ID (Alice, 16-char ASCII hex)
 * [83+N..146+N]64B  Alice's Ed25519 signature over [0x50 || BobEdPub || BobXPub || nameLen || name || voucherContactId]
 */
object IntroductionProtocol {

    const val OPCODE_INTRODUCTION: Byte = 0x50
    const val MIN_ENVELOPE_SIZE = 147 // 1 + 32 + 32 + 2 + 0 + 16 + 64

    /**
     * Encodes and signs an introduction envelope.
     */
    fun encodeIntroduction(
        introducedContact: Contact,
        voucherSeed: ByteArray,
        voucherContactId: String
    ): ByteArray {
        val edPub = Base64.decode(introducedContact.ed25519PubKey, Base64.NO_WRAP)
        val xPub = Base64.decode(introducedContact.x25519PubKey, Base64.NO_WRAP)
        return encodeIntroduction(
            introducedEd25519Pub = edPub,
            introducedX25519Pub = xPub,
            introducedName = introducedContact.name,
            voucherSeed = voucherSeed,
            voucherContactId = voucherContactId
        )
    }

    /**
     * Raw-key encoding method for testing or low-level usage.
     */
    fun encodeIntroduction(
        introducedEd25519Pub: ByteArray,
        introducedX25519Pub: ByteArray,
        introducedName: String,
        voucherSeed: ByteArray,
        voucherContactId: String
    ): ByteArray {
        val nameBytes = introducedName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size
        val voucherIdBytes = voucherContactId.toByteArray(Charsets.UTF_8).copyOf(16)

        val unsignedLen = 1 + 32 + 32 + 2 + nameLen + 16
        val buffer = ByteBuffer.allocate(unsignedLen).order(ByteOrder.BIG_ENDIAN)
        buffer.put(OPCODE_INTRODUCTION)
        buffer.put(introducedEd25519Pub.copyOf(32))
        buffer.put(introducedX25519Pub.copyOf(32))
        buffer.putShort(nameLen.toShort())
        buffer.put(nameBytes)
        buffer.put(voucherIdBytes)

        val unsignedPayload = buffer.array()
        val signature = GhostCrypto.sign(voucherSeed, unsignedPayload)

        val packet = ByteArray(unsignedLen + 64)
        System.arraycopy(unsignedPayload, 0, packet, 0, unsignedLen)
        System.arraycopy(signature, 0, packet, unsignedLen, 64)
        return packet
    }

    /**
     * Decodes an introduction packet without verifying signature.
     */
    fun decodeIntroduction(data: ByteArray): IntroductionEnvelope? {
        if (data.size < MIN_ENVELOPE_SIZE) return null
        if (data[0] != OPCODE_INTRODUCTION) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buffer.get() // Skip opcode 0x50

        val introducedEd25519Pub = ByteArray(32)
        buffer.get(introducedEd25519Pub)

        val introducedX25519Pub = ByteArray(32)
        buffer.get(introducedX25519Pub)

        val nameLen = buffer.short.toInt() and 0xFFFF
        if (nameLen < 0 || buffer.remaining() < nameLen + 16 + 64) return null

        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val introducedName = String(nameBytes, Charsets.UTF_8)

        val voucherIdBytes = ByteArray(16)
        buffer.get(voucherIdBytes)
        val voucherContactId = String(voucherIdBytes, Charsets.UTF_8).trim('\u0000')

        val signature = ByteArray(64)
        buffer.get(signature)

        return IntroductionEnvelope(
            introducedEd25519Pub = introducedEd25519Pub,
            introducedX25519Pub = introducedX25519Pub,
            introducedName = introducedName,
            voucherContactId = voucherContactId,
            signature = signature
        )
    }

    /**
     * Verifies the Ed25519 signature of an introduction envelope against the voucher's public key.
     */
    fun verifyIntroduction(envelope: IntroductionEnvelope, voucherEd25519Pub: ByteArray): Boolean {
        if (envelope.signature.size != 64) return false

        val nameBytes = envelope.introducedName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size
        val voucherIdBytes = envelope.voucherContactId.toByteArray(Charsets.UTF_8).copyOf(16)

        val unsignedLen = 1 + 32 + 32 + 2 + nameLen + 16
        val buffer = ByteBuffer.allocate(unsignedLen).order(ByteOrder.BIG_ENDIAN)
        buffer.put(OPCODE_INTRODUCTION)
        buffer.put(envelope.introducedEd25519Pub.copyOf(32))
        buffer.put(envelope.introducedX25519Pub.copyOf(32))
        buffer.putShort(nameLen.toShort())
        buffer.put(nameBytes)
        buffer.put(voucherIdBytes)

        val unsignedPayload = buffer.array()
        return GhostCrypto.verify(voucherEd25519Pub, unsignedPayload, envelope.signature)
    }

    /**
     * Computes the 16-character hex Contact ID from an Ed25519 public key:
     * SHA-256(ed25519Pub).take(8).hex
     */
    fun computeContactId(ed25519Pub: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(ed25519Pub)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
