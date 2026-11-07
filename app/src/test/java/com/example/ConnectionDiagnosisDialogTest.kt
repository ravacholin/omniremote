package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import com.example.network.ConnectionDiagnosticReport
import com.example.network.DiagnosticAction
import com.example.network.DiagnosticCheck
import com.example.network.DiagnosticStatus
import com.example.ui.components.ConnectionDiagnosisDialog
import com.example.ui.components.ConnectionDiagnosisUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionDiagnosisDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `running checks offer cancellation but no premature repair`() {
        var closed = false
        compose.setContent {
            ConnectionDiagnosisDialog(ConnectionDiagnosisUiState("Sala", stage = "Buscando si cambió la IP…"),
                onDismiss = { closed = true }, onRecheck = {}, onAction = {})
        }
        compose.onNodeWithText("Buscando si cambió la IP…").assertExists()
        compose.onNodeWithText("Actualizar IP y conectar").assertDoesNotExist()
        compose.onNodeWithText("Cerrar").performClick()
        assertTrue(closed)
    }

    @Test fun `results distinguish unknown authorization and dispatch only the selected repair`() {
        var chosen: DiagnosticAction? = null
        val report = ConnectionDiagnosticReport(7, "192.168.1.10", listOf(
            DiagnosticCheck("Red local", DiagnosticStatus.PASSED, "Red disponible"),
            DiagnosticCheck("Equipo e IP", DiagnosticStatus.ATTENTION, "Encontrado en 192.168.1.99"),
            DiagnosticCheck("Autorización", DiagnosticStatus.UNKNOWN, "Permiso todavía sin comprobar")
        ), listOf(DiagnosticAction.USE_NEW_ADDRESS), "192.168.1.99")
        compose.setContent {
            ConnectionDiagnosisDialog(ConnectionDiagnosisUiState("Sala", isRunning = false, report = report),
                onDismiss = {}, onRecheck = {}, onAction = { chosen = it })
        }
        compose.onNodeWithText("No comprobado").assertExists()
        compose.onNodeWithText("Permiso todavía sin comprobar").assertExists()
        compose.onNodeWithText("Actualizar IP y conectar").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(DiagnosticAction.USE_NEW_ADDRESS, chosen) }
        compose.onNodeWithText("Revisar autorización").assertDoesNotExist()
    }
}
