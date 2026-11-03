package com.bitchat.android.net.bridge

import java.net.InetAddress
import java.net.URI
import java.util.Base64

enum class BridgeProtocol(val wireName: String) { PLAIN(""), OBFS4("obfs4"), WEBTUNNEL("webtunnel") }

class BridgeLine(val protocol: BridgeProtocol, val address: String, val fingerprint: String, val options: Map<String, String>) {
    fun toTorLine(): String = buildList {
        if (protocol != BridgeProtocol.PLAIN) add(protocol.wireName)
        add(address); add(fingerprint)
        options.forEach { (key, value) -> add("$key=$value") }
    }.joinToString(" ")
    override fun toString(): String = "BridgeLine(${protocol.name}, [redacted])"
}

class BridgeValidationException(val lineNumber: Int) : IllegalArgumentException("Invalid bridge at line " + lineNumber)

object BridgeParser {
    fun parse(text: String): List<BridgeLine> {
        require(text.length <= 65536) { "Bridge list is too long" }
        val lines = text.lineSequence().mapIndexed { index, line -> (index + 1) to line.trim() }
            .filter { (_, line) -> line.isNotEmpty() && !line.startsWith('#') }.toList()
        require(lines.size <= 64) { "Too many bridges" }
        return lines.map { (lineNumber, line) ->
            try { parseLine(line) } catch (_: Exception) { throw BridgeValidationException(lineNumber) }
        }
    }

    private fun parseLine(line: String): BridgeLine {
        require(line.none { it.isISOControl() && it != '\t' })
        val words = line.split(Regex("[ \\t]+" )).toMutableList()
        if (words.firstOrNull() == "Bridge") words.removeAt(0)
        val protocol = when (words.firstOrNull()) {
            "obfs4" -> BridgeProtocol.OBFS4.also { words.removeAt(0) }
            "webtunnel" -> BridgeProtocol.WEBTUNNEL.also { words.removeAt(0) }
            else -> BridgeProtocol.PLAIN
        }
        require(words.size >= 2)
        val address = words.removeAt(0)
        validateAddress(address)
        val fingerprint = words.removeAt(0)
        require(fingerprint.matches(Regex("[a-fA-F0-9]{40}")))
        val options = linkedMapOf<String, String>()
        for (word in words) {
            val parts = word.split('=', limit = 2)
            require(parts.size == 2 && parts[0] !in options && parts[1].isNotEmpty())
            options[parts[0]] = parts[1]
        }
        when (protocol) {
            BridgeProtocol.PLAIN -> require(options.isEmpty())
            BridgeProtocol.OBFS4 -> {
                require(options.keys.all { it in setOf("cert", "iat-mode") })
                require(Base64.getDecoder().decode(requireNotNull(options["cert"])).size == 52)
                require((options["iat-mode"] ?: "0") in setOf("0", "1", "2"))
            }
            BridgeProtocol.WEBTUNNEL -> {
                require(options.keys.all { it in setOf("url", "ver") })
                val url = URI(requireNotNull(options["url"]))
                require(url.scheme == "https" && !url.host.isNullOrEmpty() && url.userInfo == null && url.fragment == null)
                require(url.port == -1 || url.port in 1..65535)
                options["ver"]?.let { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) }
            }
        }
        return BridgeLine(protocol, address, fingerprint.uppercase(), options.toMap())
    }

    private fun validateAddress(address: String) {
        val split = address.lastIndexOf(':')
        require(split > 0 && address.substring(split + 1).toInt() in 1..65535)
        val host = address.substring(0, split)
        if (host.startsWith('[') && host.endsWith(']')) {
            val literal = host.substring(1, host.length - 1)
            require(':' in literal && literal.matches(Regex("[a-fA-F0-9:]+")))
            require(InetAddress.getByName(literal).address.size == 16)
        } else {
            val octets = host.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
        }
    }
}
