package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import kotlin.coroutines.coroutineContext

// --- Models ---
data class AppItem(val name: String, val packageName: String, val apkPath: String, val size: Long, val icon: Drawable?)
data class FileItem(val file: File, val isDirectory: Boolean, val name: String, val size: Long)
data class DiscoveredDevice(val name: String, val ip: String, val port: Int, val lastSeen: Long)
data class TransferJob(
    val id: String,
    val targetIp: String,
    val filename: String,
    val progress: Float,
    val speedMbps: Float,
    val state: TransferState
)
enum class TransferState { PENDING, TRANSFERRING, SUCCESS, ERROR, DISCONNECTED }

data class SendTask(
    val path: String,
    val filename: String,
    val size: Long
)

// --- Global Manager ---
object TransferManager {
    private val _transfers = MutableStateFlow<List<TransferJob>>(emptyList())
    val transfers: StateFlow<List<TransferJob>> = _transfers.asStateFlow()
    
    val pendingTasks = mutableListOf<SendTask>()
    var selectedDevice: DiscoveredDevice? = null

    fun updateTransfer(id: String, mutate: (TransferJob) -> TransferJob) {
        _transfers.update { list ->
            val index = list.indexOfFirst { it.id == id }
            if (index != -1) {
                val updatedList = list.toMutableList()
                updatedList[index] = mutate(updatedList[index])
                updatedList
            } else {
                list
            }
        }
    }
    
    fun addTransfer(job: TransferJob) {
        _transfers.update { it + job }
    }
}

// --- Foreground Service ---
class TransferService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_TRANSFER") {
            startForegroundCompat(3, createNotification("Lightning Share", "Sending files..."))
            
            val tasks = TransferManager.pendingTasks.toList()
            val targetDevice = TransferManager.selectedDevice
            
            TransferManager.pendingTasks.clear()
            
            if (tasks.isNotEmpty() && targetDevice != null) {
                serviceScope.launch {
                    for (task in tasks) {
                        val jobId = UUID.randomUUID().toString()
                        val job = TransferJob(
                            id = jobId,
                            targetIp = targetDevice.ip,
                            filename = task.filename,
                            progress = 0f,
                            speedMbps = 0f,
                            state = TransferState.PENDING
                        )
                        TransferManager.addTransfer(job)
                        
                        sendFileToDevice(targetDevice.ip, targetDevice.port, task, jobId)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun sendFileToDevice(ip: String, port: Int, task: SendTask, jobId: String) {
        var socket: Socket? = null
        var totalRead = 0L
        try {
            TransferManager.updateTransfer(jobId) { it.copy(state = TransferState.TRANSFERRING) }
            
            socket = Socket()
            socket.tcpNoDelay = true
            socket.sendBufferSize = 4 * 1024 * 1024
            socket.connect(InetSocketAddress(ip, port), 10000)
            
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            
            val json = JSONObject()
            json.put("filename", task.filename)
            json.put("action", "NEW")
            val headerString = json.toString() + "\n"
            output.write(headerString.toByteArray(Charsets.UTF_8))
            output.flush()
            
            val reader = BufferedReader(InputStreamReader(input))
            val responseOffsetStr = reader.readLine()
            val startOffset = responseOffsetStr?.toLongOrNull() ?: 0L
            totalRead = startOffset
            
            val buffer = ByteArray(1 * 1024 * 1024)
            var bytesRead: Int
            val startTime = SystemClock.elapsedRealtime()
            var lastReportTime = startTime
            var bytesSinceLastReport = 0L
            
            val raf = RandomAccessFile(task.path, "r")
            raf.seek(startOffset)
            val fileStream = java.nio.channels.Channels.newInputStream(raf.channel)
            
            fileStream.use { stream ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    if (!coroutineContext.isActive) break
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    bytesSinceLastReport += bytesRead
                    
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReportTime >= 500) {
                        val speed = (bytesSinceLastReport / 1024f / 1024f) / ((now - lastReportTime) / 1000f)
                        val progress = if (task.size > 0) totalRead.toFloat() / task.size else 0f
                        TransferManager.updateTransfer(jobId) { it.copy(progress = progress, speedMbps = speed) }
                        lastReportTime = now
                        bytesSinceLastReport = 0L
                    }
                }
            }
            
            socket.shutdownOutput()
            input.read() // Wait for SUCCESS
            
            TransferManager.updateTransfer(jobId) { it.copy(progress = 1f, speedMbps = 0f, state = TransferState.SUCCESS) }
        } catch (e: Exception) {
            e.printStackTrace()
            val state = if (e is SocketException || e is IOException) TransferState.DISCONNECTED else TransferState.ERROR
            TransferManager.updateTransfer(jobId) { it.copy(state = state) }
        } finally {
            socket?.close()
        }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val channelId = "lightning_share_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "File Transfers", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
    }
}

// --- ViewModels ---
class MainViewModel : ViewModel() {
    // Selection state
    val selectedFiles = mutableStateListOf<File>()
    val selectedApps = mutableStateListOf<AppItem>()
    
