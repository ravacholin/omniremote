package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import com.example.data.model.ConnectionState
import com.example.data.model.RemoteKey
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val remoteVolumeKeysDown = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForeground()
    }

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
}

