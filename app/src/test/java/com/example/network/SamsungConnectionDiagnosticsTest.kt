package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SamsungConnectionDiagnosticsTest {
    @Test fun `fingerprints are comparable after restart but cannot be guessed from an exported numeric token hash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("samsung_connection_diagnostics", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val secret = "12345678"
        val first = SamsungConnectionDiagnostics(context)
        first.record("192.168.1.10", 1, "connect_start", secret)
        val fingerprint = JSONArray(prefs.getString("events", "[]")).getJSONObject(0).getString("credential")
        val restored = SamsungConnectionDiagnostics(context)
        restored.record("192.168.1.10", 2, "connect_start", secret)
        assertEquals(fingerprint, JSONArray(prefs.getString("events", "[]")).getJSONObject(1).getString("credential"))
        assertFalse(restored.report().contains(secret))
        // Simulate a different installation. Its private random key must produce a
        // different identifier for the same short numeric Samsung credential.
        prefs.edit().clear().commit()
        SamsungConnectionDiagnostics(context).record("192.168.1.10", 1, "connect_start", secret)
        val differentInstallation = JSONArray(prefs.getString("events", "[]")).getJSONObject(0).getString("credential")
        assertNotEquals("An unsalted numeric-token digest is dictionary recoverable", fingerprint, differentInstallation)
    }
}
