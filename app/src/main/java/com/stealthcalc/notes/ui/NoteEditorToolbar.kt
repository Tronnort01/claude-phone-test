package com.stealthcalc.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class FormatAction(
    val icon: ImageVector,
    val label: String,
    val markdownPrefix: String,
    val markdownSuffix: String = "",
)

val noteColors = listOf(
    null,                   // Default (no color)
    0xFFEF5350.toInt(),     // Red
    0xFFFF9800.toInt(),     // Orange
    0xFFFFEB3B.toInt(),     // Yellow
    0xFF4CAF50.toInt(),     // Green
    0xFF2196F3.toInt(),     // Blue
    0xFF9C27B0.toInt(),     // Purple
    0xFF795548.toInt(),     // Brown
)

@Composable
fun NoteEditorToolbar(
    onFormatAction: (prefix: String, suffix: String) -> Unit,
    onColorSelected: (Int?) -> Unit,
    selectedColor: Int?,
    modifier: Modifier = Modifier
) {
    val formatActions = listOf(
        FormatAction(Icons.Default.FormatBold, "Bold", "**", "**"),
        FormatAction(Icons.Default.FormatItalic, "Italic", "*", "*"),
        FormatAction(Icons.Default.FormatStrikethrough, "Strikethrough", "~~", "~~"),
        FormatAction(Icons.Default.Title, "Heading", "## "),
        FormatAction(Icons.Default.FormatListBulleted, "Bullet List", "- "),
        FormatAction(Icons.Default.FormatListNumbered, "Number List", "1. "),
        FormatAction(Icons.Default.CheckBox, "Checklist", "- [ ] "),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        // Format buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            formatActions.forEach { action ->
                IconButton(
                    onClick = { onFormatAction(action.markdownPrefix, action.markdownSuffix) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        action.icon,
                        contentDescription = action.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Color dots
            noteColors.forEach { color ->
                IconButton(
                    onClick = { onColorSelected(color) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = "Color",
                        tint = if (color != null) Color(color)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(if (color == selectedColor) 24.dp else 20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Column(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) { content() }
}
