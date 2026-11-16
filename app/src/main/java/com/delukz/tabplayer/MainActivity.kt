package com.delukz.tabplayer

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TabPlayerApp()
                }
            }
        }
    }
}

@Composable
private fun TabPlayerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { PlayerEngine() }

    var song by remember { mutableStateOf(DemoSong.create()) }
    var fileLabel by remember { mutableStateOf("Built-in demo song") }
    var status by remember { mutableStateOf<String?>(null) }
    var trackIndex by remember { mutableIntStateOf(0) }
    var family by remember { mutableStateOf(SoundFamily.GUITAR) }
    var variant by remember { mutableIntStateOf(0) }
    var bpm by remember { mutableIntStateOf(song.tempoBpm) }
    var loop by remember { mutableStateOf(false) }

    val currentTick by engine.positionTicks.collectAsState()
    val isPlaying by engine.isPlaying.collectAsState()
    val track = song.tracks.getOrNull(trackIndex) ?: song.tracks.first()

    DisposableEffect(Unit) { onDispose { engine.release() } }

    LaunchedEffect(song, trackIndex, family, variant) {
        engine.configure(song, trackIndex, family, variant)
    }
    LaunchedEffect(bpm) { engine.bpm = bpm }
    LaunchedEffect(loop) { engine.loop = loop }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    var n = uri.lastPathSegment ?: "tab file"
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { n = it }
                    }
                    n
                }
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.readBytes()
                        ?: throw TabParseException("Could not read that file")
                }
                val loaded = withContext(Dispatchers.Default) { SongLoader.load(name, bytes) }
                engine.stop()
                song = loaded
                fileLabel = name
                trackIndex = 0
                family = defaultFamilyFor(loaded.tracks.first().kind)
                variant = 0
                bpm = loaded.tempoBpm
                status = "Loaded ${loaded.sourceFormat}: ${loaded.tracks.size} track(s), ${loaded.measures.size} measures"
            } catch (e: Exception) {
                status = "Couldn't open that file: ${e.message}"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(song.artist, fileLabel).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Open tab")
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        // Track picker
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            song.tracks.forEachIndexed { i, t ->
                FilterChip(
                    selected = i == trackIndex,
                    onClick = {
                        trackIndex = i
                        family = defaultFamilyFor(t.kind)
                        variant = 0
                    },
                    label = { Text(t.name.ifBlank { "Track ${i + 1}" }) },
                )
            }
        }

        // Sound family + variant
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SoundFamily.values().forEach { f ->
                FilterChip(
                    selected = f == family,
                    onClick = { family = f; variant = 0 },
                    label = { Text(SOUND_FAMILY_LABELS[f] ?: f.name) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SOUND_VARIANTS[family].orEmpty().forEachIndexed { i, name ->
                FilterChip(
                    selected = i == variant,
                    onClick = { variant = i },
                    label = { Text(name) },
                )
            }
        }

        ScoreView(
            song = song,
            track = track,
            currentTick = currentTick,
            onSeek = { engine.seekTo(it) },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp),
        )

        // Transport
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { engine.stop() }) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
            }
            IconButton(onClick = { if (isPlaying) engine.pause() else engine.play() }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { loop = !loop }) {
                Icon(
                    Icons.Filled.Repeat, contentDescription = "Loop",
                    tint = if (loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val measureNum = song.measures.indexOfFirst {
                currentTick >= it.startTick && currentTick < it.startTick + it.lengthTicks
            } + 1
            Text(
                "Bar ${if (measureNum == 0) song.measures.size else measureNum}/${song.measures.size}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Tempo
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Tempo", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = bpm.toFloat(),
                onValueChange = { bpm = it.toInt() },
                valueRange = 40f..200f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("$bpm BPM", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "Song tempo: ${song.tempoBpm} BPM • Tap the score to jump • One track plays at a time",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
