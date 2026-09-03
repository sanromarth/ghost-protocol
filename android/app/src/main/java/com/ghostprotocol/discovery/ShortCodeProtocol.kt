package com.ghostprotocol.discovery

import com.ghostprotocol.crypto.GhostCrypto
import com.ghostprotocol.crypto.ShortCode

data class ShortCodeQuery(
    val epochDay: Long,
    val word1: String,
    val word2: String,
    val word3: String,
    val number: Int,
    val requesterEd25519Pub: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShortCodeQuery) return false
        return epochDay == other.epochDay &&
                word1 == other.word1 &&
                word2 == other.word2 &&
                word3 == other.word3 &&
                number == other.number &&
                requesterEd25519Pub.contentEquals(other.requesterEd25519Pub) &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = epochDay.hashCode()
        result = 31 * result + word1.hashCode()
        result = 31 * result + word2.hashCode()
        result = 31 * result + word3.hashCode()
        result = 31 * result + number
        result = 31 * result + requesterEd25519Pub.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

data class ShortCodeResponse(
    val status: Byte,
    val responderEd25519Pub: ByteArray,
    val responderX25519Pub: ByteArray,
    val name: String,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShortCodeResponse) return false
        return status == other.status &&
                responderEd25519Pub.contentEquals(other.responderEd25519Pub) &&
                responderX25519Pub.contentEquals(other.responderX25519Pub) &&
                name == other.name &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = status.toInt()
        result = 31 * result + responderEd25519Pub.contentHashCode()
        result = 31 * result + responderX25519Pub.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

/**
 * Pure wire codec for BIP-39 Short Verification Codes exchange over GATT and mesh.
 */
object ShortCodeProtocol {
    const val OPCODE_QUERY: Byte = 0x20
    const val OPCODE_RESPONSE: Byte = 0x21
    const val OPCODE_MESH_QUERY: Byte = 0x22
    const val OPCODE_MESH_RESPONSE: Byte = 0x23

    const val STATUS_FOUND: Byte = 0x01
    const val STATUS_NOT_FOUND: Byte = 0x02