    // Files Tab
    var currentPath by mutableStateOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
    var filesInCurrentPath by mutableStateOf<List<FileItem>>(emptyList())
    var fileSearchQuery by mutableStateOf("")
    
    // Apps Tab
    var installedApps by mutableStateOf<List<AppItem>>(emptyList())
    var appSearchQuery by mutableStateOf("")
    
    // Discover Tab
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()
    var isVisible by mutableStateOf(true)
    var deviceName by mutableStateOf(Build.MODEL)
    var localIp by mutableStateOf("")
    
    // Broadcast / Discovery Socket
    private var discoverySocket: DatagramSocket? = null
    private var discoveryJob: Job? = null
    private var broadcastJob: Job? = null
    
    init {
        localIp = getLocalIpAddress()
    }
    
    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (discoverySocket == null || discoverySocket?.isClosed == true) {
                    discoverySocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(8888))
                        broadcast = true
                    }
                }
                val buffer = ByteArray(1024)
                while (isActive) {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    discoverySocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(data)
                        val name = json.getString("device_name")
                        val ip = json.getString("ip")
                        val port = json.getInt("port")
                        
                        if (ip != localIp) { // Ignore self
                            val newDevice = DiscoveredDevice(name, ip, port, System.currentTimeMillis())
                            _discoveredDevices.update { list ->
                                val filtered = list.filter { it.ip != ip }
                                (filtered + newDevice).sortedByDescending { it.lastSeen }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        broadcastJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (isVisible && localIp.isNotEmpty() && localIp != "Not found") {
                    try {
                        val json = JSONObject()
                        json.put("device_name", deviceName)
                        json.put("ip", localIp)
                        json.put("port", 9999) // TCP listening port if we had a receiver component
                        val data = json.toString().toByteArray(Charsets.UTF_8)
                        
                        // Send broadcast
                        val broadcastAddress = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(data, data.size, broadcastAddress, 8888)
                        val sock = DatagramSocket()
                        sock.broadcast = true
                        sock.send(packet)
                        sock.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                delay(2000) // Broadcast every 2s
            }
        }
    }
    
    fun stopDiscovery() {
        discoveryJob?.cancel()
        broadcastJob?.cancel()
        discoverySocket?.close()
        discoverySocket = null
        discoveryJob = null
        broadcastJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
    
    fun loadFiles(path: File) {
        currentPath = path
        val list = path.listFiles()?.toList() ?: emptyList()
        filesInCurrentPath = list.map { 
            FileItem(it, it.isDirectory, it.name, it.length())
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    
    fun toggleFileSelection(file: File) {
        if (selectedFiles.contains(file)) selectedFiles.remove(file)
        else selectedFiles.add(file)
    }
    
    fun toggleAppSelection(app: AppItem) {
        if (selectedApps.contains(app)) selectedApps.remove(app)
        else selectedApps.add(app)
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (ex: Exception) {}
        return "Not found"
    }
}

// --- UI Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:" + packageName)
                // startActivity(intent)
            } catch (e: Exception) {
            }
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                LightningShareApp()
            }
        }
    }
}

@Composable
fun LightningShareApp(viewModel: MainViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        viewModel.loadFiles(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val filtered = apps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            viewModel.installedApps = filtered.map {
                AppItem(
                    name = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    apkPath = it.sourceDir,
                    size = File(it.sourceDir).length(),
                    icon = pm.getApplicationIcon(it)
                )
            }.sortedBy { it.name.lowercase() }
        }
    }
    
    DisposableEffect(selectedTab) {
        if (selectedTab == 0) {
            viewModel.startDiscovery()
        } else {
            viewModel.stopDiscovery()
        }
        onDispose {
            viewModel.stopDiscovery()
        }
    }
    
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.lightning_share_icon_1782480629924),
                            contentDescription = "Logo",
                            modifier = Modifier.size(40.dp).padding(end = 8.dp)
                        )
                        Text("Lightning Share")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Explore, "Discover") },
                    label = { Text("Discover") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Folder, "Files") },
                    label = { Text("Files") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Apps, "Apps") },
                    label = { Text("Apps") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Person, "Me") },
                    label = { Text("Me") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (selectedTab) {
                0 -> DiscoverTab(viewModel)
                1 -> FilesTab(viewModel)
                2 -> AppsTab(viewModel)
                3 -> MeTab(viewModel)
            }
            
            AnimatedVisibility(
                visible = selectedTab == 1 && viewModel.selectedFiles.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${viewModel.selectedFiles.size} selected", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Button(onClick = {
                            viewModel.selectedFiles.forEach { file ->
                                TransferManager.pendingTasks.add(SendTask(file.absolutePath, file.name, file.length()))
                            }
                            viewModel.selectedFiles.clear()
                            selectedTab = 0 
                        }) {
                            Text("Send to...")
                        }
                    }
                }
            }
            
            AnimatedVisibility(
                visible = selectedTab == 2 && viewModel.selectedApps.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = MaterialTheme.shapes.medium,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${viewModel.selectedApps.size} selected", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Button(onClick = {
                            viewModel.selectedApps.forEach { app ->
                                TransferManager.pendingTasks.add(SendTask(app.apkPath, "${app.name}.apk", app.size))
                            }
                            viewModel.selectedApps.clear()
                            selectedTab = 0 
                        }) {
                            Text("Send to...")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RadarAnimation() {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = primaryColor.copy(alpha = alpha),
                radius = size.width / 2 * scale,
                style = Stroke(width = 4f)
            )
        }
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.lightning_share_icon_1782480629924),
            contentDescription = "App Logo",
            modifier = Modifier.size(64.dp)
        )
    }
}

