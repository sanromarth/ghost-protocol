package com.ghostprotocol.discovery

import com.ghostprotocol.crypto.GhostCrypto

data class DiscoveryRequest(
    val opcode: Byte = DiscoveryProtocol.OPCODE_REQUEST,
    val ed25519Pub: ByteArray,
    val x25519Pub: ByteArray,
    val name: String,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveryRequest) return false
        return opcode == other.opcode &&
                ed25519Pub.contentEquals(other.ed25519Pub) &&
                x25519Pub.contentEquals(other.x25519Pub) &&
                name == other.name &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = opcode.toInt()
        result = 31 * result + ed25519Pub.contentHashCode()
        result = 31 * result + x25519Pub.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

data class DiscoveryResponse(
    val opcode: Byte = DiscoveryProtocol.OPCODE_RESPONSE,
    val status: Byte,
    val ed25519Pub: ByteArray,
    val x25519Pub: ByteArray,
    val name: String,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveryResponse) return false
        return opcode == other.opcode &&
                status == other.status &&
                ed25519Pub.contentEquals(other.ed25519Pub) &&
                x25519Pub.contentEquals(other.x25519Pub) &&
                name == other.name &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = opcode.toInt()
        result = 31 * result + status.toInt()
        result = 31 * result + ed25519Pub.contentHashCode()
        result = 31 * result + x25519Pub.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

object DiscoveryProtocol {
    const val OPCODE_REQUEST: Byte = 0x10
    const val OPCODE_RESPONSE: Byte = 0x11

    const val STATUS_ACCEPT: Byte = 0x01
    const val STATUS_REJECT: Byte = 0x02
    const val STATUS_BUSY: Byte = 0x03

    /**
     * DISCOVERY_REQUEST wire packet:
     * Offset   Size    Field
     * 0        1       0x10
     * 1        32      Requester Ed25519 Public Key
     * 33       32      Requester X25519 Public Key
     * 65       2       Display Name Length (N), big-endian uint16
     * 67       N       UTF-8 Display Name
     * 67+N     64      Ed25519 Signature over [0x10 || ed25519Pub || x25519Pub || nameUtf8]
     * Total: 131 + N bytes minimum
     */
    fun encodeRequest(
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

        // payloadToSign = 0x10 || ed25519Pub || x25519Pub || nameUtf8
        val payloadToSign = ByteArray(1 + 32 + 32 + nameLen)
        payloadToSign[0] = OPCODE_REQUEST
        System.arraycopy(ed25519Pub, 0, payloadToSign, 1, 32)
        System.arraycopy(x25519Pub, 0, payloadToSign, 33, 32)
        System.arraycopy(nameBytes, 0, payloadToSign, 65, nameLen)

        val signature = GhostCrypto.sign(ed25519Seed, payloadToSign)
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes" }

        val packet = ByteArray(1 + 32 + 32 + 2 + nameLen + 64)
        packet[0] = OPCODE_REQUEST
        System.arraycopy(ed25519Pub, 0, packet, 1, 32)
        System.arraycopy(x25519Pub, 0, packet, 33, 32)
        packet[65] = ((nameLen shr 8) and 0xFF).toByte()
        packet[66] = (nameLen and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, packet, 67, nameLen)
        System.arraycopy(signature, 0, packet, 67 + nameLen, 64)

        return packet
    }

    /**
     * DISCOVERY_RESPONSE wire packet:
     * Offset   Size    Field
     * 0        1       0x11
     * 1        1       Status: 0x01=ACCEPT, 0x02=REJECT, 0x03=BUSY
     * 2        32      Responder Ed25519 Public Key
     * 34       32      Responder X25519 Public Key
     * 66       2       Display Name Length (M), big-endian uint16
     * 68       M       UTF-8 Display Name
     * 68+M     64      Ed25519 Signature over [0x11 || status || ed25519Pub || x25519Pub || nameUtf8]
     * Total: 132 + M bytes minimum
     */
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

