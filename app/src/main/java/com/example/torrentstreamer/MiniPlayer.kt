package com.example.torrentstreamer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.torrentstreamer.data.WatchHistory

@Composable
fun MiniPlayer(
    session: WatchHistory,
    onTogglePlay: () -> Unit,
    onClick: () -> Unit
) {
    val isPlaying by PlaybackService.isPlayerPlaying.collectAsState()

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
    ) {
        Column {
            // ЕКСПРЕСИВНА ЛІНІЯ ПРОГРЕСУ (У самому верху)
            val progress = if (session.duration > 0) session.lastPosition.toFloat() / session.duration.toFloat() else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                strokeCap = StrokeCap.Round
            )

            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = "ПРОДОВЖИТИ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row {
                    IconButton(onClick = { PlaybackService.playerInstance?.seekTo(PlaybackService.playerInstance!!.currentPosition - 10000) }) {
                        Icon(Icons.Default.FastRewind, null, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { PlaybackService.playerInstance?.seekTo(PlaybackService.playerInstance!!.currentPosition + 10000) }) {
                        Icon(Icons.Default.FastForward, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}