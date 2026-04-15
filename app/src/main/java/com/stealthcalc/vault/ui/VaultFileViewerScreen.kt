package com.stealthcalc.vault.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.viewmodel.VaultFileViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultFileViewerScreen(
    onBack: () -> Unit,
    onMergePhoto: (baseId: String) -> Unit = {},
    viewModel: VaultFileViewerViewModel = hiltViewModel(),
) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val initialIndex by viewModel.initialIndex.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()

    // The toolbar overflow menu needs to know which file is currently
    // visible (to gate the "Merge with another photo" action — only valid
    // for PHOTO files). The pager itself is created inside ViewerPager,
    // which only composes once files have loaded; it publishes the
    // current file via [onCurrentFileChange] so the toolbar can read it.
    var currentFile by remember { mutableStateOf<VaultFile?>(null) }
    var showOverflow by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Filled in below once we have the pager — leave blank
                    // in the top-level scaffold so the back arrow still
                    // renders while files are still loading.
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (currentFile?.fileType == VaultFileType.PHOTO) {
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More",
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Merge with another photo") },
                                    onClick = {
                                        showOverflow = false
                                        onMergePhoto(currentFile.id)
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                )
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loadError != null -> {
                    Text(
                        loadError ?: "Error",
                        color = Color.White,
                        modifier = Modifier.padding(32.dp),
                    )
                }
                files.isEmpty() -> {
                    CircularProgressIndicator(color = Color.White)
                }
                else -> {
                    ViewerPager(
                        files = files,
                        initialIndex = initialIndex,
                        viewModel = viewModel,
                        onCurrentFileChange = { currentFile = it },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewerPager(
    files: List<VaultFile>,
    initialIndex: Int,
    viewModel: VaultFileViewerViewModel,
    onCurrentFileChange: (VaultFile?) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0)),
        pageCount = { files.size },
    )

    // Trim the decrypted-temp cache as the user pages so cacheDir doesn't
    // fill up on very long vault lists. Keep the current page + 2 on each
    // side hot; everything else is deleted from disk and cache. Also
    // publishes the current file up to the screen-level scaffold so the
    // toolbar overflow menu can react to it.
    LaunchedEffect(pagerState, files) {
        snapshotFlow { pagerState.currentPage }.collectLatest { page ->
            viewModel.trimCache(page, keepAround = 2)
            onCurrentFileChange(files.getOrNull(page))
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val file = files[page]
        VaultFilePage(file = file, viewModel = viewModel)
    }
}

@Composable
private fun VaultFilePage(
    file: VaultFile,
    viewModel: VaultFileViewerViewModel,
) {
    var tempFile by remember(file.id) { mutableStateOf<File?>(null) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.id) {
        val result = viewModel.decrypt(file)
        if (result != null) tempFile = result
        else error = "Failed to decrypt"
    }

    val temp = tempFile
    when {
        error != null -> Text(error ?: "", color = Color.White)
        temp == null -> CircularProgressIndicator(color = Color.White)
        else -> when (file.fileType) {
            VaultFileType.PHOTO -> PhotoView(temp)
            VaultFileType.VIDEO -> VideoPlayer(temp)
            VaultFileType.AUDIO -> AudioPlayer(file, temp)
            VaultFileType.DOCUMENT, VaultFileType.OTHER -> ExternalOpenView(file, temp)
        }
    }
}

@Composable
private fun PhotoView(tempFile: File) {
    val bitmap = remember(tempFile.absolutePath) {
        runCatching { BitmapFactory.decodeFile(tempFile.absolutePath) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text("Failed to decode image", color = Color.White)
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayer(tempFile: File) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var videoError by remember(tempFile.absolutePath) { mutableStateOf<String?>(null) }
    var isBuffering by remember(tempFile.absolutePath) { mutableStateOf(true) }

    // Build the ExoPlayer once per tempFile; release it on dispose. Unlike
    // VideoView, ExoPlayer exposes Player.STATE_BUFFERING / READY / ENDED via
    // Player.Listener so we can show a real loading spinner instead of a
    // blank black surface while MediaPlayer.prepare() runs in the background
    // (which was the "spins forever" symptom users hit through Round 4).
    val exoPlayer = remember(tempFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(tempFile)))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    // Snap back to start so a tap on play replays.
                    exoPlayer.seekTo(0)
                    exoPlayer.playWhenReady = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                AppLogger.log(
                    context,
                    "vault",
                    "VideoPlayer ExoPlayer error code=${error.errorCode} " +
                        "name=${error.errorCodeName} msg=${error.message} " +
                        "file=${tempFile.absolutePath} exists=${tempFile.exists()} " +
                        "size=${if (tempFile.exists()) tempFile.length() else -1L}"
                )
                videoError = error.errorCodeName
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Pause when the host lifecycle stops (e.g. user pages away or
    // backgrounds the activity) so a stale player doesn't keep the audio
    // track running.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && exoPlayer.isPlaying) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (videoError != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Video can't be played.",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The recording may be corrupt or in an unsupported format. " +
                    "Details saved to the crash log (Settings → Diagnostics → Export crash log).",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
        )
        if (isBuffering) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun AudioPlayer(file: VaultFile, tempFile: File) {
    val context = LocalContext.current
    // Build the MediaPlayer lazily and, crucially, catch any setup
    // failure. MediaPlayer.prepare() is synchronous and can throw
    // IOException (status=0x1 etc.) on a corrupt / empty / unsupported
    // file — previously that exception propagated through the Compose
    // recomposition and crashed the whole activity (see crash log
    // "Prepare failed.: status=0x1" at this line). We now surface the
    // failure as an error UI state and log the diagnostic so it's
    // visible from Settings → Diagnostics → Export crash log.
    val prepareResult = remember(tempFile.absolutePath) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
            }
        }.onFailure { e ->
            AppLogger.log(
                context,
                "vault",
                "AudioPlayer prepare failed: ${e.javaClass.simpleName}: ${e.message} " +
                    "file=${tempFile.absolutePath} exists=${tempFile.exists()} " +
                    "size=${if (tempFile.exists()) tempFile.length() else -1L}"
            )
        }
    }
    val player = prepareResult.getOrNull()

    if (player == null) {
        DisposableEffect(tempFile.absolutePath) {
            onDispose { /* nothing to release */ }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                file.fileName,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Audio can't be played. The recording may be corrupt or " +
                    "in an unsupported format. Details saved to the crash log " +
                    "(Settings → Diagnostics → Export crash log).",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(player) {
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    // Stop playback when the lifecycle stops (user leaves the screen).
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && player.isPlaying) {
                player.pause()
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val dur = player.duration.coerceAtLeast(1)
            progress = player.currentPosition.toFloat() / dur.toFloat()
            if (!player.isPlaying) {
                isPlaying = false
                progress = 0f
                break
            }
            delay(200)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            file.fileName,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(32.dp))
        IconButton(
            onClick = {
                if (isPlaying) {
                    player.pause()
                    isPlaying = false
                } else {
                    player.start()
                    isPlaying = true
                }
            },
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExternalOpenView(file: VaultFile, tempFile: File) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            file.fileName,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Open with")
                context.startActivity(chooser)
            } catch (_: Exception) {
                // No app available to handle this type — silently ignore.
            }
        }) {
            Text("Open with…")
        }
    }
}
