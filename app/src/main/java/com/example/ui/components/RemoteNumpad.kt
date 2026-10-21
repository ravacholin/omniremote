package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .testTag("remote_numpad_container"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Send Text to TV Search/Password Box
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0C0C0C),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, RemoteBorderStrong)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "VIRTUAL KEYBOARD BEAM",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tv_text_input"),
                        placeholder = {
                            Text(
                                "Type text or query...",
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = TextMuted
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = RemoteBorderStrong,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Color.White
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    onSendText(inputText)
                                    inputText = ""
                                    focusManager.clearFocus()
                                }
                            }
                        )
                    )

                    Button(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSendText(inputText)
                                inputText = ""
                                focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("tv_text_send_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color.Black,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        // Color Buttons Bar (Brutalist Monochromatic Function Keys A, B, C, D)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ColorButton(bgColor = Color(0xFFE4E4E7), textColor = Color.Black, label = "[A]", onClick = { handlePress(RemoteKey.COLOR_RED) }, testTag = "key_color_red")
            ColorButton(bgColor = Color(0xFFA1A1AA), textColor = Color.Black, label = "[B]", onClick = { handlePress(RemoteKey.COLOR_GREEN) }, testTag = "key_color_green")
            ColorButton(bgColor = Color(0xFF3F3F46), textColor = Color.White, label = "[C]", onClick = { handlePress(RemoteKey.COLOR_YELLOW) }, testTag = "key_color_yellow")
            ColorButton(bgColor = Color(0xFF18181B), textColor = Color.White, label = "[D]", onClick = { handlePress(RemoteKey.COLOR_BLUE) }, testTag = "key_color_blue")
        }

        // 3x4 Number Keypad Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NumButton(number = "1", label = ".@/", onClick = { handlePress(RemoteKey.NUM_1) }, testTag = "key_num_1")
                NumButton(number = "2", label = "ABC", onClick = { handlePress(RemoteKey.NUM_2) }, testTag = "key_num_2")
                NumButton(number = "3", label = "DEF", onClick = { handlePress(RemoteKey.NUM_3) }, testTag = "key_num_3")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NumButton(number = "4", label = "GHI", onClick = { handlePress(RemoteKey.NUM_4) }, testTag = "key_num_4")
                NumButton(number = "5", label = "JKL", onClick = { handlePress(RemoteKey.NUM_5) }, testTag = "key_num_5")
                NumButton(number = "6", label = "MNO", onClick = { handlePress(RemoteKey.NUM_6) }, testTag = "key_num_6")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NumButton(number = "7", label = "PQRS", onClick = { handlePress(RemoteKey.NUM_7) }, testTag = "key_num_7")
                NumButton(number = "8", label = "TUV", onClick = { handlePress(RemoteKey.NUM_8) }, testTag = "key_num_8")
                NumButton(number = "9", label = "WXYZ", onClick = { handlePress(RemoteKey.NUM_9) }, testTag = "key_num_9")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NumButton(number = "INFO", label = "Guide", onClick = { handlePress(RemoteKey.INFO) }, testTag = "key_info")
                NumButton(number = "0", label = "+", onClick = { handlePress(RemoteKey.NUM_0) }, testTag = "key_num_0")
                NumButton(number = "PRE-CH", label = "Last", onClick = { handlePress(RemoteKey.NUM_PRE_CH) }, testTag = "key_pre_ch")
            }
        }
    }
}

@Composable
fun NumButton(
    number: String,
    onClick: () -> Unit,
    testTag: String,
    label: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "num_btn_scale"
    )

    Surface(
        modifier = Modifier
            .size(width = 88.dp, height = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .testTag(testTag),
        color = if (isPressed) Color.White else Color(0xFF101010),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPressed) Color.White else RemoteBorderStrong
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = if (isPressed) Color.Black else Color.White,
                    fontSize = if (number.length > 2) 12.sp else 18.sp,
                    letterSpacing = 1.sp
                )
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = if (isPressed) Color(0xFF444444) else TextMuted
                    )
                )
            }
        }
    }
}

@Composable
fun ColorButton(
    bgColor: Color,
    textColor: Color,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "color_btn_scale"
    )

    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 30.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPressed) Color.White else bgColor)
            .border(1.dp, RemoteBorderStrong, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (isPressed) Color.Black else textColor,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
}
