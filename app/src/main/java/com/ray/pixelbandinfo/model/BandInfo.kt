package com.ray.pixelbandinfo.model

enum class NetworkTechnology(val label: String) {
    NR_SA("NR5G SA"),
    NR_NSA("NR5G NSA"),
    LTE("LTE"),
    HSPA("HSPA"),
    GSM("GSM"),
    UNKNOWN("Unknown")
}

data class SignalMetrics(
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val level: Int = 0,
    val is5g: Boolean = false
)

enum class ReceptionQuality(val label: String, val colorArgb: Long) {
    EXCELLENT("Excellent", 0xFF00E676L),
    GOOD("Good", 0xFF8BC34AL),
    FAIR("Fair", 0xFFFFD54FL),
    POOR("Poor", 0xFFFF9100L),
    BAD("Bad", 0xFFEF5350L),
    UNKNOWN("----", 0xFFB0A4D6L)
}

val SignalMetrics.quality: ReceptionQuality
    get() {
        val r = rsrp ?: return when (level) {
            4 -> ReceptionQuality.EXCELLENT
            3 -> ReceptionQuality.GOOD
            2 -> ReceptionQuality.FAIR
            1 -> ReceptionQuality.POOR
            else -> ReceptionQuality.BAD
        }
        return when {
            r >= -85 -> ReceptionQuality.EXCELLENT
            r >= -98 -> ReceptionQuality.GOOD
            r >= -108 -> ReceptionQuality.FAIR
            r >= -118 -> ReceptionQuality.POOR
            else -> ReceptionQuality.BAD
        }
    }

data class BandInfo(
    val technology: NetworkTechnology,
    val band: Int? = null,
    val bandwidthDownMhz: Int? = null,
    val bandwidthUpMhz: Int? = null,
    val earfcn: Int? = null,
    val pci: Int? = null,
    val tac: Int? = null,
    val isAnchor: Boolean = false,
    val signalMetrics: SignalMetrics? = null
)

val BandInfo.displayLabel: String
    get() {
        val freqMhz = band?.let { b ->
            when (b) {
                1 -> "2100MHz"
                2 -> "1900MHz"
                3 -> "1800MHz"
                4 -> "1700/2100MHz"
                5 -> "850MHz"
                7 -> "2600MHz"
                8 -> "900MHz"
                12, 13, 14, 17, 28 -> "700MHz"
                20 -> "800MHz"
                25 -> "1900MHz"
                26 -> "850MHz"
                38 -> "2600MHz"
                40 -> "2300MHz"
                41 -> "2500MHz"
                42 -> "3500MHz"
                48 -> "3600MHz"
                66 -> "1700/2100MHz"
                71 -> "600MHz"
                77 -> "3700MHz"
                78 -> "3500MHz"
                79 -> "4700MHz"
                257, 261 -> "28GHz"
                258 -> "26GHz"
                260 -> "39GHz"
                else -> null
            }
        }

        val prefix = when (technology) {
            NetworkTechnology.NR_SA -> if (band != null) "NR SA n$band" else "NR SA ----"
            NetworkTechnology.NR_NSA -> if (band != null) "NR NSA n$band" else "NR NSA ----"
            NetworkTechnology.LTE -> if (band != null) "LTE B$band" else "LTE ----"
            NetworkTechnology.HSPA -> if (band != null) "HSPA B$band" else "HSPA ----"
            NetworkTechnology.GSM -> if (band != null) "GSM B$band" else "GSM ----"
            NetworkTechnology.UNKNOWN -> if (band != null) "B$band" else "----"
        }

        return if (freqMhz != null) "$prefix ($freqMhz)" else prefix
    }

val BandInfo.channelDetailsText: String
    get() {
        val channelLabel = when (technology) {
            NetworkTechnology.NR_SA, NetworkTechnology.NR_NSA -> "NRARFCN"
            else -> "EARFCN"
        }
        val channelStr = earfcn?.let { "$it" } ?: "----"
        return if (isAnchor) {
            val pciStr = pci?.let { "$it" } ?: "----"
            val tacStr = tac?.let { "$it" } ?: "----"
            "$channelLabel:$channelStr PCI:$pciStr TAC:$tacStr"
        } else {
            // Secondary component carrier in Carrier Aggregation:
            // TAC does not exist in 3GPP specs for SCells. Only show PCI if provided by the modem.
            val pciPart = pci?.let { " PCI:$it" } ?: ""
            "$channelLabel:$channelStr$pciPart"
        }
    }

enum class NrAvailability {
    NONE,
    STANDBY_ULI,
    CONNECTED
}

data class QciInfo(
    val apnName: String,
    val qci: Int?,
    val apnTypes: List<String> = emptyList()
)

data class SimSlotState(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String? = null,
    val carrierName: String? = null,
    val plmn: String? = null,
    val isActiveDataSim: Boolean = false,
    val technologyName: String = "No Service",
    val bands: List<BandInfo> = emptyList(),
    val qciList: List<QciInfo> = emptyList(),
    val nrAvailability: NrAvailability = NrAvailability.NONE
)
