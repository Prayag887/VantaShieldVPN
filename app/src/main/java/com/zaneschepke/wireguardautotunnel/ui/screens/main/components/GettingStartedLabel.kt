package com.zaneschepke.wireguardautotunnel.ui.screens.main.components

import android.net.VpnService
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelManager
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelStatus
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConf
import com.zaneschepke.wireguardautotunnel.domain.state.TunnelState
import com.zaneschepke.wireguardautotunnel.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class VpnCountry(
    val name: String,
    val configFileName: String,
    val flag: String,
    val config: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnCountrySelector(tunnelProvider: TunnelManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var countries by remember { mutableStateOf<List<VpnCountry>>(emptyList()) }
    var selectedCountry by remember { mutableStateOf<VpnCountry?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingConfigs by remember { mutableStateOf(true) } // Add loading state for configs
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var hasVpnPermission by remember { mutableStateOf(false) }
    var activeTunnel by remember { mutableStateOf<TunnelConf?>(null) }
    var tunnelStates by remember { mutableStateOf<Map<TunnelConf, TunnelState>>(emptyMap()) }
    var configError by remember { mutableStateOf<String?>(null) } // Add error state

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        hasVpnPermission = result.resultCode == android.app.Activity.RESULT_OK
        connectionStatus = if (hasVpnPermission) {
            "VPN permission granted"
        } else {
            "VPN permission denied"
        }
    }

    // Check VPN permission on startup
    LaunchedEffect(Unit) {
        val intent = VpnService.prepare(context)
        hasVpnPermission = intent == null
        if (!hasVpnPermission) {
            connectionStatus = "VPN permission required"
            intent?.let { vpnPermissionLauncher.launch(it) }
        }
    }

// Load configuration files
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                val countryConfigs = listOf(
                    Triple("Germany", "germany.conf", "🇩🇪"),
                    Triple("Japan", "japan.conf", "🇯🇵"),
                    Triple("USA", "usa.conf", "🇺🇸"),
                    Triple("Singapore", "singapore.conf", "🇸🇬")
                )

                val loadedCountries = countryConfigs.mapNotNull { (name, fileName, flag) ->
                    runCatching {
                        context.assets.open("wireguard_configs/$fileName").use { inputStream ->
                            val config = inputStream.bufferedReader().use { it.readText() }
                            Timber.tag("VpnCountrySelector")
                                .d("Loaded config for $name (${config.length} chars)")
                            VpnCountry(name, fileName, flag, config)
                        }
                    }.onFailure { e ->
                        Log.e("VpnCountrySelector", "Failed to load $fileName: ${e.message}", e)
                    }.getOrNull()
                }

                Log.d("VpnCountrySelector", "Total countries loaded: ${loadedCountries.size}")

                // Update state on main thread
                withContext(Dispatchers.Main) {
                    countries = loadedCountries
                    isLoadingConfigs = false
                    if (loadedCountries.isEmpty()) {
                        configError = "No VPN configurations found. Please ensure .conf files are in assets/wireguard_configs/"
                    }
                    Log.d("VpnCountrySelector", "State updated with ${countries.size} countries")
                }
            }
        } catch (e: Exception) {
            Log.e("VpnCountrySelector", "Error loading configurations: ${e.message}", e)
            withContext(Dispatchers.Main) {
                isLoadingConfigs = false
                configError = "Error loading configurations: ${e.message}"
                connectionStatus = "Error loading configurations"
            }
        }
    }

    // Collect tunnel states
    LaunchedEffect(tunnelProvider) {
        tunnelProvider.activeTunnels.collect { tunnels ->
            tunnelStates = tunnels

            // Update active tunnel and connection status
            tunnels.forEach { (tunnel, state) ->
                if (state.status == TunnelStatus.Up) {
                    activeTunnel = tunnel
                    connectionStatus = "Connected to ${tunnel.name}"
                } else if (tunnel == activeTunnel && state.status == TunnelStatus.Down) {
                    activeTunnel = null
                    connectionStatus = "Disconnected"
                }
            }
        }
    }

    // Get connection status for current selection
    val isConnected = selectedCountry?.let { country ->
        tunnelStates.any { (tunnel, state) ->
            tunnel.name == country.name && state.status == TunnelStatus.Up
        }
    } == true

    suspend fun connectVpn(country: VpnCountry): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Parse the WireGuard config using Amnezia's parser
                val amConfig = TunnelConf.configFromAmQuick(country.config)

                // Create country-specific tunnel configuration
                val tunnelConf = TunnelConf.createCountryTunnel(
                    config = amConfig,
                    countryCode = country.name,
                    flagEmoji = country.flag,
                    serverLocation = country.name,
                    name = country.name
                )

                tunnelProvider.startTunnel(tunnelConf)
                true
            } catch (e: Exception) {
                connectionStatus = "Connection failed: ${e.message}"
                false
            }
        }
    }

    suspend fun disconnectVpn(country: VpnCountry) {
        withContext(Dispatchers.IO) {
            try {
                // Parse the WireGuard config using Amnezia's parser
                val amConfig = TunnelConf.configFromAmQuick(country.config)

                // Create country-specific tunnel configuration
                val tunnelConf = TunnelConf.createCountryTunnel(
                    config = amConfig,
                    countryCode = country.name,
                    flagEmoji = country.flag,
                    serverLocation = country.name,
                    name = country.name
                )

                tunnelProvider.stopTunnel(tunnelConf)
            } catch (e: Exception) {
                connectionStatus = "Disconnection failed: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "WireGuard VPN",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = connectionStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isConnected) Color.Green else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Connection Control
        selectedCountry?.let { country ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = country.flag,
                            fontSize = 24.sp,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Column {
                            Text(
                                text = "Selected: ${country.name}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isConnected) "Connected" else "Ready to connect",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isConnected) Color.Green else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (!hasVpnPermission) {
                                val intent = VpnService.prepare(context)
                                intent?.let { vpnPermissionLauncher.launch(it) }
                                return@Button
                            }

                            scope.launch {
                                isLoading = true
                                try {
                                    if (isConnected) {
                                        disconnectVpn(country)
                                    } else {
                                        connectVpn(country)
                                    }
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(if (isConnected) "Disconnect" else "Connect")
                        }
                    }
                }
            }
        }

        // Country List Title
        Text(
            text = "Select VPN Server Location",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        // Country List with proper loading and error states
        when {
            isLoadingConfigs -> {
                // Loading state
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Loading VPN configurations...")
                    }
                }
            }

            configError != null -> {
                // Error state
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = configError!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            countries.isNotEmpty() -> {
                // Success state - show countries
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 200.dp),

                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Log.d("VpnCountrySelector", "LazyColumn rendering with ${countries.size} countries")

                    items(
                        items = countries,
                        key = { country -> country.name }
                    ) { country ->
                        Log.d("VpnCountrySelector", "Rendering country: ${country.name}")

                        val isSelected = selectedCountry?.name == country.name
                        val isCountryConnected = tunnelStates.any { (tunnel, state) ->
                            tunnel.name == country.name && state.status == TunnelStatus.Up
                        }

                        CountryCard(
                            country = country,
                            isSelected = isSelected,
                            isConnected = isCountryConnected,
                            onClick = {
                                if (!isCountryConnected) {
                                    selectedCountry = country
                                    Log.d("VpnCountrySelector", "Selected country: ${country.name}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryCard(
    country: VpnCountry,
    isSelected: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnected) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 2.dp
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = country.flag,
                    fontSize = 28.sp,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Column {
                    Text(
                        text = country.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            isConnected -> "Connected"
                            isSelected -> "Selected"
                            else -> "Available"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isConnected -> Color.Green
                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}