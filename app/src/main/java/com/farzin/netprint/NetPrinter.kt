package com.farzin.netprint

enum class Protocol { IPP, RAW, LPD }

data class NetPrinter(
    val name: String,
    val host: String,
    val port: Int,
    val protocol: Protocol,
    val path: String = "ipp/print",   // IPP resource path
    val queue: String = "lp"          // LPD queue
) {
    val key get() = "$host:$port"
    override fun toString() = "$name\n$host:$port · $protocol"
}
