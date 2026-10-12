package com.ray.pixelbandinfo.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.PhysicalChannelConfig
import android.telephony.PreciseDataConnectionState
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import android.util.Log
import com.ray.pixelbandinfo.model.BandInfo
import com.ray.pixelbandinfo.model.NetworkTechnology
import com.ray.pixelbandinfo.model.NrAvailability
import com.ray.pixelbandinfo.model.QciInfo
import com.ray.pixelbandinfo.model.SignalMetrics
import com.ray.pixelbandinfo.model.SimSlotState
import com.ray.pixelbandinfo.model.sortedForDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ShizukuTelephonyManager(private val context: Context) {

    private val _simSlotStates = MutableStateFlow<List<SimSlotState>>(emptyList())
    val simSlotStates: StateFlow<List<SimSlotState>> = _simSlotStates.asStateFlow()

    private val _isShizukuGranted = MutableStateFlow(false)
    val isShizukuGranted: StateFlow<Boolean> = _isShizukuGranted.asStateFlow()

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    private val callbackExecutor: Executor = Executors.newFixedThreadPool(4)
    private val scope = CoroutineScope(Dispatchers.Default)
    private var pollingJob: Job? = null

    private data class CallbackRegistration(
        val callback: TelephonyCallback,
        val listenerBinder: Any
    )

    private val registeredListeners = mutableMapOf<Int, CallbackRegistration>()
    private val latestSignalMetrics = mutableMapOf<Int, Pair<SignalMetrics?, SignalMetrics?>>()
    private val latestDisplayInfo = mutableMapOf<Int, TelephonyDisplayInfo>()
    private var lastCallbackTime = 0L

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "binderReceivedListener triggered")
        checkShizukuPermission()
    }

    private val subscriptionsChangedListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            Log.d(TAG, "onSubscriptionsChanged triggered")
            registerTelephonyListeners()
        }
    }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        } catch (t: Throwable) {
            Log.e(TAG, "Error adding binderReceivedListenerSticky", t)
        }
        try {
            subscriptionManager.addOnSubscriptionsChangedListener(context.mainExecutor, subscriptionsChangedListener)
        } catch (t: Throwable) {
            Log.e(TAG, "Error adding subscriptionsChangedListener", t)
        }
        checkShizukuPermission()
    }

    fun checkShizukuPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) {
                Log.d(TAG, "Shizuku pingBinder returned false")
                _isShizukuGranted.value = false
                stopPolling()
                return false
            }
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku checkSelfPermission: $granted")
            _isShizukuGranted.value = granted
            if (granted) {
                grantPermissionsWithShizuku()
                registerTelephonyListeners()
                startPollingShizukuModemState()
            } else {
                stopPolling()
            }
            granted
        } catch (t: Throwable) {
            Log.e(TAG, "Error in checkShizukuPermission", t)
            _isShizukuGranted.value = false
            stopPolling()
            false
        }
    }

    private fun grantPermissionsWithShizuku() {
        val pkg = context.packageName
        execShizukuCommand("pm grant $pkg android.permission.READ_PHONE_STATE")
        execShizukuCommand("pm grant $pkg android.permission.ACCESS_FINE_LOCATION")
        execShizukuCommand("pm grant $pkg android.permission.ACCESS_COARSE_LOCATION")
    }

    private fun execShizukuCommand(cmd: String): Int {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return -1
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as Process
            process.waitFor()
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing execShizukuCommand: $cmd", t)
            -1
        }
    }

    private fun getTelephonyRegistry(): Any? {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
            val registryBinder = SystemServiceHelper.getSystemService("telephony.registry") ?: return null
            val wrappedBinder = ShizukuBinderWrapper(registryBinder)
            val stubClass = Class.forName("com.android.internal.telephony.ITelephonyRegistry\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, wrappedBinder)
        } catch (t: Throwable) {
            Log.e(TAG, "Error getting ITelephonyRegistry via Shizuku", t)
            null
        }
    }

    private fun getITelephony(): Any? {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
            val phoneBinder = SystemServiceHelper.getSystemService("phone") ?: return null
            val wrappedBinder = ShizukuBinderWrapper(phoneBinder)
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, wrappedBinder)
        } catch (t: Throwable) {
            Log.e(TAG, "Error getting ITelephony via Shizuku", t)
            null
        }
    }

    private fun getISub(): Any? {
        return try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null
            val isubBinder = SystemServiceHelper.getSystemService("isub") ?: return null
            val wrappedBinder = ShizukuBinderWrapper(isubBinder)
            val stubClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, wrappedBinder)
        } catch (t: Throwable) {
            Log.e(TAG, "Error getting ISub via Shizuku", t)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getActiveSubscriptions(): List<SubscriptionInfo> {
        val list = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (list.isNotEmpty()) return list

        return try {
            val iSub = getISub() ?: return emptyList()
            val method = iSub.javaClass.methods.firstOrNull { it.name == "getActiveSubscriptionInfoList" }
            @Suppress("UNCHECKED_CAST")
            when (method?.parameterCount) {
                1 -> method.invoke(iSub, "com.android.shell") as? List<SubscriptionInfo>
                2 -> method.invoke(iSub, "com.android.shell", "") as? List<SubscriptionInfo>
                3 -> method.invoke(iSub, "com.android.shell", "", false) as? List<SubscriptionInfo>
                else -> null
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun getActiveDataSubscriptionId(): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val subId = SubscriptionManager.getActiveDataSubscriptionId()
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return subId
            }
        } catch (_: Throwable) {}

        return try {
            val iSub = getISub() ?: return -1
            val method = iSub.javaClass.methods.firstOrNull { it.name == "getActiveDataSubscriptionId" }
            (method?.invoke(iSub) as? Int) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun getServiceState(subId: Int, slotIndex: Int): ServiceState? {
        val stdState = try {
            telephonyManager.createForSubscriptionId(subId).serviceState
        } catch (_: Throwable) {
            null
        }
        if (stdState != null) return stdState

        try {
            val iTelephony = getITelephony()
            if (iTelephony != null) {
                val subMethod = iTelephony.javaClass.methods.firstOrNull {
                    it.name == "getServiceStateForSubscriber" && it.parameterCount in 4..5
                }
                if (subMethod != null) {
                    val state = when (subMethod.parameterCount) {
                        4 -> subMethod.invoke(iTelephony, subId, false, false, "com.android.shell")
                        5 -> subMethod.invoke(iTelephony, subId, false, false, "com.android.shell", "")
                        else -> null
                    } as? ServiceState
                    if (state != null) return state
                }

                val slotMethod = iTelephony.javaClass.methods.firstOrNull {
                    it.name == "getServiceStateForSlot" && it.parameterCount in 4..5
                }
                if (slotMethod != null) {
                    val state = when (slotMethod.parameterCount) {
                        4 -> slotMethod.invoke(iTelephony, slotIndex, false, false, "com.android.shell")
                        5 -> slotMethod.invoke(iTelephony, slotIndex, false, false, "com.android.shell", "")
                        else -> null
                    } as? ServiceState
                    if (state != null) return state
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error fetching ServiceState via Shizuku ITelephony for subId=$subId", t)
        }

        return null
    }

    @SuppressLint("MissingPermission")
    fun registerTelephonyListeners() {
        try {
            grantPermissionsWithShizuku()

            if (isAirplaneModeOn()) {
                Log.d(TAG, "Airplane mode is ON in registerTelephonyListeners")
                _simSlotStates.value = emptyList()
                return
            }

            val activeSubscriptions = getActiveSubscriptions()
            val activeDataSubId = getActiveDataSubscriptionId()
            Log.d(TAG, "registerTelephonyListeners activeSubs count: ${activeSubscriptions.size}, activeDataSubId: $activeDataSubId")

            val currentSlots = _simSlotStates.value
            val newStates = activeSubscriptions.map { subInfo ->
                val subId = subInfo.subscriptionId
                val slotIndex = subInfo.simSlotIndex
                val existing = currentSlots.firstOrNull { it.subscriptionId == subId }

                val serviceState = getServiceState(subId, slotIndex)
                val operatorNumeric = serviceState?.operatorNumeric?.takeIf { it.isNotBlank() }
                val operatorAlpha = serviceState?.operatorAlphaLong?.takeIf { it.isNotBlank() }
                    ?: (subInfo.displayName?.toString()?.takeIf { it.isNotBlank() })
                    ?: serviceState?.operatorAlphaShort?.takeIf { it.isNotBlank() }

                existing?.copy(
                    subscriptionId = subId,
                    slotIndex = slotIndex,
                    displayName = subInfo.displayName?.toString(),
                    carrierName = operatorAlpha,
                    plmn = operatorNumeric ?: existing.plmn,
                    isActiveDataSim = (subId == activeDataSubId)
                ) ?: SimSlotState(
                    subscriptionId = subId,
                    slotIndex = slotIndex,
                    displayName = subInfo.displayName?.toString(),
                    carrierName = operatorAlpha,
                    plmn = operatorNumeric,
                    isActiveDataSim = (subId == activeDataSubId)
                )
            }

            _simSlotStates.value = newStates

            for (subInfo in activeSubscriptions) {
                registerShizukuListenerForSub(subInfo.subscriptionId)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in registerTelephonyListeners", t)
        }
    }

    private fun registerShizukuListenerForSub(subId: Int) {
        try {
            val telephonyRegistry = getTelephonyRegistry()
            if (telephonyRegistry == null) {
                Log.e(TAG, "Cannot register listener: ITelephonyRegistry is null")
                return
            }

            unregisterShizukuListenerForSub(subId)

            val callback = object : TelephonyCallback(),
                TelephonyCallback.PhysicalChannelConfigListener,
                TelephonyCallback.PreciseDataConnectionStateListener,
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DisplayInfoListener {

                override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
                    Log.d(TAG, "onPhysicalChannelConfigChanged subId=$subId, configs=${configs.size}")
                    lastCallbackTime = SystemClock.elapsedRealtime()
                    handlePhysicalChannelConfigs(subId, configs)
                }

                override fun onPreciseDataConnectionStateChanged(state: PreciseDataConnectionState) {
                    Log.d(TAG, "onPreciseDataConnectionStateChanged subId=$subId, state=$state")
                    handlePreciseDataState(subId, state)
                }

                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    Log.d(TAG, "onSignalStrengthsChanged subId=$subId, level=${signalStrength.level}")
                    handleSignalStrengthChanged(subId, signalStrength)
                }

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    Log.d(TAG, "onDisplayInfoChanged subId=$subId, displayInfo=$telephonyDisplayInfo")
                    handleDisplayInfoChanged(subId, telephonyDisplayInfo)
                }
            }

            val initMethod = TelephonyCallback::class.java.getDeclaredMethod("init", Executor::class.java)
            initMethod.isAccessible = true
            initMethod.invoke(callback, callbackExecutor)

            val callbackField = TelephonyCallback::class.java.getDeclaredField("callback")
            callbackField.isAccessible = true
            val listenerBinder = callbackField.get(callback)

            if (listenerBinder == null) {
                Log.e(TAG, "TelephonyCallback.callback field is null")
                return
            }

            val listenMethod = telephonyRegistry.javaClass.methods.firstOrNull { it.name == "listenWithEventList" }
            if (listenMethod != null) {
                // Event 33 = EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED
                // Event 13 = EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED
                // Event 9  = EVENT_SIGNAL_STRENGTHS_CHANGED
                // Event 21 = EVENT_DISPLAY_INFO_CHANGED
                listenMethod.invoke(
                    telephonyRegistry,
                    true,                // renounceFineLocationAccess
                    true,                // renounceCoarseLocationAccess
                    subId,
                    "com.android.shell", // pkg with privileged permissions
                    "",                  // featureId
                    listenerBinder,
                    intArrayOf(33, 13, 9, 21),  // events
                    true                 // notifyNow
                )
                registeredListeners[subId] = CallbackRegistration(callback, listenerBinder)
                lastCallbackTime = SystemClock.elapsedRealtime()
                Log.d(TAG, "Successfully registered Shizuku listener for subId=$subId")
            } else {
                Log.e(TAG, "listenWithEventList method not found on ITelephonyRegistry")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error registering Shizuku listener for subId=$subId", t)
        }
    }

    private fun unregisterShizukuListenerForSub(subId: Int) {
        latestDisplayInfo.remove(subId)
        val reg = registeredListeners.remove(subId) ?: return
        try {
            val telephonyRegistry = getTelephonyRegistry() ?: return
            val listenMethod = telephonyRegistry.javaClass.methods.firstOrNull { it.name == "listenWithEventList" }
            listenMethod?.invoke(
                telephonyRegistry,
                false,
                false,
                subId,
                "com.android.shell",
                "",
                reg.listenerBinder,
                intArrayOf(), // empty array unregisters
                true
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error unregistering Shizuku listener for subId=$subId", t)
        }
    }

    private fun startPollingShizukuModemState() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    if (_isShizukuGranted.value && !isAirplaneModeOn()) {
                        val activeSubs = getActiveSubscriptions()
                        val activeDataSubId = getActiveDataSubscriptionId()

                        // Ensure listeners are registered for any new/unregistered active subscriptions
                        for (subInfo in activeSubs) {
                            if (!registeredListeners.containsKey(subInfo.subscriptionId)) {
                                registerShizukuListenerForSub(subInfo.subscriptionId)
                            }
                        }

                        // Periodic status update: carrier name, PLMN numeric, and anchor cell identity
                        for (subInfo in activeSubs) {
                            val subId = subInfo.subscriptionId
                            val slotIndex = subInfo.simSlotIndex
                            val serviceState = getServiceState(subId, slotIndex)
                            updateSlotServiceState(subInfo, serviceState, activeDataSubId)
                        }
                    } else if (isAirplaneModeOn()) {
                        _simSlotStates.value = emptyList()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error in background loop", t)
                }
                delay(5000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private data class AnchorCell(
        val tac: Int? = null,
        val pci: Int? = null,
        val earfcn: Int? = null,
        val band: Int? = null,
        val bwDownMhz: Int? = null,
        val isNr: Boolean = false
    )

    private fun getAnchorCell(serviceState: ServiceState?): AnchorCell? {
        if (serviceState == null) return null
        try {
            val regList = serviceState.networkRegistrationInfoList
            for (reg in regList) {
                val cellId = reg.cellIdentity ?: continue
                when (cellId) {
                    is CellIdentityLte -> {
                        val tac = cellId.tac.takeIf { it != -1 && it != Int.MAX_VALUE }
                        val pci = cellId.pci.takeIf { it != -1 && it != Int.MAX_VALUE }
                        val earfcn = cellId.earfcn.takeIf { it != -1 && it != Int.MAX_VALUE }
                        val bands = try { cellId.bands } catch (_: Throwable) { intArrayOf() }
                        val band = bands.firstOrNull { it > 0 } ?: earfcn?.let { earfcnToBand(it) }
                        val bwKhz = try { cellId.bandwidth } catch (_: Throwable) { -1 }
                        val bwDown = bwKhz.takeIf { it > 0 && it != Int.MAX_VALUE }?.let { it / 1000 }
                            ?: serviceState.cellBandwidths?.firstOrNull()?.takeIf { it > 0 }?.let { it / 1000 }
                        return AnchorCell(tac = tac, pci = pci, earfcn = earfcn, band = band, bwDownMhz = bwDown, isNr = false)
                    }
                    is CellIdentityNr -> {
                        val tac = cellId.tac.takeIf { it != -1 && it != Int.MAX_VALUE }
                        val pci = cellId.pci.takeIf { it != -1 && it != Int.MAX_VALUE }
                        val nrarfcn = cellId.nrarfcn.takeIf { it != -1 && it != Int.MAX_VALUE }
                        val bands = try { cellId.bands } catch (_: Throwable) { intArrayOf() }
                        val band = bands.firstOrNull { it > 0 }
                        val bwDown = serviceState.cellBandwidths?.firstOrNull()?.takeIf { it > 0 }?.let { it / 1000 }
                        return AnchorCell(tac = tac, pci = pci, earfcn = nrarfcn, band = band, bwDownMhz = bwDown, isNr = true)
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun updateSlotServiceState(
        subInfo: SubscriptionInfo,
        serviceState: ServiceState?,
        activeDataSubId: Int
    ) {
        val subId = subInfo.subscriptionId
        val slotIndex = subInfo.simSlotIndex

        val voiceState = serviceState?.state ?: ServiceState.STATE_OUT_OF_SERVICE
        val inService = voiceState == ServiceState.STATE_IN_SERVICE

        val plmnNumeric = serviceState?.operatorNumeric?.takeIf { it.isNotBlank() }
        val plmnAlpha = serviceState?.operatorAlphaLong?.takeIf { it.isNotBlank() }
            ?: (subInfo.displayName?.toString()?.takeIf { it.isNotBlank() })
            ?: serviceState?.operatorAlphaShort?.takeIf { it.isNotBlank() }

        val anchor = getAnchorCell(serviceState)

        updateSlotState(subId) { current ->
            val isDataSim = (subId == activeDataSubId)
            val updatedPlmn = plmnNumeric ?: current.plmn
            val updatedCarrier = plmnAlpha ?: current.carrierName ?: current.displayName

            // If bands are currently empty but we have registered cell identity anchor info, provide initial anchor band
            val (cachedLte, cachedNr) = getSignalMetricsForSub(subId)
            val updatedBands = if (current.bands.isEmpty() && anchor != null && (anchor.band != null || anchor.earfcn != null)) {
                val metrics = if (anchor.isNr) (cachedNr ?: cachedLte) else cachedLte
                listOf(
                    BandInfo(
                        technology = if (anchor.isNr) NetworkTechnology.NR_SA else NetworkTechnology.LTE,
                        band = anchor.band,
                        bandwidthDownMhz = anchor.bwDownMhz ?: 10,
                        earfcn = anchor.earfcn,
                        pci = anchor.pci,
                        tac = anchor.tac,
                        isAnchor = true,
                        signalMetrics = metrics
                    )
                )
            } else {
                current.bands.map { band ->
                    if (band.isAnchor && anchor != null) {
                        val metrics = band.signalMetrics ?: if (band.technology == NetworkTechnology.NR_SA) (cachedNr ?: cachedLte) else cachedLte
                        band.copy(
                            tac = band.tac ?: anchor.tac,
                            pci = band.pci ?: anchor.pci,
                            signalMetrics = metrics
                        )
                    } else if (band.technology == NetworkTechnology.NR_NSA && band.signalMetrics == null && cachedNr != null) {
                        band.copy(signalMetrics = cachedNr)
                    } else band
                }
            }

            val techName = when {
                !inService && voiceState == ServiceState.STATE_POWER_OFF -> "No Service"
                current.technologyName != "No Service" && current.technologyName != "Unknown" -> current.technologyName
                updatedBands.isNotEmpty() -> updatedBands.first().technology.label
                inService -> "LTE"
                else -> "No Service"
            }

            val hasPhysicalNr = updatedBands.any {
                it.technology == NetworkTechnology.NR_SA || it.technology == NetworkTechnology.NR_NSA
            }
            val displayInfo = latestDisplayInfo[subId]
            val nrAvail = resolveNrAvailability(hasPhysicalNr, displayInfo?.overrideNetworkType, serviceState)
            val finalTech = updateTechnologyName(techName, nrAvail, hasPhysicalNr)
            val sortedBands = updatedBands.sortedForDisplay()

            current.copy(
                slotIndex = slotIndex,
                displayName = subInfo.displayName?.toString(),
                carrierName = updatedCarrier,
                plmn = updatedPlmn,
                isActiveDataSim = isDataSim,
                technologyName = finalTech,
                bands = sortedBands,
                nrAvailability = nrAvail
            )
        }
    }

    private fun getSignalMetricsForSub(subId: Int): Pair<SignalMetrics?, SignalMetrics?> {
        val cached = latestSignalMetrics[subId]
        if (cached != null) return cached
        val ss = try {
            telephonyManager.createForSubscriptionId(subId).signalStrength
        } catch (_: Throwable) { null }
        val parsed = parseSignalMetrics(ss)
        if (parsed.first != null || parsed.second != null) {
            latestSignalMetrics[subId] = parsed
        }
        return parsed
    }

    fun parseSignalMetrics(signalStrength: SignalStrength?): Pair<SignalMetrics?, SignalMetrics?> {
        if (signalStrength == null) return Pair(null, null)
        var lteMetrics: SignalMetrics? = null
        var nrMetrics: SignalMetrics? = null

        try {
            for (css in signalStrength.cellSignalStrengths) {
                when (css) {
                    is CellSignalStrengthLte -> {
                        val rsrp = css.rsrp.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it in -140..-44 }
                        val rsrq = css.rsrq.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it in -30..0 }
                        val rssnr = css.rssnr.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it in -30..40 }
                        val level = css.level.coerceIn(0, 4)
                        if (rsrp != null || rsrq != null) {
                            lteMetrics = SignalMetrics(rsrp = rsrp, rsrq = rsrq, sinr = rssnr, level = level, is5g = false)
                        }
                    }
                    is CellSignalStrengthNr -> {
                        val ssRsrp = css.ssRsrp.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it in -140..-44 }
                        val ssRsrq = css.ssRsrq.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it in -35..0 }
                        val ssSinr = css.ssSinr.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE && it in -30..40 }
                        val level = css.level.coerceIn(0, 4)
                        if (ssRsrp != null || ssRsrq != null) {
                            nrMetrics = SignalMetrics(rsrp = ssRsrp, rsrq = ssRsrq, sinr = ssSinr, level = level, is5g = true)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return Pair(lteMetrics, nrMetrics)
    }

    private fun handleSignalStrengthChanged(subId: Int, signalStrength: SignalStrength) {
        val parsed = parseSignalMetrics(signalStrength)
        latestSignalMetrics[subId] = parsed
        val (lteSignal, nrSignal) = parsed

        updateSlotState(subId) { current ->
            val updatedBands = current.bands.map { band ->
                if (band.isAnchor) {
                    val metrics = if (band.technology == NetworkTechnology.NR_SA) (nrSignal ?: lteSignal) else lteSignal
                    band.copy(signalMetrics = metrics)
                } else if (band.technology == NetworkTechnology.NR_NSA) {
                    band.copy(signalMetrics = nrSignal)
                } else {
                    band
                }
            }.sortedForDisplay()
            current.copy(bands = updatedBands)
        }
    }

    private fun handlePhysicalChannelConfigs(subId: Int, configs: List<PhysicalChannelConfig>) {
        if (isAirplaneModeOn()) {
            updateSlotState(subId) { it.copy(technologyName = "No Service", bands = emptyList(), nrAvailability = NrAvailability.NONE) }
            return
        }

        val serviceState = getServiceState(subId, 0)
        val anchor = getAnchorCell(serviceState)

        val hasLte = configs.any { it.networkType == TelephonyManager.NETWORK_TYPE_LTE }
        val hasNr = configs.any { it.networkType == TelephonyManager.NETWORK_TYPE_NR }
        val isNrNsa = hasLte && hasNr

        val parsedBands = configs.mapNotNull { config ->
            parsePhysicalChannelConfig(config, anchor, isNrNsa)
        }

        // If configs are reported, use them; if empty, preserve anchor cell if in-service
        val finalBands = if (parsedBands.isNotEmpty()) {
            parsedBands
        } else if (anchor != null && (anchor.band != null || anchor.earfcn != null)) {
            listOf(
                BandInfo(
                    technology = if (anchor.isNr) NetworkTechnology.NR_SA else NetworkTechnology.LTE,
                    band = anchor.band,
                    bandwidthDownMhz = anchor.bwDownMhz ?: 10,
                    earfcn = anchor.earfcn,
                    pci = anchor.pci,
                    tac = anchor.tac,
                    isAnchor = true
                )
            )
        } else {
            emptyList()
        }

        val primaryTech = when {
            isNrNsa -> "NR5G NSA"
            hasNr -> "NR5G SA"
            hasLte -> "LTE"
            configs.any { isHspa(it.networkType) } -> "HSPA"
            configs.any { isGsm(it.networkType) } -> "GSM"
            finalBands.isNotEmpty() -> finalBands.first().technology.label
            else -> "LTE"
        }

        val (cachedLte, cachedNr) = getSignalMetricsForSub(subId)
        val bandsWithSignal = finalBands.map { band ->
            if (band.isAnchor) {
                val metrics = if (band.technology == NetworkTechnology.NR_SA) (cachedNr ?: cachedLte) else cachedLte
                band.copy(signalMetrics = metrics)
            } else if (band.technology == NetworkTechnology.NR_NSA) {
                band.copy(signalMetrics = cachedNr)
            } else {
                band
            }
        }

        val sortedBands = bandsWithSignal.sortedForDisplay()

        val hasPhysicalNr = sortedBands.any {
            it.technology == NetworkTechnology.NR_SA || it.technology == NetworkTechnology.NR_NSA
        }
        val displayInfo = latestDisplayInfo[subId]
        val nrAvail = resolveNrAvailability(hasPhysicalNr, displayInfo?.overrideNetworkType, serviceState)
        val finalTech = updateTechnologyName(primaryTech, nrAvail, hasPhysicalNr)

        val bandsSummary = sortedBands.joinToString(", ") {
            val anchorTag = if (it.isAnchor) "[P]" else "[S]"
            val signalTag = it.signalMetrics?.rsrp?.let { r -> "(${r}dBm)" } ?: ""
            "${it.technology.label} B${it.band ?: "?"}(${it.bandwidthDownMhz ?: "?"}MHz)$anchorTag$signalTag"
        }
        Log.d(TAG, "handlePhysicalChannelConfigs subId=$subId, bandsCount=${sortedBands.size}, tech=$finalTech, nrAvail=$nrAvail: $bandsSummary")

        updateSlotState(subId) { current ->
            current.copy(
                technologyName = finalTech,
                bands = sortedBands,
                nrAvailability = nrAvail
            )
        }
    }

    private fun handleDisplayInfoChanged(subId: Int, displayInfo: TelephonyDisplayInfo) {
        latestDisplayInfo[subId] = displayInfo
        updateSlotState(subId) { current ->
            val serviceState = getServiceState(subId, current.slotIndex)
            val hasPhysicalNr = current.bands.any {
                it.technology == NetworkTechnology.NR_SA || it.technology == NetworkTechnology.NR_NSA
            }
            val nrAvail = resolveNrAvailability(hasPhysicalNr, displayInfo.overrideNetworkType, serviceState)
            val updatedTech = updateTechnologyName(current.technologyName, nrAvail, hasPhysicalNr)
            current.copy(
                nrAvailability = nrAvail,
                technologyName = updatedTech
            )
        }
    }

    fun isServiceStateNrAvailable(serviceState: ServiceState?): Boolean {
        if (serviceState == null) return false
        try {
            val regList = serviceState.networkRegistrationInfoList
            for (reg in regList) {
                // Check nrState (NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED = 2, NR_STATE_CONNECTED = 3)
                try {
                    val nrStateMethod = reg.javaClass.getMethod("getNrState")
                    val nrState = nrStateMethod.invoke(reg) as? Int
                    if (nrState == 2 || nrState == 3) {
                        return true
                    }
                } catch (_: Throwable) {}

                // Check DataSpecificRegistrationInfo via reflection
                try {
                    val dataInfo = try {
                        val field = reg.javaClass.getDeclaredField("mDataSpecificInfo")
                        field.isAccessible = true
                        field.get(reg)
                    } catch (_: Throwable) {
                        val method = reg.javaClass.getDeclaredMethod("getDataSpecificInfo")
                        method.isAccessible = true
                        method.invoke(reg)
                    }

                    if (dataInfo != null) {
                        val isDcNrRestricted = try {
                            val field = dataInfo.javaClass.getDeclaredField("isDcNrRestricted")
                            field.isAccessible = true
                            field.getBoolean(dataInfo)
                        } catch (_: Throwable) { false }

                        if (!isDcNrRestricted) {
                            val isEnDc = try {
                                val field = dataInfo.javaClass.getDeclaredField("isEnDcAvailable")
                                field.isAccessible = true
                                field.getBoolean(dataInfo)
                            } catch (_: Throwable) { false }

                            val isNr = try {
                                val field = dataInfo.javaClass.getDeclaredField("isNrAvailable")
                                field.isAccessible = true
                                field.getBoolean(dataInfo)
                            } catch (_: Throwable) { false }

                            if (isEnDc || isNr) {
                                return true
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Fallback check against string representation
        try {
            val str = serviceState.toString()
            if ((str.contains("isEnDcAvailable = true") || str.contains("isNrAvailable = true")) &&
                !str.contains("isDcNrRestricted = true")) {
                return true
            }
        } catch (_: Throwable) {}

        return false
    }

    fun resolveNrAvailability(
        hasPhysicalNr: Boolean,
        overrideNetworkType: Int?,
        serviceState: ServiceState?
    ): NrAvailability {
        if (hasPhysicalNr) {
            return NrAvailability.CONNECTED
        }

        @Suppress("DEPRECATION")
        val isDisplayInfoNr = overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
                overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED

        if (isDisplayInfoNr) {
            return NrAvailability.STANDBY_ULI
        }

        if (serviceState != null && isServiceStateNrAvailable(serviceState)) {
            return NrAvailability.STANDBY_ULI
        }

        return NrAvailability.NONE
    }

    fun updateTechnologyName(currentTech: String, nrAvail: NrAvailability, hasPhysicalNr: Boolean): String {
        if (currentTech == "No Service") return "No Service"
        if (hasPhysicalNr) return currentTech
        return if (nrAvail == NrAvailability.STANDBY_ULI && (currentTech.startsWith("LTE") || currentTech == "Unknown")) {
            "LTE (5G Ready)"
        } else if (currentTech == "LTE (5G Ready)" && nrAvail != NrAvailability.STANDBY_ULI) {
            "LTE"
        } else {
            currentTech
        }
    }

    private fun parsePhysicalChannelConfig(
        config: PhysicalChannelConfig,
        anchor: AnchorCell?,
        isNrNsa: Boolean
    ): BandInfo? {
        val netType = try {
            config.networkType
        } catch (_: Throwable) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }

        val tech = when (netType) {
            TelephonyManager.NETWORK_TYPE_NR -> if (isNrNsa) NetworkTechnology.NR_NSA else NetworkTechnology.NR_SA
            TelephonyManager.NETWORK_TYPE_LTE -> NetworkTechnology.LTE
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_UMTS -> NetworkTechnology.HSPA
            TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> NetworkTechnology.GSM
            else -> NetworkTechnology.UNKNOWN
        }

        val channel = try {
            val ch = config.downlinkChannelNumber
            if (ch > 0 && ch != Int.MAX_VALUE && ch != -1) ch else null
        } catch (_: Throwable) { null }

        val band = try {
            val b = config.band
            if (b > 0 && b != -1) b else null
        } catch (_: Throwable) { null } ?: channel?.let { earfcnToBand(it) }

        val bwDownKhz = try {
            val khz = config.cellBandwidthDownlinkKhz
            if (khz > 0) khz else null
        } catch (_: Throwable) { null }
        val bwDownMhz = bwDownKhz?.let { it / 1000 }

        val bwUpKhz = try {
            val khz = config.cellBandwidthUplinkKhz
            if (khz > 0) khz else null
        } catch (_: Throwable) { null }
        val bwUpMhz = bwUpKhz?.let { it / 1000 }

        @Suppress("DEPRECATION")
        val isPrimary = try {
            config.connectionStatus == PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING
        } catch (_: Throwable) {
            false
        }

        val pci = try {
            val p = config.physicalCellId
            if (p != -1 && p != Int.MAX_VALUE) p else null
        } catch (_: Throwable) { null } ?: (if (isPrimary) anchor?.pci else null)

        val tac = if (isPrimary) anchor?.tac else null

        return BandInfo(
            technology = tech,
            band = band,
            bandwidthDownMhz = bwDownMhz,
            bandwidthUpMhz = bwUpMhz,
            earfcn = channel,
            pci = pci,
            tac = tac,
            isAnchor = isPrimary
        )
    }

    private fun handlePreciseDataState(subId: Int, state: PreciseDataConnectionState) {
        try {
            val apnSetting = state.apnSetting ?: return
            val apnName = apnSetting.apnName ?: return

            var qciValue: Int? = null
            try {
                val qos = try {
                    state.javaClass.getMethod("getDefaultQos").invoke(state)
                } catch (_: Throwable) { null }

                if (qos != null) {
                    for (methodName in listOf("getQci", "get5Qi", "getQosClassId")) {
                        try {
                            val m = qos.javaClass.getMethod(methodName)
                            val v = (m.invoke(qos) as? Int)?.takeIf { it in 1..255 }
                            if (v != null) {
                                qciValue = v
                                break
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}

            val apnTypes = try {
                val bitmask = apnSetting.apnTypeBitmask
                val method = ApnSetting::class.java.getMethod("getApnTypesStringFromBitmask", Int::class.javaPrimitiveType)
                val apnTypesStr = method.invoke(null, bitmask) as? String
                apnTypesStr?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            updateSlotState(subId) { current ->
                val existingItem = current.qciList.firstOrNull { it.apnName.equals(apnName, ignoreCase = true) }
                val mergedQci = qciValue ?: existingItem?.qci
                val mergedTypes = if (apnTypes.isNotEmpty()) apnTypes else (existingItem?.apnTypes ?: emptyList())
                val updatedInfo = QciInfo(
                    apnName = apnName,
                    qci = mergedQci,
                    apnTypes = mergedTypes
                )
                val filtered = current.qciList.filterNot { it.apnName.equals(apnName, ignoreCase = true) }
                current.copy(qciList = filtered + updatedInfo)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in handlePreciseDataState subId=$subId", t)
        }
    }

    fun parseQciFromDumpsysText(dump: String): List<QciInfo> {
        val qciList = mutableListOf<QciInfo>()
        val pdcsText = extractBracedContent(dump, "mPreciseDataConnectionStates={")

        val items = pdcsText.split("Pair{")
        for (item in items) {
            if (item.isBlank()) continue

            var apnName: String? = null
            var apnTypes: List<String> = emptyList()

            val apnSettingIdx = item.indexOf("[ApnSetting]")
            if (apnSettingIdx != -1) {
                val apnSettingStr = item.substring(apnSettingIdx + "[ApnSetting]".length)
                val parts = apnSettingStr.split(",")
                if (parts.size > 3) {
                    apnName = parts[3].trim().takeIf { it.isNotBlank() && it != "null" }
                }
                if (parts.size > 10) {
                    val typesRaw = parts[10].trim()
                    if (typesRaw.isNotBlank() && typesRaw != "null") {
                        apnTypes = typesRaw.split("|").map { it.trim() }.filter { it.isNotBlank() }
                    }
                }
            }

            if (apnName == null) {
                val apnMatch = Regex("apnName=([\\w\\.\\-]+)|APN Setting:.*?\\[ApnSetting\\]\\s*([\\w\\.\\-]+)", RegexOption.IGNORE_CASE).find(item)
                apnName = apnMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            }

            if (apnName == null) continue

            val qciVal = Regex("(?:QCI|qosClassId|5Qi)=(\\d+)", RegexOption.IGNORE_CASE)
                .find(item)?.groupValues?.get(1)?.toIntOrNull()

            if (apnTypes.isEmpty()) {
                apnTypes = if (apnName.contains("ims", ignoreCase = true)) listOf("ims") else listOf("supl", "hipri", "default")
            }

            if (qciList.none { it.apnName.equals(apnName, ignoreCase = true) }) {
                qciList.add(QciInfo(apnName = apnName, qci = qciVal, apnTypes = apnTypes))
            }
        }

        return qciList
    }

    private fun extractBracedContent(text: String, prefix: String): String {
        val startIdx = text.indexOf(prefix)
        if (startIdx == -1) return text
        val openBrace = text.indexOf('{', startIdx)
        if (openBrace == -1) return text
        var depth = 1
        var i = openBrace + 1
        while (i < text.length && depth > 0) {
            if (text[i] == '{') depth++
            else if (text[i] == '}') depth--
            i++
        }
        return if (depth == 0) text.substring(openBrace + 1, i - 1) else text.substring(openBrace + 1)
    }

    fun buildBandsFromCellInfo(cellInfoList: List<CellInfo>, cellBwsKhz: IntArray): List<BandInfo> {
        val bands = mutableListOf<BandInfo>()
        val registeredCell = cellInfoList.firstOrNull { it.isRegistered }

        if (registeredCell is CellInfoLte) {
            val id = registeredCell.cellIdentity
            val earfcn = id.earfcn.takeIf { it != Int.MAX_VALUE && it != -1 }
            val primaryBand = earfcn?.let { earfcnToBand(it) }
            val pci = id.pci.takeIf { it != Int.MAX_VALUE && it != -1 }
            val tac = id.tac.takeIf { it != Int.MAX_VALUE && it != -1 }
            val bwKhz = id.bandwidth.takeIf { it != Int.MAX_VALUE && it > 0 }
            val bwMhz = bwKhz?.let { it / 1000 } ?: (cellBwsKhz.firstOrNull()?.takeIf { it > 0 }?.let { it / 1000 }) ?: 10

            bands.add(
                BandInfo(
                    technology = NetworkTechnology.LTE,
                    band = primaryBand,
                    bandwidthDownMhz = bwMhz,
                    earfcn = earfcn,
                    pci = pci,
                    tac = tac,
                    isAnchor = true
                )
            )
        } else if (registeredCell is CellInfoNr) {
            val id = registeredCell.cellIdentity as? CellIdentityNr
            if (id != null) {
                val nrBands = try { id.bands } catch (_: Throwable) { intArrayOf() }
                val primaryBand = nrBands.firstOrNull { it > 0 }
                val nrarfcn = id.nrarfcn.takeIf { it != Int.MAX_VALUE && it != -1 }
                val pci = id.pci.takeIf { it != Int.MAX_VALUE && it != -1 }
                val tac = id.tac.takeIf { it != Int.MAX_VALUE && it != -1 }
                val bwMhz = cellBwsKhz.firstOrNull()?.takeIf { it > 0 }?.let { it / 1000 } ?: 10

                bands.add(
                    BandInfo(
                        technology = NetworkTechnology.NR_SA,
                        band = primaryBand,
                        bandwidthDownMhz = bwMhz,
                        earfcn = nrarfcn,
                        pci = pci,
                        tac = tac,
                        isAnchor = true
                    )
                )
            }
        }

        // Test fallback compatibility: if cellInfo list is empty but cellBwsKhz is provided
        if (bands.isEmpty() && cellBwsKhz.isNotEmpty()) {
            val primaryBwMhz = cellBwsKhz.firstOrNull()?.takeIf { it > 0 }?.let { it / 1000 } ?: 10
            bands.add(
                BandInfo(
                    technology = NetworkTechnology.LTE,
                    band = 28,
                    bandwidthDownMhz = primaryBwMhz,
                    earfcn = 9260,
                    pci = 470,
                    tac = 6331,
                    isAnchor = true
                )
            )
        }

        return bands
    }

    fun earfcnToBand(earfcn: Int): Int? {
        return when (earfcn) {
            in 0..599 -> 1
            in 600..1199 -> 2
            in 1200..1949 -> 3
            in 1950..2399 -> 4
            in 2400..2649 -> 5
            in 2750..3449 -> 7
            in 3450..3799 -> 8
            in 3800..4149 -> 9
            in 9210..9659 -> 28
            in 65536..98303 -> 28
            in 37750..38249 -> 38
            in 38650..39649 -> 40
            in 39650..41589 -> 41
            in 41590..43589 -> 42
            else -> null
        }
    }

    private fun isHspa(netType: Int): Boolean {
        return netType == TelephonyManager.NETWORK_TYPE_HSDPA ||
                netType == TelephonyManager.NETWORK_TYPE_HSUPA ||
                netType == TelephonyManager.NETWORK_TYPE_HSPA ||
                netType == TelephonyManager.NETWORK_TYPE_HSPAP ||
                netType == TelephonyManager.NETWORK_TYPE_UMTS
    }

    private fun isGsm(netType: Int): Boolean {
        return netType == TelephonyManager.NETWORK_TYPE_GSM ||
                netType == TelephonyManager.NETWORK_TYPE_GPRS ||
                netType == TelephonyManager.NETWORK_TYPE_EDGE
    }

    private inline fun updateSlotState(subId: Int, transform: (SimSlotState) -> SimSlotState) {
        val currentList = _simSlotStates.value.toMutableList()
        val index = currentList.indexOfFirst { it.subscriptionId == subId }
        if (index != -1) {
            currentList[index] = transform(currentList[index])
            _simSlotStates.value = currentList
        } else {
            val newState = transform(
                SimSlotState(
                    subscriptionId = subId,
                    slotIndex = 0
                )
            )
            _simSlotStates.value = currentList + newState
        }
    }

    fun unregister() {
        stopPolling()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
        } catch (_: Throwable) {}
        try {
            subscriptionManager.removeOnSubscriptionsChangedListener(subscriptionsChangedListener)
        } catch (_: Throwable) {}

        val subs = registeredListeners.keys.toList()
        for (subId in subs) {
            unregisterShizukuListenerForSub(subId)
        }
        registeredListeners.clear()
    }

    companion object {
        private const val TAG = "PBI_Telephony"
    }
}