@Composable
fun DiscoverTab(viewModel: MainViewModel) {
    val devices by viewModel.discoveredDevices.collectAsState()
    val context = LocalContext.current
    val transfers by TransferManager.transfers.collectAsState()
    
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        RadarAnimation()
        Spacer(Modifier.height(32.dp))
        
        Text("Listening for devices...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
            items(devices) { device ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(device.ip, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        if (TransferManager.pendingTasks.isNotEmpty()) {
                            Button(onClick = {
                                TransferManager.selectedDevice = device
                                val intent = Intent(context, TransferService::class.java).apply {
                                    action = "START_TRANSFER"
                                }
                                ContextCompat.startForegroundService(context, intent)
                            }) {
                                Text("Connect & Send")
                            }
                        }
                    }
                }
            }
        }
        
        if (transfers.isNotEmpty()) {
            Text("Active Transfers", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                items(transfers.filter { it.state != TransferState.SUCCESS }) { job ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${job.targetIp} - ${job.filename}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(job.state.name, fontSize = 12.sp, color = when(job.state) {
                                    TransferState.SUCCESS -> Color(0xFF00C853)
                                    TransferState.ERROR, TransferState.DISCONNECTED -> Color.Red
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                                if (job.state == TransferState.TRANSFERRING) {
                                    Text(String.format("%.2f MB/s", job.speedMbps), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilesTab(viewModel: MainViewModel) {
    val filteredFiles = viewModel.filesInCurrentPath.filter { 
        viewModel.fileSearchQuery.isEmpty() || it.name.contains(viewModel.fileSearchQuery, ignoreCase = true)
    }
    
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = viewModel.fileSearchQuery,
            onValueChange = { viewModel.fileSearchQuery = it },
            placeholder = { Text("Search local files") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true
        )
        
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chips = listOf("Downloads", "Documents", "DCIM", "Movies")
            items(chips) { chip ->
                FilterChip(
                    selected = false,
                    onClick = {
                        val dir = Environment.getExternalStoragePublicDirectory(chip)
                        if (dir.exists()) viewModel.loadFiles(dir)
                    },
                    label = { Text(chip) }
                )
            }
        }
        
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (viewModel.currentPath.parentFile != null) {
                IconButton(onClick = { viewModel.loadFiles(viewModel.currentPath.parentFile!!) }) {
                    Text("←", fontSize = 24.sp)
                }
            }
            Text(viewModel.currentPath.absolutePath, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        
        LazyColumn(Modifier.fillMaxSize()) {
            items(filteredFiles) { item ->
                val isSelected = viewModel.selectedFiles.contains(item.file)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (item.isDirectory) viewModel.loadFiles(item.file)
                            else viewModel.toggleFileSelection(item.file)
                        }
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = MaterialTheme.colorScheme.onSurface)
                        if (!item.isDirectory) {
                            Text("${item.size / 1024} KB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!item.isDirectory) {
                        Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleFileSelection(item.file) })
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) } 
        }
    }
}

@Composable
fun AppsTab(viewModel: MainViewModel) {
    val filteredApps = viewModel.installedApps.filter { 
        viewModel.appSearchQuery.isEmpty() || it.name.contains(viewModel.appSearchQuery, ignoreCase = true)
    }
    
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = viewModel.appSearchQuery,
            onValueChange = { viewModel.appSearchQuery = it },
            placeholder = { Text("Search installed apps") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true
        )
        
        LazyColumn(Modifier.fillMaxSize()) {
            items(filteredApps) { app ->
                val isSelected = viewModel.selectedApps.contains(app)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleAppSelection(app) }
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (app.icon != null) {
                        Image(bitmap = app.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
                    } else {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${app.size / 1024 / 1024} MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleAppSelection(app) })
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun MeTab(viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Device Info", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        
        OutlinedTextField(
            value = viewModel.deviceName,
            onValueChange = { viewModel.deviceName = it },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text("Local IP: ${viewModel.localIp}", fontSize = 16.sp)
        
        Spacer(Modifier.height(16.dp))
        
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Discoverable to others")
            Switch(
                checked = viewModel.isVisible,
                onCheckedChange = { viewModel.isVisible = it }
            )
        }
        
        Spacer(Modifier.height(32.dp))
        
        val successTransfers = TransferManager.transfers.collectAsState().value.filter { it.state == TransferState.SUCCESS }
        Text("Transfer History", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(successTransfers) { job ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(job.filename, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF00C853))
                    }
                }
            }
        }
    }
}
