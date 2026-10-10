package com.kevin.fanzhou.cloud

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom

/** 115 "m115" request/response cipher (port of SheltonZhu/115driver pkg/crypto/m115). */
object M115 {
    private val N = BigInteger(
        "8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b86021" +
            "36477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad633" +
            "8c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f1" +
            "0a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683", 16
    )
    private val E = BigInteger("10001", 16)
    private val KEY_LEN = N.bitLength() / 8
    private val rnd = SecureRandom()

    private val seed = intArrayOf(
        0xf0, 0xe5, 0x69, 0xae, 0xbf, 0xdc, 0xbf, 0x8a, 0x1a, 0x45, 0xe8, 0xbe, 0x7d, 0xa6, 0x73, 0xb8,
        0xde, 0x8f, 0xe7, 0xc4, 0x45, 0xda, 0x86, 0xc4, 0x9b, 0x64, 0x8b, 0x14, 0x6a, 0xb4, 0xf1, 0xaa,
        0x38, 0x01, 0x35, 0x9e, 0x26, 0x69, 0x2c, 0x86, 0x00, 0x6b, 0x4f, 0xa5, 0x36, 0x34, 0x62, 0xa6,
        0x2a, 0x96, 0x68, 0x18, 0xf2, 0x4a, 0xfd, 0xbd, 0x6b, 0x97, 0x8f, 0x4d, 0x8f, 0x89, 0x13, 0xb7,
        0x6c, 0x8e, 0x93, 0xed, 0x0e, 0x0d, 0x48, 0x3e, 0xd7, 0x2f, 0x88, 0xd8, 0xfe, 0xfe, 0x7e, 0x86,
        0x50, 0x95, 0x4f, 0xd1, 0xeb, 0x83, 0x26, 0x34, 0xdb, 0x66, 0x7b, 0x9c, 0x7e, 0x9d, 0x7a, 0x81,
        0x32, 0xea, 0xb6, 0x33, 0xde, 0x3a, 0xa9, 0x59, 0x34, 0x66, 0x3b, 0xaa, 0xba, 0x81, 0x60, 0x48,
        0xb9, 0xd5, 0x81, 0x9c, 0xf8, 0x6c, 0x84, 0x77, 0xff, 0x54, 0x78, 0x26, 0x5f, 0xbe, 0xe8, 0x1e,
        0x36, 0x9f, 0x34, 0x80, 0x5c, 0x45, 0x2c, 0x9b, 0x76, 0xd5, 0x1b, 0x8f, 0xcc, 0xc3, 0xb8, 0xf5,
    )
    private val clientKey = intArrayOf(0x78, 0x06, 0xad, 0x4c, 0x33, 0x86, 0x5d, 0x18, 0x4c, 0x01, 0x3f, 0x46)

    fun generateKey(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    private fun deriveKey(s: ByteArray, size: Int): IntArray = IntArray(size) { i ->
        (((s[i].toInt() and 0xff) + seed[size * i]) and 0xff) xor seed[size * (size - i - 1)]
    }

    private fun xor(data: ByteArray, from: Int, len: Int, key: IntArray) {
        val mod = len % 4
        for (i in 0 until mod) data[from + i] = (data[from + i].toInt() xor key[i % key.size]).toByte()
        for (i in mod until len) data[from + i] = (data[from + i].toInt() xor key[(i - mod) % key.size]).toByte()
    }

    private fun reverse(data: ByteArray, from: Int, len: Int) {
        var i = from; var j = from + len - 1
        while (i < j) { val t = data[i]; data[i] = data[j]; data[j] = t; i++; j-- }
    }

    fun encode(input: ByteArray, key: ByteArray): String {
        val buf = ByteArray(16 + input.size)
        System.arraycopy(key, 0, buf, 0, 16)
        System.arraycopy(input, 0, buf, 16, input.size)
        xor(buf, 16, input.size, deriveKey(key, 4))
        reverse(buf, 16, input.size)
        xor(buf, 16, input.size, clientKey)
        return Base64.encodeToString(rsaEncrypt(buf), Base64.NO_WRAP)
    }

    fun decode(input: String, key: ByteArray): ByteArray {
        val data = rsaDecrypt(Base64.decode(input, Base64.DEFAULT))
        val out = data.copyOfRange(16, data.size)
        xor(out, 0, out.size, deriveKey(data.copyOfRange(0, 16), 12))
        reverse(out, 0, out.size)
        xor(out, 0, out.size, deriveKey(key, 4))
        return out
    }

    private fun rsaEncrypt(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < input.size) {
            val n = minOf(KEY_LEN - 11, input.size - off)
            val slice = input.copyOfRange(off, off + n)
            val padSize = KEY_LEN - n - 3
            val pad = ByteArray(padSize).also { rnd.nextBytes(it) }
            val buf = ByteArray(KEY_LEN)
            buf[0] = 0; buf[1] = 2
            for (i in 0 until padSize) buf[2 + i] = (((pad[i].toInt() and 0xff) % 0xff) + 1).toByte()
            buf[padSize + 2] = 0
            System.arraycopy(slice, 0, buf, padSize + 3, n)
            val ret = toFixed(BigInteger(1, buf).modPow(E, N), KEY_LEN)
            out.write(ret)
            off += n
        }
        return out.toByteArray()
    }

    private fun rsaDecrypt(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < input.size) {
            val n = minOf(KEY_LEN, input.size - off)
            val ret = unsigned(BigInteger(1, input.copyOfRange(off, off + n)).modPow(E, N))
            for (i in ret.indices) {
                if (ret[i].toInt() == 0 && i != 0) { out.write(ret, i + 1, ret.size - i - 1); break }
            }
            off += n
        }
        return out.toByteArray()
    }

    private fun unsigned(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
    }

    private fun toFixed(v: BigInteger, len: Int): ByteArray {
        val b = unsigned(v)
        if (b.size >= len) return b
        return ByteArray(len - b.size) + b
    }
}
