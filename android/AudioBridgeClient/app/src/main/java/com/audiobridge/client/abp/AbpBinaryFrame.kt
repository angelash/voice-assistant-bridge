package com.audiobridge.client.abp

data class AbpBinaryFrame(
    val streamId: AbpStreamId,
    val seq: Long,
    val timestampSamples: Long,
    val payload: ByteArray,
) {
    fun encode(): ByteArray {
        require(seq in 0..0xFFFF_FFFFL) { "seq out of range: $seq" }
        require(timestampSamples in 0..0xFFFF_FFFFL) { "timestampSamples out of range: $timestampSamples" }
        require(payload.size <= 0xFFFF) { "payload too large: ${payload.size}" }

        val out = ByteArray(AbpConstants.HEADER_SIZE + payload.size)

        writeU16LE(out, 0, AbpConstants.MAGIC)
        out[2] = AbpConstants.VERSION.toByte()
        out[3] = streamId.id.toByte()
        writeU32LE(out, 4, seq)
        writeU32LE(out, 8, timestampSamples)
        writeU16LE(out, 12, payload.size)

        payload.copyInto(out, destinationOffset = AbpConstants.HEADER_SIZE)
        return out
    }

    companion object {
        fun tryDecode(bytes: ByteArray): Result<AbpBinaryFrame> {
            if (bytes.size < AbpConstants.HEADER_SIZE) {
                return Result.failure(IllegalArgumentException("frame too short: ${bytes.size}"))
            }

            val magic = readU16LE(bytes, 0)
            if (magic != AbpConstants.MAGIC) {
                return Result.failure(IllegalArgumentException("bad magic: 0x${magic.toString(16)}"))
            }

            val version = bytes[2].toInt() and 0xFF
            if (version != AbpConstants.VERSION) {
                return Result.failure(IllegalArgumentException("unsupported version: $version"))
            }

            val streamIdRaw = bytes[3].toInt() and 0xFF
            val streamId = AbpStreamId.fromId(streamIdRaw)
                ?: return Result.failure(IllegalArgumentException("invalid streamId: $streamIdRaw"))

            val seq = readU32LE(bytes, 4)
            val ts = readU32LE(bytes, 8)
            val payloadLen = readU16LE(bytes, 12)

            val expectedLen = AbpConstants.HEADER_SIZE + payloadLen
            if (bytes.size != expectedLen) {
                return Result.failure(
                    IllegalArgumentException("length mismatch: data=${bytes.size}, expected=$expectedLen"),
                )
            }

            val payload = if (payloadLen == 0) {
                ByteArray(0)
            } else {
                bytes.copyOfRange(AbpConstants.HEADER_SIZE, expectedLen)
            }

            return Result.success(AbpBinaryFrame(streamId, seq, ts, payload))
        }

        private fun writeU16LE(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        private fun writeU32LE(buf: ByteArray, offset: Int, value: Long) {
            val v = value and 0xFFFF_FFFFL
            buf[offset] = (v and 0xFF).toByte()
            buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            buf[offset + 2] = ((v ushr 16) and 0xFF).toByte()
            buf[offset + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        private fun readU16LE(buf: ByteArray, offset: Int): Int {
            val b0 = buf[offset].toInt() and 0xFF
            val b1 = buf[offset + 1].toInt() and 0xFF
            return b0 or (b1 shl 8)
        }

        private fun readU32LE(buf: ByteArray, offset: Int): Long {
            val b0 = buf[offset].toLong() and 0xFF
            val b1 = buf[offset + 1].toLong() and 0xFF
            val b2 = buf[offset + 2].toLong() and 0xFF
            val b3 = buf[offset + 3].toLong() and 0xFF
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }
}

