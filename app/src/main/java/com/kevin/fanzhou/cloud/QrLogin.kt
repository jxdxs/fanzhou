package com.kevin.fanzhou.cloud

sealed class QrState {
    data object Waiting : QrState()
    data object Scanned : QrState()
    data object Expired : QrState()
    data class Failed(val msg: String) : QrState()
    /** token = cookie (115/夸克) or refresh token (阿里). */
    data class Done(val token: String, val extra: Map<String, String> = emptyMap()) : QrState()
}

interface QrLogin {
    val appHint: String
    /** Returns the content to encode in the QR code. */
    suspend fun start(): String
    suspend fun poll(): QrState

    companion object {
        fun forType(type: String): QrLogin = when (type) {
            com.kevin.fanzhou.data.Source.TYPE_115 -> Drive115.Login()
            com.kevin.fanzhou.data.Source.TYPE_QUARK -> QuarkDrive.Login()
            else -> AliyunDrive.Login()
        }
    }
}
