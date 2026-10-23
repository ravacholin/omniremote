package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.data.model.DeviceType
import com.example.data.model.RemoteKey
import com.example.ui.theme.*

@Composable
fun RemoteNumpad(
    deviceType: DeviceType,
    onKeyPress: (RemoteKey) -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var inputText by remember { mutableStateOf("") }

    fun handlePress(key: RemoteKey) {
        HapticUtil.triggerKeyHaptic(context)
        onKeyPress(key)
    }

    fun send() {
        if (inputText.isNotBlank()) {
            onSendText(inputText)
            inputText = ""
            focusManager.clearFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_numpad_container"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Text to TV: one field, one inverted send key.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("tv_text_input"),
                placeholder = { MonoLabel("Escribir en la TV") },
                singleLine = true,
                shape = Sharp,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Paper,
                    unfocusedBorderColor = Hairline,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Paper
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            IconKey(
                icon = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                onClick = { send() },
                modifier = Modifier.size(56.dp),
                testTag = "tv_text_send_button",
                inverted = true,
                iconSize = 18.dp
            )
        }

        // Colour function keys, rendered as four grey values.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp + GridGap * 2)
                .hairlineGrid(),
            horizontalArrangement = Arrangement.spacedBy(GridGap)
        ) {
            ColorButton(bgColor = Paper, textColor = Void, label = "A", onClick = { handlePress(RemoteKey.COLOR_RED) }, testTag = "key_color_red")
            ColorButton(bgColor = Ash, textColor = Void, label = "B", onClick = { handlePress(RemoteKey.COLOR_GREEN) }, testTag = "key_color_green")
            ColorButton(bgColor = Graphite, textColor = Paper, label = "C", onClick = { handlePress(RemoteKey.COLOR_YELLOW) }, testTag = "key_color_yellow")
            ColorButton(bgColor = Hairline, textColor = Paper, label = "D", onClick = { handlePress(RemoteKey.COLOR_BLUE) }, testTag = "key_color_blue")
        }

        // 3×4 keypad as one ruled block.
        val rows = listOf(
            listOf(Triple("1", ".@/", RemoteKey.NUM_1), Triple("2", "ABC", RemoteKey.NUM_2), Triple("3", "DEF", RemoteKey.NUM_3)),
            listOf(Triple("4", "GHI", RemoteKey.NUM_4), Triple("5", "JKL", RemoteKey.NUM_5), Triple("6", "MNO", RemoteKey.NUM_6)),
            listOf(Triple("7", "PQRS", RemoteKey.NUM_7), Triple("8", "TUV", RemoteKey.NUM_8), Triple("9", "WXYZ", RemoteKey.NUM_9)),
            listOf(Triple("INFO", "Guide", RemoteKey.INFO), Triple("0", "+", RemoteKey.NUM_0), Triple("PRE-CH", "Last", RemoteKey.NUM_PRE_CH))
        )
        val tags = mapOf(RemoteKey.INFO to "key_info", RemoteKey.NUM_PRE_CH to "key_pre_ch")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .hairlineGrid(),
            verticalArrangement = Arrangement.spacedBy(GridGap)
        ) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    row.forEach { (number, label, key) ->
                        NumButton(
                            number = number,
                            label = label,
                            onClick = { handlePress(key) },
                            testTag = tags[key] ?: "key_num_$number"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.NumButton(
    number: String,
    onClick: () -> Unit,
    testTag: String,
    label: String? = null
) {
    GridKey(onClick = onClick, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = testTag) { tint ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (number.length > 2) {
                Text(number, style = MaterialTheme.typography.labelMedium, color = tint)
            } else {
                Text(number, style = MaterialTheme.typography.headlineMedium, color = tint)
            }
            if (label != null) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tint == Paper) TextMuted else tint
                )
            }
        }
    }
}

@Composable
fun RowScope.ColorButton(
    bgColor: Color,
    textColor: Color,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    GridKey(onClick = onClick, modifier = Modifier.weight(1f).fillMaxHeight(), testTag = testTag) { tint ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (tint == Void) Void else bgColor)
                    .border(GridGap, Graphite)
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}
