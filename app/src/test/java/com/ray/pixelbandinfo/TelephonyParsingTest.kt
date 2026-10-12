package com.ray.pixelbandinfo

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.ray.pixelbandinfo.model.BandInfo
import com.ray.pixelbandinfo.model.NetworkTechnology
import com.ray.pixelbandinfo.model.displayLabel
import com.ray.pixelbandinfo.model.channelDetailsText
import com.ray.pixelbandinfo.model.sortedForDisplay
import com.ray.pixelbandinfo.shizuku.ShizukuTelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class TelephonyParsingTest {

    private lateinit var mockContext: Context
    private lateinit var mockTelephonyManager: TelephonyManager
    private lateinit var mockSubscriptionManager: SubscriptionManager

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockTelephonyManager = mock(TelephonyManager::class.java)
        mockSubscriptionManager = mock(SubscriptionManager::class.java)

        `when`(mockContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mockTelephonyManager)
        `when`(mockContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)).thenReturn(mockSubscriptionManager)
    }

    @Test
    fun testEarfcnToBandMapping() {
        val manager = ShizukuTelephonyManager(mockContext)

        assertEquals(28, manager.earfcnToBand(9260))
        assertEquals(3, manager.earfcnToBand(1800))
        assertEquals(7, manager.earfcnToBand(3250))
        assertEquals(1, manager.earfcnToBand(300))
        assertEquals(5, manager.earfcnToBand(2500))
    }

    @Test
    fun testBandDisplayLabelFormat() {
        val b28 = BandInfo(technology = NetworkTechnology.LTE, band = 28)
        assertEquals("LTE B28 (700MHz)", b28.displayLabel)

        val b3 = BandInfo(technology = NetworkTechnology.LTE, band = 3)
        assertEquals("LTE B3 (1800MHz)", b3.displayLabel)

        val b7 = BandInfo(technology = NetworkTechnology.LTE, band = 7)
        assertEquals("LTE B7 (2600MHz)", b7.displayLabel)

        val n78Sa = BandInfo(technology = NetworkTechnology.NR_SA, band = 78)
        assertEquals("NR SA n78 (3500MHz)", n78Sa.displayLabel)

        val n78Nsa = BandInfo(technology = NetworkTechnology.NR_NSA, band = 78)
        assertEquals("NR NSA n78 (3500MHz)", n78Nsa.displayLabel)
    }

    @Test
    fun testChannelDetailsTextForPrimaryVsSecondaryBands() {
        val primary = BandInfo(
            technology = NetworkTechnology.LTE,
            band = 28,
            earfcn = 9260,
            pci = 470,
            tac = 6331,
            isAnchor = true
        )
        assertEquals("EARFCN:9260 PCI:470 TAC:6331", primary.channelDetailsText)

        val secondaryWithPci = BandInfo(
            technology = NetworkTechnology.LTE,
            band = 3,
            earfcn = 1800,
            pci = 123,
            isAnchor = false
        )
        assertEquals("EARFCN:1800 PCI:123", secondaryWithPci.channelDetailsText)

        val secondaryNoPci = BandInfo(
            technology = NetworkTechnology.LTE,
            band = 7,
            earfcn = 3250,
            pci = null,
            isAnchor = false
        )
        assertEquals("EARFCN:3250", secondaryNoPci.channelDetailsText)
    }

    @Test
    fun testCarrierAggregationInactiveVsActive() {
        val manager = ShizukuTelephonyManager(mockContext)
        val cellBwsKhz = intArrayOf(10000, 20000, 20000) // 10MHz + 20MHz + 20MHz

        val bands = manager.buildBandsFromCellInfo(emptyList(), cellBwsKhz)
        assertEquals(1, bands.size)
        assertEquals(10, bands[0].bandwidthDownMhz)
    }

    @Test
    fun testAirplaneModeAndNoServiceReturnsEmptyBands() {
        val manager = ShizukuTelephonyManager(mockContext)
        val bands = manager.buildBandsFromCellInfo(emptyList(), intArrayOf())
        assertEquals(0, bands.size)
    }

    @Test
    fun testQciParsingFromDumpsys() {
        val manager = ShizukuTelephonyManager(mockContext)
        val mockDumpsys = """
            mPreciseDataConnectionStates={Pair{1 [ApnSetting] IMS, 3, 42503, ims, , null, , null, null, 0, ims, IPV4V6, IPV4V6, true, 0, false, 0, 0, 0, 0, 0, null, , false, LTE|IWLAN|LTE_CA|NR, UNKNOWN, 0, -1, -1, false, 3, 961, false, UNEDITED}= state: CONNECTED, APN Setting: [ApnSetting] IMS, 3, 42503, ims, , null, , null, null, 0, ims, IPV4V6, IPV4V6, true, 0, false, 0, 0, 0, 0, 0, null, , false, LTE|IWLAN|LTE_CA|NR, UNKNOWN, 0, -1, -1, false, 3, 961, false, UNEDITED, default QoS: EpsQos { qosClassId=5 downlink=Bandwidth { maxBitrateKbps=2000000 guaranteedBitrateKbps=0} uplink=Bandwidth { maxBitrateKbps=1000000 guaranteedBitrateKbps=0}}, fail cause: NONE(0x0), Pair{2 [ApnSetting] Sphone Pelephone, 2, 42503, sphone.pelephone.net.il, , null, , null, null, 2, supl | hipri | default, IP, IP, true, 0, false, 0, 0, 0, 0, 0, null, , false, UNKNOWN, UNKNOWN, 0, -1, -1, false, 3, -742706017, false, UNEDITED}= state: CONNECTED, APN Setting: [ApnSetting] Sphone Pelephone, 2, 42503, sphone.pelephone.net.il, , null, , null, null, 2, supl | hipri | default, IP, IP, true, 0, false, 0, 0, 0, 0, 0, null, , false, UNKNOWN, UNKNOWN, 0, -1, -1, false, 3, -742706017, false, UNEDITED, default QoS: EpsQos { qosClassId=8 downlink=Bandwidth { maxBitrateKbps=2000000 guaranteedBitrateKbps=0} uplink=Bandwidth { maxBitrateKbps=1000000 guaranteedBitrateKbps=0}}}
        """.trimIndent()

        val qciList = manager.parseQciFromDumpsysText(mockDumpsys)
        assertNotNull(qciList)
        assertEquals(2, qciList.size)

        val sphoneQci = qciList.firstOrNull { it.apnName.contains("sphone", ignoreCase = true) }
        assertNotNull(sphoneQci)
        assertEquals(8, sphoneQci?.qci)

        val imsQci = qciList.firstOrNull { it.apnName.contains("ims", ignoreCase = true) }
        assertNotNull(imsQci)
        assertEquals(5, imsQci?.qci)
    }

    @Test
    fun testResolveNrAvailability() {
        val manager = ShizukuTelephonyManager(mockContext)

        // 1. Physical NR carrier active -> CONNECTED
        assertEquals(
            com.ray.pixelbandinfo.model.NrAvailability.CONNECTED,
            manager.resolveNrAvailability(
                hasPhysicalNr = true,
                overrideNetworkType = 0,
                serviceState = null
            )
        )

        // 2. Physical NR carrier NOT active, but TelephonyDisplayInfo indicates NR_NSA (3) -> STANDBY_ULI
        assertEquals(
            com.ray.pixelbandinfo.model.NrAvailability.STANDBY_ULI,
            manager.resolveNrAvailability(
                hasPhysicalNr = false,
                overrideNetworkType = 3, // OVERRIDE_NETWORK_TYPE_NR_NSA
                serviceState = null
            )
        )

        // 3. Physical NR carrier NOT active, but TelephonyDisplayInfo indicates NR_NSA_MMWAVE (4) -> STANDBY_ULI
        assertEquals(
            com.ray.pixelbandinfo.model.NrAvailability.STANDBY_ULI,
            manager.resolveNrAvailability(
                hasPhysicalNr = false,
                overrideNetworkType = 4, // OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
                serviceState = null
            )
        )

        // 4. Physical NR carrier NOT active, but TelephonyDisplayInfo indicates NR_ADVANCED (5) -> STANDBY_ULI
        assertEquals(
            com.ray.pixelbandinfo.model.NrAvailability.STANDBY_ULI,
            manager.resolveNrAvailability(
                hasPhysicalNr = false,
                overrideNetworkType = 5, // OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                serviceState = null
            )
        )

        // 5. Physical NR carrier NOT active, override is NONE (0) -> NONE
        assertEquals(
            com.ray.pixelbandinfo.model.NrAvailability.NONE,
            manager.resolveNrAvailability(
                hasPhysicalNr = false,
                overrideNetworkType = 0,
                serviceState = null
            )
        )
    }

    @Test
    fun testUpdateTechnologyName() {
        val manager = ShizukuTelephonyManager(mockContext)

        // Pure LTE transitions to LTE (5G Ready) when ULI active
        assertEquals(
            "LTE (5G Ready)",
            manager.updateTechnologyName(
                currentTech = "LTE",
                nrAvail = com.ray.pixelbandinfo.model.NrAvailability.STANDBY_ULI,
                hasPhysicalNr = false
            )
        )

        // LTE (5G Ready) transitions back to LTE when ULI inactive
        assertEquals(
            "LTE",
            manager.updateTechnologyName(
                currentTech = "LTE (5G Ready)",
                nrAvail = com.ray.pixelbandinfo.model.NrAvailability.NONE,
                hasPhysicalNr = false
            )
        )

        // NR5G NSA is preserved when physical NR is active
        assertEquals(
            "NR5G NSA",
            manager.updateTechnologyName(
                currentTech = "NR5G NSA",
                nrAvail = com.ray.pixelbandinfo.model.NrAvailability.CONNECTED,
                hasPhysicalNr = true
            )
        )

        // No Service is preserved regardless of availability
        assertEquals(
            "No Service",
            manager.updateTechnologyName(
                currentTech = "No Service",
                nrAvail = com.ray.pixelbandinfo.model.NrAvailability.STANDBY_ULI,
                hasPhysicalNr = false
            )
        )
    }

    @Test
    fun testBandSortingNrFirst() {
        val lteAnchor = BandInfo(
            technology = NetworkTechnology.LTE,
            band = 28,
            bandwidthDownMhz = 10,
            isAnchor = true
        )
        val lteSecB3 = BandInfo(
            technology = NetworkTechnology.LTE,
            band = 3,
            bandwidthDownMhz = 20,
            isAnchor = false
        )
        val lteSecB7 = BandInfo(
            technology = NetworkTechnology.LTE,
            band = 7,
            bandwidthDownMhz = 20,
            isAnchor = false
        )
        val nrNsaN78 = BandInfo(
            technology = NetworkTechnology.NR_NSA,
            band = 78,
            bandwidthDownMhz = 100,
            isAnchor = false
        )

        // Given physical carrier order (LTE anchor first, NR last)
        val inputList = listOf(lteAnchor, lteSecB3, lteSecB7, nrNsaN78)
        val sorted = inputList.sortedForDisplay()

        // 1. NR carrier must be at the very top
        assertEquals(NetworkTechnology.NR_NSA, sorted[0].technology)
        assertEquals(78, sorted[0].band)
        assertEquals(100, sorted[0].bandwidthDownMhz)

        // 2. LTE anchor carrier next
        assertEquals(NetworkTechnology.LTE, sorted[1].technology)
        assertEquals(28, sorted[1].band)
        assertEquals(true, sorted[1].isAnchor)

        // 3. LTE secondary carriers follow
        assertEquals(NetworkTechnology.LTE, sorted[2].technology)
        assertEquals(false, sorted[2].isAnchor)
        assertEquals(NetworkTechnology.LTE, sorted[3].technology)
        assertEquals(false, sorted[3].isAnchor)
    }

    @Test
    fun testBuildConfigVersionIsPopulated() {
        assertNotNull(BuildConfig.VERSION_NAME)
        assertEquals(true, BuildConfig.VERSION_NAME.isNotBlank())
        // Version name should match semver like 1.2.2
        val semverRegex = Regex("""^\d+\.\d+\.\d+.*$""")
        assertEquals(true, semverRegex.matches(BuildConfig.VERSION_NAME))
    }

    @Test
    fun testShizukuTelephonyManagerInitialState() {
        val manager = ShizukuTelephonyManager(mockContext)
        // Before Shizuku binder connects or permission granted, isShizukuGranted is false
        assertEquals(false, manager.isShizukuGranted.value)
    }
}
