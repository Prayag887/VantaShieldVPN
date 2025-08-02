package com.zaneschepke.wireguardautotunnel

import android.content.Context
import com.zaneschepke.wireguardautotunnel.di.IoDispatcher
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConf
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber

class AssetConfigLoader @Inject constructor(
    private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun loadConfigsFromAssets(): Result<List<TunnelConf>> = withContext(ioDispatcher) {
        runCatching {
            val configFiles = context.assets.list("wireguard_configs") ?: emptyArray()
            val confFiles = configFiles.filter { it.endsWith(".conf") }

            // Only log count, not individual file names
            Timber.d("Loading ${confFiles.size} config files")

            val tunnelConfigs = mutableListOf<TunnelConf>()

            confFiles.forEach { fileName ->
                try {
                    val configContent = context.assets.open("wireguard_configs/$fileName").use { inputStream ->
                        inputStream.bufferedReader().readText()
                    }

                    // Remove excessive logging - only log file size, not content
                    Timber.v("Processing $fileName (${configContent.length} chars)")

                    val tunnelConf = parseConfigContent(configContent, fileName)
                    val tunnelName = fileName.substringBeforeLast(".conf")
                    val finalTunnel = tunnelConf.copy(tunName = tunnelName)

                    tunnelConfigs.add(finalTunnel)

                } catch (e: Exception) {
                    // Reduce logging verbosity
                    Timber.w("Failed to load $fileName: ${e.message}")
                }
            }

            Timber.d("Loaded ${tunnelConfigs.size} configs successfully")
            tunnelConfigs
        }
    }

    private fun parseConfigContent(configContent: String, fileName: String): TunnelConf {
        // Try AmQuick first (since it seems to be preferred)
        return try {
            val amConfig = TunnelConf.configFromAmQuick(configContent)
            TunnelConf.tunnelConfigFromAmConfig(amConfig)
        } catch (e: Exception) {
            // Only try fallback if absolutely necessary
            Timber.v("Trying fallback parser for $fileName")
            try {
                parseWireGuardConfig(configContent, fileName)
            } catch (e2: Exception) {
                Timber.e("All parsing failed for $fileName: ${e2.message}")
                throw e2
            }
        }!!
    }
}