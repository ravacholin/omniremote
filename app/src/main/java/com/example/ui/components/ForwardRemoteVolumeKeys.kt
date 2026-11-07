package com.example.ui.components

import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.example.MainActivity

/** Dialogs have their own key dispatch; forward volume before focused fields see it. */
@Composable
internal fun ForwardRemoteVolumeKeys() {
    val view = LocalView.current
    val activity = remember(view) {
        generateSequence(view.context) { context ->
            (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
        }.filterIsInstance<MainActivity>().firstOrNull()
    }
    val window = generateSequence(view.parent) { it.parent }
        .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window

    DisposableEffect(window, activity) {
        val original = window?.callback
        if (window == null || original == null || activity == null) {
            onDispose { }
        } else {
            val forwarding = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    activity.handleRemoteVolumeKey(event) || original.dispatchKeyEvent(event)
            }
            window.callback = forwarding
            onDispose {
                if (window.callback === forwarding) window.callback = original
            }
        }
    }
}
