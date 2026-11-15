package com.github.gezimos.inkos.ui.notifications

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.gezimos.inkos.data.Prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

sealed interface SimpleTrayUiEvent {
    object OpenWifiPanel : SimpleTrayUiEvent
    object OpenInternetPanel : SimpleTrayUiEvent
    object OpenBluetoothSettings : SimpleTrayUiEvent
}

sealed interface SimpleTrayPermissionRequest {
    object BluetoothConnect : SimpleTrayPermissionRequest
    object Camera : SimpleTrayPermissionRequest
    object ReadPhoneState : SimpleTrayPermissionRequest
}

class SimpleTrayViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Context = application.applicationContext
    private val prefs = Prefs(appContext)

    private val _brightnessBarEnabled = MutableStateFlow(prefs.simpleTrayBrightnessBarEnabled)
    val brightnessBarEnabled: StateFlow<Boolean> = _brightnessBarEnabled.asStateFlow()

    private val _wifiEnabled = MutableStateFlow(false)
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _mobileDataEnabled = MutableStateFlow(false)
    val mobileDataEnabled: StateFlow<Boolean> = _mobileDataEnabled.asStateFlow()

    private val _flashlightEnabled = MutableStateFlow(false)
    val flashlightEnabled: StateFlow<Boolean> = _flashlightEnabled.asStateFlow()

    private val _brightnessLevel = MutableStateFlow(try {
        Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Exception) { prefs.brightnessLevel })
    val brightnessLevel: StateFlow<Int> = _brightnessLevel.asStateFlow()

    private val _uiEvents = MutableSharedFlow<SimpleTrayUiEvent>(extraBufferCapacity = 4)
    val uiEvents = _uiEvents.asSharedFlow()

    private val _permissionRequests = MutableSharedFlow<SimpleTrayPermissionRequest>(extraBufferCapacity = 4)
    val permissionRequests = _permissionRequests.asSharedFlow()

    private val cameraManager = appContext.getSystemService(CameraManager::class.java)

    // 注册对象全部用字段持有，onCleared 里才能对称注销
    private var wifiReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var airplaneReceiver: BroadcastReceiver? = null
    private var simReceiver: BroadcastReceiver? = null
    private var brightnessObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var torchCallback: CameraManager.TorchCallback? = null
    private var torchTargetId: String? = null

    private val _torchBusy = MutableStateFlow(false)
    val torchBusy: StateFlow<Boolean> = _torchBusy.asStateFlow()
    private val _torchAvailable = MutableStateFlow(true)
    val torchAvailable: StateFlow<Boolean> = _torchAvailable.asStateFlow()
    private val _torchErrors = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val torchErrors = _torchErrors.asSharedFlow()

    /** 等待 TorchCallback 回真值；toggle 不再乐观改状态。 */
    private val torchAwait = AtomicReference<CompletableDeferred<Boolean?>?>(null)
    @Volatile private var cleared = false

    init {
        viewModelScope.launch {
            prefs.preferenceChangeFlow.collect { key ->
                if (key == com.github.gezimos.inkos.data.Constants.SIMPLE_TRAY_BRIGHTNESS_BAR ||
                    key == com.github.gezimos.inkos.data.Prefs.ALL_KEYS_CHANGED) {
                    _brightnessBarEnabled.value = prefs.simpleTrayBrightnessBarEnabled
                }
            }
        }
        refreshStates()
        registerReceiversAndCallbacks()
    }

    fun refreshStates() {
        viewModelScope.launch {
            _wifiEnabled.value = try {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wm?.isWifiEnabled == true
            } catch (_: Exception) { false }

            _bluetoothEnabled.value = try {
                val btManager = appContext.getSystemService(BluetoothManager::class.java)
                val adapter = btManager?.adapter
                adapter?.isEnabled == true
            } catch (_: Exception) { false }

            _mobileDataEnabled.value = try {
                // If airplane mode is on, treat mobile data as OFF
                val airplane = try { Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) } catch (_: Exception) { 0 }
                if (airplane == 1) {
                    false
                } else {
                    val sm = try { appContext.getSystemService(android.telephony.SubscriptionManager::class.java) } catch (_: Exception) { null }
                    val activeCount = try { sm?.activeSubscriptionInfoCount ?: 0 } catch (_: Exception) { 0 }
                    if (activeCount <= 0) {
                        false
                    } else {
                        val tm = appContext.getSystemService(TelephonyManager::class.java)
                        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            try { tm.isDataEnabled } catch (_: Exception) {
                                try { Settings.Global.getInt(appContext.contentResolver, "mobile_data", 0) == 1 } catch (_: Exception) { currentCellState() }
                            }
                        } else {
                            try { Settings.Global.getInt(appContext.contentResolver, "mobile_data", 0) == 1 } catch (_: Exception) { currentCellState() }
                        }
                    }
                }
            } catch (_: Exception) { false }

            // 注意：这里不再把 flashlight 置 false；手电筒真值只由 CameraManager.TorchCallback 提供

            // Update brightness from system
            val currentBrightness = try {
                Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) { prefs.brightnessLevel }
            if (currentBrightness == 1 && _brightnessLevel.value == 0) {
                // Keep the "off" state
            } else if (_brightnessLevel.value != currentBrightness) {
                _brightnessLevel.value = currentBrightness
            }
        }
    }

    private fun currentCellState(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (_: Exception) { false }
    }

    private fun registerReceiversAndCallbacks() {
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (cleared) return
                viewModelScope.launch {
                    _wifiEnabled.value = try {
                        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
                    } catch (_: Exception) { false }
                }
            }
        }
        registerSystemReceiver(wifiReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))

        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (cleared) return
                viewModelScope.launch {
                    _bluetoothEnabled.value = try {
                        (appContext.getSystemService(BluetoothManager::class.java))?.adapter?.isEnabled == true
                    } catch (_: Exception) { false }
                }
            }
        }
        registerSystemReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        airplaneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (cleared) return
                refreshStates()
            }
        }
        registerSystemReceiver(airplaneReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))

        simReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (cleared) return
                refreshStates()
            }
        }
        registerSystemReceiver(simReceiver, IntentFilter("android.intent.action.SIM_STATE_CHANGED"))

        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    if (cleared) return
                    viewModelScope.launch {
                        val current = try {
                            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        } catch (_: Exception) {
                            return@launch
                        }
                        if (current == 1 && _brightnessLevel.value == 0) return@launch
                        if (_brightnessLevel.value != current) _brightnessLevel.value = current
                    }
                }
            }
            appContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer
            )
            brightnessObserver = observer
        } catch (_: Exception) {
        }

        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (cleared) return
                    refreshStates()
                }

                override fun onLost(network: Network) {
                    if (cleared) return
                    refreshStates()
                }
            }
            cm?.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (_: Exception) {
        }

        try {
            val cm = cameraManager
            if (cm != null) {
                torchTargetId = findTorchCameraId(cm)
                val callback = object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cleared) return
                        val target = torchTargetId
                        if (target != null && cameraId != target) return
                        _torchAvailable.value = true
                        _flashlightEnabled.value = enabled
                        torchAwait.get()?.complete(enabled)
                    }

                    override fun onTorchModeUnavailable(cameraId: String) {
                        if (cleared) return
                        val target = torchTargetId
                        if (target != null && cameraId != target) return
                        _torchAvailable.value = false
                        torchAwait.get()?.complete(null)
                    }
                }
                cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                torchCallback = callback
            }
        } catch (_: Exception) {
        }
    }

    private fun registerSystemReceiver(receiver: BroadcastReceiver?, filter: IntentFilter) {
        if (receiver == null) return
        try {
            // 系统保护的广播：API 33+ 需要显式 flag；ContextCompat 在旧版本退化为普通注册。
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {
        }
    }

    private fun findTorchCameraId(cm: CameraManager): String? {
        var fallback: String? = null
        try {
            for (id in cm.cameraIdList) {
                val characteristics = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { null } ?: continue
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!hasFlash) continue
                if (fallback == null) fallback = id
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    override fun onCleared() {
        cleared = true
        unregisterQuietly { wifiReceiver?.let { appContext.unregisterReceiver(it) } }
        unregisterQuietly { bluetoothReceiver?.let { appContext.unregisterReceiver(it) } }
        unregisterQuietly { airplaneReceiver?.let { appContext.unregisterReceiver(it) } }
        unregisterQuietly { simReceiver?.let { appContext.unregisterReceiver(it) } }
        unregisterQuietly { brightnessObserver?.let { appContext.contentResolver.unregisterContentObserver(it) } }
        unregisterQuietly {
            networkCallback?.let { callback ->
                (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(callback)
            }
        }
        unregisterQuietly { torchCallback?.let { cameraManager?.unregisterTorchCallback(it) } }
        wifiReceiver = null
        bluetoothReceiver = null
        airplaneReceiver = null
        simReceiver = null
        brightnessObserver = null
        networkCallback = null
        torchCallback = null
        torchAwait.set(null)
        super.onCleared()
    }

    private inline fun unregisterQuietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    // UI intent emitters
    fun openWifiPanel() { viewModelScope.launch { _uiEvents.emit(SimpleTrayUiEvent.OpenWifiPanel) } }
    fun openInternetPanel() { viewModelScope.launch { _uiEvents.emit(SimpleTrayUiEvent.OpenInternetPanel) } }
    fun openBluetoothSettings() { viewModelScope.launch { _uiEvents.emit(SimpleTrayUiEvent.OpenBluetoothSettings) } }

    fun requestToggleBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            viewModelScope.launch { _permissionRequests.emit(SimpleTrayPermissionRequest.BluetoothConnect) }
        } else {
            toggleBluetooth()
        }
    }

    fun toggleBluetooth() {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                _permissionRequests.emit(SimpleTrayPermissionRequest.BluetoothConnect)
                return@launch
            }
            try {
                if (Build.VERSION.SDK_INT >= 35) {
                    _uiEvents.emit(SimpleTrayUiEvent.OpenBluetoothSettings)
                    return@launch
                }
                
                val btManager = appContext.getSystemService(BluetoothManager::class.java)
                val adapter = btManager?.adapter
                if (adapter != null) {
                    val wasEnabled = adapter.isEnabled
                    // attempt to toggle
                    try {
                        if (wasEnabled) adapter.disable() else adapter.enable()
                    } catch (_: Exception) {}
                    // give the system a moment to apply the change
                    kotlinx.coroutines.delay(800)
                    val nowEnabled = try { btManager?.adapter?.isEnabled == true } catch (_: Exception) { wasEnabled }
                    if (nowEnabled == wasEnabled) {
                        viewModelScope.launch { _uiEvents.emit(SimpleTrayUiEvent.OpenBluetoothSettings) }
                    }
                    _bluetoothEnabled.value = nowEnabled
                }
            } catch (_: Exception) {}
        }
    }

    fun requestToggleFlashlight() {
        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            viewModelScope.launch { _permissionRequests.emit(SimpleTrayPermissionRequest.Camera) }
        } else {
            toggleFlashlight()
        }
    }

    fun toggleFlashlight() {
        viewModelScope.launch {
            if (cleared) return@launch
            val cm = cameraManager ?: run {
                _torchErrors.tryEmit("camera_unavailable")
                return@launch
            }
            val targetId = torchTargetId ?: findTorchCameraId(cm)?.also { torchTargetId = it }
            if (targetId == null) {
                _torchErrors.tryEmit("flashlight_unavailable")
                return@launch
            }
            if (_torchBusy.value) return@launch
            _torchBusy.value = true
            val pending = CompletableDeferred<Boolean?>()
            torchAwait.set(pending)
            try {
                // 真值只认 TorchCallback；这里只发起切换请求
                val desired = !_flashlightEnabled.value
                try {
                    cm.setTorchMode(targetId, desired)
                } catch (e: Exception) {
                    _torchErrors.tryEmit("set_torch_failed")
                    return@launch
                }
                val confirmed = withTimeoutOrNull(1500L) { pending.await() }
                if (confirmed == null) {
                    // null 只表示“没等到真值/不可用”，不乐观改状态；UI 仍以回调为准
                    _torchErrors.tryEmit("torch_state_unconfirmed")
                }
            } finally {
                torchAwait.set(null)
                _torchBusy.value = false
            }
        }
    }

    fun setBrightness(level: Int) {
        viewModelScope.launch {
            val v = level.coerceIn(0, 255)
            val writeVal = if (v == 0) 1 else v
            try {
                prefs.brightnessLevel = v
                if (v > 0) {
                    prefs.lastBrightnessLevel = v
                }
                if (Settings.System.canWrite(appContext)) {
                    try { Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, writeVal) } catch (_: Exception) {}
                }
                _brightnessLevel.value = v
            } catch (_: Exception) {}
        }
    }

    fun onPermissionResult(request: SimpleTrayPermissionRequest, granted: Boolean) {
        viewModelScope.launch {
            if (!granted) return@launch
            when (request) {
                SimpleTrayPermissionRequest.BluetoothConnect -> toggleBluetooth()
                SimpleTrayPermissionRequest.Camera -> toggleFlashlight()
                SimpleTrayPermissionRequest.ReadPhoneState -> refreshStates()
            }
        }
    }
}
