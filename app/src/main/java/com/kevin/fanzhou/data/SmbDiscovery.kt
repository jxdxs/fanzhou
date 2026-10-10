package com.kevin.fanzhou.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Finds SMB servers on the local /24 network: TCP 445 probe plus a NetBIOS name query. */
object SmbDiscovery {
    private fun localIpv4(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    /** Returns entries like "192.168.1.10  (NAS)". */
    suspend fun scan(): List<String> = withContext(Dispatchers.IO) {
        val prefixes = localIpv4().map { it.hostAddress!!.substringBeforeLast('.') }.distinct()
        val self = localIpv4().map { it.hostAddress }.toSet()
        val gate = Semaphore(48)
        coroutineScope {
            prefixes.flatMap { p -> (1..254).map { "$p.$it" } }.filter { it !in self }.map { ip ->
                async {
                    gate.withPermit {
                        val open = runCatching {
                            Socket().use { s -> s.connect(InetSocketAddress(ip, 445), 350); true }
                        }.getOrDefault(false)
                        if (!open) null else {
                            val name = netbiosName(ip)
                            if (name != null) "$ip  ($name)" else ip
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** NetBIOS node status (NBSTAT) query on UDP 137. */
    private fun netbiosName(ip: String): String? = runCatching {
        val q = ByteArray(50)
        q[0] = 0x13; q[1] = 0x37 // transaction id
        q[5] = 1 // one question
        q[12] = 0x20
        // "*" padded with NULs, first-level encoded
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (i in 0 until 16) {
            val b = raw[i].toInt() and 0xFF
            q[13 + i * 2] = ('A'.code + (b shr 4)).toByte()
            q[14 + i * 2] = ('A'.code + (b and 0xF)).toByte()
        }
        q[45] = 0; q[46] = 0; q[47] = 0x21; q[48] = 0; q[49] = 1
        DatagramSocket().use { s ->
            s.soTimeout = 600
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val n = buf[56].toInt() and 0xFF
            for (i in 0 until n) {
                val off = 57 + i * 18
                if (off + 18 > p.length) break
                val name = String(buf, off, 15, Charsets.US_ASCII).trim()
                val suffix = buf[off + 15].toInt() and 0xFF
                val group = (buf[off + 16].toInt() and 0x80) != 0
                if (!group && (suffix == 0x20 || suffix == 0x00) && name.isNotEmpty()) return@runCatching name
            }
            null
        }
    }.getOrNull()
}
