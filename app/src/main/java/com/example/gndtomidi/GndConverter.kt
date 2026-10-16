package com.example.gndtomidi

import java.io.ByteArrayOutputStream

object GndConverter {
    private val headerTag = "GOGONRB_ODYSSEYSOFT_HEADER".toByteArray(Charsets.US_ASCII)
    private val trackTag = "GOGONRB_ODYSSEYSOFT_TRACK".toByteArray(Charsets.US_ASCII)

    data class Result(val midi: ByteArray, val trackCount: Int, val format: Int, val ppq: Int)

    fun convert(input: ByteArray): Result {
        require(input.size >= headerTag.size + 10) { "파일이 너무 짧습니다." }
        require(matches(input, 0, headerTag)) { "지원하지 않는 GND 파일 헤더입니다." }
        val h = headerTag.size
        require(u32(input, h) == 6L && h + 10 <= input.size) { "MIDI 헤더 영역이 올바르지 않습니다." }
        val format = u16(input, h + 4)
        val declaredTracks = u16(input, h + 6)
        val division = u16(input, h + 8)
        require(format in 0..2 && declaredTracks in 1..256 && division > 0) { "MIDI 헤더 값이 올바르지 않습니다." }
        var pos = h + 10
        val tracks = ArrayList<ByteArray>()
        while (pos < input.size) {
            require(matches(input, pos, trackTag)) { "트랙 ${tracks.size + 1} 데이터가 올바르지 않습니다. offset=$pos" }
            val lenPos = pos + trackTag.size
            require(lenPos + 4 <= input.size) { "트랙 길이 정보가 잘렸습니다." }
            val lenLong = u32(input, lenPos)
            require(lenLong in 1..Int.MAX_VALUE.toLong()) { "트랙 길이가 올바르지 않습니다." }
            val start = lenPos + 4
            val end = start + lenLong.toInt()
            require(end >= start && end <= input.size) { "트랙 데이터가 파일 범위를 벗어났습니다." }
            val track = input.copyOfRange(start, end)
            require(track.size >= 4 && track[track.size - 3] == 0xFF.toByte() && track[track.size - 2] == 0x2F.toByte() && track.last() == 0x00.toByte()) {
                "트랙 ${tracks.size + 1}의 MIDI 종료 이벤트가 없습니다."
            }
            tracks.add(track)
            pos = end
        }
        require(tracks.isNotEmpty()) { "MIDI 트랙을 찾을 수 없습니다." }
        require(tracks.size == declaredTracks) { "헤더는 $declaredTracks개 트랙을 선언하지만 실제 트랙은 ${tracks.size}개입니다." }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        writeU32(out, 6)
        writeU16(out, format); writeU16(out, tracks.size); writeU16(out, division)
        for (track in tracks) {
            out.write("MTrk".toByteArray(Charsets.US_ASCII))
            writeU32(out, track.size); out.write(track)
        }
        return Result(out.toByteArray(), tracks.size, format, division)
    }

    private fun matches(data: ByteArray, offset: Int, signature: ByteArray): Boolean {
        if (offset < 0 || offset + signature.size > data.size) return false
        for (i in signature.indices) if (data[offset + i] != signature[i]) return false
        return true
    }
    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 255) shl 24) or ((b[p + 1].toLong() and 255) shl 16) or
        ((b[p + 2].toLong() and 255) shl 8) or (b[p + 3].toLong() and 255)
    private fun writeU16(out: ByteArrayOutputStream, n: Int) {
        out.write((n ushr 8) and 255); out.write(n and 255)
    }
    private fun writeU32(out: ByteArrayOutputStream, n: Int) {
        out.write((n ushr 24) and 255); out.write((n ushr 16) and 255)
        out.write((n ushr 8) and 255); out.write(n and 255)
    }
}