        // payloadToSign = 0x11 || status || ed25519Pub || x25519Pub || nameUtf8
        val payloadToSign = ByteArray(1 + 1 + 32 + 32 + nameLen)
        payloadToSign[0] = OPCODE_RESPONSE
        payloadToSign[1] = status
        System.arraycopy(ed25519Pub, 0, payloadToSign, 2, 32)
        System.arraycopy(x25519Pub, 0, payloadToSign, 34, 32)
        System.arraycopy(nameBytes, 0, payloadToSign, 66, nameLen)

        val signature = GhostCrypto.sign(ed25519Seed, payloadToSign)
        require(signature.size == 64) { "Ed25519 signature must be 64 bytes" }

        val packet = ByteArray(1 + 1 + 32 + 32 + 2 + nameLen + 64)
        packet[0] = OPCODE_RESPONSE
        packet[1] = status
        System.arraycopy(ed25519Pub, 0, packet, 2, 32)
        System.arraycopy(x25519Pub, 0, packet, 34, 32)
        packet[66] = ((nameLen shr 8) and 0xFF).toByte()
        packet[67] = (nameLen and 0xFF).toByte()
        System.arraycopy(nameBytes, 0, packet, 68, nameLen)
        System.arraycopy(signature, 0, packet, 68 + nameLen, 64)

        return packet
    }

    fun decodeRequest(data: ByteArray): DiscoveryRequest? {
        if (data.size < 131) return null
        if (data[0] != OPCODE_REQUEST) return null

        val ed25519Pub = data.copyOfRange(1, 33)
        val x25519Pub = data.copyOfRange(33, 65)
        val nameLen = ((data[65].toInt() and 0xFF) shl 8) or (data[66].toInt() and 0xFF)
        val expectedMinSize = 67 + nameLen + 64
        if (data.size < expectedMinSize) return null

        val name = String(data, 67, nameLen, Charsets.UTF_8)
        val signature = data.copyOfRange(67 + nameLen, 67 + nameLen + 64)

        return DiscoveryRequest(
            opcode = OPCODE_REQUEST,
            ed25519Pub = ed25519Pub,
            x25519Pub = x25519Pub,
            name = name,
            signature = signature
        )
    }

    fun decodeResponse(data: ByteArray): DiscoveryResponse? {
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

        return DiscoveryResponse(
            opcode = OPCODE_RESPONSE,
            status = status,
            ed25519Pub = ed25519Pub,
            x25519Pub = x25519Pub,
            name = name,
            signature = signature
        )
    }

    fun verifyRequest(request: DiscoveryRequest): Boolean {
        if (request.ed25519Pub.size != 32 || request.signature.size != 64) return false
        val nameBytes = request.name.toByteArray(Charsets.UTF_8)
        val payloadToSign = ByteArray(1 + 32 + 32 + nameBytes.size)
        payloadToSign[0] = OPCODE_REQUEST
        System.arraycopy(request.ed25519Pub, 0, payloadToSign, 1, 32)
        System.arraycopy(request.x25519Pub, 0, payloadToSign, 33, 32)
        System.arraycopy(nameBytes, 0, payloadToSign, 65, nameBytes.size)

        return GhostCrypto.verify(request.ed25519Pub, payloadToSign, request.signature)
    }

    fun verifyResponse(response: DiscoveryResponse): Boolean {
        if (response.ed25519Pub.size != 32 || response.signature.size != 64) return false
        val nameBytes = response.name.toByteArray(Charsets.UTF_8)
        val payloadToSign = ByteArray(1 + 1 + 32 + 32 + nameBytes.size)
        payloadToSign[0] = OPCODE_RESPONSE
        payloadToSign[1] = response.status
        System.arraycopy(response.ed25519Pub, 0, payloadToSign, 2, 32)
        System.arraycopy(response.x25519Pub, 0, payloadToSign, 34, 32)
        System.arraycopy(nameBytes, 0, payloadToSign, 66, nameBytes.size)

        return GhostCrypto.verify(response.ed25519Pub, payloadToSign, response.signature)
    }
}
