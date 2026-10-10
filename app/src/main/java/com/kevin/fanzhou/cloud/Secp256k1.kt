package com.kevin.fanzhou.cloud

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** Minimal secp256k1 ECDSA (recoverable signature r||s||v) for Aliyun Drive device signing. */
object Secp256k1 {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val rnd = SecureRandom()

    data class Pt(val x: BigInteger, val y: BigInteger)

    private fun add(a: Pt?, b: Pt?): Pt? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            if (a.y.add(b.y).mod(P) == BigInteger.ZERO) return null
            return dbl(a)
        }
        val l = b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        val x = l.multiply(l).subtract(a.x).subtract(b.x).mod(P)
        val y = l.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Pt(x, y)
    }

    private fun dbl(a: Pt): Pt? {
        if (a.y.signum() == 0) return null
        val l = a.x.multiply(a.x).multiply(BigInteger.valueOf(3)).multiply(a.y.shiftLeft(1).modInverse(P)).mod(P)
        val x = l.multiply(l).subtract(a.x.shiftLeft(1)).mod(P)
        val y = l.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Pt(x, y)
    }

    fun mul(k: BigInteger, p: Pt = Pt(GX, GY)): Pt? {
        var r: Pt? = null
        var q: Pt? = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = q?.let { dbl(it) }
        }
        return r
    }

    private fun b32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun publicKeyHex(priv: BigInteger): String {
        val p = mul(priv)!!
        return hex(b32(p.x) + b32(p.y))
    }

    fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    fun signHex(priv: BigInteger, hash: ByteArray): String {
        val z = BigInteger(1, hash)
        while (true) {
            val k = BigInteger(256, rnd).mod(N)
            if (k.signum() == 0) continue
            val r1 = mul(k) ?: continue
            val r = r1.x.mod(N)
            if (r.signum() == 0) continue
            var s = k.modInverse(N).multiply(z.add(r.multiply(priv))).mod(N)
            if (s.signum() == 0) continue
            var v = (if (r1.y.testBit(0)) 1 else 0) or (if (r1.x >= N) 2 else 0)
            if (s > N.shiftRight(1)) { s = N.subtract(s); v = v xor 1 }
            return hex(b32(r) + b32(s) + byteArrayOf(v.toByte()))
        }
    }
}
