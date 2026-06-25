package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FileShareScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

val BgColor = Color(0xFF1C1B1F)
val SurfaceColor = Color(0xFF2B2930)
val PrimaryColor = Color(0xFFD0BCFF)
val OnPrimaryColor = Color(0xFF381E72)
val BorderColor = Color(0xFF49454F)
val TextPrimaryColor = Color(0xFFE6E1E5)
val TextSecondaryColor = Color(0xFFCAC4D0)
val SuccessColor = Color(0xFF00E676)
val ProgressContainerColor = Color(0x4D49454F)

@Composable
fun FileShareScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var ipAddress by remember { mutableStateOf("192.168.1.50") }
    var port by remember { mutableStateOf("9999") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf(0L) }

    var isTransferring by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var speedMbps by remember { mutableFloatStateOf(0f) }
    var etaSeconds by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("Idle") }

    fun startTransfer(uri: Uri, name: String, size: Long) {
        val portInt = port.toIntOrNull()
        if (portInt != null && ipAddress.isNotBlank()) {
            coroutineScope.launch {
                isTransferring = true
                progress = 0f
                speedMbps = 0f
                etaSeconds = 0
                fileName = name
                fileSize = size
                
                sendFile(
                    context = context,
                    uri = uri,
                    ip = ipAddress,
                    port = portInt,
                    fileName = name,
                    fileSize = size,
                    onProgress = { progress = it },
                    onSpeed = { speedMbps = it },
                    onEta = { etaSeconds = it },
                    onStatus = { statusMessage = it }
                )
                isTransferring = false
            }
        } else {
            statusMessage = "Please enter a valid IP and Port."
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedUri = it
            val info = getFileInfo(context, it)
            startTransfer(it, info.first, info.second)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(16.dp)
    ) {
        // Header Section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PrimaryColor, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "S",
                        color = OnPrimaryColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                Column {
                    Text(
                        "SocketShare",
                        color = TextPrimaryColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp
                    )
                    Text(
                        "TCP File Protocol",
                        color = TextSecondaryColor,
                        fontSize = 12.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(BorderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(SuccessColor, CircleShape)
                )
            }
        }

        // Bento Grid Main Content
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Connection Configuration (IP/Port)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
                    .background(SurfaceColor, RoundedCornerShape(24.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(24.dp))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "REMOTE SERVER",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = PrimaryColor,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .background(BorderColor, CircleShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("IPv4 Ready", fontSize = 10.sp, color = TextPrimaryColor)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "IP Address",
                                fontSize = 10.sp,
                                color = TextSecondaryColor,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            BasicTextField(
                                value = ipAddress,
                                onValueChange = { ipAddress = it },
                                textStyle = TextStyle(
                                    color = TextPrimaryColor,
                                    fontSize = 20.sp,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = (-0.5).sp
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isTransferring
                            )
                        }
                        Column(modifier = Modifier.width(96.dp)) {
                            Text(
                                "Port",
                                fontSize = 10.sp,
                                color = TextSecondaryColor,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            BasicTextField(
                                value = port,
                                onValueChange = { port = it },
                                textStyle = TextStyle(
                                    color = TextPrimaryColor,
                                    fontSize = 20.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isTransferring
                            )
                        }
                    }
                }
            }

            // File Selector Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(PrimaryColor)
                    .clickable(enabled = !isTransferring) {
                        filePickerLauncher.launch("*/*")
                    }
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(OnPrimaryColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Send,
                            contentDescription = "Select File",
                            tint = PrimaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isTransferring) "Transferring..." else "Select File to Send",
                            color = OnPrimaryColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isTransferring) fileName else "Auto-starts on selection",
                            color = OnPrimaryColor.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Metrics Row (Speed & ETA)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Speed Metric
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(SurfaceColor, RoundedCornerShape(24.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(24.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            "SPEED",
                            fontSize = 10.sp,
                            color = TextSecondaryColor,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                String.format("%.1f", speedMbps),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text("MB/s", fontSize = 12.sp, color = PrimaryColor)
                        }
                    }
                }

                // ETA Metric
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(SurfaceColor, RoundedCornerShape(24.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(24.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            "ETA",
                            fontSize = 10.sp,
                            color = TextSecondaryColor,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val displayEta = if (etaSeconds > 60) {
                                "${etaSeconds / 60}:${(etaSeconds % 60).toString().padStart(2, '0')}"
                            } else {
                                "0:${etaSeconds.toString().padStart(2, '0')}"
                            }
                            Text(
                                displayEta,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(if (etaSeconds > 60) "min" else "sec", fontSize = 12.sp, color = PrimaryColor)
                        }
                    }
                }
            }

            // Active Transfer / Progress
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(ProgressContainerColor, RoundedCornerShape(24.dp))
                    .border(1.dp, BorderColor.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = if (fileName.isEmpty()) "Waiting for file..." else fileName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sentMb = (fileSize * progress) / (1024 * 1024)
                            val totalMb = fileSize / (1024 * 1024)
                            Text(
                                text = if (fileSize > 0) "${sentMb.toInt()} MB / ${totalMb} MB" else "0 MB / 0 MB",
                                fontSize = 10.sp,
                                color = TextSecondaryColor
                            )
                        }
                        Text(
                            "${(progress * 100).toInt()}%",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryColor
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(BorderColor)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = progress)
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrimaryColor)
                        )
                    }
                }
            }
        }

        // Bottom Controls / Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(6.dp).background(PrimaryColor, CircleShape))
                Text(statusMessage, fontSize = 11.sp, color = TextSecondaryColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chunks: 4MB", fontSize = 11.sp, color = TextSecondaryColor)
                Box(
                    modifier = Modifier.size(16.dp).background(BorderColor, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.CheckCircle,
                        contentDescription = "Ok",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
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

suspend fun sendFile(
    context: Context,
    uri: Uri,
    ip: String,
    port: Int,
    fileName: String,
    fileSize: Long,
    onProgress: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onEta: (Int) -> Unit,
    onStatus: (String) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        onStatus("Connecting to $ip:$port...")
        val socket = Socket(ip, port)
        socket.use { s ->
            val output = s.getOutputStream()
            val input = s.getInputStream()

            // 1. Send Header
            val headerJson = """{"filename": "$fileName", "filesize": $fileSize}"""
            val headerString = headerJson + "\n"
            output.write(headerString.toByteArray(Charsets.UTF_8))
            output.flush()

            // 2. Wait 50ms
            delay(50)

            // 3. Stream File
            onStatus("Socket Stream: IO-Dispatcher")
            context.contentResolver.openInputStream(uri)?.use { fileStream ->
                val buffer = ByteArray(4 * 1024 * 1024)
                var bytesRead: Int
                var totalRead = 0L
                
                val startTime = System.currentTimeMillis()
                var lastReportTime = startTime
                var bytesSinceLastReport = 0L

                while (fileStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) {
                        onStatus("Transfer cancelled.")
                        return@withContext
                    }
                    
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    bytesSinceLastReport += bytesRead

                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastReportTime
                    if (timeDiff >= 500) {
                        val currentProgress = if (fileSize > 0) totalRead.toFloat() / fileSize.toFloat() else 0f
                        onProgress(currentProgress)

                        val timeDiffSec = timeDiff / 1000f
                        val mbps = (bytesSinceLastReport / (1024f * 1024f)) / timeDiffSec
                        onSpeed(mbps)
                        
                        val totalTimeSec = (now - startTime) / 1000f
                        if (totalTimeSec > 0 && totalRead > 0) {
                            val overallSpeed = totalRead / totalTimeSec
                            val bytesRemaining = fileSize - totalRead
                            if (overallSpeed > 0) {
                                val eta = (bytesRemaining / overallSpeed).toInt()
                                onEta(eta)
                            }
                        }

                        lastReportTime = now
                        bytesSinceLastReport = 0L
                    }
                }
                output.flush()
                onProgress(1f)
                onEta(0)
            } ?: throw Exception("Could not open input stream.")

            // 4. Read Response
            onStatus("Waiting for response from server...")
            val reader = BufferedReader(InputStreamReader(input))
            val response = reader.readLine()
            
            if (response == "SUCCESS") {
                onStatus("Transfer Complete! Success.")
            } else {
                onStatus("Server returned: $response")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        onStatus("Error: ${e.message ?: e.toString()}")
    }
}
