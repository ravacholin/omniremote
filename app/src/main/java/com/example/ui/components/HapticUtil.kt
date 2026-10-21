package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Utility for triggering tactile haptic feedback on button presses and gestures.
 * Persists the user's enabled/disabled preference in SharedPreferences.
 */
object HapticUtil {
    private const val PREFS_NAME = "omni_remote_prefs"
    private const val KEY_HAPTIC_ENABLED = "haptic_feedback_enabled"
    private var isHapticEnabledCached: Boolean? = null

    /**
     * Checks whether haptic feedback for button presses is enabled. Defaults to true.
     */
    fun isHapticEnabled(context: Context): Boolean {
        if (isHapticEnabledCached == null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isHapticEnabledCached = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        }
        return isHapticEnabledCached ?: true
    }

    /**
     * Updates the haptic feedback preference and saves it to SharedPreferences.
     * When enabled, triggers an immediate physical confirmation vibration.
     */
    fun setHapticEnabled(context: Context, enabled: Boolean) {
        isHapticEnabledCached = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAPTIC_ENABLED, enabled)
            .apply()

        if (enabled) {
            triggerConfirmationHaptic(context)
        }
    }

    /**
     * Triggers a crisp micro-vibration for remote button presses and trackpad interactions.
     * Only vibrates if haptic feedback is enabled in settings.
     */
    fun triggerKeyHaptic(context: Context) {
        if (!isHapticEnabled(context)) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(20)
            }
        } catch (_: Exception) {
            // Ignore if vibration is disabled or unsupported
        }
    }

    /**
     * Triggers a distinct tactile confirmation pulse when haptics are enabled or settings changed.
     */
    fun triggerConfirmationHaptic(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {
            // Ignore if vibration is disabled or unsupported
        }
    }
}
