package com.ray.pixelbandinfo.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ray.pixelbandinfo.model.BandInfo
import com.ray.pixelbandinfo.model.NetworkTechnology
import com.ray.pixelbandinfo.model.QciInfo
import com.ray.pixelbandinfo.model.SimSlotState
import com.ray.pixelbandinfo.model.displayLabel
import com.ray.pixelbandinfo.model.channelDetailsText
import com.ray.pixelbandinfo.model.SignalMetrics
import com.ray.pixelbandinfo.model.quality
import kotlin.coroutines.cancellation.CancellationException

val DeepPurpleBackground = Color(0xFF0D061A)
val HeaderPurple = Color(0xFFA28BFF)
val HeaderTextDark = Color(0xFF13092A)
val TextWhite = Color(0xFFFFFFFF)
val TextMutedLavender = Color(0xFFC7BDED)
val FabPurple = Color(0xFFB39DFF)
val SwitchActivePurple = Color(0xFF7A51E3)

data class AppSettingsState(
    val keepScreenOn: Boolean = true,
    val showQciInfo: Boolean = true,
    val onlyShowActiveSimInPip: Boolean = true,
    val hideDetailsInPip: Boolean = false
)

@Composable
fun HomeScreen(
    simSlotStates: List<SimSlotState>,
    isShizukuGranted: Boolean,
    isInPip: Boolean,
    settings: AppSettingsState,
    onSettingsChanged: (AppSettingsState) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onEnterPip: () -> Unit,
    onRefresh: () -> Unit
) {
    var showSettingsScreen by remember { mutableStateOf(false) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    if (showSettingsScreen) {
        PredictiveBackHandler(enabled = true) { progress ->
            try {
                progress.collect { backEvent ->
                    backProgress = backEvent.progress
                }
                showSettingsScreen = false
                backProgress = 0f
            } catch (_: CancellationException) {
                backProgress = 0f
            }
        }
    }

    val scale = 1f - (backProgress * 0.12f)
    val alpha = 1f - (backProgress * 0.35f)
    val cornerRadius = (backProgress * 28).dp

    if (isInPip) {
        PipView(
            simSlotStates = simSlotStates,
            settings = settings
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepPurpleBackground)
        ) {
            MainContentView(
                simSlotStates = simSlotStates,
                isShizukuGranted = isShizukuGranted,
                settings = settings,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onEnterPip = onEnterPip,
                onRefresh = onRefresh,
                onOpenSettings = { showSettingsScreen = true }
            )

            if (showSettingsScreen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .clip(RoundedCornerShape(cornerRadius))
                ) {
                    SettingsScreen(
                        settings = settings,
                        onSettingsChanged = onSettingsChanged,
                        onBack = { showSettingsScreen = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContentView(
    simSlotStates: List<SimSlotState>,
    isShizukuGranted: Boolean,
    settings: AppSettingsState,
    onRequestShizukuPermission: () -> Unit,
    onEnterPip: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        containerColor = DeepPurpleBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeaderPurple,
                    titleContentColor = HeaderTextDark,
                    actionIconContentColor = HeaderTextDark
                ),
                title = {
                    Text(
                        "Pixel BandInfo Beta v1.2.0",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(26.dp))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(26.dp))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onEnterPip,
                containerColor = FabPurple,
                contentColor = HeaderTextDark,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 16.dp, end = 8.dp)
            ) {
                Icon(Icons.Default.PictureInPicture, contentDescription = "PiP Mode", modifier = Modifier.size(28.dp))
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepPurpleBackground)
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (!isShizukuGranted) {
                    ShizukuBanner(onRequestPermission = onRequestShizukuPermission)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (simSlotStates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No active SIM cards detected.", color = TextMutedLavender, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(simSlotStates) { slotState ->
                            SimSlotSection(
                                slotState = slotState,
                                showQciInfo = settings.showQciInfo
                            )
                        }
                    }
                }

                Text(
                    text = "Copyright © 2026 Raymond Wang",
                    color = TextMutedLavender,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun PipView(
    simSlotStates: List<SimSlotState>,
    settings: AppSettingsState
) {
    val displayStates = if (settings.onlyShowActiveSimInPip) {
        simSlotStates.filter { it.isActiveDataSim }.ifEmpty {
            simSlotStates.filter { it.bands.isNotEmpty() }.ifEmpty { simSlotStates.take(1) }
        }
    } else {
        simSlotStates
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepPurpleBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            displayStates.forEach { slotState ->
                val totalBwMhz = slotState.bands.mapNotNull { it.bandwidthDownMhz }.sum()
                val bwStr = if (totalBwMhz > 0) " $totalBwMhz MHz" else ""
                val techStr = if (slotState.technologyName != "No Service" && slotState.technologyName != "Unknown") " ${slotState.technologyName}" else ""
                val carrierTitle = slotState.carrierName?.takeIf { it.isNotBlank() }
                    ?: slotState.displayName?.takeIf { it.isNotBlank() }
                    ?: "SIM ${slotState.slotIndex + 1}"
                val activeMarker = if (slotState.isActiveDataSim) "*" else ""
                Text(
                    text = "$carrierTitle (SIM ${slotState.slotIndex + 1})$activeMarker$techStr$bwStr",
                    color = TextWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                if (!slotState.plmn.isNullOrBlank()) {
                    Text(
                        text = "PLMN ${slotState.plmn}",
                        color = TextMutedLavender,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                }

                if (slotState.bands.isEmpty()) {
                    Text("No active frequency band data reported.", color = TextMutedLavender, fontSize = 12.sp)
                } else {
                    slotState.bands.forEach { band ->
                        val bwLabel = band.bandwidthDownMhz?.let { "${it}MHz" } ?: "--MHz"

                        Text(
                            text = "${band.displayLabel} BW:$bwLabel",
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (!settings.hideDetailsInPip && band.channelDetailsText.isNotBlank()) {
                            Text(
                                text = band.channelDetailsText,
                                color = TextMutedLavender,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }

                        if (!settings.hideDetailsInPip && band.signalMetrics != null) {
                            SignalQualityBadge(metrics = band.signalMetrics, isCompact = true)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettingsState,
    onSettingsChanged: (AppSettingsState) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = DeepPurpleBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeaderPurple,
                    titleContentColor = HeaderTextDark,
                    navigationIconContentColor = HeaderTextDark
                ),
                title = {
                    Text(
                        "Settings",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(26.dp))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // General
                SettingsHeader("General")
                Spacer(modifier = Modifier.height(12.dp))

                SettingsSwitchItem(
                    title = "Keep Screen On",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { onSettingsChanged(settings.copy(keepScreenOn = it)) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                SettingsSwitchItem(
                    title = "Show QCI/APN Info",
                    checked = settings.showQciInfo,
                    onCheckedChange = { onSettingsChanged(settings.copy(showQciInfo = it)) }
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Picture-in-Picture
                SettingsHeader("Picture-in-Picture")
                Spacer(modifier = Modifier.height(12.dp))

                SettingsSwitchItem(
                    title = "Only Show Active SIM in PiP",
                    checked = settings.onlyShowActiveSimInPip,
                    onCheckedChange = { onSettingsChanged(settings.copy(onlyShowActiveSimInPip = it)) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                SettingsSwitchItem(
                    title = "Hide Details in PiP",
                    checked = settings.hideDetailsInPip,
                    onCheckedChange = { onSettingsChanged(settings.copy(hideDetailsInPip = it)) }
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Credits
                SettingsHeader("Credits")
                Spacer(modifier = Modifier.height(12.dp))

                SettingsTextItem(
                    title = "Kyujin Cho for Pixel IMS"
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsTextItem(
                    title = "Google for the Android Open Source Project"
                )
            }

            Text(
                text = "Copyright © 2026 Raymond Wang",
                color = TextMutedLavender,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun SettingsHeader(text: String) {
    Text(
        text = text,
        color = TextMutedLavender,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = TextWhite,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextWhite,
                checkedTrackColor = SwitchActivePurple,
                uncheckedThumbColor = TextMutedLavender,
                uncheckedTrackColor = DeepPurpleBackground
            )
        )
    }
}

@Composable
fun SettingsTextItem(title: String) {
    Text(
        text = title,
        color = TextWhite,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun ShizukuBanner(onRequestPermission: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Shizuku Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Pixel BandInfo requires Shizuku permission to read detailed modem frequency band & QCI values without root.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Shizuku Access")
            }
        }
    }
}

@Composable
fun SimSlotSection(
    slotState: SimSlotState,
    showQciInfo: Boolean
) {
    val totalBwMhz = slotState.bands.mapNotNull { it.bandwidthDownMhz }.sum()
    val bwStr = if (totalBwMhz > 0) " $totalBwMhz MHz" else ""
    val techStr = if (slotState.technologyName != "No Service" && slotState.technologyName != "Unknown") " ${slotState.technologyName}" else ""
    val carrierTitle = slotState.carrierName?.takeIf { it.isNotBlank() }
        ?: slotState.displayName?.takeIf { it.isNotBlank() }
        ?: "SIM ${slotState.slotIndex + 1}"
    val activeMarker = if (slotState.isActiveDataSim) "*" else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Header
        Text(
            text = "$carrierTitle (SIM ${slotState.slotIndex + 1})$activeMarker$techStr$bwStr",
            color = TextWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // PLMN
        if (!slotState.plmn.isNullOrBlank()) {
            Text(
                text = "PLMN ${slotState.plmn}",
                color = TextWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // QCI & APN List
        if (showQciInfo) {
            if (slotState.qciList.isNotEmpty()) {
                slotState.qciList.forEach { qciInfo ->
                    QciItem(qciInfo = qciInfo)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Bands List
        if (slotState.bands.isEmpty()) {
            Text(
                text = "No active frequency band data reported.",
                color = TextMutedLavender,
                fontSize = 16.sp
            )
        } else {
            slotState.bands.forEach { band ->
                BandItem(band = band)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun BandItem(band: BandInfo) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val bwStr = band.bandwidthDownMhz?.let { "${it}MHz" } ?: "--MHz"

        Text(
            text = "${band.displayLabel} BW:$bwStr",
            color = TextWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        if (band.channelDetailsText.isNotBlank()) {
            Text(
                text = band.channelDetailsText,
                color = TextMutedLavender,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal
            )
        }

        if (band.signalMetrics != null) {
            SignalQualityBadge(metrics = band.signalMetrics, isCompact = false)
        }
    }
}

@Composable
fun QciItem(qciInfo: QciInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        val qciStr = qciInfo.qci?.let { "$it" } ?: "----"
        Text(
            text = "APN:${qciInfo.apnName} QCI:$qciStr",
            color = TextWhite,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
        if (qciInfo.apnTypes.isNotEmpty()) {
            Text(
                text = "TYPES:${qciInfo.apnTypes}",
                color = TextMutedLavender,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
fun SignalQualityBadge(
    metrics: SignalMetrics,
    isCompact: Boolean = false
) {
    val quality = metrics.quality
    val qualityColor = Color(quality.colorArgb)

    val labelPrefix = if (metrics.is5g) "SS-" else ""
    val parts = mutableListOf<String>()
    metrics.rsrp?.let { parts.add("$labelPrefix" + "RSRP:${it}dBm") }
    metrics.rsrq?.let { parts.add("RSRQ:${it}dB") }
    metrics.sinr?.let { parts.add("SINR:${it}dB") }

    val metricsText = parts.joinToString("  ")

    Row(
        modifier = Modifier.padding(top = if (isCompact) 1.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(qualityColor.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                .padding(horizontal = if (isCompact) 5.dp else 6.dp, vertical = if (isCompact) 1.dp else 2.dp)
        ) {
            Text(
                text = quality.label,
                color = qualityColor,
                fontSize = if (isCompact) 10.sp else 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (metricsText.isNotBlank()) {
            Text(
                text = metricsText,
                color = TextWhite,
                fontSize = if (isCompact) 11.sp else 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