    fun encodeQuery(
        ed25519Seed: ByteArray,
        requesterEd25519Pub: ByteArray,
        code: ShortCode
    ): ByteArray {
        require(ed25519Seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        require(requesterEd25519Pub.size == 32) { "Requester Ed25519 pubkey must be 32 bytes" }

        val w1Bytes = code.word1.toByteArray(Charsets.UTF_8)
        val w2Bytes = code.word2.toByteArray(Charsets.UTF_8)
        val w3Bytes = code.word3.toByteArray(Charsets.UTF_8)
        require(w1Bytes.size <= 255 && w2Bytes.size <= 255 && w3Bytes.size <= 255) { "Word too long" }

        val payloadSize = 1 + 8 + 1 + w1Bytes.size + 1 + w2Bytes.size + 1 + w3Bytes.size + 2 + 32
        val payloadToSign = ByteArray(payloadSize)
        var offset = 0

        payloadToSign[offset++] = OPCODE_QUERY
        for (i in 0..7) {
            payloadToSign[offset++] = ((code.epochDay ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        payloadToSign[offset++] = w1Bytes.size.toByte()
        System.arraycopy(w1Bytes, 0, payloadToSign, offset, w1Bytes.size)
        offset += w1Bytes.size

        payloadToSign[offset++] = w2Bytes.size.toByte()
        System.arraycopy(w2Bytes, 0, payloadToSign, offset, w2Bytes.size)
        offset += w2Bytes.size

        payloadToSign[offset++] = w3Bytes.size.toByte()
        System.arraycopy(w3Bytes, 0, payloadToSign, offset, w3Bytes.size)
        offset += w3Bytes.size

        payloadToSign[offset++] = ((code.number shr 8) and 0xFF).toByte()
        payloadToSign[offset++] = (code.number and 0xFF).toByte()

        System.arraycopy(requesterEd25519Pub, 0, payloadToSign, offset, 32)
        offset += 32

        val signature = GhostCrypto.sign(ed25519Seed, payloadToSign)
        require(signature.size == 64) { "Signature must be 64 bytes" }

        val packet = ByteArray(payloadSize + 64)
        System.arraycopy(payloadToSign, 0, packet, 0, payloadSize)
        System.arraycopy(signature, 0, packet, payloadSize, 64)
        return packet
    }

    fun decodeQuery(data: ByteArray): ShortCodeQuery? {
        // Min size: 1 + 8 + 1 + 0 + 1 + 0 + 1 + 0 + 2 + 32 + 64 = 110 bytes
        if (data.size < 110) return null
        if (data[0] != OPCODE_QUERY) return null

        var offset = 1
        var epochDay = 0L
        for (i in 0..7) {
            epochDay = (epochDay shl 8) or (data[offset++].toLong() and 0xFF)
        }

        val w1Len = data[offset++].toInt() and 0xFF
        if (offset + w1Len > data.size - 64) return null
        val w1 = String(data, offset, w1Len, Charsets.UTF_8)
        offset += w1Len

        val w2Len = data[offset++].toInt() and 0xFF
        if (offset + w2Len > data.size - 64) return null
        val w2 = String(data, offset, w2Len, Charsets.UTF_8)
        offset += w2Len

        val w3Len = data[offset++].toInt() and 0xFF
        if (offset + w3Len > data.size - 64) return null
        val w3 = String(data, offset, w3Len, Charsets.UTF_8)
        offset += w3Len

        if (offset + 2 + 32 + 64 > data.size) return null
        val number = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2

        val requesterEd25519Pub = data.copyOfRange(offset, offset + 32)
        offset += 32

        val signature = data.copyOfRange(offset, offset + 64)

        return ShortCodeQuery(
            epochDay = epochDay,
            word1 = w1,
            word2 = w2,
            word3 = w3,
            number = number,
            requesterEd25519Pub = requesterEd25519Pub,
            signature = signature
        )
    }

    fun verifyQuery(query: ShortCodeQuery): Boolean {
        if (query.requesterEd25519Pub.size != 32 || query.signature.size != 64) return false

        val w1Bytes = query.word1.toByteArray(Charsets.UTF_8)
        val w2Bytes = query.word2.toByteArray(Charsets.UTF_8)
        val w3Bytes = query.word3.toByteArray(Charsets.UTF_8)
        val payloadSize = 1 + 8 + 1 + w1Bytes.size + 1 + w2Bytes.size + 1 + w3Bytes.size + 2 + 32
        val payloadToSign = ByteArray(payloadSize)
        var offset = 0

        payloadToSign[offset++] = OPCODE_QUERY
        for (i in 0..7) {
            payloadToSign[offset++] = ((query.epochDay ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        payloadToSign[offset++] = w1Bytes.size.toByte()
        System.arraycopy(w1Bytes, 0, payloadToSign, offset, w1Bytes.size)
        offset += w1Bytes.size

        payloadToSign[offset++] = w2Bytes.size.toByte()
        System.arraycopy(w2Bytes, 0, payloadToSign, offset, w2Bytes.size)
        offset += w2Bytes.size

        payloadToSign[offset++] = w3Bytes.size.toByte()
        System.arraycopy(w3Bytes, 0, payloadToSign, offset, w3Bytes.size)
        offset += w3Bytes.size

        payloadToSign[offset++] = ((query.number shr 8) and 0xFF).toByte()
        payloadToSign[offset++] = (query.number and 0xFF).toByte()

        System.arraycopy(query.requesterEd25519Pub, 0, payloadToSign, offset, 32)

        return GhostCrypto.verify(query.requesterEd25519Pub, payloadToSign, query.signature)
    }

    fun encodeResponse(
        status: Byte,
        ed25519Seed: ByteArray,
        ed25519Pub: ByteArray,
        x25519Pub: ByteArray,
        name: String
    ): ByteArray {
        require(ed25519Seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        require(ed25519Pub.size == 32) { "Ed25519 pubkey must be 32 bytes" }
        require(x25519Pub.size == 32) { "X25519 pubkey must be 32 bytes" }

        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size
        require(nameLen <= 65535) { "Name length exceeds uint16" }

        val payloadSize = 1 + 1 + 32 + 32 + 2 + nameLen
        val payloadToSign = ByteArray(payloadSize)
        payloadToSign[0] = OPCODE_RESPONSE
        payloadToSign[1] = status
        System.arraycopy(ed25519Pub, 0, payloadToSign, 2, 32)
        System.arraycopy(x25519Pub, 0, payloadToSign, 34, 32)
        payloadToSign[66] = ((nameLen shr 8) and 0xFF).toByte()
        payloadToSign[67] = (nameLen and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, payloadToSign, 68, nameLen)

        val signature = GhostCrypto.sign(ed25519Seed, payloadToSign)
        require(signature.size == 64) { "Signature must be 64 bytes" }

        val packet = ByteArray(payloadSize + 64)
        System.arraycopy(payloadToSign, 0, packet, 0, payloadSize)
        System.arraycopy(signature, 0, packet, payloadSize, 64)
        return packet
    }

    fun decodeResponse(data: ByteArray): ShortCodeResponse? {
        // Min size: 1 + 1 + 32 + 32 + 2 + 0 + 64 = 132 bytes
        if (data.size < 132) return null
        if (data[0] != OPCODE_RESPONSE) return null

        val status = data[1]
        val ed25519Pub = data.copyOfRange(2, 34)
        val x25519Pub = data.copyOfRange(34, 66)
        val nameLen = ((data[66].toInt() and 0xFF) shl 8) or (data[67].toInt() and 0xFF)
        val expectedMinSize = 68 + nameLen + 64
        if (data.size < expectedMinSize) return null

        val name = String(data, 68, nameLen, Charsets.UTF_8)
        val signature = data.copyOfRange(68 + nameLen, 68 + nameLen + 64)

        return ShortCodeResponse(
            status = status,
            responderEd25519Pub = ed25519Pub,
            responderX25519Pub = x25519Pub,
            name = name,
            signature = signature
        )
    }

    fun verifyResponse(response: ShortCodeResponse): Boolean {
        if (response.responderEd25519Pub.size != 32 || response.signature.size != 64) return false
        val nameBytes = response.name.toByteArray(Charsets.UTF_8)
        val payloadSize = 1 + 1 + 32 + 32 + 2 + nameBytes.size
        val payloadToSign = ByteArray(payloadSize)
        payloadToSign[0] = OPCODE_RESPONSE
        payloadToSign[1] = response.status
        System.arraycopy(response.responderEd25519Pub, 0, payloadToSign, 2, 32)
        System.arraycopy(response.responderX25519Pub, 0, payloadToSign, 34, 32)
        payloadToSign[66] = ((nameBytes.size shr 8) and 0xFF).toByte()
        payloadToSign[67] = (nameBytes.size and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, payloadToSign, 68, nameBytes.size)

        return GhostCrypto.verify(response.responderEd25519Pub, payloadToSign, response.signature)
    }
}
