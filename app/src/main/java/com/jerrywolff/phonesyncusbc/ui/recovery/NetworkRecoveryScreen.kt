package com.jerrywolff.phonesyncusbc.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerrywolff.phonesyncusbc.domain.CompanionInstallPolicy
import com.jerrywolff.phonesyncusbc.domain.SourcePlatform
import com.jerrywolff.phonesyncusbc.recovery.IosNetworkRecoveryEngine
import com.jerrywolff.phonesyncusbc.recovery.IosDataFetcher
import kotlinx.coroutines.launch

/**
 * Network Recovery Screen - Discovers and connects to iPhone Companion App
 */
@Composable
fun NetworkRecoveryScreen(
    onDataRecovered: (messages: Int, contacts: Int, notes: Int) -> Unit,
) {
    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("") }
    var recoveredData by remember { mutableStateOf<RecoveredData?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    LaunchedEffect(Unit) {
        startDiscovery(context) { device ->
            discoveredDevices = discoveredDevices + device
            connectionStatus = "Found: ${device.name}"
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Network iOS Recovery",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Step 1: Discovery
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF0F4FF)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Step 1: Discover iPhone",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            isDiscovering = true
                            discoveredDevices = emptyList()
                            selectedDevice = null
                            connectionStatus = "Searching for iPhone on the local network..."
                            startDiscovery(context) { device ->
                                discoveredDevices = discoveredDevices + device
                                connectionStatus = "Found: ${device.name}"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isDiscovering) "Discovering..." else "Scan Network")
                    }
                }
                
                Text(
                    text = connectionStatus,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
        
        // Step 2: Device Selection
        if (discoveredDevices.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF0FFF4)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Step 2: Select Device",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    discoveredDevices.forEach { device ->
                        DeviceCard(
                            device = device,
                            isSelected = selectedDevice == device,
                            onSelect = { selectedDevice = device }
                        )
                    }
                }
            }
        }
        
        // Step 3: Connect & Recover
        if (selectedDevice != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF8F0)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Step 3: Recovery",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Button(
                        onClick = {
                            scope.launch {
                                val device = selectedDevice ?: return@launch
                                val fetcher = IosDataFetcher(device.ipAddress, device.port)
                                
                                connectionStatus = "Connecting to iPhone..."
                                val status = fetcher.checkStatus()
                                
                                if (status != null) {
                                    connectionStatus = "Connected! Recovering data..."
                                    
                                    val messages = fetcher.fetchMessages()
                                    val contacts = fetcher.fetchContacts()
                                    val notes = fetcher.fetchNotes()
                                    val calls = fetcher.fetchCallHistory()
                                    val sharedFiles = fetcher.fetchSharedFiles()
                                    
                                    recoveredData = RecoveredData(
                                        messagesCount = messages.size,
                                        contactsCount = contacts.size,
                                        notesCount = notes.size,
                                        callsCount = calls.size,
                                        messages = messages,
                                        contacts = contacts,
                                        notes = notes,
                                        calls = calls,
                                        sharedFilesCount = sharedFiles.size,
                                    )
                                    
                                    connectionStatus = "Recovery complete!"
                                    onDataRecovered(messages.size, contacts.size, notes.size)
                                } else {
                                    connectionStatus = "Failed to connect. Ensure iPhone has Companion App running."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Recover Data from iPhone")
                    }
                    
                    if (recoveredData != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        RecoverySummary(recoveredData!!)
                    }
                }
            }
        }
        
        // Status
        if (connectionStatus.isNotEmpty()) {
            Text(
                text = "Status: $connectionStatus",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveredDevice,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onSelect() }
            .background(
                if (isSelected) Color(0xFFE3F2FD) else Color.White
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFE3F2FD) else Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "${device.ipAddress}:${device.port}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            
            if (isSelected) {
                Text(
                    text = "✓",
                    fontSize = 20.sp,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
private fun RecoverySummary(data: RecoveredData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5))
            .padding(12.dp)
    ) {
        Text(
            text = "Recovered Data",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        SummaryItem("Messages", data.messagesCount)
        SummaryItem("Contacts", data.contactsCount)
        SummaryItem("Notes", data.notesCount)
        SummaryItem("Calls", data.callsCount)
        SummaryItem("Shared files available for backup", data.sharedFilesCount)
    }
}

@Composable
private fun SummaryItem(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp)
        Text(text = "$count items", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun startDiscovery(
    context: android.content.Context,
    onDeviceFound: (DiscoveredDevice) -> Unit,
) {
    val engine = IosNetworkRecoveryEngine(context) { deviceName, ipAddress, port ->
        onDeviceFound(
            DiscoveredDevice(
                name = deviceName,
                ipAddress = ipAddress,
                port = port,
            ),
        )
    }
    engine.startDiscovery()
}

data class DiscoveredDevice(
    val name: String,
    val ipAddress: String,
    val port: Int,
)

data class RecoveredData(
    val messagesCount: Int,
    val contactsCount: Int,
    val notesCount: Int,
    val callsCount: Int,
    val messages: List<Any> = emptyList(),
    val contacts: List<Any> = emptyList(),
    val notes: List<Any> = emptyList(),
    val calls: List<Any> = emptyList(),
    val sharedFilesCount: Int = 0,
)
