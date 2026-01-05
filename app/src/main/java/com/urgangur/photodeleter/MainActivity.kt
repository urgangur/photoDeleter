package com.urgangur.photodeleter

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.urgangur.photodeleter.ui.theme.PhotoDeleterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// --- Activity ---
class MainActivity : ComponentActivity() {
    private val viewModel: PhotoViewModel by viewModels()

    val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) viewModel.onPhotosDeletedSuccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoDeleterTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhotoDeleterRoot(viewModel)
                }
            }
        }
    }
}

// --- Navigation & Root ---
enum class Screen { Main, Trash }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDeleterRoot(viewModel: PhotoViewModel) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasPhotoPermission(context)) }
    var currentScreen by remember { mutableStateOf(Screen.Main) }

    val prefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    var showTutorial by remember { mutableStateOf(prefs.getBoolean("first_launch", true)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.loadPhotos(context)
    }

    if (!hasPermission) {
        PermissionRequestView {
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            permissionLauncher.launch(perm)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (currentScreen) {
            Screen.Main -> MainSwipeScreen(viewModel) { currentScreen = Screen.Trash }
            Screen.Trash -> TrashCanScreen(viewModel) { currentScreen = Screen.Main }
        }

        if (showTutorial) {
            TutorialOverlay {
                showTutorial = false
                prefs.edit().putBoolean("first_launch", false).apply()
            }
        }
    }
}

// --- Main Screens ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSwipeScreen(viewModel: PhotoViewModel, onGoToTrash: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("PhotoDeleter") },
                actions = {
                    IconButton(onClick = onGoToTrash) {
                        BadgedBox(badge = { if (viewModel.trashBin.isNotEmpty()) Badge { Text("${viewModel.trashBin.size}") } }) {
                            Icon(Icons.Default.Delete, contentDescription = "垃圾桶")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp)) {
            if (viewModel.photos.isEmpty()) {
                EmptyPhotosView(viewModel.trashBin.size, onGoToTrash) { viewModel.loadPhotos(context) }
            } else {
                viewModel.photos.take(2).reversed().forEach { photo ->
                    key(photo.id) {
                        SwipeablePhotoCard(
                            photo = photo,
                            onSwipeLeft = { viewModel.moveToTrash(photo) },
                            onSwipeRight = { viewModel.keepPhoto(photo) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashCanScreen(viewModel: PhotoViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待粉碎清單 (${viewModel.trashBin.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.Refresh, "返回") } },
                actions = {
                    if (viewModel.trashBin.isNotEmpty()) {
                        TextButton(onClick = {
                            viewModel.emptyTrash(context) { pi ->
                                val req = IntentSenderRequest.Builder(pi.intentSender).build()
                                (context as? MainActivity)?.deleteLauncher?.launch(req)
                            }
                        }) { Text("徹底粉碎", color = MaterialTheme.colorScheme.error) }
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            items(viewModel.trashBin, key = { it.id }) { photo ->
                Box(modifier = Modifier.aspectRatio(1f).padding(2.dp)) {
                    AsyncImage(model = photo.uri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    IconButton(
                        onClick = { viewModel.recoverFromTrash(photo) },
                        modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(0.5f), RoundedCornerShape(50))
                    ) { Icon(Icons.Default.Refresh, "救回", tint = Color.White) }
                }
            }
        }
    }
}

// --- Components ---
@Composable
fun SwipeablePhotoCard(photo: PhotoItem, onSwipeLeft: () -> Unit, onSwipeRight: () -> Unit) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = 250f
    var showFullPreview by remember { mutableStateOf(false) }

    val overlayColor by animateColorAsState(
        targetValue = when {
            offsetX.value > threshold -> Color.Green.copy(alpha = 0.2f)
            offsetX.value < -threshold -> Color.Red.copy(alpha = 0.2f)
            else -> Color.Transparent
        }, label = "swipe_overlay"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer {
                rotationZ = offsetX.value / 45
                alpha = 1f - (abs(offsetX.value) / 1200f).coerceIn(0f, 0.8f)
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { showFullPreview = true }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                offsetX.value > threshold -> { offsetX.animateTo(1500f, tween(300)); onSwipeRight() }
                                offsetX.value < -threshold -> { offsetX.animateTo(-1500f, tween(300)); onSwipeLeft() }
                                else -> offsetX.animateTo(0f, tween(200))
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount.x) }
                    }
                )
            }
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(model = photo.uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(overlayColor))
    }

    if (showFullPreview) {
        FullScreenPreview(photo.uri) { showFullPreview = false }
    }
}

@Composable
fun FullScreenPreview(uri: Uri, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoomChange, offsetChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            if (scale > 1f) offset += offsetChange else offset = Offset.Zero
        }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f },
                        onTap = { if (scale <= 1f) onDismiss() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = uri, contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y).transformable(state = state),
                contentScale = ContentScale.Fit
            )
            if (scale <= 1f) Text("雙指縮放 / 雙擊放大\n點擊背景返回", color = Color.White.copy(0.5f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp))
        }
    }
}

// --- Simple Views ---
@Composable
fun EmptyPhotosView(trashSize: Int, onGoToTrash: () -> Unit, onRefresh: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painter = painterResource(id = R.drawable.baseline_delete_sweep_24), contentDescription = null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        Spacer(Modifier.height(24.dp))
        Text("所有照片審核完畢！", style = MaterialTheme.typography.titleMedium)
        Text("垃圾桶內有 $trashSize 張照片待粉碎", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onGoToTrash) { Text("前往垃圾桶") }
        TextButton(onClick = onRefresh) { Text("重新掃描") }
    }
}

@Composable
fun TutorialOverlay(onDismiss: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.85f)) {
        Column(modifier = Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("如何使用？", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(40.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                TutorialItem("← 左滑", "準備刪除", Color.Red)
                TutorialItem("右滑 →", "保留照片", Color.Green)
            }
            Spacer(Modifier.height(40.dp))
            TutorialItem("單擊相片", "放大檢查細節", Color.White)
            Spacer(Modifier.height(60.dp))
            Button(onClick = onDismiss) { Text("開始使用") }
        }
    }
}

@Composable
fun TutorialItem(title: String, desc: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = color, style = MaterialTheme.typography.titleLarge)
        Text(desc, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PermissionRequestView(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onClick) { Text("授權存取相片") }
    }
}

// --- ViewModel & Logic ---
class PhotoViewModel : ViewModel() {
    var photos = mutableStateListOf<PhotoItem>()
    var trashBin = mutableStateListOf<PhotoItem>()

    fun loadPhotos(context: Context) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val list = mutableListOf<PhotoItem>()
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                    null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        list.add(PhotoItem(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)))
                    }
                }
                list
            }
            photos.clear(); photos.addAll(result)
        }
    }

    fun moveToTrash(photo: PhotoItem) { trashBin.add(0, photo); photos.remove(photo) }
    fun keepPhoto(photo: PhotoItem) { photos.remove(photo) }
    fun recoverFromTrash(photo: PhotoItem) { trashBin.remove(photo); photos.add(0, photo) }
    fun onPhotosDeletedSuccess() { trashBin.clear() }

    fun emptyTrash(context: Context, onPendingIntent: (android.app.PendingIntent) -> Unit) {
        if (trashBin.isEmpty()) return
        try {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, trashBin.map { it.uri })
            onPendingIntent(pi)
        } catch (e: Exception) { Log.e("PhotoDebug", "刪除失敗", e) }
    }
}

data class PhotoItem(val id: Long, val uri: Uri)

fun hasPhotoPermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}