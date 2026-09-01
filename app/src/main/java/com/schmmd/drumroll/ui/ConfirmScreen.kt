package com.schmmd.drumroll.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schmmd.drumroll.data.MeterConfig
import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.util.Issue
import com.schmmd.drumroll.util.Severity
import com.schmmd.drumroll.util.Validation
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ConfirmState(
    val drums: String,
    val agreement: List<Float>,
    val readingDate: LocalDate,
    val capturedAt: LocalDate,
    val note: String = "",
    val previous: Reading? = null,
    val duplicate: Boolean = false,
    val ocrSummary: String? = null,
)

/**
 * The only screen the user must touch. Everything upstream of it is a suggestion;
 * everything downstream is a fact they approved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmScreen(
    state: ConfirmState,
    config: MeterConfig,
    onDrumsChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val issues = remember(state.drums, state.readingDate, state.previous, state.duplicate) {
        Validation.check(
            drums = state.drums.filter(Char::isDigit),
            readingDate = state.readingDate,
            capturedAt = state.capturedAt,
            previous = state.previous,
            config = config,
            duplicateOnDate = state.duplicate,
        )
    }
    val blocked = Validation.blocking(issues)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm reading") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onSave,
                    enabled = !blocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                ) { Text("Save reading", fontSize = 16.sp) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Text("Reading", style = MaterialTheme.typography.labelMedium)
            DigitBoxes(
                drums = state.drums,
                agreement = state.agreement,
                config = config,
                onChange = onDrumsChange,
            )

            state.ocrSummary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            issues.forEach { issue -> IssueRow(issue) }

            HorizontalDivider()

            DateRow(state.readingDate, onDateChange)

            state.previous?.let { prev ->
                val drums = state.drums.filter(Char::isDigit)
                if (drums.length == config.drumCount) {
                    val days = ChronoUnit.DAYS.between(
                        LocalDate.parse(prev.readingDate), state.readingDate
                    )
                    val delta = config.toValue(drums) - prev.value
                    val rate = if (days > 0) "${delta / days} gal/day" else "same day"
                    LabelledValue(
                        "Since ${prev.readingDate}",
                        "${if (delta >= 0) "+" else ""}$delta · $rate",
                    )
                } else {
                    LabelledValue("Previous", config.display(prev.value))
                }
            } ?: LabelledValue("Previous", "none — this is the first reading")

            OutlinedTextField(
                value = state.note,
                onValueChange = onNoteChange,
                label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IssueRow(issue: Issue) {
    val color = if (issue.severity == Severity.BLOCK) {
        MaterialTheme.colorScheme.error
    } else {
        WarnColor
    }
    Text(issue.message, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DateRow(date: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Date", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(date.minusDays(1)) }) { Text("−1d") }
            Text(date.toString(), fontFamily = FontFamily.Monospace)
            TextButton(onClick = { onChange(date.plusDays(1)) }) { Text("+1d") }
        }
    }
}
