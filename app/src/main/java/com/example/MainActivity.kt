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
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.channels.Channels
import kotlin.coroutines.coroutineContext

data class AppInfo(val name: String, val packageName: String, val apkPath: String, val icon: Drawable)

sealed class TransferState {
    object Idle : TransferState()
    data class Transferring(val filename: String, val progress: Float, val speed: Float, val isResume: Boolean) : TransferState()
    data class Disconnected(val filename: String, val progress: Float, val sentBytes: Long, val totalBytes: Long) : TransferState()
    data class Success(val filename: String) : TransferState()
    data class Error(val message: String) : TransferState()
}

object TransferManager {
    val senderState = MutableStateFlow<TransferState>(TransferState.Idle)
    val receiverState = MutableStateFlow<TransferState>(TransferState.Idle)
    
    var currentSendJob: Job? = null
    var receiverJob: Job? = null
    
    var lastTargetIp = ""
    var lastTargetPort = 9999
    var lastFileUri: Uri? = null
    var lastApkPath: String? = null
    var lastFilename = ""
    var lastFilesize = 0L
}

class TransferService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START_RECEIVER") {
            val port = intent.getIntExtra("port", 9999)
            if (TransferManager.receiverJob?.isActive == true) return START_STICKY
            startForegroundCompat(1, createNotification("LightningShare Receiver", "Listening on port $port"))
            TransferManager.receiverJob = serviceScope.launch {
                startReceiver(port)
            }
        } else if (action == "STOP_RECEIVER") {
            TransferManager.receiverJob?.cancel()
            TransferManager.receiverState.value = TransferState.Idle
            stopSelf()
        } else if (action == "START_SENDER" || action == "RESUME_SENDER") {
            val isResume = action == "RESUME_SENDER"
            startForegroundCompat(2, createNotification("LightningShare Sender", "Transferring..."))
            TransferManager.currentSendJob?.cancel()
            TransferManager.currentSendJob = serviceScope.launch {
                sendFile(
                    ip = TransferManager.lastTargetIp,
                    port = TransferManager.lastTargetPort,
                    uri = TransferManager.lastFileUri,
                    apkPath = TransferManager.lastApkPath,
                    filename = TransferManager.lastFilename,
                    filesize = TransferManager.lastFilesize,
                    actionStr = if (isResume) "RESUME" else "NEW"
                )
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val channelId = "transfer_channel"
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

    private suspend fun startReceiver(port: Int) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(port)
            TransferManager.receiverState.value = TransferState.Idle
            while (coroutineContext.isActive) {
                val client = serverSocket.accept()
                serviceScope.launch { handleClient(client) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            serverSocket?.close()
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 1 * 1024 * 1024
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            val reader = BufferedReader(InputStreamReader(input))
            val header = reader.readLine() ?: return
            val json = JSONObject(header)
            val filename = json.getString("filename")
            val action = json.optString("action", "NEW")
            
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val lightningDir = File(downloadsDir, "LightningShare")
            lightningDir.mkdirs()
            
            val targetFile = File(lightningDir, filename)
            var startOffset = 0L
            if (action == "RESUME" && targetFile.exists()) {
                startOffset = targetFile.length()
            } else if (action == "NEW") {
                if (targetFile.exists()) targetFile.delete()
                targetFile.createNewFile()
            }
            
            output.write("$startOffset\n".toByteArray(Charsets.UTF_8))
            output.flush()
            
            val raf = RandomAccessFile(targetFile, "rw")
            raf.seek(startOffset)
            
            var totalRead = startOffset
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            val startTime = SystemClock.elapsedRealtime()
            var lastReportTime = startTime
            var bytesSinceLastReport = 0L
            
            while (input.read(buffer).also { bytesRead = it } != -1) {
                raf.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                bytesSinceLastReport += bytesRead
                
                val now = SystemClock.elapsedRealtime()
                if (now - lastReportTime >= 500) {
                    val speed = (bytesSinceLastReport / 1024f / 1024f) / ((now - lastReportTime) / 1000f)
                    TransferManager.receiverState.value = TransferState.Transferring(filename, 0f, speed, action == "RESUME")
                    lastReportTime = now
                    bytesSinceLastReport = 0L
                }
            }
            raf.close()
            
            output.write("SUCCESS\n".toByteArray(Charsets.UTF_8))
            output.flush()
            
            TransferManager.receiverState.value = TransferState.Success(filename)
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
        }
    }

    private suspend fun sendFile(ip: String, port: Int, uri: Uri?, apkPath: String?, filename: String, filesize: Long, actionStr: String) {
        var socket: Socket? = null
        var totalRead = 0L
        try {
            TransferManager.senderState.value = TransferState.Transferring(filename, 0f, 0f, actionStr == "RESUME")
            socket = Socket()
            socket.tcpNoDelay = true
            socket.sendBufferSize = 1 * 1024 * 1024
            socket.connect(InetSocketAddress(ip, port), 10000)
            
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            
            val json = JSONObject()
            json.put("filename", filename)
            json.put("action", actionStr)
            val headerString = json.toString() + "\n"
            output.write(headerString.toByteArray(Charsets.UTF_8))
            output.flush()
            
            val reader = BufferedReader(InputStreamReader(input))
            val responseOffsetStr = reader.readLine()
            val startOffset = responseOffsetStr?.toLongOrNull() ?: 0L
            totalRead = startOffset
            
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            val startTime = SystemClock.elapsedRealtime()
            var lastReportTime = startTime
            var bytesSinceLastReport = 0L
            
            val fileStream: InputStream = if (apkPath != null) {
                val raf = RandomAccessFile(apkPath, "r")
                raf.seek(startOffset)
                Channels.newInputStream(raf.channel)
            } else if (uri != null) {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw Exception("PFD null")
                val fis = FileInputStream(pfd.fileDescriptor)
                fis.channel.position(startOffset)
                fis
            } else throw Exception("No file source")
            
            fileStream.use { stream ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    if (!coroutineContext.isActive) break
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    bytesSinceLastReport += bytesRead
                    
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReportTime >= 500) {
                        val speed = (bytesSinceLastReport / 1024f / 1024f) / ((now - lastReportTime) / 1000f)
                        val progress = if (filesize > 0) totalRead.toFloat() / filesize else 0f
                        TransferManager.senderState.value = TransferState.Transferring(filename, progress, speed, actionStr == "RESUME")
                        lastReportTime = now
                        bytesSinceLastReport = 0L
                    }
                }
            }
            
            // Wait for SUCCESS from server
            socket.shutdownOutput()
            val responseChar = input.read()
            
            TransferManager.senderState.value = TransferState.Success(filename)
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
            if (e is SocketException || e is IOException) {
                val progress = if (filesize > 0) totalRead.toFloat() / filesize else 0f
                TransferManager.senderState.value = TransferState.Disconnected(filename, progress, totalRead, filesize)
            } else {
                TransferManager.senderState.value = TransferState.Error(e.message ?: "Unknown error")
            }
        } finally {
            socket?.close()
            stopSelf()
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

fun getFileInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = "unknown_file"
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex != -1) name = cursor.getString(nameIndex)
            if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
        }
    }
    return Pair(name, size)
}

fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    return apps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        .map {
            AppInfo(
                name = pm.getApplicationLabel(it).toString(),
                packageName = it.packageName,
                apkPath = it.sourceDir,
                icon = pm.getApplicationIcon(it)
            )
        }
}

fun getLocalIpAddress(): String {
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

fun startSendIntent(context: Context, ip: String, port: Int, uri: Uri?, apkPath: String?, filename: String, filesize: Long, isResume: Boolean = false) {
    TransferManager.lastTargetIp = ip
    TransferManager.lastTargetPort = port
    TransferManager.lastFileUri = uri
    TransferManager.lastApkPath = apkPath
    TransferManager.lastFilename = filename
    TransferManager.lastFilesize = filesize
    
    val intent = Intent(context, TransferService::class.java).apply {
        action = if (isResume) "RESUME_SENDER" else "START_SENDER"
    }
    ContextCompat.startForegroundService(context, intent)
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, "Send Files") },
                    label = { Text("Send Files") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.List, "Send Apps") },
                    label = { Text("Send Apps") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, "Receiver") },
                    label = { Text("Receiver Status") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                0 -> SendFilesTab()
                1 -> SendAppsTab()
                2 -> ReceiverTab()
            }
        }
    }
}

@Composable
fun SendFilesTab() {
    val context = LocalContext.current
    var ip by remember { mutableStateOf("192.168.1.50") }
    var port by remember { mutableStateOf("9999") }
    val senderState by TransferManager.senderState.collectAsState()
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (name, size) = getFileInfo(context, uri)
            startSendIntent(context, ip, port.toIntOrNull() ?: 9999, uri, null, name, size)
        }
    }
    
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Target IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Target Port") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(16.dp))
        Button(onClick = { launcher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Select File to Send")
        }
        Spacer(Modifier.height(16.dp))
        TransferStatusCard(senderState) {
            val intent = Intent(context, TransferService::class.java).apply { action = "RESUME_SENDER" }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

@Composable
fun SendAppsTab() {
    val context = LocalContext.current
    var ip by remember { mutableStateOf("192.168.1.50") }
    var port by remember { mutableStateOf("9999") }
    val senderState by TransferManager.senderState.collectAsState()
    
    var apps by remember { mutableStateOf(emptyList<AppInfo>()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            apps = getInstalledApps(context)
        }
    }
    
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("Target IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Target Port") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        
        TransferStatusCard(senderState) {
            val intent = Intent(context, TransferService::class.java).apply { action = "RESUME_SENDER" }
            ContextCompat.startForegroundService(context, intent)
        }
        
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(apps) { app ->
                Row(Modifier.fillMaxWidth().clickable {
                    val file = File(app.apkPath)
                    startSendIntent(context, ip, port.toIntOrNull() ?: 9999, null, app.apkPath, "${app.name}.apk", file.length())
                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bitmap = app.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(app.name, fontWeight = FontWeight.Bold)
                        Text(app.packageName, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ReceiverTab() {
    val context = LocalContext.current
    var port by remember { mutableStateOf("9999") }
    val receiverState by TransferManager.receiverState.collectAsState()
    val localIp = remember { getLocalIpAddress() }
    
    Column(Modifier.padding(16.dp)) {
        Text("Your IP: $localIp", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Listen Port") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val intent = Intent(context, TransferService::class.java).apply {
                action = "START_RECEIVER"
                putExtra("port", port.toIntOrNull() ?: 9999)
            }
            ContextCompat.startForegroundService(context, intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Start Receiver")
        }
        Button(onClick = {
            val intent = Intent(context, TransferService::class.java).apply { action = "STOP_RECEIVER" }
            context.startService(intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Stop Receiver")
        }
        Spacer(Modifier.height(16.dp))
        Text("Receiver Status:")
        TransferStatusCard(receiverState) {}
    }
}

@Composable
fun TransferStatusCard(state: TransferState, onResume: () -> Unit) {
    if (state is TransferState.Idle) return
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is TransferState.Transferring -> {
                    Text("Transferring: ${state.filename} " + if (state.isResume) "(Resuming)" else "")
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text(String.format("%.2f MB/s", state.speed))
                }
                is TransferState.Disconnected -> {
                    Text("Disconnected: ${state.filename}")
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                        Text("Tap to Resume")
                    }
                }
                is TransferState.Success -> {
                    Text("Success: ${state.filename}", color = Color(0xFF00C853))
                }
                is TransferState.Error -> {
                    Text("Error: ${state.message}", color = Color.Red)
                }
                else -> {}
            }
        }
    }
}
