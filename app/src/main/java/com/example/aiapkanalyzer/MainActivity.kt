package com.example.aiapkanalyzer

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val context: Context = this

        // 启动时检查悬浮球开关
        val appConfig = AppConfig.load(context)
        if (appConfig.floatingEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(context)) {
                startActivity(Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                FloatingWidgetService.start(context)
            }
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: ChatViewModel = viewModel { ChatViewModel(context) }
                    val showArtifacts by vm.artifactsState.collectAsState()
                    val showSettings by vm.settingsState.collectAsState()
                    var currentPage by remember { mutableStateOf("chat") }

                    // ---- 启动时强制引导：workDirUri 为空就弹 OpenDocumentTree ----
                    val cfg = vm.config
                    var workDirBootstrap by remember { mutableStateOf(cfg.workDirUri.isBlank()) }
                    val scope = rememberCoroutineScope()
                    val treeLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { uri: Uri? ->
                        if (uri != null) {
                            vm.setWorkDirUri(uri.toString())
                            workDirBootstrap = false
                        }
                    }
                    // ---- SAF 权限健康检查（冷启动：非空 workDirUri 若无效自动回引导页） ----
                    LaunchedEffect(Unit) {
                        if (cfg.workDirUri.isNotBlank()) {
                            val (st, _) = vm.checkWorkDir(cfg.workDirUri)
                            when (st) {
                                WorkDirCheckStatus.OK, WorkDirCheckStatus.EMPTY, WorkDirCheckStatus.UNCHECKED -> {
                                    // OK / EMPTY 维持现状：EMPTY 会被上面 workDirBootstrap=true 触发引导页
                                }
                                else -> {
                                    // NO_PERMISSION / NOT_FOUND / WRITE_FAIL / UNKNOWN_ERR → 重新弹引导页
                                    workDirBootstrap = true
                                }
                            }
                        }
                    }
                    if (workDirBootstrap) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "选择一个自定义工作目录",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "AI 生成/修改的所有文件、产物、临时缓存都会保存在你选的这个文件夹里，方便在手机里随时查找和管理。\n\n推荐选择：「内部存储」下自建的文件夹（例如 AiAssistWork）。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { treeLauncher.launch(null) },
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("选择工作目录", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(14.dp))
                            TextButton(onClick = { workDirBootstrap = false }) {
                                Text("先跳过（之后在「我的→模型设置→工作目录」里随时设置）")
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "提示：选中目录后系统会提示「允许应用访问」，请点允许。权限会被持久保存，下次启动直接生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        when {
                            showSettings.visible -> SettingsScreen(vm)
                            showArtifacts.visible -> ArtifactsScreen(vm, onBootstrap = {
                                // 工作目录页内部如果发现 workDir 没配置，想直接跳 SAF
                                workDirBootstrap = true
                            })
                            currentPage == "profile" -> ProfileScreen(vm, onBack = { currentPage = "chat" })
                            else -> ChatScreen(
                                vm,
                                onOpenArtifacts = vm::openArtifacts,
                                onOpenProfile = { currentPage = "profile" }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: ChatViewModel, onOpenArtifacts: () -> Unit, onOpenProfile: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf(listOf<Int>()) }
    var currentMatch by remember { mutableStateOf(-1) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(state.snackbarMessage) {
        if (state.snackbarMessage.isNotBlank()) {
            snackbarHostState.showSnackbar(state.snackbarMessage)
            vm.clearSnackbar()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIndex) else it.lastPathSegment ?: "file"
            } ?: it.lastPathSegment ?: "file"
            val mimeType = context.contentResolver.getType(it) ?: "*/*"
            val type = when {
                mimeType.startsWith("image/") -> AttachmentType.IMAGE
                mimeType.startsWith("video/") -> AttachmentType.VIDEO
                else -> AttachmentType.FILE
            }
            vm.addAttachment(PendingAttachment(it, name, mimeType, type))
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            capturedImageUri?.let { uri ->
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                vm.addAttachment(PendingAttachment(uri, "IMG_${System.currentTimeMillis()}.jpg", mimeType, AttachmentType.IMAGE))
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) cursor.getString(nameIndex) else uri.lastPathSegment ?: "file"
            } ?: uri.lastPathSegment ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "*/*"
            val type = when {
                mimeType.startsWith("image/") -> AttachmentType.IMAGE
                mimeType.startsWith("video/") -> AttachmentType.VIDEO
                else -> AttachmentType.FILE
            }
            vm.addAttachment(PendingAttachment(uri, name, mimeType, type))
        }
    }

    LaunchedEffect(state.messages.size, state.streamingText) {
        if (state.messages.isNotEmpty() || state.streamingText.isNotEmpty()) {
            listState.animateScrollToItem((state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        // 顶栏：状态光点 + 图标按钮
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state.connectionState, onClick = { vm.reconnect() })
            Text(
                text = state.status,
                style = MaterialTheme.typography.labelSmall,
                color = when (state.connectionState) {
                    ConnectionState.DISCONNECTED -> Color(0xFFE53935)
                    ConnectionState.CONNECTING -> Color(0xFFFFA000)
                    ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                },
                modifier = Modifier.padding(start = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            if (state.isBusy) {
                IconButton(onClick = vm::stopGeneration) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) { searchQuery = ""; searchMatches = emptyList(); currentMatch = -1 } }) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onOpenArtifacts) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "工作目录")
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onOpenProfile) {
                Icon(Icons.Filled.Person, contentDescription = "我的")
            }
        }

        // 搜索抽屉
        AnimatedVisibility(visible = searchVisible, enter = expandVertically(), exit = shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索推理过程...") },
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = ""; searchMatches = emptyList(); currentMatch = -1 }) {
                                Icon(Icons.Filled.Close, contentDescription = "清除")
                            }
                        }
                    }
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = {
                    if (searchQuery.isBlank()) {
                        searchMatches = emptyList(); currentMatch = -1
                    } else {
                        searchMatches = state.messages.indices.filter {
                            state.messages[it].content.contains(searchQuery, ignoreCase = true)
                        }
                        currentMatch = if (searchMatches.isNotEmpty()) 0 else -1
                        if (currentMatch >= 0) scope.launch { listState.animateScrollToItem(searchMatches[currentMatch]) }
                    }
                }) {
                    Icon(Icons.Filled.Search, contentDescription = "查找")
                }
                if (searchMatches.isNotEmpty()) {
                    Text("${currentMatch + 1}/${searchMatches.size}", style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = {
                        currentMatch = (currentMatch + 1) % searchMatches.size
                        scope.launch { listState.animateScrollToItem(searchMatches[currentMatch]) }
                    }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "下一个")
                    }
                }
            }
        }

        // 主内容区：左侧关键点导航 + 右侧消息列表
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(state.messages) { index, msg ->
                    MessageBubble(
                        msg,
                        keyword = if (currentMatch >= 0 && searchMatches.getOrNull(currentMatch) == index) searchQuery else "",
                        isHighlighted = currentMatch >= 0 && searchMatches.getOrNull(currentMatch) == index
                    )
                }
                if (state.streamingText.isNotEmpty()) {
                    item { MessageBubble(ChatMessage("assistant", state.streamingText)) }
                }
            }
            // 左侧关键点导航
            KeyPointNav(
                messages = state.messages,
                listState = listState,
                modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().width(20.dp)
            )
        }

        // 附件预览区
        if (state.pendingAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(state.pendingAttachments) { index, att ->
                    AttachmentChip(
                        attachment = att,
                        onRemove = { vm.removeAttachment(index) }
                    )
                }
            }
        }

        // 底部输入区：折叠/展开
        if (state.inputCollapsed && state.isBusy) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
                FloatingActionButton(onClick = vm::toggleInputCollapsed, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "展开输入")
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 附件菜单按钮
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }, enabled = !state.isBusy) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "附件")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("选择文件") },
                                leadingIcon = { Icon(Icons.Filled.Description, null) },
                                onClick = { showMenu = false; filePickerLauncher.launch("*/*") }
                            )
                            DropdownMenuItem(
                                text = { Text("拍照") },
                                leadingIcon = { Icon(Icons.Filled.CameraAlt, null) },
                                onClick = {
                                    showMenu = false
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider",
                                        java.io.File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                                    )
                                    capturedImageUri = uri
                                    cameraLauncher.launch(uri)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("相册图片/视频") },
                                leadingIcon = { Icon(Icons.Filled.Image, null) },
                                onClick = { showMenu = false; galleryLauncher.launch("image/* video/*") }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.input,
                        onValueChange = vm::onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("请输入...") },
                        enabled = !state.isBusy,
                        maxLines = 4
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.isBusy) {
                        IconButton(onClick = vm::stopGeneration) {
                            Icon(Icons.Filled.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = vm::send, enabled = state.input.isNotBlank() || state.pendingAttachments.isNotEmpty()) {
                            Icon(Icons.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
    )
}

}

@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (attachment.type) {
                AttachmentType.IMAGE -> Color(0xFFE3F2FD)
                AttachmentType.VIDEO -> Color(0xFFF3E5F5)
                AttachmentType.FILE -> Color(0xFFE8F5E9)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (attachment.type == AttachmentType.IMAGE) {
                Image(
                    bitmap = loadThumbnail(attachment.uri, 80),
                    contentDescription = attachment.name,
                    modifier = Modifier.size(60.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(60.dp).clip(RoundedCornerShape(4.dp)).background(
                        when (attachment.type) {
                            AttachmentType.VIDEO -> Color(0xFFE1BEE7)
                            else -> Color(0xFFC8E6C9)
                        }
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when (attachment.type) {
                            AttachmentType.VIDEO -> Icons.Filled.VideoLibrary
                            else -> Icons.Filled.Description
                        },
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = when (attachment.type) {
                            AttachmentType.VIDEO -> Color(0xFF7B1FA2)
                            else -> Color(0xFF388E3C)
                        }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 60.dp)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun loadThumbnail(uri: Uri, size: Int): androidx.compose.ui.graphics.ImageBitmap {
    val context = LocalContext.current
    return remember(uri, size) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            val sampleSize = calculateInSampleSize(options, size, size)
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                })
            }?.asImageBitmap() ?: androidx.compose.ui.graphics.ImageBitmap(size, size)
        } catch (_: Exception) {
            androidx.compose.ui.graphics.ImageBitmap(size, size)
        }
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height, width) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@Composable
private fun ArtifactsScreen(
    vm: ChatViewModel,
    onBootstrap: () -> Unit = {}
) {
    val artifacts by vm.artifactsState.collectAsState()
    val context = LocalContext.current
    BackHandler {
        if (artifacts.selectMode) vm.exitSelectMode() else vm.closeArtifacts()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    // 工作目录页内部：直接调 SAF 选择目录（不用绕到 ProfileScreen）
    val localTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.setWorkDirUri(uri.toString())
            vm.refreshWorkDir()
        }
    }

    LaunchedEffect(artifacts.snackbarMessage) {
        if (artifacts.snackbarMessage.isNotBlank()) {
            snackbarHostState.showSnackbar(artifacts.snackbarMessage)
            vm.clearArtifactsSnackbar()
        }
    }

    var renameTarget by remember { mutableStateOf<WorkFile?>(null) }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (artifacts.selectMode) vm.exitSelectMode() else vm.closeArtifacts()
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    if (artifacts.selectMode) "已选中 ${artifacts.selectedNames.size} 项" else "工作目录",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.weight(1f))
                if (!artifacts.selectMode) {
                    TextButton(onClick = { vm.enterSelectMode() }) {
                        Text("管理")
                    }
                } else {
                    IconButton(onClick = { vm.selectAllFiles() }) {
                        Icon(Icons.Filled.DoneAll, contentDescription = "全选")
                    }
                    IconButton(onClick = { vm.invertFileSelection() }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "反选")
                    }
                    IconButton(onClick = {
                        if (artifacts.selectedNames.isNotEmpty()) {
                            vm.deleteSelectedFiles()
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = if (artifacts.selectedNames.isNotEmpty()) Color(0xFFD32F2F) else Color.Gray)
                    }
                }
                IconButton(onClick = { vm.refreshWorkDir() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }

            if (artifacts.error.isNotBlank()) {
                Text(
                    text = artifacts.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(Color(0xFFFFE0E0), RoundedCornerShape(8.dp)).padding(12.dp),
                    color = Color(0xFFB00020),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val hasWorkDir = vm.config.workDirUri.isNotBlank()
            if (!hasWorkDir) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text("未设置工作目录", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "工作目录用于保存 AI 生成的所有文件，选一个你记得住的文件夹吧（例如内部存储/AiAssistWork）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { localTreeLauncher.launch(null) }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("立即选择目录")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onBootstrap,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("返回首次启动引导页")
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when {
                        artifacts.loading && artifacts.files.isEmpty() -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        artifacts.files.isEmpty() -> {
                            Text(
                                text = "工作目录为空",
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(artifacts.files) { file ->
                                    WorkFileCard(
                                        file = file,
                                        vm = vm,
                                        selectMode = artifacts.selectMode,
                                        isSelected = file.name in artifacts.selectedNames,
                                        onRename = { renameTarget = file }
                                    )
                                }
                                item { Spacer(Modifier.height(16.dp)) }
                            }
                        }
                    }
                }
            }
        }

        if (renameTarget != null) {
            var newName by remember { mutableStateOf(renameTarget!!.name) }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("重命名") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val file = renameTarget!!
                        vm.renameFile(file.name, newName.trim())
                        renameTarget = null
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text("取消")
                    }
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

@Composable
private fun WorkFileCard(
    file: WorkFile,
    vm: ChatViewModel,
    selectMode: Boolean = false,
    isSelected: Boolean = false,
    onRename: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { vm.toggleFileSelection(file.name) }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(
                        when {
                            file.isApk -> Color(0xFF2196F3)
                            file.isText -> Color(0xFF4CAF50)
                            else -> Color(0xFF9E9E9E)
                        }
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when {
                            file.isApk -> Icons.Filled.Android
                            file.isText -> Icons.Filled.Edit
                            else -> Icons.Filled.FolderOpen
                        },
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${humanReadableSize(file.sizeBytes)}  ·  ${formatDateTime(file.lastModified)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!selectMode) {
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.openWorkFile(file) }, modifier = Modifier.weight(1f)) {
                        Text(if (file.isApk) "安装" else "打开")
                    }
                    OutlinedButton(onClick = { vm.analyzeWorkFile(file) }, modifier = Modifier.weight(1f)) {
                        Text("分析")
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRename,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重命名")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: ChatViewModel) {
    val settings by vm.settingsState.collectAsState()
    val tools by vm.toolsState.collectAsState()
    val scrollState = rememberScrollState()
    BackHandler { vm.closeSettings() }

    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }

    val presets = when (settings.currentProvider) {
        "doubao" -> listOf(
            "doubao-1.5-pro-32k",
            "doubao-1.5-lite-32k",
            "doubao-1.5-pro-256k",
            "doubao-seed-1.6",
            "doubao-seed-2-1-pro-260628",
            "doubao-1.5-vision-pro-32k"
        )
        "deepseek" -> listOf("deepseek-chat", "deepseek-reasoner")
        "qianwen" -> listOf(
            "qwen-turbo", "qwen-plus", "qwen-max",
            "qwen-long", "qwen-vl-plus", "qwen-vl-max"
        )
        else -> emptyList()
    }
    val onlineModels = tools.modelsResult.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("[") && !it.contains("失败") && !it.contains("查询") }
    val modelOptions = (presets + onlineModels).distinct()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::closeSettings) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box {
                OutlinedTextField(
                    value = settings.currentProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("供应商") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { providerExpanded = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择供应商")
                        }
                    }
                )
                DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                    settings.providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p) },
                            onClick = { vm.onProviderChange(p); providerExpanded = false }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = vm::onApiKeyChange,
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = vm::onBaseUrlChange,
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Box {
                Column {
                    OutlinedTextField(
                        value = settings.model,
                        onValueChange = vm::onModelChange,
                        label = { Text("模型") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { modelExpanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择模型")
                            }
                        }
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = vm::queryModels) { Text("拉取在线模型") }
                    }
                }
                DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    if (modelOptions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("（无可用模型）") },
                            onClick = { modelExpanded = false }
                        )
                    } else {
                        modelOptions.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = { vm.onModelChange(m); modelExpanded = false }
                            )
                        }
                    }
                }
            }

            Box {
                OutlinedTextField(
                    value = settings.apiMode,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("API 模式") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { modeExpanded = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择 API 模式")
                        }
                    }
                )
                DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                    listOf("chat", "responses").forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode) },
                            onClick = { vm.onApiModeChange(mode); modeExpanded = false }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用 MCP", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.mcpEnabled,
                    onCheckedChange = { enabled -> vm.onMcpEnabledChange(enabled) }
                )
            }

            OutlinedTextField(
                value = settings.mcpUrl,
                onValueChange = vm::onMcpUrlChange,
                label = { Text("MCP 地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = settings.maxIterations.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.onMaxIterChange(it) } },
                label = { Text("最大迭代") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = settings.temperature.toString(),
                onValueChange = { v -> v.toFloatOrNull()?.let { vm.onTemperatureChange(it) } },
                label = { Text("温度") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::saveSettings) { Text("保存") }
                Spacer(Modifier.width(12.dp))
                if (settings.saveResult.isNotEmpty()) {
                    Text(settings.saveResult, style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(onClick = vm::reconnect) { Text("重新连接") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, keyword: String = "", isHighlighted: Boolean = false) {
    val isUser = msg.role == "user"
    val bg = if (isHighlighted) Color(0xFFFFF59D).copy(alpha = 0.3f)
             else if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
             else MaterialTheme.colorScheme.surfaceVariant
    val align = if (isUser) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = align) {
        Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(8.dp)) {
                if (msg.imageUris.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(bottom = if (msg.content.isNotBlank()) 4.dp else 0.dp)
                    ) {
                        items(msg.imageUris) { uri ->
                            Image(
                                bitmap = loadThumbnail(uri, 120),
                                contentDescription = "图片",
                                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                if (msg.content.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = if (keyword.isNotBlank()) highlightKeyword(msg.content, keyword) else AnnotatedString(msg.content),
                            fontFamily = if (msg.role == "tool") FontFamily.Monospace else null,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

private fun humanReadableSize(bytes: Long): String =
    if (bytes < 1024) "$bytes B"
    else if (bytes < 1024 * 1024) String.format("%.1f KB", bytes / 1024.0)
    else if (bytes < 1024L * 1024L * 1024L) String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

private fun formatDateTime(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

private fun highlightKeyword(text: String, keyword: String): AnnotatedString {
    if (keyword.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        var pos = 0
        val lower = text.lowercase()
        val kw = keyword.lowercase()
        while (pos < text.length) {
            val idx = lower.indexOf(kw, pos)
            if (idx < 0) { append(text.substring(pos)); break }
            append(text.substring(pos, idx))
            withStyle(SpanStyle(background = Color(0xFFFFF59D), color = Color.Black)) {
                append(text.substring(idx, idx + keyword.length))
            }
            pos = idx + keyword.length
        }
    }
}

@Composable
private fun StatusDot(state: ConnectionState, onClick: (() -> Unit)? = null) {
    val color = when (state) {
        ConnectionState.DISCONNECTED -> Color(0xFFE53935)
        ConnectionState.CONNECTING -> Color(0xFFFFA000)
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
    }
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (state == ConnectionState.CONNECTING) color.copy(alpha = alpha) else color)
        )
    }
}

@Composable
private fun KeyPointNav(messages: List<ChatMessage>, listState: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // 提取关键点：每条 user/tool 消息 和 assistant 消息开头摘要
    val keyPoints = remember(messages) {
        messages.mapIndexedNotNull { idx, msg ->
            val label = when (msg.role) {
                "user" -> "Q"
                "tool" -> "T"
                else -> "A"
            }
            val summary = when (msg.role) {
                "user" -> msg.content.take(20)
                "tool" -> msg.toolName.take(15)
                else -> msg.content.take(2).ifBlank { "A" }
            }
            Triple(idx, label, summary)
        }
    }
    if (keyPoints.isEmpty()) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        keyPoints.forEach { (idx, label, _) ->
            val dotColor = when (label) {
                "Q" -> Color(0xFF42A5F5)
                "T" -> Color(0xFFFFA726)
                else -> Color(0xFF66BB6A)
            }
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(dotColor.copy(alpha = 0.7f))
                    .clickable { scope.launch { listState.animateScrollToItem(idx) } }
            )
        }
    }
}

@Composable
private fun ProfileScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val settings by vm.settingsState.collectAsState()
    val tools by vm.toolsState.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    BackHandler { onBack() }
    LaunchedEffect(Unit) { vm.loadSettingsData() }

    var settingsExpanded by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }

    // 设置页内部状态
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }

    // SAF 选择工作目录
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            vm.setWorkDirUri(uri.toString())
        }
    }

    // 选择自定义悬浮球图标 → 进入圆形裁剪
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            pendingCropUri = uri
        }
    }

    val presets = when (settings.currentProvider) {
        "doubao" -> listOf(
            "doubao-1.5-pro-32k", "doubao-1.5-lite-32k", "doubao-1.5-pro-256k",
            "doubao-seed-1.6", "doubao-seed-2-1-pro-260628", "doubao-1.5-vision-pro-32k"
        )
        "deepseek" -> listOf("deepseek-chat", "deepseek-reasoner")
        "qianwen" -> listOf(
            "qwen-turbo", "qwen-plus", "qwen-max",
            "qwen-long", "qwen-vl-plus", "qwen-vl-max"
        )
        else -> emptyList()
    }
    val onlineModels = tools.modelsResult.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("[") && !it.contains("失败") && !it.contains("查询") }
    val modelOptions = (presets + onlineModels).distinct()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("我的", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- 模型设置（折叠抽屉）----
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { settingsExpanded = !settingsExpanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("模型设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(
                            if (settingsExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.rotate(if (settingsExpanded) 180f else 90f)
                        )
                    }
                    AnimatedVisibility(visible = settingsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 供应商
                            Box {
                                OutlinedTextField(
                                    value = settings.currentProvider, onValueChange = {}, readOnly = true,
                                    label = { Text("供应商") }, modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = { IconButton(onClick = { providerExpanded = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) } }
                                )
                                DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                                    settings.providers.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { vm.onProviderChange(p); providerExpanded = false }) }
                                }
                            }
                            OutlinedTextField(value = settings.apiKey, onValueChange = vm::onApiKeyChange, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(value = settings.baseUrl, onValueChange = vm::onBaseUrlChange, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Box {
                                Column {
                                    OutlinedTextField(
                                        value = settings.model, onValueChange = vm::onModelChange,
                                        label = { Text("模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                        trailingIcon = { IconButton(onClick = { modelExpanded = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) } }
                                    )
                                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                                        Button(onClick = vm::queryModels) { Text("拉取在线模型") }
                                    }
                                }
                                DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                                    if (modelOptions.isEmpty()) {
                                        DropdownMenuItem(text = { Text("（无可用模型）") }, onClick = { modelExpanded = false })
                                    } else {
                                        modelOptions.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { vm.onModelChange(m); modelExpanded = false }) }
                                    }
                                }
                            }
                            Box {
                                OutlinedTextField(
                                    value = settings.apiMode, onValueChange = {}, readOnly = true,
                                    label = { Text("API 模式") }, modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = { IconButton(onClick = { modeExpanded = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) } }
                                )
                                DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                                    listOf("chat", "responses").forEach { mode -> DropdownMenuItem(text = { Text(mode) }, onClick = { vm.onApiModeChange(mode); modeExpanded = false }) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("启用 MCP", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = settings.mcpEnabled,
                                    onCheckedChange = { enabled -> vm.onMcpEnabledChange(enabled) }
                                )
                            }
                            OutlinedTextField(value = settings.mcpUrl, onValueChange = vm::onMcpUrlChange, label = { Text("MCP 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Text("工作目录", style = MaterialTheme.typography.labelMedium)
                            if (settings.workDirUri.isBlank()) {
                                OutlinedButton(onClick = { treeLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("选择目录")
                                }
                            } else {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = settings.workDirUri.substringAfterLast('/'),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        TextButton(onClick = { treeLauncher.launch(null) }) {
                                            Text("更换")
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val wUri = settings.workDirUri
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    val u = Uri.parse(wUri)
                                                    setDataAndType(u, DocumentsContract.Document.MIME_TYPE_DIR)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(Intent.createChooser(intent, "打开工作目录")) }
                                                    .onFailure {
                                                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(Uri.parse(wUri), "resource/folder")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                        runCatching { context.startActivity(Intent.createChooser(fallback, "打开工作目录")) }
                                                    }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("打开", fontSize = MaterialTheme.typography.labelMedium.fontSize)
                                        }
                                        OutlinedButton(
                                            onClick = { vm.onWorkDirUriChange("") },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("清空", fontSize = MaterialTheme.typography.labelMedium.fontSize)
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    // --- 新增：立即诊断 + 状态卡 ---
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { vm.triggerCheckWorkDir(settings.workDirUri) },
                                            enabled = !settings.workDirCheckingNow,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            if (settings.workDirCheckingNow) {
                                                androidx.compose.material3.CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            } else {
                                                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                if (settings.workDirCheckingNow) "诊断中…" else "立即诊断目录状态",
                                                fontSize = MaterialTheme.typography.labelMedium.fontSize
                                            )
                                        }
                                        Text(
                                            text = when (settings.workDirCheckStatus) {
                                                WorkDirCheckStatus.OK -> "正常"
                                                WorkDirCheckStatus.UNCHECKED -> "未检测"
                                                WorkDirCheckStatus.EMPTY -> "未设置"
                                                WorkDirCheckStatus.NO_PERMISSION -> "无权限"
                                                WorkDirCheckStatus.NOT_FOUND -> "不存在"
                                                WorkDirCheckStatus.WRITE_FAIL -> "读写失败"
                                                WorkDirCheckStatus.UNKNOWN_ERR -> "异常"
                                            },
                                            color = when (settings.workDirCheckStatus) {
                                                WorkDirCheckStatus.OK -> Color(0xFF2E7D32)
                                                WorkDirCheckStatus.UNCHECKED, WorkDirCheckStatus.EMPTY ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> Color(0xFFC62828)
                                            },
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    if (settings.workDirCheckMsg.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        val bgColor = when (settings.workDirCheckStatus) {
                                            WorkDirCheckStatus.OK -> Color(0xFFE8F5E9)
                                            WorkDirCheckStatus.UNCHECKED, WorkDirCheckStatus.EMPTY ->
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else -> Color(0xFFFFEBEE)
                                        }
                                        val fgColor = when (settings.workDirCheckStatus) {
                                            WorkDirCheckStatus.OK -> Color(0xFF1B5E20)
                                            WorkDirCheckStatus.UNCHECKED, WorkDirCheckStatus.EMPTY ->
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> Color(0xFFB71C1C)
                                        }
                                        androidx.compose.material3.Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = bgColor,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(
                                                text = settings.workDirCheckMsg,
                                                modifier = Modifier.padding(10.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = fgColor
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "选择完工作目录后，记得点下面的「保存」按钮（已清空的目录保存后会再次生效为已选值）。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = settings.maxIterations.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { vm.onMaxIterChange(it) } },
                                label = { Text("最大迭代") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = settings.temperature.toString(),
                                onValueChange = { v -> v.toFloatOrNull()?.let { vm.onTemperatureChange(it) } },
                                label = { Text("温度") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = vm::saveSettings) { Text("保存") }
                                Spacer(Modifier.width(12.dp))
                                if (settings.saveResult.isNotEmpty()) Text(settings.saveResult, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = vm::reconnect) { Text("重新连接") }
                        }
                    }
                }
            }

            // ---- 悬浮球 ----
            Card(Modifier.fillMaxWidth()) {
                var expanded by remember { mutableStateOf(false) }
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("悬浮球", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = settings.floatingEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !vm.isOverlayPermissionGranted()) {
                                    context.startActivity(vm.getOverlaySettingsIntent())
                                }
                                vm.setFloatingEnabled(enabled)
                            }
                        )
                    }
                    if (settings.floatingEnabled) {
                        AnimatedVisibility(expanded) {
                            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                                Text(
                                    "悬浮球大小 ${settings.floatingBallSizeDp}dp",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = settings.floatingBallSizeDp.toFloat(),
                                    onValueChange = { vm.setFloatingBallSize(it.toInt()) },
                                    valueRange = 30f..120f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "正常透明度 ${"%.0f".format(settings.floatingBallNormalAlpha * 100)}%",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = settings.floatingBallNormalAlpha,
                                    onValueChange = { vm.setFloatingBallNormalAlpha(it) },
                                    valueRange = 0.2f..1.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "半隐藏透明度 ${"%.0f".format(settings.floatingBallHiddenAlpha * 100)}%",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = settings.floatingBallHiddenAlpha,
                                    onValueChange = { vm.setFloatingBallHiddenAlpha(it) },
                                    valueRange = 0.05f..0.8f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "半隐藏程度 ${"%.0f".format(settings.floatingBallHiddenRatio * 100)}%",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = settings.floatingBallHiddenRatio,
                                    onValueChange = { vm.setFloatingBallHiddenRatio(it) },
                                    valueRange = 0f..0.9f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(12.dp))
                                // 更换图标
                                Text("更换图标", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = settings.floatingBallIconIndex == 0,
                                        onClick = { vm.setFloatingBallIcon(0) },
                                        label = { Text("默认") }
                                    )
                                    FilterChip(
                                        selected = settings.floatingBallIconIndex == 2 && settings.floatingBallCustomIconPath.isNotEmpty(),
                                        onClick = {
                                            imagePickerLauncher.launch(arrayOf("image/*"))
                                        },
                                        label = { Text("自选图片") }
                                    )
                                }
                                if (settings.floatingBallIconIndex == 2 && settings.floatingBallCustomIconPath.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "已选择自定义图片",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (!expanded) {
                            Text(
                                "点击展开设置  |  悬浮球可随意拖动，贴边后 3 秒自动半隐藏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                            )
                        }
                    }
                }
            }

            // ---- 余额查询 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("额度查询", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = vm::queryBalance, enabled = !tools.isQuerying, modifier = Modifier.fillMaxWidth()) { Text("查询余额") }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (tools.balanceResult.isEmpty()) "（未查询）" else tools.balanceResult,
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ---- 模型列表（折叠抽屉）----
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { modelsExpanded = !modelsExpanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("模型列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(
                            if (modelsExpanded) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowBack,
                            contentDescription = null, modifier = Modifier.rotate(if (modelsExpanded) 180f else 90f)
                        )
                    }
                    AnimatedVisibility(visible = modelsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                            Button(onClick = vm::queryModels, enabled = !tools.isQuerying, modifier = Modifier.fillMaxWidth()) { Text("获取模型列表") }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (tools.modelsResult.isEmpty()) "（未查询）" else tools.modelsResult,
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // ---- 关于 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("By TRAE & 狂客", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "本工具可以调用多个AI模型，完成对话以及辅助功能，减少负担。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 圆形裁剪弹窗
    pendingCropUri?.let { uri ->
        CircleCropDialog(
            uri = uri,
            onConfirm = { bitmap ->
                vm.saveCroppedIcon(bitmap)
                pendingCropUri = null
            },
            onCancel = { pendingCropUri = null }
        )
    }
}

@Composable
private fun CircleCropDialog(
    uri: Uri,
    onConfirm: (android.graphics.Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    LaunchedEffect(uri) {
        val inputStream = context.contentResolver.openInputStream(uri)
        bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        inputStream?.close()
    }

    val bmp = bitmap
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Dialog(onDismissRequest = onCancel) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("裁剪图标", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.3f, 5f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            ),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text("双指缩放、拖动调整位置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onCancel) { Text("取消") }
                    Button(onClick = {
                        val size = 240
                        val scaledW = (bmp.width * scale).toInt().coerceAtLeast(1)
                        val scaledH = (bmp.height * scale).toInt().coerceAtLeast(1)
                        val scaledBmp = android.graphics.Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
                        val result = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(result)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
                        val path = android.graphics.Path()
                        path.addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
                        canvas.clipPath(path)
                        val srcSize = minOf(scaledBmp.width, scaledBmp.height).toFloat()
                        val srcLeft = (scaledBmp.width - srcSize) / 2f - offsetX
                        val srcTop = (scaledBmp.height - srcSize) / 2f - offsetY
                        val srcRect = android.graphics.Rect(
                            srcLeft.toInt().coerceIn(0, scaledBmp.width - 1),
                            srcTop.toInt().coerceIn(0, scaledBmp.height - 1),
                            (srcLeft + srcSize).toInt().coerceIn(1, scaledBmp.width),
                            (srcTop + srcSize).toInt().coerceIn(1, scaledBmp.height)
                        )
                        val dstRect = android.graphics.Rect(0, 0, size, size)
                        canvas.drawBitmap(scaledBmp, srcRect, dstRect, paint)
                        onConfirm(result)
                    }) { Text("确认裁剪") }
                }
            }
        }
    }
}

