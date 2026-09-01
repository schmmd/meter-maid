package com.schmmd.drumroll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schmmd.drumroll.data.MeterConfig

/**
 * The register as separate boxes rather than one text field.
 *
 * Correcting a single misread drum should be one keystroke, not select-all-and-retype
 * with cold hands. Digits the OCR wasn't sure about arrive blank and outlined; the
 * first of them takes focus so the common case is: glance, one key, save.
 */
@Composable
fun DigitBoxes(
    drums: String,
    agreement: List<Float>,
    config: MeterConfig,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = remember(config.drumCount) {
        List(config.drumCount) { FocusRequester() }
    }
    val firstUncertain = remember(drums, agreement) {
        (0 until config.drumCount).firstOrNull { i ->
            drums.getOrNull(i)?.isDigit() != true
        } ?: -1
    }

    LaunchedEffect(firstUncertain) {
        if (firstUncertain in focusRequesters.indices) {
            runCatching { focusRequesters[firstUncertain].requestFocus() }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until config.drumCount) {
            val ch = drums.getOrNull(i)?.takeIf { it.isDigit() }
            val low = agreement.getOrNull(i)?.let { it < com.schmmd.drumroll.ocr.PREFILL_THRESHOLD } ?: true
            val borderColor = when {
                ch == null -> WarnColor
                low -> WarnColor
                else -> MaterialTheme.colorScheme.outline
            }

            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(if (ch == null) 2.dp else 1.dp, borderColor, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = TextFieldValue(ch?.toString().orEmpty()),
                    onValueChange = { new ->
                        val digit = new.text.lastOrNull()?.takeIf(Char::isDigit)
                        val padded = drums.padEnd(config.drumCount, ' ').toCharArray()
                        padded[i] = digit ?: ' '
                        onChange(String(padded))
                        if (digit != null && i + 1 < config.drumCount) {
                            runCatching { focusRequesters[i + 1].requestFocus() }
                        }
                    },
                    modifier = Modifier.focusRequester(focusRequesters[i]),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 30.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            }
        }

        // The painted zeros. They never move, so they are shown but not editable.
        repeat(config.trailingZeros) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "0",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 30.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
