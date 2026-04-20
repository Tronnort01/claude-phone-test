package com.stealthcalc.monitoring.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.PI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationMapScreen(
    onBack: () -> Unit,
    viewModel: LocationMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location Trail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.points.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No location data yet.\nEnable location collection on the agent device.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(32.dp)
                    )
                }

                else -> {
                    // Stats card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val first = state.points.first()
                            val last = state.points.last()
                            val fmt = SimpleDateFormat("MMM d h:mm a", Locale.US)
                            Text(
                                "${state.points.size} points · ${fmt.format(Date(first.timestampMs))} → ${fmt.format(Date(last.timestampMs))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    // Map canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF1A237E).copy(alpha = 0.08f))
                    ) {
                        LocationMapCanvas(points = state.points)
                    }

                    // Last known position
                    state.points.lastOrNull()?.let { last ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Last known position", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("%.6f, %.6f".format(last.latitude, last.longitude), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US).format(Date(last.timestampMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationMapCanvas(points: List<LocationPoint>) {
    val trackColor = Color(0xFF2196F3)
    val dotColorOld = Color(0xFF90CAF9)
    val dotColorNew = Color(0xFF4CAF50)

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (points.isEmpty()) return@Canvas

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }

        val latRange = (maxLat - minLat).coerceAtLeast(0.001)
        val lonRange = (maxLon - minLon).coerceAtLeast(0.001)
        val padding = 40f

        fun toOffset(lat: Double, lon: Double): Offset {
            val x = padding + ((lon - minLon) / lonRange * (size.width - 2 * padding)).toFloat()
            val y = size.height - padding - ((lat - minLat) / latRange * (size.height - 2 * padding)).toFloat()
            return Offset(x, y)
        }

        // Draw track path
        if (points.size > 1) {
            val path = Path()
            val first = toOffset(points[0].latitude, points[0].longitude)
            path.moveTo(first.x, first.y)
            points.drop(1).forEach { pt ->
                val off = toOffset(pt.latitude, pt.longitude)
                path.lineTo(off.x, off.y)
            }
            drawPath(path, color = trackColor, style = Stroke(width = 3.dp.toPx()))
        }

        // Draw dots
        points.forEachIndexed { i, pt ->
            val off = toOffset(pt.latitude, pt.longitude)
            val isLast = i == points.lastIndex
            val color = if (isLast) dotColorNew else dotColorOld
            val radius = if (isLast) 8.dp.toPx() else 5.dp.toPx()
            drawCircle(color = color, radius = radius, center = off)
            if (isLast) {
                drawCircle(color = Color.White, radius = 3.dp.toPx(), center = off)
            }
        }
    }
}
