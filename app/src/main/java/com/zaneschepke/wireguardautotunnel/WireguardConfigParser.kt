package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConf

fun parseWireGuardConfig(
    configText: String,
    countryCode: String? = null,
    countryInfo: CountryInfo? = null
): TunnelConf? {
    return try {
        // Parse the configuration text to extract interface and peer data
        val lines = configText.lines()
        val interfaceData = mutableMapOf<String, String>()
        val peerData = mutableMapOf<String, String>()

        var currentSection = ""

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("[Interface]") -> currentSection = "interface"
                trimmedLine.startsWith("[Peer]") -> currentSection = "peer"
                trimmedLine.contains("=") -> {
                    val (key, value) = trimmedLine.split("=", limit = 2)
                    when (currentSection) {
                        "interface" -> interfaceData[key.trim()] = value.trim()
                        "peer" -> peerData[key.trim()] = value.trim()
                    }
                }
            }
        }

        // Extract endpoint for location and name generation
        val endpoint = peerData["Endpoint"] ?: ""

        // Generate tunnel name
        val tunnelName = countryInfo?.let { "${it.flagEmoji} ${it.countryName}" }
            ?: extractNameFromEndpoint(endpoint)

        // Create the TunnelConf object with the correct parameters
        TunnelConf(
            tunName = tunnelName,
            wgQuick = configText, // Use the original config text
            amQuick = configText, // Use the original config text (or convert if needed)
            countryCode = countryCode,
            flagEmoji = countryInfo?.flagEmoji,
            serverLocation = extractLocationFromEndpoint(endpoint)
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// Helper data class for country information
data class CountryInfo(
    val countryName: String,
    val flagEmoji: String
)

// Helper function to extract name from endpoint
private fun extractNameFromEndpoint(endpoint: String): String {
    return try {
        val host = endpoint.split(":").firstOrNull() ?: "Unknown"
        "Tunnel - $host"
    } catch (e: Exception) {
        "Unknown Tunnel"
    }
}

// Helper function to extract location from endpoint
private fun extractLocationFromEndpoint(endpoint: String): String? {
    return try {
        val host = endpoint.split(":").firstOrNull()
        // You could implement reverse DNS lookup or use a database
        // to map IP addresses to locations
        host
    } catch (e: Exception) {
        null
    }
}

// Alternative factory method using the existing createCountryTunnel
fun parseWireGuardConfigWithFactory(
    configText: String,
    countryCode: String,
    flagEmoji: String? = null,
    serverLocation: String? = null,
    name: String? = null
): TunnelConf? {
    return try {
        // Parse the config text into an AmneziaWG config object
        val config = TunnelConf.configFromAmQuick(configText)

        // Use the existing factory method
        TunnelConf.createCountryTunnel(
            config = config,
            countryCode = countryCode,
            flagEmoji = flagEmoji,
            serverLocation = serverLocation,
            name = name
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}