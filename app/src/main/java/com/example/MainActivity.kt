package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.data.model.ConnectionState
import com.example.data.model.RemoteKey
import com.example.service.QuickRemoteManager
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val remoteVolumeKeysDown = mutableSetOf<Int>()

    private val quickRemote by lazy { QuickRemoteManager.getInstance(this) }
    // This activity extends ComponentActivity directly, never the transitive FragmentActivity 1.1.
    // QuickRemotePermissionTest verifies delivery through the real permission-result callback.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        quickRemote.refresh()
        if (!granted) Toast.makeText(this, R.string.quick_permission_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collect { state ->
                    if (state is ConnectionState.Connected && Build.VERSION.SDK_INT >= 33 && quickRemote.shouldRequestPermission()) {
                        quickRemote.markPermissionRequested()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        setContent {
            MyApplicationTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        quickRemote.onAppForeground()
        viewModel.onAppForeground()
    }
    // Android Activity's public callback is inherited through a restricted AndroidX Core shim.
    // Keep framework key dispatch (and the hardware-volume regressions) on this Activity.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return handleRemoteVolumeKey(event) || super.dispatchKeyEvent(event)
    }

    // Dialog windows share the same press ownership as the Activity.
    internal fun handleRemoteVolumeKey(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOL_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOL_DOWN
            else -> return false
        }
        val canControlRemote = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            viewModel.activeDevice.value != null &&
            viewModel.connectionState.value is ConnectionState.Connected

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && canControlRemote) remoteVolumeKeysDown.add(event.keyCode)
                if (event.keyCode in remoteVolumeKeysDown) {
                    if (canControlRemote && !event.isCanceled) viewModel.sendKey(key)
                    return true
                }
            }
            // Consume the release even if the connection dropped during the press.
            KeyEvent.ACTION_UP -> if (remoteVolumeKeysDown.remove(event.keyCode)) return true
        }
        return false
    }

    override fun onPause() {
        remoteVolumeKeysDown.clear()
        super.onPause()
    }

    override fun onStop() {
        quickRemote.onAppBackground(isChangingConfigurations)
        super.onStop()
    }
}

