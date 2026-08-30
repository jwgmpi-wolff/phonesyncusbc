package com.jerrywolff.phonesyncusbc

import android.app.PendingIntent
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.jerrywolff.phonesyncusbc.data.BackupTargetType
import com.jerrywolff.phonesyncusbc.data.DeviceIdentity
import com.jerrywolff.phonesyncusbc.data.AuditEntry
import com.jerrywolff.phonesyncusbc.data.DataExportManager
import com.jerrywolff.phonesyncusbc.data.displayName
import com.jerrywolff.phonesyncusbc.data.externalDeviceRecoveryEntries
import com.jerrywolff.phonesyncusbc.data.IosBackupImportProgress
import com.jerrywolff.phonesyncusbc.data.IosBackupImportResult
import com.jerrywolff.phonesyncusbc.data.IosBackupImportStage
import com.jerrywolff.phonesyncusbc.data.OwnerArchiveImportProgress
import com.jerrywolff.phonesyncusbc.data.OwnerArchiveImportResult
import com.jerrywolff.phonesyncusbc.data.OwnerArchiveImportStage
import com.jerrywolff.phonesyncusbc.data.isCollectorOwnedSourceItem
import com.jerrywolff.phonesyncusbc.data.mergeSourceBackupSelection
import com.jerrywolff.phonesyncusbc.data.storageLocation
import com.jerrywolff.phonesyncusbc.data.StoredTrust
import com.jerrywolff.phonesyncusbc.data.TargetSelectionStore
import com.jerrywolff.phonesyncusbc.data.RecoveryIssue
import com.jerrywolff.phonesyncusbc.data.planExternalRecoveryEntries
import com.jerrywolff.phonesyncusbc.data.recoveredCoverageCategories
import com.jerrywolff.phonesyncusbc.data.primaryActionLabel
import com.jerrywolff.phonesyncusbc.data.providerTarget
import com.jerrywolff.phonesyncusbc.data.TrustLoadResult
import com.jerrywolff.phonesyncusbc.domain.CompanionInstallPolicy
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import com.jerrywolff.phonesyncusbc.domain.SourceCapabilityPolicy
import com.jerrywolff.phonesyncusbc.domain.SourceCapabilities
import com.jerrywolff.phonesyncusbc.domain.SourceExportRequirements
import com.jerrywolff.phonesyncusbc.domain.SourcePlatform
import com.jerrywolff.phonesyncusbc.domain.OwnerExportCoordinator
import com.jerrywolff.phonesyncusbc.domain.RecoveryDeviceType
import com.jerrywolff.phonesyncusbc.domain.RecoveryProfiles
import com.jerrywolff.phonesyncusbc.domain.LOGICAL_ACQUISITION_LIMIT
import com.jerrywolff.phonesyncusbc.domain.TrustContext
import com.jerrywolff.phonesyncusbc.domain.TrustDecision
import com.jerrywolff.phonesyncusbc.domain.TrustPolicy
import com.jerrywolff.phonesyncusbc.usb.AttachedSource
import com.jerrywolff.phonesyncusbc.usb.IdentityReadProgress
import com.jerrywolff.phonesyncusbc.usb.IdentityReadStage
import com.jerrywolff.phonesyncusbc.usb.PeerIdentity
import com.jerrywolff.phonesyncusbc.sync.SyncProgress
import com.jerrywolff.phonesyncusbc.sync.SyncPhase
import com.jerrywolff.phonesyncusbc.sync.SyncResult
import com.jerrywolff.phonesyncusbc.sync.MtpScanSummary
import com.jerrywolff.phonesyncusbc.ui.recovery.NetworkRecoveryScreen
import com.jerrywolff.phonesyncusbc.ui.recovery.DiscoveredDevice
import com.jerrywolff.phonesyncusbc.recovery.IosDataFetcher
import com.jerrywolff.phonesyncusbc.recovery.IosNetworkRecoveryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.DateFormat
import java.util.Date

private enum class AppSection(val label: String) {
    USB_SOURCE("USB Source"),
    DATA_READER("Data Reader"),
    BACKUP_ACTIVITY("Backup"),
}

private data class BackupActivityUi(
    val title: String = "Backup and export activity",
    val status: String = "No backup or export is running.",
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val currentItem: String? = null,
    val bytesProcessed: Long = 0,
    val currentItemBytes: Long = 0,
    val currentItemTotal: Long = 0,
    val running: Boolean = false,
    val failed: Boolean = false,
)

@androidx.compose.material3.ExperimentalMaterial3Api
class MainActivity : ComponentActivity() {
    private val usbPermissionAction = "com.jerrywolff.phonesyncusbc.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PhoneSyncApp(
                onRequestUsbPermission = { source -> requestUsbPermission(source) },
            )
        }
    }

    private fun requestUsbPermission(source: AttachedSource) {
        val manager = getSystemService(UsbManager::class.java)
        if (manager.hasPermission(source.device)) {
            Toast.makeText(this, "USB access is already granted. Rechecking the source.", Toast.LENGTH_SHORT).show()
            recreate()
            return
        }
        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(usbPermissionAction).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Toast.makeText(
                        context,
                        if (granted) "USB access granted." else "USB access was not granted.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    recreate()
                }
            },
            IntentFilter(usbPermissionAction),
            RECEIVER_NOT_EXPORTED,
        )
        manager.requestPermission(source.device, intent)
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@androidx.compose.runtime.Composable
private fun PhoneSyncApp(onRequestUsbPermission: (AttachedSource) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as PhoneSyncApplication
    val productName = remember {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }
    val defaultMobileTargetName = remember(productName) {
        "This phone / Downloads / $productName Packages"
    }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(application.usbSourceResolver.attachedSources()) }
    var selectedSource by remember { mutableStateOf<AttachedSource?>(sources.singleOrNull()) }
    var recoveryDeviceType by remember {
        mutableStateOf(RecoveryDeviceType.defaultFor(selectedSource?.detected))
    }
    var identity by remember { mutableStateOf<PeerIdentity?>(null) }
    var trust by remember { mutableStateOf<StoredTrust?>(null) }
    var capabilities by remember { mutableStateOf<SourceCapabilities?>(null) }
    var selectedCategories by remember { mutableStateOf(setOf(ConsentCategory.PHOTOS_AND_VIDEOS)) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf(AppSection.USB_SOURCE) }
    var messageSection by remember { mutableStateOf(AppSection.USB_SOURCE) }
    var backupWorkflowSection by remember { mutableStateOf(AppSection.BACKUP_ACTIVITY) }
    var backupActivity by remember { mutableStateOf(BackupActivityUi()) }
    var liveProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var mtpScanSummary by remember { mutableStateOf<MtpScanSummary?>(null) }
    var identityReadProgress by remember { mutableStateOf<IdentityReadProgress?>(null) }
    var identityReadError by remember { mutableStateOf<String?>(null) }
    var identityReadRequest by remember { mutableStateOf(0) }
    var recoveryIssues by remember { mutableStateOf(emptyList<RecoveryIssue>()) }
    var iosBackupImporting by remember { mutableStateOf(false) }
    var iosBackupImportProgress by remember { mutableStateOf<IosBackupImportProgress?>(null) }
    var iosBackupImportResult by remember { mutableStateOf<IosBackupImportResult?>(null) }
    var ownerArchiveImporting by remember { mutableStateOf(false) }
    var ownerArchiveImportProgress by remember { mutableStateOf<OwnerArchiveImportProgress?>(null) }
    var ownerArchiveImportResult by remember { mutableStateOf<OwnerArchiveImportResult?>(null) }
    var auditRevision by remember { mutableStateOf(0) }
    var showLibrary by remember { mutableStateOf(false) }
    var showParsedData by remember { mutableStateOf(false) }
    var libraryEntries by remember { mutableStateOf(emptyList<AuditEntry>()) }
    var previewEntry by remember { mutableStateOf<AuditEntry?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewVideoUri by remember { mutableStateOf<Uri?>(null) }
    var showBackupSelection by remember { mutableStateOf(false) }
    val initialBackupPeerId = remember { application.auditLog.latestExternalPeerId() }
    var backupPeerId by remember { mutableStateOf(initialBackupPeerId) }
    val initialBackupEntries = remember(initialBackupPeerId) {
        application.auditLog.completedExternalTransfers(initialBackupPeerId)
    }
    var backupEntries by remember { mutableStateOf(initialBackupEntries) }
    var selectedBackupIds by remember {
        mutableStateOf<Set<Long>>(initialBackupEntries.mapTo(linkedSetOf()) { it.id })
    }
    var selectedUsbBackupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedUsbPeerId by remember { mutableStateOf<String?>(null) }
    var knownUsbBackupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showTargetWizard by remember { mutableStateOf(false) }
    var targetType by remember { mutableStateOf(BackupTargetType.PHONE_DOWNLOADS) }
    var targetUri by remember { mutableStateOf<Uri?>(null) }
    var targetName by remember { mutableStateOf(defaultMobileTargetName) }
    var pendingTargetName by remember { mutableStateOf<String?>(null) }
    var backupStatus by remember {
        mutableStateOf("${selectedBackupIds.size} items ready for $defaultMobileTargetName.")
    }
    var usbBackupStatus by remember { mutableStateOf("No USB source data has been recovered yet.") }
    val targetSelectionStore = remember { TargetSelectionStore(context) }
    val savedTarget = remember { targetSelectionStore.load() }
    var targetRestored by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(savedTarget, targetRestored) {
        if (!targetRestored) {
            val restoredTarget = savedTarget?.takeIf { candidate ->
                candidate.type != BackupTargetType.DOCUMENT_TREE ||
                    candidate.uri?.let { uri -> hasPersistedWriteAccess(context, uri) } == true
            }
            if (savedTarget?.type == BackupTargetType.DOCUMENT_TREE && restoredTarget == null) {
                targetSelectionStore.clear()
                message = "The saved destination permission expired. Choose the folder again before backup."
                messageSection = AppSection.BACKUP_ACTIVITY
            }
            targetType = restoredTarget?.type ?: BackupTargetType.PHONE_DOWNLOADS
            targetUri = restoredTarget?.uri
            targetName = restoredTarget?.name ?: defaultMobileTargetName
            backupStatus = "${selectedBackupIds.size} items ready for $targetName."
            targetRestored = true
        }
    }

    fun clearPreview() {
        previewEntry = null
        previewText = null
        previewBitmap = null
        previewVideoUri = null
    }

    fun refreshSource() {
        sources = application.usbSourceResolver.attachedSources()
        selectedSource = sources.singleOrNull()
        recoveryDeviceType = RecoveryDeviceType.defaultFor(selectedSource?.detected)
        identity = null
        trust = null
        capabilities = null
        mtpScanSummary = null
        identityReadProgress = null
        identityReadError = null
    }

    fun selectSource(selected: AttachedSource) {
        selectedSource = selected
        recoveryDeviceType = RecoveryDeviceType.defaultFor(selected.detected)
        identity = null
        trust = null
        capabilities = null
        mtpScanSummary = null
        identityReadProgress = null
        identityReadError = null
    }

    val source = selectedSource
    val usbCollectedEntries = remember(identity?.peerId, refreshToken) {
        application.auditLog.completedExternalTransfers(identity?.peerId)
    }

    LaunchedEffect(identity?.peerId, refreshToken) {
        val peerId = identity?.peerId
        val currentIds = usbCollectedEntries.mapTo(linkedSetOf()) { it.id }
        selectedUsbBackupIds = mergeSourceBackupSelection(
            peerId = peerId,
            previousPeerId = selectedUsbPeerId,
            currentIds = currentIds,
            knownIds = knownUsbBackupIds,
            selectedIds = selectedUsbBackupIds,
        )
        selectedUsbPeerId = peerId
        knownUsbBackupIds = currentIds
        usbBackupStatus = if (usbCollectedEntries.isEmpty()) {
            "No USB source data has been recovered yet."
        } else {
            "${usbCollectedEntries.size} source items ready for $targetName."
        }
    }

    LaunchedEffect(refreshToken, selectedSource, identityReadRequest) {
        val source = selectedSource ?: return@LaunchedEffect
        if (!source.permissionGranted) return@LaunchedEffect
        identityReadError = null
        identityReadProgress = IdentityReadProgress(IdentityReadStage.CHECKING_PERMISSION, 0)
        val resolved = try {
            withTimeout(IDENTITY_READ_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) {
                application.usbSourceResolver.resolveIdentity(source) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        identityReadProgress = progress
                    }
                }
            }
            }
        } catch (_: TimeoutCancellationException) {
            identityReadProgress = null
            identityReadError =
                "Identity handshake timed out. Unlock the external device, approve its Trust/Allow prompt, then retry."
            return@LaunchedEffect
        } catch (throwable: Throwable) {
            identityReadProgress = null
            identityReadError = throwable.message ?: "The source did not return a readable USB identity."
            return@LaunchedEffect
        }
        identityReadProgress = null
        identity = resolved
        backupPeerId = resolved.peerId
        backupEntries = application.auditLog.completedExternalTransfers(resolved.peerId)
        selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
        backupStatus = "${backupEntries.size} external-source items ready for $targetName."
        val resolvedCapabilities = SourceCapabilityPolicy.forSource(source.detected)
        capabilities = resolvedCapabilities
        when (val loaded = application.trustStore.load(resolved.peerId, resolved.profileId)) {
            is TrustLoadResult.Found -> {
                val decision = TrustPolicy.evaluate(
                    loaded.trust.record,
                    TrustContext(resolved.peerId, loaded.trust.record.localDeviceId, application.keyManager.currentProof()),
                )
                trust = if (decision is TrustDecision.Approved && resolved.serialAvailable) loaded.trust else null
                if (trust != null) {
                    selectedCategories = trust!!.record.authorizedCategories
                        .intersect(resolvedCapabilities.supportedCategories)
                } else if (!resolved.serialAvailable) {
                    selectedCategories = resolvedCapabilities.supportedCategories
                    message = "This source has no stable USB/MTP serial. Confirm and authorize it for this session."
                    messageSection = AppSection.USB_SOURCE
                }
            }
            else -> {
                trust = null
                selectedCategories = resolvedCapabilities.supportedCategories
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && libraryEntries.isNotEmpty()) {
            activeSection = AppSection.BACKUP_ACTIVITY
            backupActivity = BackupActivityUi(
                title = "Export recovered data",
                status = "Starting export...",
                totalItems = libraryEntries.size,
                running = true,
            )
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DataExportManager(context).export(
                        libraryEntries,
                        uri,
                        identity?.peerId ?: backupPeerId,
                    ) { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            backupActivity = BackupActivityUi(
                                title = "Export recovered data",
                                status = "Exporting ${progress.completedItems} of ${progress.totalItems}",
                                completedItems = progress.completedItems,
                                totalItems = progress.totalItems,
                                currentItem = progress.currentItem,
                                bytesProcessed = progress.bytesExported,
                                currentItemBytes = progress.currentItemBytes,
                                currentItemTotal = progress.currentItemTotal,
                                running = true,
                            )
                        }
                    }
                }
                message = "Exported ${result.exportedItems} items (${formatBytes(result.bytesExported)})." +
                    if (result.failedItems > 0) " ${result.failedItems} failed." else ""
                messageSection = AppSection.BACKUP_ACTIVITY
                backupActivity = BackupActivityUi(
                    title = "Export recovered data",
                    status = message.orEmpty(),
                    completedItems = result.exportedItems + result.failedItems,
                    totalItems = libraryEntries.size,
                    bytesProcessed = result.bytesExported,
                    failed = result.failedItems > 0,
                )
            }
        }
    }

    val iosBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val currentSource = selectedSource
        val currentIdentity = identity
        if (currentSource?.detected?.platform != SourcePlatform.IOS || currentIdentity == null) {
            message = "Select and authorize the connected iPhone before importing its owner-approved backup."
            messageSection = AppSection.USB_SOURCE
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        iosBackupImporting = true
        ownerArchiveImporting = true
        iosBackupImportProgress = IosBackupImportProgress(
            IosBackupImportStage.PRESERVING_BACKUP,
            "Opening owner-approved Apple backup",
        )
        iosBackupImportResult = null
        ownerArchiveImportResult = null
        message = "IPHONE BACKUP: preserving the complete backup before extracting messages..."
        messageSection = AppSection.USB_SOURCE
        scope.launch {
            val universalResult = withContext(Dispatchers.IO) {
                application.ownerApprovedArchiveImporter.importSource(
                    sourceUri = uri,
                    peerId = currentIdentity.peerId,
                    sourceName = currentSource.detected.displayName,
                    deviceType = RecoveryDeviceType.IPHONE_IPAD,
                ) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        ownerArchiveImportProgress = progress
                        message = buildOwnerArchiveStatus(progress)
                        messageSection = AppSection.USB_SOURCE
                    }
                }
            }
            ownerArchiveImporting = false
            ownerArchiveImportResult = universalResult
            val result = withContext(Dispatchers.IO) {
                application.iosBackupImporter.importBackup(
                    archiveUri = uri,
                    peerId = currentIdentity.peerId,
                    sourceName = currentSource.detected.displayName,
                ) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        iosBackupImportProgress = progress
                        message = buildIosBackupStatus(progress)
                        messageSection = AppSection.USB_SOURCE
                    }
                }
            }
            iosBackupImporting = false
            iosBackupImportResult = result
            recoveryIssues = (universalResult.issues + result.issues)
                .distinctBy { "${it.reason}:${it.sourceItem}:${it.remediation}" }
            auditRevision += 1
            backupPeerId = currentIdentity.peerId
            backupEntries = application.auditLog.completedExternalTransfers(currentIdentity.peerId)
            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
            message = buildIosBackupCompletionStatus(result)
            messageSection = AppSection.USB_SOURCE
        }
    }

    val ownerArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val currentSource = selectedSource
        val currentIdentity = identity
        if (currentSource == null || currentIdentity == null) {
            message = "Select and authorize the matching external device before importing its owner-approved export."
            messageSection = AppSection.USB_SOURCE
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ownerArchiveImporting = true
        ownerArchiveImportProgress = OwnerArchiveImportProgress(
            OwnerArchiveImportStage.PRESERVING_SOURCE,
            "Opening owner-approved source data",
        )
        ownerArchiveImportResult = null
        message = "OWNER EXPORT: preserving the complete source before recovering every item..."
        messageSection = AppSection.USB_SOURCE
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                application.ownerApprovedArchiveImporter.importSource(
                    sourceUri = uri,
                    peerId = currentIdentity.peerId,
                    sourceName = currentSource.detected.displayName,
                    deviceType = recoveryDeviceType,
                ) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        ownerArchiveImportProgress = progress
                        message = buildOwnerArchiveStatus(progress)
                        messageSection = AppSection.USB_SOURCE
                    }
                }
            }
            ownerArchiveImporting = false
            ownerArchiveImportResult = result
            recoveryIssues = (recoveryIssues + result.issues)
                .distinctBy { "${it.reason}:${it.sourceItem}:${it.remediation}" }
            auditRevision += 1
            backupPeerId = currentIdentity.peerId
            backupEntries = application.auditLog.completedExternalTransfers(currentIdentity.peerId)
            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
            message = buildOwnerArchiveCompletionStatus(result)
            messageSection = AppSection.USB_SOURCE
        }
    }

    fun updateBackupWorkflowStatus(section: AppSection, status: String) {
        if (section == AppSection.USB_SOURCE) {
            usbBackupStatus = status
        } else {
            backupStatus = status
        }
    }

    fun backupWorkflowSelectedCount(): Int {
        return if (backupWorkflowSection == AppSection.USB_SOURCE) {
            selectedUsbBackupIds.size
        } else {
            selectedBackupIds.size
        }
    }

    val allDataExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val providerName = pendingTargetName ?: "Selected destination"
            val folderName = DocumentFile.fromTreeUri(context, uri)?.name
            targetUri = uri
            targetType = BackupTargetType.DOCUMENT_TREE
            targetName = listOfNotNull(providerName, folderName)
                .distinctBy(String::lowercase)
                .joinToString(" / ")
            targetSelectionStore.saveFolder(uri, targetName!!)
            pendingTargetName = null
            showTargetWizard = false
            updateBackupWorkflowStatus(
                backupWorkflowSection,
                "${backupWorkflowSelectedCount()} items ready for ${targetName}.",
            )
            message = "Destination saved: ${targetName}."
            messageSection = backupWorkflowSection
        } else {
            pendingTargetName = null
            message = "Folder selection canceled."
            messageSection = backupWorkflowSection
        }
    }

    fun launchTargetPicker(label: String) {
        pendingTargetName = label
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, "Choose backup folder")
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(LOCAL_STORAGE_AUTHORITY, PRIMARY_STORAGE_ROOT_ID),
            )
        }
        val pickerInstruction = "Navigate to the $label folder, then tap USE THIS FOLDER at the bottom."
        message = pickerInstruction
        messageSection = backupWorkflowSection
        Toast.makeText(context, pickerInstruction, Toast.LENGTH_LONG).show()
        allDataExportLauncher.launch(picker)
    }

    fun useDefaultMobileTarget() {
        targetSelectionStore.clear()
        targetType = BackupTargetType.PHONE_DOWNLOADS
        targetUri = null
        targetName = defaultMobileTargetName
        showTargetWizard = false
        updateBackupWorkflowStatus(
            backupWorkflowSection,
            "${backupWorkflowSelectedCount()} items ready for $targetName.",
        )
        message = "Backups will stay on this phone in Downloads."
        messageSection = backupWorkflowSection
    }

    fun selectProviderTarget(type: BackupTargetType, name: String) {
        targetType = type
        targetUri = null
        targetName = name
        targetSelectionStore.saveProvider(type, name)
        showTargetWizard = false
        updateBackupWorkflowStatus(
            backupWorkflowSection,
            "${backupWorkflowSelectedCount()} items ready to push to $name.",
        )
        message = "Destination selected: $name. Use the primary push button to continue."
        messageSection = backupWorkflowSection
    }

    fun providerUploadIntent(
        uris: List<Uri>,
        packageName: String?,
    ): Intent {
        val mimeTypes = uris.mapNotNull(context.contentResolver::getType).distinct()
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeTypes.singleOrNull() ?: "*/*"
            packageName?.let(::setPackage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("$productName recovery", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            if (mimeTypes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, "$productName recovery")
        }
    }

    fun launchProviderUpload(
        uris: List<Uri>,
        packageName: String?,
        label: String,
        itemCount: Int,
        ownerSection: AppSection,
    ): Boolean {
        val uploadIntent = providerUploadIntent(uris, packageName)
        val launchResult = runCatching {
            context.startActivity(
                if (packageName == null) {
                    Intent.createChooser(uploadIntent, "Upload backup to")
                } else {
                    uploadIntent
                },
            )
        }.onSuccess {
            val nextStep = if (packageName == null) {
                "Choose an app and destination, then confirm Upload. The selected app controls cloud progress."
            } else {
                "In $label, choose the destination folder and tap Upload. $label controls cloud progress."
            }
            message = "$itemCount recovered item(s) handed directly to $label. $nextStep"
            messageSection = ownerSection
            updateBackupWorkflowStatus(ownerSection, message.orEmpty())
            backupActivity = BackupActivityUi(
                title = "$label upload handoff",
                status = message.orEmpty(),
                running = false,
                failed = false,
            )
        }.onFailure { throwable ->
            message = "Could not open $label. Install/sign in to it or choose another destination. ${throwable.message.orEmpty()}"
            messageSection = ownerSection
            backupActivity = backupActivity.copy(
                title = "$label upload",
                status = message.orEmpty(),
                running = false,
                failed = true,
            )
        }
        return launchResult.isSuccess
    }

    fun uploadBackupEntries(
        entries: List<AuditEntry>,
        expectedPeerId: String,
        packageName: String?,
        label: String,
        ownerSection: AppSection,
    ) {
        backupWorkflowSection = ownerSection
        activeSection = ownerSection
        if (entries.isEmpty()) {
            updateBackupWorkflowStatus(ownerSection, "Select at least one available item before uploading.")
            return
        }
        val selection = planExternalRecoveryEntries(entries, expectedPeerId)
        recoveryIssues = selection.issues
        val eligibleEntries = selection.eligibleEntries
        val latestSourceSession = application.auditLog.latestSession(expectedPeerId)
        val completeSourceSession = permitsCloudHandoff(latestSourceSession?.status)
        if (eligibleEntries.isEmpty()) {
            updateBackupWorkflowStatus(ownerSection, "No eligible external-source items. Review recovery actions below.")
            return
        }
        backingUp = true
        val stagingStatus =
            "Building one verified $productName package in Downloads / $productName Packages. " +
                "$label upload has not started yet."
        updateBackupWorkflowStatus(ownerSection, stagingStatus)
        backupActivity = BackupActivityUi(
            title = "Local staging before $label",
            status = stagingStatus,
            totalItems = eligibleEntries.size,
            running = true,
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DataExportManager(context).createUploadArchive(
                    entries = entries,
                    expectedPeerId = expectedPeerId,
                    archiveNamePrefix = productName,
                    destinationFolder = "$productName Packages",
                    onProgress = { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            val progressStatus =
                                "Packaging: ${progress.completedItems}/${progress.totalItems} · ${progress.currentItem}. " +
                                    "$label upload starts after package verification and owner confirmation."
                            updateBackupWorkflowStatus(ownerSection, progressStatus)
                            backupActivity = BackupActivityUi(
                                title = "Package before $label",
                                status = progressStatus,
                                completedItems = progress.completedItems,
                                totalItems = progress.totalItems,
                                currentItem = progress.currentItem,
                                bytesProcessed = progress.sourceBytesArchived,
                                currentItemBytes = progress.currentItemBytes,
                                currentItemTotal = progress.currentItemTotal,
                                running = true,
                            )
                        }
                    },
                )
            }
            backingUp = false
            recoveryIssues = result.recoveryIssues
            if (result.uri == null) {
                val failureStatus = "Could not prepare upload: ${result.error ?: "unknown error"}"
                updateBackupWorkflowStatus(ownerSection, failureStatus)
                message = failureStatus
                messageSection = ownerSection
                backupActivity = backupActivity.copy(
                    status = failureStatus,
                    running = false,
                    failed = true,
                )
            } else {
                if (!completeSourceSession) {
                    val partialStatus =
                        "Partial verified package kept locally as ${result.displayName}. " +
                            "Cloud handoff is blocked until USB recovery completes without failures. " +
                            "Retry recovery, or deliberately choose a local/folder destination for partial preservation."
                    updateBackupWorkflowStatus(ownerSection, partialStatus)
                    message = partialStatus
                    messageSection = ownerSection
                    backupActivity = BackupActivityUi(
                        title = "Partial package preserved locally",
                        status = partialStatus,
                        completedItems = result.archivedItems,
                        totalItems = result.archivedItems,
                        bytesProcessed = result.archiveBytes,
                        failed = true,
                    )
                    return@launch
                }
                val readyStatus =
                    "${if (completeSourceSession) "Verified" else "Partial verified"} package ready: " +
                        "${result.archivedItems} items, ${formatBytes(result.archiveBytes)}, " +
                        "SHA-256 ${result.archiveSha256?.take(16) ?: "unavailable"}…." +
                        if (result.excludedItems > 0) {
                            " ${result.excludedItems} item(s) need remediation. Opening $label."
                        } else {
                            " Opening $label."
                        }
                updateBackupWorkflowStatus(ownerSection, readyStatus)
                backupActivity = BackupActivityUi(
                    title = "Local staging complete",
                    status = readyStatus,
                    completedItems = result.archivedItems,
                    totalItems = result.archivedItems,
                    bytesProcessed = result.archiveBytes,
                )
                launchProviderUpload(
                    listOf(result.uri),
                    packageName,
                    label,
                    result.archivedItems,
                    ownerSection,
                )
            }
        }
    }

    fun packageBackupForSelectedTarget(
        entries: List<AuditEntry>,
        expectedPeerId: String,
        ownerSection: AppSection,
    ) {
        val destination = targetUri
        val destinationName = targetName
        if (entries.isEmpty()) {
            updateBackupWorkflowStatus(ownerSection, "Select at least one available item before packaging.")
            return
        }
        if (destination != null && !hasPersistedWriteAccess(context, destination)) {
            targetSelectionStore.clear()
            targetType = BackupTargetType.PHONE_DOWNLOADS
            targetUri = null
            targetName = defaultMobileTargetName
            val failure = "The selected destination is no longer writable. Choose it again before packaging."
            updateBackupWorkflowStatus(ownerSection, failure)
            message = failure
            messageSection = ownerSection
            return
        }
        if (backingUp) return
        val existingRecoveryIssues = recoveryIssues
        val latestSourceSession = application.auditLog.latestSession(expectedPeerId)
        val completeSourceSession = permitsCloudHandoff(latestSourceSession?.status)
        backingUp = true
        backupWorkflowSection = ownerSection
        activeSection = ownerSection
        val startingStatus = "Packaging ${entries.size} verified item(s) for $destinationName..."
        updateBackupWorkflowStatus(ownerSection, startingStatus)
        message = "BACKUP: $startingStatus"
        messageSection = ownerSection
        backupActivity = BackupActivityUi(
            title = "Package for $destinationName",
            status = startingStatus,
            totalItems = entries.size,
            running = true,
        )
        scope.launch {
            val packageResult = withContext(Dispatchers.IO) {
                DataExportManager(context).createUploadArchive(
                    entries = entries,
                    expectedPeerId = expectedPeerId,
                    archiveNamePrefix = productName,
                    destinationFolder = "$productName Packages",
                    onProgress = { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            val progressStatus =
                                "Packaging ${progress.completedItems}/${progress.totalItems} · ${progress.currentItem}"
                            updateBackupWorkflowStatus(ownerSection, progressStatus)
                            backupActivity = BackupActivityUi(
                                title = "Package for $destinationName",
                                status = progressStatus,
                                completedItems = progress.completedItems,
                                totalItems = progress.totalItems,
                                currentItem = progress.currentItem,
                                bytesProcessed = progress.sourceBytesArchived,
                                currentItemBytes = progress.currentItemBytes,
                                currentItemTotal = progress.currentItemTotal,
                                running = true,
                            )
                        }
                    },
                )
            }
            recoveryIssues = (existingRecoveryIssues + packageResult.recoveryIssues)
                .distinctBy { issue -> "${issue.reason}:${issue.sourceItem}" }
            val packageUri = packageResult.uri
            val packageSha256 = packageResult.archiveSha256
            if (packageUri == null || packageSha256.isNullOrBlank()) {
                val failure = "Backup package failed: ${packageResult.error ?: "verification did not complete"}"
                updateBackupWorkflowStatus(ownerSection, failure)
                message = failure
                messageSection = ownerSection
                backupActivity = backupActivity.copy(status = failure, running = false, failed = true)
                backingUp = false
                return@launch
            }

            if (destination == null) {
                val complete =
                    "${if (completeSourceSession) "Verified" else "PARTIAL verified"} package saved to " +
                        "Downloads / $productName Packages: " +
                        "${packageResult.displayName}, ${formatBytes(packageResult.archiveBytes)}, " +
                        "SHA-256 $packageSha256."
                updateBackupWorkflowStatus(ownerSection, complete)
                message = complete
                messageSection = ownerSection
                backupActivity = BackupActivityUi(
                    title = if (completeSourceSession) "Backup complete" else "Partial backup preserved",
                    status = complete,
                    completedItems = packageResult.archivedItems,
                    totalItems = packageResult.archivedItems,
                    bytesProcessed = packageResult.archiveBytes,
                    failed = packageResult.excludedItems > 0 || !completeSourceSession,
                )
                backingUp = false
                return@launch
            }

            val packageName = packageResult.displayName ?: "$productName.zip"
            val packageEntry = AuditEntry(
                id = DeviceIdentity.sha256("$expectedPeerId|$packageSha256").take(15).toLong(16),
                transferredAtEpochMillis = System.currentTimeMillis(),
                category = ConsentCategory.DOCUMENTS,
                sourceItem = "/RecoverByBackup package/$packageName",
                destination = packageUri.toString(),
                bytesTransferred = packageResult.archiveBytes,
                status = com.jerrywolff.phonesyncusbc.data.TransferStatus.COMPLETED,
                error = null,
                sourceSize = packageResult.archiveBytes,
                contentSha256 = packageSha256,
                peerId = expectedPeerId,
                sourceFingerprint = "recoverbybackup-package:$expectedPeerId:$packageSha256",
            )
            val copyResult = withContext(Dispatchers.IO) {
                DataExportManager(context).export(
                    entries = listOf(packageEntry),
                    destinationTree = destination,
                    expectedPeerId = expectedPeerId,
                    folderNamePrefix = "$productName Backup",
                ) { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        backupActivity = BackupActivityUi(
                            title = "Copy package to $destinationName",
                            status = "Copying and verifying package at destination...",
                            completedItems = progress.completedItems,
                            totalItems = progress.totalItems,
                            currentItem = progress.currentItem,
                            bytesProcessed = progress.bytesExported,
                            currentItemBytes = progress.currentItemBytes,
                            currentItemTotal = progress.currentItemTotal,
                            running = true,
                        )
                    }
                }
            }
            val complete = if (copyResult.failedItems == 0) {
                "${if (completeSourceSession) "Verified" else "PARTIAL verified"} package copied to " +
                    "$destinationName: $packageName, " +
                    "${formatBytes(packageResult.archiveBytes)}, SHA-256 $packageSha256."
            } else {
                "Package copy failed: ${copyResult.error ?: "choose another writable destination and retry"}."
            }
            recoveryIssues = (existingRecoveryIssues + packageResult.recoveryIssues + copyResult.recoveryIssues)
                .distinctBy { issue -> "${issue.reason}:${issue.sourceItem}" }
            updateBackupWorkflowStatus(ownerSection, complete)
            message = complete
            messageSection = ownerSection
            backupActivity = BackupActivityUi(
                title = when {
                    copyResult.failedItems > 0 -> "Backup incomplete"
                    completeSourceSession -> "Backup complete"
                    else -> "Partial backup preserved"
                },
                status = complete,
                completedItems = copyResult.exportedItems,
                totalItems = 1,
                bytesProcessed = copyResult.bytesExported,
                failed = copyResult.failedItems > 0 || !completeSourceSession,
            )
            backingUp = false
        }
    }

    fun executeBackupForSelectedTarget(
        entries: List<AuditEntry>,
        ownerSection: AppSection,
    ) {
        val expectedPeerId = identity?.peerId ?: backupPeerId
        if (expectedPeerId.isNullOrBlank()) {
            val failure = "Backup refused: no selected external USB source identity is available."
            updateBackupWorkflowStatus(ownerSection, failure)
            message = failure
            messageSection = ownerSection
            return
        }
        val providerTarget = targetType.providerTarget()
        if (providerTarget == null) {
            packageBackupForSelectedTarget(entries, expectedPeerId, ownerSection)
        } else {
            uploadBackupEntries(
                entries,
                expectedPeerId,
                providerTarget.packageName,
                providerTarget.label,
                ownerSection,
            )
        }
    }

    fun runUsbRecovery(packageAfterRecovery: Boolean) {
        val currentSource = selectedSource ?: run {
            message = "Connect and select an external USB source first."
            messageSection = AppSection.USB_SOURCE
            return
        }
        val currentIdentity = identity ?: run {
            message = "Complete USB identity and trust approval before recovery."
            messageSection = AppSection.USB_SOURCE
            return
        }
        if (syncing || backingUp) return
        syncing = true
        liveProgress = SyncProgress(
            currentItem = null,
            transferredItems = 0,
            skippedItems = 0,
            failedItems = 0,
            bytesTransferred = 0,
            phase = SyncPhase.DISCOVERING,
        )
        mtpScanSummary = null
        message = "DETECTING: preparing an owner-authorized USB inventory."
        messageSection = AppSection.USB_SOURCE
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val publishProgress: (SyncProgress) -> Unit = { progress ->
                    scope.launch(Dispatchers.Main.immediate) {
                        liveProgress = progress
                        message = buildLiveStatus(progress)
                        messageSection = AppSection.USB_SOURCE
                    }
                }
                application.mtpSyncEngine.sync(
                    source = currentSource,
                    identity = currentIdentity,
                    recoveryDeviceType = recoveryDeviceType,
                    authorizedCategories = selectedCategories,
                    onProgress = publishProgress,
                )
            }
            syncing = false
            val scan = result.mtpScan
            liveProgress = SyncProgress(
                currentItem = null,
                transferredItems = result.transferredItems,
                skippedItems = result.skippedItems,
                failedItems = result.failedItems,
                bytesTransferred = result.bytesTransferred,
                phase = SyncPhase.COMPLETE,
                discoveredItems = scan?.scannedItems ?: 0,
                processedItems = scan?.processedItems ?: 0,
                totalItems = scan?.scannedItems ?: 0,
                advertisedBytes = scan?.advertisedBytes ?: 0,
            )
            mtpScanSummary = scan
            val completedEntries = application.auditLog.completedExternalTransfers(currentIdentity.peerId)
            val recoveredCategories = recoveredCoverageCategories(completedEntries)
            message = buildSyncCompletionStatus(result, recoveredCategories)
            messageSection = AppSection.USB_SOURCE
            backupPeerId = currentIdentity.peerId
            backupEntries = completedEntries
            selectedBackupIds = completedEntries.mapTo(linkedSetOf()) { it.id }
            refreshToken += 1
            if (packageAfterRecovery) {
                if (result.status != com.jerrywolff.phonesyncusbc.data.SyncStatus.COMPLETED || result.failedItems > 0) {
                    message = "Recovery is incomplete and was not packaged automatically. " +
                        "Review failed items, reconnect or unlock the source, and retry. " +
                        "Verified items remain preserved locally."
                    messageSection = AppSection.USB_SOURCE
                } else if (completedEntries.isEmpty()) {
                    message = "No verified USB-visible items are available to package. Review source access and retry."
                    messageSection = AppSection.USB_SOURCE
                } else {
                    executeBackupForSelectedTarget(completedEntries, AppSection.USB_SOURCE)
                }
            }
        }
    }

    fun copyAllUsbVisibleFilesAndRescan() {
        val currentTrust = trust ?: return
        val currentCapabilities = capabilities ?: return
        val authorizedCategories = currentCapabilities.supportedCategories
        val updatedTrust = currentTrust.copy(
            record = currentTrust.record.copy(authorizedCategories = authorizedCategories),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        application.trustStore.save(updatedTrust)
        trust = updatedTrust
        selectedCategories = authorizedCategories
        recoveryIssues = emptyList()
        message = "All USB-visible external files are authorized. Starting a fresh collection."
        messageSection = AppSection.USB_SOURCE
        runUsbRecovery(packageAfterRecovery = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(productName) },
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_connection_logo),
                        contentDescription = "Connection",
                        tint = Color(0xFF1976D2),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = activeSection.ordinal) {
                AppSection.entries.forEach { section ->
                    Tab(
                        selected = activeSection == section,
                        onClick = {
                            activeSection = section
                            if (section == AppSection.DATA_READER) {
                                val peerId = identity?.peerId ?: backupPeerId
                                libraryEntries = application.auditLog.completedExternalTransfers(peerId)
                                showParsedData = true
                                showLibrary = true
                            }
                            scope.launch { listState.scrollToItem(0) }
                        },
                        text = { Text(section.label) },
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            item {
                Spacer(Modifier.height(12.dp))
            }
            if (activeSection == AppSection.USB_SOURCE) item {
                if (recoveryDeviceType == RecoveryDeviceType.NETWORK_IPHONE) {
                    NetworkRecoveryScreen(
                        onDataRecovered = { messages, contacts, notes ->
                            message = "Recovered $messages messages, $contacts contacts, and $notes notes from the iPhone companion app."
                            messageSection = AppSection.USB_SOURCE
                        },
                    )
                } else if (recoveryDeviceType == RecoveryDeviceType.NETWORK_DEVICE) {
                    GenericNetworkRecoveryScreen()
                } else {
                    SourceConnectionPanel(
                        sources = sources,
                        source = source,
                        onSourceSelected = ::selectSource,
                        recoveryDeviceType = recoveryDeviceType,
                        onRecoveryDeviceTypeSelected = { recoveryDeviceType = it },
                        onRefresh = ::refreshSource,
                        onNetworkIphone = { recoveryDeviceType = RecoveryDeviceType.NETWORK_IPHONE },
                        onNetworkDevice = { recoveryDeviceType = RecoveryDeviceType.NETWORK_DEVICE },
                        onRequestUsbPermission = onRequestUsbPermission,
                        onContinue = {
                            identityReadError = null
                            identityReadRequest += 1
                            scope.launch {
                                val lastItem = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                listState.animateScrollToItem(minOf(SOURCE_SYNC_SECTION_INDEX, lastItem))
                            }
                        },
                    )
                }
            }
            if (activeSection == AppSection.BACKUP_ACTIVITY) item {
                BackupActivityPanel(backupActivity)
            }
            if (activeSection == AppSection.DATA_READER) {
                item {
                    ArtifactDataReaderView(
                        entries = libraryEntries,
                        initialSourceId = identity?.peerId ?: backupPeerId,
                        initialSourceName = source?.detected?.displayName ?: "External source",
                        database = application.artifactIndexDatabase,
                        indexer = application.artifactIndexer,
                        onBack = { activeSection = AppSection.USB_SOURCE },
                    )
                }
            }
            if (activeSection == AppSection.BACKUP_ACTIVITY) item {
                BackupPanel(
                    title = "Preserve recovered source data",
                    description = if (source != null) {
                        "Only verified recovery results from ${source.detected.displayName}."
                    } else {
                        "Only verified results from the most recently recovered external source."
                    },
                    onSelectBackup = {
                        if (!backingUp) {
                            backupWorkflowSection = AppSection.BACKUP_ACTIVITY
                            val peerId = identity?.peerId ?: backupPeerId
                            backupEntries = application.auditLog.completedExternalTransfers(peerId)
                            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                            showTargetWizard = false
                            showBackupSelection = true
                        }
                    },
                    onChooseTarget = {
                        backupWorkflowSection = AppSection.BACKUP_ACTIVITY
                        val peerId = identity?.peerId ?: backupPeerId
                        backupEntries = application.auditLog.completedExternalTransfers(peerId)
                        if (selectedBackupIds.isEmpty()) {
                            selectedBackupIds = backupEntries.mapTo(linkedSetOf()) { it.id }
                        }
                        showBackupSelection = false
                        showTargetWizard = true
                    },
                    backingUp = backingUp,
                    selectedItemCount = selectedBackupIds.size,
                    selectedBytes = backupEntries
                        .filter { it.id in selectedBackupIds }
                        .sumOf { it.bytesTransferred },
                    passwordVaultItemCount = backupEntries.count {
                        it.id in selectedBackupIds && it.category == ConsentCategory.PASSWORD_EXPORTS
                    },
                    targetType = targetType,
                    targetName = targetName,
                    backupStatus = backupStatus,
                    onExecuteBackup = {
                        executeBackupForSelectedTarget(
                            backupEntries.filter { it.id in selectedBackupIds },
                            AppSection.BACKUP_ACTIVITY,
                        )
                    },
                )
            }
            if (
                activeSection == AppSection.BACKUP_ACTIVITY &&
                backupWorkflowSection == AppSection.BACKUP_ACTIVITY &&
                showBackupSelection
            ) {
                item {
                    val workflowEntries = if (backupWorkflowSection == AppSection.USB_SOURCE) {
                        usbCollectedEntries
                    } else {
                        backupEntries
                    }
                    val workflowSelectedIds = if (backupWorkflowSection == AppSection.USB_SOURCE) {
                        selectedUsbBackupIds
                    } else {
                        selectedBackupIds
                    }
                    BackupSelectionView(
                        entries = workflowEntries,
                        selectedIds = workflowSelectedIds,
                        onSelectionChanged = { selected ->
                            if (backupWorkflowSection == AppSection.USB_SOURCE) {
                                selectedUsbBackupIds = selected
                            } else {
                                selectedBackupIds = selected
                            }
                            updateBackupWorkflowStatus(
                                backupWorkflowSection,
                                "${selected.size} items ready for $targetName.",
                            )
                        },
                        onCancel = { showBackupSelection = false },
                        onContinue = {
                            showBackupSelection = false
                            updateBackupWorkflowStatus(
                                backupWorkflowSection,
                                "${backupWorkflowSelectedCount()} items ready for $targetName.",
                            )
                        },
                    )
                }
            }
            if (
                activeSection == AppSection.BACKUP_ACTIVITY &&
                backupWorkflowSection == AppSection.BACKUP_ACTIVITY &&
                showTargetWizard
            ) {
                item {
                    TargetMediaWizard(
                        onBack = { showTargetWizard = false },
                        onUsePhoneStorage = ::useDefaultMobileTarget,
                        onChooseFolder = { selectedTargetName ->
                            launchTargetPicker(selectedTargetName)
                        },
                        onUseOneDrive = {
                            selectProviderTarget(BackupTargetType.ONEDRIVE, "OneDrive")
                        },
                        onUseGoogleDrive = {
                            selectProviderTarget(BackupTargetType.GOOGLE_DRIVE, "Google Drive")
                        },
                        onUseOtherApp = {
                            selectProviderTarget(BackupTargetType.OTHER_APP, "Another app")
                        },
                    )
                }
            }
            if (activeSection == AppSection.USB_SOURCE && showLibrary) {
                item {
                    if (previewEntry != null && previewText != null) {
                        TextPreview(
                            entry = previewEntry!!,
                            text = previewText!!,
                            onBack = {
                                clearPreview()
                            },
                        )
                    } else if (previewEntry != null && previewBitmap != null) {
                        ImagePreview(
                            entry = previewEntry!!,
                            bitmap = previewBitmap!!,
                            onBack = { clearPreview() },
                        )
                    } else if (previewEntry != null && previewVideoUri != null) {
                        VideoPreview(
                            entry = previewEntry!!,
                            uri = previewVideoUri!!,
                            context = context,
                            onBack = { clearPreview() },
                        )
                    } else {
                        if (showParsedData) {
                            ArtifactDataReaderView(
                                entries = libraryEntries,
                                initialSourceId = identity?.peerId ?: backupPeerId,
                                initialSourceName = source?.detected?.displayName ?: "External source",
                                database = application.artifactIndexDatabase,
                                indexer = application.artifactIndexer,
                                onBack = { showParsedData = false },
                            )
                        } else {
                            ImportedDataView(
                                entries = libraryEntries,
                                onBack = { showLibrary = false },
                                onBrowseParsed = { showParsedData = true },
                                onOpen = { entry ->
                                val destination = entry.destination?.let(Uri::parse)
                                if (destination != null) {
                                    if (isTextLike(entry)) {
                                        scope.launch {
                                            val text = withContext(Dispatchers.IO) {
                                                readPreviewText(context, destination)
                                            }
                                            if (text != null) {
                                                previewEntry = entry
                                                previewText = text
                                            } else {
                                                message = "This recovered artifact could not be read."
                                                messageSection = AppSection.USB_SOURCE
                                            }
                                        }
                                    } else if (isImageLike(entry)) {
                                        scope.launch {
                                            val bitmap = withContext(Dispatchers.IO) {
                                                readPreviewBitmap(context, destination)
                                            }
                                            if (bitmap != null) {
                                                previewEntry = entry
                                                previewBitmap = bitmap
                                            } else {
                                                message = "This recovered image could not be read."
                                                messageSection = AppSection.USB_SOURCE
                                            }
                                        }
                                    } else if (isVideoLike(entry)) {
                                        previewEntry = entry
                                        previewVideoUri = destination
                                    } else {
                                        val intent = collectedItemIntent(
                                            destination = destination,
                                            mimeType = DataExportManager(context).mimeType(entry),
                                        )
                                        runCatching {
                                            context.startActivity(intent)
                                        }.onFailure {
                                            message = "No installed app can open this recovered artifact type."
                                            messageSection = AppSection.USB_SOURCE
                                        }
                                    }
                                }
                                },
                                onExport = { exportLauncher.launch(null) },
                            )
                        }
                    }
                }
            }
            if (activeSection == AppSection.USB_SOURCE && source == null) {
                item { Text("Connect a source phone using a data-capable USB cable.") }
            } else if (activeSection == AppSection.USB_SOURCE && source != null) {
                if (capabilities == null || identity == null) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Reading USB source identity", style = MaterialTheme.typography.titleMedium)
                                identityReadProgress?.let { progress ->
                                    val percentage = if (progress.totalSteps > 0) {
                                        progress.completedSteps * 100 / progress.totalSteps
                                    } else {
                                        0
                                    }
                                    Text(identityReadStageLabel(progress.stage))
                                    LinearProgressIndicator(
                                        progress = {
                                            (progress.completedSteps.toFloat() / progress.totalSteps)
                                                .coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        "$percentage% · ${progress.completedSteps} of ${progress.totalSteps} checks complete",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                identityReadError?.let { error ->
                                    Text("Source identity read failed: $error")
                                    OutlinedButton(onClick = ::refreshSource, modifier = Modifier.fillMaxWidth()) {
                                        Text("Retry source read")
                                    }
                                }
                            }
                        }
                    }
                }
                capabilities?.let { policy ->
                    item {
                        Text(policy.connectionMessage)
                        if (!policy.connectionSupported) Text("This connection cannot be used by Android USB host mode.")
                    }
                }
                if (trust == null && identity != null && capabilities?.connectionSupported == true) {
                    item {
                        TrustApproval(
                            capabilities = capabilities!!,
                            onApprove = {
                                val currentIdentity = identity!!
                                val authorizedCategories = capabilities!!.supportedCategories
                                selectedCategories = authorizedCategories
                                val now = System.currentTimeMillis()
                                val created = StoredTrust(
                                    record = com.jerrywolff.phonesyncusbc.domain.TrustRecord(
                                        peerDeviceId = currentIdentity.peerId,
                                        localDeviceId = DeviceIdentity.localId(context),
                                        encryptionKeyProof = application.keyManager.currentProof(),
                                        authorizedCategories = authorizedCategories,
                                    ),
                                    profileId = currentIdentity.profileId,
                                    sourceName = source.detected.displayName,
                                    platform = source.detected.platform,
                                    family = source.detected.family,
                                    createdAtEpochMillis = now,
                                    updatedAtEpochMillis = now,
                                )
                                application.trustStore.save(created)
                                trust = created
                                message = "USB source authorized for every available data category."
                                messageSection = AppSection.USB_SOURCE
                            },
                        )
                    }
                } else if (trust != null && identity != null) {
                    item {
                        TrustedDashboard(
                                trust = trust!!,
                                categories = selectedCategories,
                                syncing = syncing,
                                backingUp = backingUp,
                                liveProgress = liveProgress,
                                mtpScanSummary = mtpScanSummary,
                                recoveryIssues = recoveryIssues,
                                iosBackupImporting = iosBackupImporting,
                                iosBackupImportProgress = iosBackupImportProgress,
                                iosBackupImportResult = iosBackupImportResult,
                                ownerArchiveImporting = ownerArchiveImporting,
                                ownerArchiveImportProgress = ownerArchiveImportProgress,
                                ownerArchiveImportResult = ownerArchiveImportResult,
                                auditRevision = auditRevision,
                                recoveryDeviceType = recoveryDeviceType,
                                sourcePlatform = source.detected.platform,
                                identitySerialAvailable = identity!!.serialAvailable,
                                targetName = targetName,
                                auditLog = application.auditLog,
                                onViewLibrary = {
                                    libraryEntries = application.auditLog.completedExternalTransfers(
                                        trust!!.record.peerDeviceId,
                                    )
                                    showParsedData = false
                                    showLibrary = true
                                },
                                onSync = { runUsbRecovery(packageAfterRecovery = false) },
                                onCollectSourceSms = { runUsbRecovery(packageAfterRecovery = false) },
                                onCopyAllUsbVisibleFiles = ::copyAllUsbVisibleFilesAndRescan,
                                onCompleteBackup = { runUsbRecovery(packageAfterRecovery = true) },
                                onChooseTarget = {
                                    backupWorkflowSection = AppSection.USB_SOURCE
                                    showBackupSelection = false
                                    showTargetWizard = true
                                },
                                onImportIosBackup = {
                                    iosBackupLauncher.launch(
                                        arrayOf(
                                            "application/zip",
                                            "application/octet-stream",
                                            "application/x-zip-compressed",
                                        ),
                                    )
                                },
                                onImportOwnerArchive = {
                                    ownerArchiveLauncher.launch(arrayOf("*/*"))
                                },
                                onRevoke = {
                                    trust = application.trustStore.revoke(trust!!)
                                    message = "Trust revoked. Re-approval is required before another acquisition."
                                    messageSection = AppSection.USB_SOURCE
                                },
                        )
                    }
                    if (backupWorkflowSection == AppSection.USB_SOURCE && showTargetWizard) {
                        item {
                            TargetMediaWizard(
                                onBack = { showTargetWizard = false },
                                onUsePhoneStorage = ::useDefaultMobileTarget,
                                onChooseFolder = { selectedTargetName -> launchTargetPicker(selectedTargetName) },
                                onUseOneDrive = {
                                    selectProviderTarget(BackupTargetType.ONEDRIVE, "OneDrive")
                                },
                                onUseGoogleDrive = {
                                    selectProviderTarget(BackupTargetType.GOOGLE_DRIVE, "Google Drive")
                                },
                                onUseOtherApp = {
                                    selectProviderTarget(BackupTargetType.OTHER_APP, "Another app")
                                },
                            )
                        }
                    }
                    if (usbCollectedEntries.isNotEmpty()) {
                        if (
                            backupWorkflowSection == AppSection.USB_SOURCE &&
                            (backupActivity.running || backupActivity.totalItems > 0 || backupActivity.failed)
                        ) {
                            item { BackupActivityPanel(backupActivity) }
                        }
                        item {
                            BackupPanel(
                                title = "Preserve this recovery set",
                                description =
                                    "Send only verified artifacts recovered from ${source.detected.displayName} to preservation storage.",
                                onSelectBackup = {
                                    if (!backingUp) {
                                        backupWorkflowSection = AppSection.USB_SOURCE
                                        showTargetWizard = false
                                        showBackupSelection = true
                                    }
                                },
                                onChooseTarget = {
                                    backupWorkflowSection = AppSection.USB_SOURCE
                                    showBackupSelection = false
                                    showTargetWizard = true
                                },
                                backingUp = backingUp,
                                selectedItemCount = selectedUsbBackupIds.size,
                                selectedBytes = usbCollectedEntries
                                    .filter { it.id in selectedUsbBackupIds }
                                    .sumOf { it.bytesTransferred },
                                passwordVaultItemCount = usbCollectedEntries.count {
                                    it.id in selectedUsbBackupIds &&
                                        it.category == ConsentCategory.PASSWORD_EXPORTS
                                },
                                targetType = targetType,
                                targetName = targetName,
                                backupStatus = usbBackupStatus,
                                onExecuteBackup = {
                                    executeBackupForSelectedTarget(
                                        usbCollectedEntries.filter { it.id in selectedUsbBackupIds },
                                        AppSection.USB_SOURCE,
                                    )
                                },
                            )
                        }
                        if (
                            backupWorkflowSection == AppSection.USB_SOURCE &&
                            showBackupSelection
                        ) {
                            item {
                                BackupSelectionView(
                                    entries = usbCollectedEntries,
                                    selectedIds = selectedUsbBackupIds,
                                    onSelectionChanged = { selected ->
                                        selectedUsbBackupIds = selected
                                        usbBackupStatus =
                                            "${selected.size} items ready for $targetName."
                                    },
                                    onCancel = { showBackupSelection = false },
                                    onContinue = {
                                        showBackupSelection = false
                                        usbBackupStatus =
                                            "${selectedUsbBackupIds.size} items ready for $targetName."
                                    },
                                )
                            }
                        }
                    }
                }
            }
            message?.takeIf { messageSection == activeSection }?.let {
                item { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            }
        }
    }

}

@androidx.compose.runtime.Composable
private fun TrustApproval(
    capabilities: SourceCapabilities,
    onApprove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Trust External Source", style = MaterialTheme.typography.headlineSmall)
            Text("Authorize every data category this source exposes over USB or as an export file.")
            CapabilityList(capabilities)
            Button(
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Authorize all available data") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CapabilityList(
    capabilities: SourceCapabilities,
) {
    capabilities.categories.forEach { capability ->
        Column {
            Text(capability.category.label())
            Text(capability.description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@androidx.compose.runtime.Composable
private fun TrustedDashboard(
    trust: StoredTrust,
    categories: Set<ConsentCategory>,
    syncing: Boolean,
    backingUp: Boolean,
    liveProgress: SyncProgress?,
    mtpScanSummary: MtpScanSummary?,
    recoveryIssues: List<RecoveryIssue>,
    iosBackupImporting: Boolean,
    iosBackupImportProgress: IosBackupImportProgress?,
    iosBackupImportResult: IosBackupImportResult?,
    ownerArchiveImporting: Boolean,
    ownerArchiveImportProgress: OwnerArchiveImportProgress?,
    ownerArchiveImportResult: OwnerArchiveImportResult?,
    auditRevision: Int,
    recoveryDeviceType: RecoveryDeviceType,
    sourcePlatform: SourcePlatform,
    identitySerialAvailable: Boolean,
    targetName: String,
    auditLog: com.jerrywolff.phonesyncusbc.data.AuditLog,
    onViewLibrary: () -> Unit,
    onSync: () -> Unit,
    onCollectSourceSms: () -> Unit,
    onCopyAllUsbVisibleFiles: () -> Unit,
    onCompleteBackup: () -> Unit,
    onChooseTarget: () -> Unit,
    onImportIosBackup: () -> Unit,
    onImportOwnerArchive: () -> Unit,
    onRevoke: () -> Unit,
) {
    val latest = remember(trust.updatedAtEpochMillis) { auditLog.latestSession(trust.record.peerDeviceId) }
    val audit = remember(trust.updatedAtEpochMillis, mtpScanSummary, auditRevision) {
        auditLog.recentTransfers(trust.record.peerDeviceId, 25)
    }
    val recoveredCategories = remember(trust.updatedAtEpochMillis, mtpScanSummary, auditRevision) {
        recoveredCoverageCategories(auditLog.completedExternalTransfers(trust.record.peerDeviceId))
    }
    val failedRecoveryIssues = remember(audit) {
        planExternalRecoveryEntries(
            audit.filter { it.status != com.jerrywolff.phonesyncusbc.data.TransferStatus.COMPLETED },
            trust.record.peerDeviceId,
        ).issues
    }
    val currentRecoveryIssues = (recoveryIssues + failedRecoveryIssues)
        .distinctBy { "${it.reason}:${it.sourceItem}" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Read-only logical recovery", style = MaterialTheme.typography.titleMedium)
            Text("All USB-visible file categories authorized: ${categories.joinToString { it.label() }}")
            Text(
                if (identitySerialAvailable) {
                    "Trusted identity: source-reported serial available."
                } else {
                    "Trusted identity: descriptor-only. Confirm the selected device carefully after reconnecting identical models."
                },
                color = if (identitySerialAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Last acquisition: ${latest?.completedAtEpochMillis?.let(::formatTime) ?: "Never"}")
            liveProgress?.let { progress ->
                Text(
                    when {
                        syncing && progress.phase == SyncPhase.DISCOVERING -> "Discovering USB-visible files"
                        syncing && progress.phase == SyncPhase.VERIFYING -> "Verifying recovered copy"
                        syncing -> "Reading USB source"
                        else -> "Last USB read"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                progress.currentItem?.let { currentItem ->
                    val action = when (progress.phase) {
                        SyncPhase.DISCOVERING -> "Inspecting"
                        SyncPhase.VERIFYING -> "Hashing"
                        else -> "Reading"
                    }
                    Text("$action: ${currentItem.substringAfterLast('/')}")
                }
                if (progress.readMethod != null && progress.readAttemptsForMethod > 0) {
                    Text(
                        "USB method: ${progress.readMethod.label()} · attempt " +
                            "${progress.readAttempt}/${progress.readAttemptsForMethod}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (syncing && progress.phase == SyncPhase.DISCOVERING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "${progress.discoveredItems} files found · " +
                            "${formatBytes(progress.advertisedBytes)} advertised",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (progress.phase != SyncPhase.DISCOVERING && progress.totalItems > 0) {
                    val overallPercentage =
                        (progress.processedItems * 100 / progress.totalItems).coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = {
                            (progress.processedItems.toFloat() / progress.totalItems)
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$overallPercentage% overall · ${progress.processedItems} of " +
                            "${progress.totalItems} files processed",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (progress.phase != SyncPhase.DISCOVERING && progress.currentItemTotal > 0) {
                    val currentPercentage =
                        (progress.currentItemBytes * 100 / progress.currentItemTotal).coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = {
                            (progress.currentItemBytes.toFloat() / progress.currentItemTotal)
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$currentPercentage% ${if (progress.phase == SyncPhase.VERIFYING) "verified" else "of current file"} · " +
                            "${formatBytes(progress.currentItemBytes)} / ${formatBytes(progress.currentItemTotal)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (progress.phase != SyncPhase.DISCOVERING && progress.advertisedBytes > 0) {
                    Text(
                        "USB-visible source: ${progress.discoveredItems} files · " +
                            formatBytes(progress.advertisedBytes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Copied ${formatBytes(progress.bytesTransferred)} · " +
                        "${progress.transferredItems} new · ${progress.skippedItems} skipped · " +
                        "${progress.failedItems} failed",
                )
            }
            mtpScanSummary?.let { scan ->
                SourceExportReadiness(
                    sourcePlatform = sourcePlatform,
                    scan = scan,
                    recoveredCategories = recoveredCategories,
                    syncing = syncing,
                    iosBackupImporting = iosBackupImporting,
                    iosBackupImportProgress = iosBackupImportProgress,
                    iosBackupImportResult = iosBackupImportResult,
                    onImportIosBackup = onImportIosBackup,
                    onRescan = onSync,
                )
            }
            if (sourcePlatform == SourcePlatform.IOS && mtpScanSummary == null) {
                IosBackupRequirementPanel(
                    smsRecovered = ConsentCategory.SMS_EXPORTS in recoveredCategories,
                    importing = iosBackupImporting,
                    progress = iosBackupImportProgress,
                    result = iosBackupImportResult,
                    onImport = onImportIosBackup,
                )
            }
            Button(
                onClick = onCompleteBackup,
                enabled = !syncing && !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        syncing -> "Recovering and verifying..."
                        backingUp -> "Packaging and sending..."
                        else -> "Recover, verify & package to selected destination"
                    },
                )
            }
            Text("Backup destination: $targetName", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onChooseTarget,
                enabled = !syncing && !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose local, SD, USB, OneDrive, Google Drive, or other destination")
            }
            OutlinedButton(
                onClick = onSync,
                enabled = !syncing && !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan and recover only")
            }
            Button(
                onClick = onCollectSourceSms,
                enabled = !syncing && !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Collect all SMS from USB-connected source")
            }
            Text(
                "This reads SMS only when the connected source exposes an SMS export or companion-protocol result over USB. " +
                    "MTP cannot open the source phone's private SMS database.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onCopyAllUsbVisibleFiles,
                enabled = !syncing && !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy all USB-visible files from external device")
            }
            OwnerApprovedArchivePanel(
                deviceType = recoveryDeviceType,
                importing = ownerArchiveImporting,
                progress = ownerArchiveImportProgress,
                result = ownerArchiveImportResult,
                onImport = onImportOwnerArchive,
            )
            if (currentRecoveryIssues.isNotEmpty()) {
                RecoveryRemediationPanel(currentRecoveryIssues, syncing, onSync)
            }
            HorizontalDivider()
            OutlinedButton(onClick = onViewLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("View recovered artifacts")
            }
            Text(
                "Data may be recovered from objects advertised by the active tethered device over MTP/PTP or from an owner-approved " +
                    "backup/archive/export explicitly imported for this selected source. Every path is inventoried and integrity checked.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when (sourcePlatform) {
                    SourcePlatform.ANDROID ->
                        "Android MTP cannot read app-private SMS, call, or mail databases. Prepare USB-visible exports " +
                            "on the source phone first."
                    SourcePlatform.IOS ->
                        "iPhone PTP exposes media, not its private SMS, call, chat, mail, or notification databases. " +
                            "Use supported source-app export files for those records."
                    SourcePlatform.WINDOWS_PHONE ->
                        "Windows Phone MTP exposes shared media files, not its private SMS, call, or email stores. " +
                            "This app requests every object that the source publishes, but USB cannot force private stores public."
                    else ->
                        "USB can recover files exposed by the source. Private app databases require supported exports."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Recent recovery records", style = MaterialTheme.typography.titleMedium)
            if (audit.isEmpty()) Text("No recovered artifacts yet.")
            audit.forEach { entry -> Text("${entry.category.label()}: ${entry.sourceItem}") }
            OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth()) { Text("Revoke trust") }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SourceExportReadiness(
    sourcePlatform: SourcePlatform,
    scan: MtpScanSummary,
    recoveredCategories: Set<ConsentCategory>,
    syncing: Boolean,
    iosBackupImporting: Boolean,
    iosBackupImportProgress: IosBackupImportProgress?,
    iosBackupImportResult: IosBackupImportResult?,
    onImportIosBackup: () -> Unit,
    onRescan: () -> Unit,
) {
    val advertisedCategories = scan.visibleCategories
    val found = SourceExportRequirements.categories.filter { it in recoveredCategories }
    val missing = SourceExportRequirements.missingFrom(recoveredCategories)
    val ownerExportWorkflow = OwnerExportCoordinator.trigger(sourcePlatform, recoveredCategories)
    var showOwnerActions by remember(recoveredCategories) { mutableStateOf(false) }

    HorizontalDivider()
    Text("USB export readiness", style = MaterialTheme.typography.titleMedium)
    Text("${scan.scannedItems} USB-visible files scanned.")
    Text(
        "Downloads visible: ${if (scan.downloadDirectoryVisible) "Yes" else "No"} · " +
            "Recovery export folders visible: ${if (scan.phoneSyncDirectoryVisible) "Yes" else "No"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Existing backup/export candidates: ${scan.backupCandidatesVisible} · " +
            formatBytes(scan.backupCandidateBytes),
        style = MaterialTheme.typography.bodySmall,
    )
    if (scan.enumerationFailures > 0) {
        Text(
            "Enumeration incomplete: ${scan.enumerationFailures} folder/storage error(s). " +
                "This run is partial; keep the source unlocked and retry.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        scan.enumerationErrors.take(5).forEach { detail ->
            Text(detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (advertisedCategories.isNotEmpty()) {
        Text(
            "Advertised category candidates: ${advertisedCategories.joinToString { sourceExportLabel(it) }}. " +
                "A category counts as recovered only after copy and integrity verification.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        "Backup trigger: standard MTP/PTP can discover and read existing backups but cannot start private OS/app backups. " +
            "Use the owner-guided request below, then rescan; a future companion protocol may trigger only after visible source-device consent.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        when {
            scan.partialObject64Supported == true ->
                "USB request mode: 64-bit chunked, standard chunked, then full-file retry."
            scan.partialObjectSupported == true ->
                "USB request mode: standard chunked transfer with full-file retry."
            scan.fullObjectSupported == true ->
                "USB request mode: full-file transfer."
            else ->
                "USB request mode: best-effort full-file compatibility transfer."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (sourcePlatform == SourcePlatform.IOS) {
        HorizontalDivider()
        Text("iPhone photo coverage", style = MaterialTheme.typography.titleMedium)
        Text(
            "PTP exposed ${scan.mediaItemsVisible} media files this scan: " +
                "${scan.mediaItemsTransferred} new, " +
                "${scan.mediaItemsAlreadyCollected} already recovered, " +
                "${scan.mediaItemsNotAuthorized} not authorized, " +
                "${scan.mediaItemsFailed} failed.",
        )
        if (scan.mediaItemsFailed > 0) {
            Text(
                "Keep the iPhone unlocked and connected, then retry. The app tries both iOS PTP chunked modes " +
                    "before a full-file request.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Photos stored only in iCloud are not included in the PTP count. On the iPhone, choose " +
                "iCloud Photos > Download and Keep Originals, wait for originals to finish downloading, " +
                "set Photos > Transfer to Mac or PC to Keep Originals, then scan again.",
            style = MaterialTheme.typography.bodySmall,
        )
        IosBackupRequirementPanel(
            smsRecovered = ConsentCategory.SMS_EXPORTS in recoveredCategories,
            importing = iosBackupImporting,
            progress = iosBackupImportProgress,
            result = iosBackupImportResult,
            onImport = onImportIosBackup,
        )
    }
    if (found.isNotEmpty()) {
        Text("Found: ${found.joinToString { sourceExportLabel(it) }}")
    }
    if (missing.isNotEmpty()) {
        Text(
            "Not exposed by source: ${missing.joinToString { sourceExportLabel(it) }}",
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            when (sourcePlatform) {
                SourcePlatform.ANDROID ->
                    "On the external source phone, use each source app's supported export. Save it in shared USB-visible storage " +
                        "for another USB scan, or import the complete owner-approved export/archive above."
                SourcePlatform.IOS ->
                    "iPhone PTP exposes downloaded photos and videos, not its private app databases. Import a complete Apple local backup " +
                        "for Messages/SMS and use owner-approved app/provider exports or complete archives for the remaining categories."
                SourcePlatform.WINDOWS_PHONE ->
                    "The app already requested every MTP-visible Lumia object. Windows Phone provides no USB consent " +
                        "request for private SMS, call history, or email. Restore SMS through the phone's Microsoft account " +
                        "backup when available, and export email from its mail provider; call history has no standard USB export."
                else ->
                    "Create supported owner exports on the source, expose them over MTP/PTP or import the complete owner-approved archive above, then verify coverage again."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { showOwnerActions = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Request backup / export on source")
        }
        if (showOwnerActions) {
            ownerExportWorkflow.actions.forEach { action ->
                Text(action.service, style = MaterialTheme.typography.titleSmall)
                Text(action.ownerSteps, style = MaterialTheme.typography.bodySmall)
                Text("Expected: ${action.expectedArtifacts}", style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = onRescan,
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (syncing) "Scanning external device..." else "Owner exports complete - rescan")
            }
        }
    } else {
        Text("All required message, communication, contact, calendar, voicemail, notification, and credential exports are represented in this recovery set.")
    }
}

@androidx.compose.runtime.Composable
private fun OwnerApprovedArchivePanel(
    deviceType: RecoveryDeviceType,
    importing: Boolean,
    progress: OwnerArchiveImportProgress?,
    result: OwnerArchiveImportResult?,
    onImport: () -> Unit,
) {
    HorizontalDivider()
    Text("Owner-approved source data", style = MaterialTheme.typography.titleMedium)
    Text(
        "Import a complete ${deviceType.label} backup/archive ZIP or an individual owner-created export. " +
            "The original is preserved first; every ZIP entry is then inventoried as recovered, already recovered, directory metadata, or remediation required.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = onImport,
        enabled = !importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (importing) "Importing owner-approved data..." else "Import owner-approved backup / archive / export")
    }
    if (importing) {
        val current = progress
        val fraction = current?.takeIf { it.totalItems > 0 }
            ?.let { it.completedItems.toFloat() / it.totalItems }
            ?: 0f
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        current?.let {
            Text(ownerArchiveStageLabel(it.stage), style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append(it.currentItem)
                    if (it.totalItems > 0) append(" · ${it.completedItems}/${it.totalItems}")
                    if (it.bytesProcessed > 0) append(" · ${formatBytes(it.bytesProcessed)}")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    result?.let {
        Text(
            "Original preserved: ${if (it.sourcePreserved) "Yes" else "No"} · " +
                "${it.recoveredItems} files recovered · ${it.declaredItems} total entries · " +
                "${it.alreadyRecoveredItems} already verified · ${it.directoryItems} directories · " +
                "${it.failedItems} need remediation",
            style = MaterialTheme.typography.bodySmall,
        )
        if (it.categoriesRecovered.isNotEmpty()) {
            Text(
                "Recovered categories: ${it.categoriesRecovered.joinToString { category -> category.label() }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        it.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) }
    }
    Text(
        "Credential/password artifacts are copied intact and inventoried as opaque; they are not decrypted or omitted.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "For iPhone data, you may first use a trusted desktop extractor such as iMazing, OpenExtract, Dr.Fone, or iBackup Extractor. " +
            "Export its owner-authorized results to one folder, then import the folder as a ZIP. Encrypted backups must be unencrypted " +
            "or owner-decrypted on the trusted computer before extraction.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@androidx.compose.runtime.Composable
private fun IosBackupRequirementPanel(
    smsRecovered: Boolean,
    importing: Boolean,
    progress: IosBackupImportProgress?,
    result: IosBackupImportResult?,
    onImport: () -> Unit,
) {
    var showBackupSteps by remember { mutableStateOf(false) }
    HorizontalDivider()
    Text("Required iPhone Messages / SMS", style = MaterialTheme.typography.titleMedium)
    Text(
        if (smsRecovered) {
            "Requirement satisfied: an owner-approved iPhone message artifact is preserved and available to the Data Reader."
        } else {
            "Requirement not satisfied. iPhone PTP cannot expose Messages or sms.db. Import a complete owner-approved local Apple backup to retrieve them."
        },
        color = if (smsRecovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    OutlinedButton(
        onClick = { showBackupSteps = !showBackupSteps },
        enabled = !importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (showBackupSteps) "Hide iPhone backup steps" else "Start iPhone backup workflow")
    }
    if (showBackupSteps) {
        Text(
                "On a trusted Windows computer, open Apple Devices, connect and unlock the iPhone, approve Trust This Computer, " +
                "and choose Back Up Now. On macOS, use Finder and Back Up Now. Provide an unencrypted backup, or decrypt a copy " +
                "on the trusted computer before importing. Copy the complete backup directory, ZIP it without changing its hashed files, " +
                "then return here and import that ZIP. Android cannot initiate or decrypt this private iPhone backup over PTP.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(
        onClick = onImport,
        enabled = !importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (importing) "Importing Apple backup..." else "Import owner-approved Apple backup ZIP")
    }
    if (importing) {
        val current = progress
        val fraction = current?.takeIf { it.totalItems > 0 }
            ?.let { it.completedItems.toFloat() / it.totalItems }
            ?: 0f
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        current?.let {
            Text(iosBackupStageLabel(it.stage), style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append(it.currentItem)
                    if (it.totalItems > 0) append(" · ${it.completedItems}/${it.totalItems}")
                    if (it.bytesProcessed > 0) append(" · ${formatBytes(it.bytesProcessed)}")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    result?.let {
        Text(
            "Backup preserved: ${if (it.backupPreserved) "Yes" else "No"} · " +
                "${it.presentFiles}/${it.declaredFiles} declared files present · " +
                "${it.messagesExported} messages · ${it.attachmentsExported} attachments",
            style = MaterialTheme.typography.bodySmall,
        )
        it.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) }
    }
    Text(
        "Create the local backup with Apple Devices on Windows or Finder on macOS, ZIP the complete backup directory, then select it here. " +
            "Encrypted backups are preserved intact but must be decrypted by the owner on a trusted computer before message parsing.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@androidx.compose.runtime.Composable
private fun RecoveryRemediationPanel(
    issues: List<RecoveryIssue>,
    syncing: Boolean,
    onRetry: () -> Unit,
) {
    HorizontalDivider()
    Text("Recovery actions", style = MaterialTheme.typography.titleMedium)
    Text(
        "${issues.size} item(s) were not lost or silently discarded. Resolve the issue below, then retry.",
        style = MaterialTheme.typography.bodySmall,
    )
    issues.take(MAX_VISIBLE_RECOVERY_ISSUES).forEach { issue ->
        Text(issue.sourceItem.substringAfterLast('/').ifBlank { issue.sourceItem })
        Text(
            "${issue.reason.name.lowercase().replace('_', ' ')}: ${issue.remediation}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (issues.size > MAX_VISIBLE_RECOVERY_ISSUES) {
        Text(
            "${issues.size - MAX_VISIBLE_RECOVERY_ISSUES} more issue(s) remain in the recovery audit.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (issues.any(RecoveryIssue::retryable)) {
        Button(onClick = onRetry, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
            Text(if (syncing) "Retrying recovery..." else "Retry recoverable items")
        }
    }
}

private fun ConsentCategory.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun AuditEntry.displayName(): String {
    return sourceItem.substringAfterLast('/').ifBlank { "Recovered artifact" }
}

private fun AuditEntry.storageLocation(): String {
    return destination ?: "Unavailable"
}

private fun isTextLike(entry: AuditEntry): Boolean {
    if (
        entry.category == ConsentCategory.PASSWORD_EXPORTS ||
        entry.category == ConsentCategory.VOICEMAIL_EXPORTS
    ) return false
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf(
        "txt", "text", "csv", "json", "xml", "html", "htm", "md", "log",
        "eml", "mbox", "vcf", "vcard", "ics", "ical", "yaml", "yml",
        "srt", "sub", "xml", "db-wal", "sql",
    ) || entry.category in setOf(
        ConsentCategory.SMS_EXPORTS,
        ConsentCategory.CHAT_EXPORTS,
        ConsentCategory.EMAIL_EXPORTS,
        ConsentCategory.NOTIFICATION_EXPORTS,
    )
}

private fun readPreviewText(context: Context, uri: Uri): String? {
    return runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val buffer = ByteArray(PREVIEW_MAX_BYTES)
            val count = stream.read(buffer)
            if (count <= 0) "(Empty recovered artifact)"
            else String(buffer, 0, count, Charsets.UTF_8)
        }
    }.getOrNull()
}

private fun collectedItemIntent(destination: Uri, mimeType: String): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        data = destination
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, destination)
        clipData = ClipData.newRawUri("Recovered artifact", destination)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

private fun formatTime(epochMillis: Long): String = DateFormat.getDateTimeInstance().format(Date(epochMillis))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_024 * 1_024 * 1_024 -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
    else -> "%.1f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
}

private fun identityReadStageLabel(stage: IdentityReadStage): String = when (stage) {
    IdentityReadStage.CHECKING_PERMISSION -> "Checking Android USB authorization"
    IdentityReadStage.READING_USB_SERIAL -> "Reading the USB device serial"
    IdentityReadStage.READING_USB_DESCRIPTOR -> "Reading the source USB descriptor"
    IdentityReadStage.CREATING_IDENTITY -> "Creating a stable source identity"
    IdentityReadStage.COMPLETE -> "Source identity ready"
}

private fun iosBackupStageLabel(stage: IosBackupImportStage): String = when (stage) {
    IosBackupImportStage.PRESERVING_BACKUP -> "Preserving complete Apple backup"
    IosBackupImportStage.READING_MANIFEST -> "Reading Apple backup manifest"
    IosBackupImportStage.VERIFYING_BACKUP_FILES -> "Accounting for every declared backup file"
    IosBackupImportStage.EXTRACTING_MESSAGES -> "Extracting raw Messages database"
    IosBackupImportStage.EXPORTING_MESSAGES -> "Exporting searchable SMS and iMessage rows"
    IosBackupImportStage.EXPORTING_ATTACHMENTS -> "Collecting message attachments"
    IosBackupImportStage.COMPLETE -> "Apple backup import complete"
}

private fun ownerArchiveStageLabel(stage: OwnerArchiveImportStage): String = when (stage) {
    OwnerArchiveImportStage.PRESERVING_SOURCE -> "Preserving complete owner-approved source"
    OwnerArchiveImportStage.COUNTING_ITEMS -> "Counting every archive entry"
    OwnerArchiveImportStage.RECOVERING_ITEMS -> "Recovering and verifying archive entries"
    OwnerArchiveImportStage.WRITING_INVENTORY -> "Writing complete entry inventory"
    OwnerArchiveImportStage.COMPLETE -> "Owner-approved source import complete"
}

private fun buildOwnerArchiveStatus(progress: OwnerArchiveImportProgress): String {
    val count = if (progress.totalItems > 0) {
        " ${progress.completedItems}/${progress.totalItems}."
    } else {
        ""
    }
    val bytes = if (progress.bytesProcessed > 0) {
        " ${formatBytes(progress.bytesProcessed)} published."
    } else {
        ""
    }
    return "OWNER EXPORT: ${ownerArchiveStageLabel(progress.stage)}: ${progress.currentItem}.$count$bytes"
}

private fun buildOwnerArchiveCompletionStatus(result: OwnerArchiveImportResult): String {
    return "OWNER EXPORT: preserved=${result.sourcePreserved}, " +
        "recovered files=${result.recoveredItems}, total entries=${result.declaredItems}, " +
        "already verified=${result.alreadyRecoveredItems}, " +
        "directories=${result.directoryItems}, remediation=${result.failedItems}."
}

private fun buildIosBackupStatus(progress: IosBackupImportProgress): String {
    val count = if (progress.totalItems > 0) {
        " ${progress.completedItems}/${progress.totalItems}."
    } else {
        ""
    }
    val bytes = if (progress.bytesProcessed > 0) {
        " ${formatBytes(progress.bytesProcessed)} processed."
    } else {
        ""
    }
    return "IPHONE BACKUP: ${iosBackupStageLabel(progress.stage)}: ${progress.currentItem}.$count$bytes"
}

private fun buildIosBackupCompletionStatus(result: IosBackupImportResult): String {
    val requirement = if (result.smsRequirementSatisfied) {
        "Messages/SMS requirement satisfied with ${result.messagesExported} searchable rows."
    } else {
        "Messages/SMS requirement remains unresolved. Follow the listed recovery action and import again."
    }
    return "IPHONE BACKUP: preserved=${result.backupPreserved}, " +
        "files=${result.presentFiles}/${result.declaredFiles}, " +
        "attachments=${result.attachmentsExported}. $requirement"
}

private fun buildLiveStatus(progress: SyncProgress): String {
    if (progress.phase == SyncPhase.DISCOVERING) {
        return "LIVE: ${progress.discoveredItems} USB-visible files found, " +
            "${formatBytes(progress.advertisedBytes)} advertised."
    }
    val processed = if (progress.totalItems > 0) {
        " ${progress.processedItems}/${progress.totalItems} files processed."
    } else {
        ""
    }
    val current = if (progress.currentItemTotal > 0) {
        " Current file ${formatBytes(progress.currentItemBytes)}/${formatBytes(progress.currentItemTotal)}."
    } else {
        ""
    }
    val method = progress.readMethod?.let {
        " ${it.label()} attempt ${progress.readAttempt}/${progress.readAttemptsForMethod}."
    }.orEmpty()
    return "LIVE:${processed} ${progress.transferredItems} transferred, " +
        "${progress.skippedItems} skipped, ${progress.failedItems} failed.$current$method"
}

private fun com.jerrywolff.phonesyncusbc.sync.MtpReadMode.label(): String = when (this) {
    com.jerrywolff.phonesyncusbc.sync.MtpReadMode.PARTIAL_64 -> "64-bit chunked"
    com.jerrywolff.phonesyncusbc.sync.MtpReadMode.PARTIAL_STANDARD -> "standard chunked"
    com.jerrywolff.phonesyncusbc.sync.MtpReadMode.FULL_OBJECT -> "full object"
}

internal fun permitsCloudHandoff(status: com.jerrywolff.phonesyncusbc.data.SyncStatus?): Boolean {
    return status == null || status == com.jerrywolff.phonesyncusbc.data.SyncStatus.COMPLETED
}

private fun buildSyncCompletionStatus(
    result: SyncResult,
    recoveredCategories: Set<ConsentCategory> = emptySet(),
): String {
    val visibleCategories = result.mtpScan?.visibleCategories.orEmpty() + recoveredCategories
    val missingExports = SourceExportRequirements.missingFrom(visibleCategories)
    val displayedStatus = if (missingExports.isEmpty()) result.status.toString() else "REQUIRED DATA PENDING"
    val base = "$displayedStatus: ${result.transferredItems} transferred, " +
        "${result.skippedItems} already verified, ${result.failedItems} failed."
    val inventory = result.recoveryInventory
    val inventoryStatus = when {
        inventory?.uri != null -> {
            val passwordStatus = if (inventory.passwordArtifactCount > 0) {
                " ${inventory.recoveredPasswordArtifactCount}/${inventory.passwordArtifactCount} " +
                    "password artifacts accounted for as opaque files."
            } else {
                " No USB-visible password artifacts were found."
            }
            val passkeyStatus = if (inventory.passkeyRelatedArtifactCount > 0) {
                " ${inventory.recoveredPasskeyRelatedArtifactCount}/${inventory.passkeyRelatedArtifactCount} " +
                    "passkey-related backup artifacts preserved. Private passkey keys were not extracted; " +
                    "restore them through their credential provider."
            } else {
                " No provider-supported USB-visible passkey backup was found. Hardware-backed passkey keys " +
                    "remain in the credential provider and require provider sync or account recovery."
            }
            " Inventory saved as ${inventory.displayName}.$passwordStatus$passkeyStatus"
        }
        inventory?.error != null -> " Inventory generation failed: ${inventory.error}."
        else -> ""
    }
    return if (missingExports.isEmpty()) {
        base + inventoryStatus
    } else {
        "$base Source did not expose: ${missingExports.joinToString { sourceExportLabel(it) }}.$inventoryStatus"
    }
}

private fun sourceExportLabel(category: ConsentCategory): String = when (category) {
    ConsentCategory.SMS_EXPORTS -> "SMS/MMS"
    ConsentCategory.CHAT_EXPORTS -> "app chats and meeting transcripts"
    ConsentCategory.CALL_LOGS -> "call logs"
    ConsentCategory.EMAIL_EXPORTS -> "email exports"
    else -> category.label()
}

@androidx.compose.runtime.Composable
private fun ImportedDataView(
    entries: List<AuditEntry>,
    onBack: () -> Unit,
    onBrowseParsed: () -> Unit,
    onOpen: (AuditEntry) -> Unit,
    onExport: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<ConsentCategory?>(null) }
    val categories = entries.map { it.category }.distinct()
    val visibleEntries = selectedCategory?.let { category ->
        entries.filter { it.category == category }
    } ?: entries
    val groupedEntries = visibleEntries.groupBy { it.category }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recovered artifacts", style = MaterialTheme.typography.headlineSmall)
            Text("${entries.size} verified recovery results available in Android storage.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(onClick = onExport, enabled = entries.isNotEmpty()) { Text("Export all") }
            }
            Button(
                onClick = onBrowseParsed,
                enabled = entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Browse images, messages, SMS, and voicemail")
            }
            Text("Recovered source folders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Items open from their recorded Android storage location. Choose a type to browse its recovery folder.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterButton(
                    label = "All",
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                )
                categories.forEach { category ->
                    FilterButton(
                        label = category.label(),
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
            }
            HorizontalDivider()
            if (visibleEntries.isEmpty()) {
                Text("No verified recovery results yet.")
            } else {
                groupedEntries.forEach { (category, categoryEntries) ->
                    Text(category.label(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        categoryDescription(category),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    categoryEntries.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.displayName(), style = MaterialTheme.typography.titleMedium)
                            Text(entry.sourceItem, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${formatBytes(entry.bytesTransferred)} · ${formatTime(entry.transferredAtEpochMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (entry.sourceSize > 0) {
                                Text("Source size: ${formatBytes(entry.sourceSize)}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.sourceModifiedAtEpochMillis > 0) {
                                Text(
                                    "Source modified: ${formatTime(entry.sourceModifiedAtEpochMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            entry.contentSha256?.let { sha256 ->
                                Text("SHA-256: $sha256", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = { onOpen(entry) },
                                enabled = entry.category != ConsentCategory.PASSWORD_EXPORTS,
                            ) {
                                Text(
                                    if (entry.category == ConsentCategory.PASSWORD_EXPORTS) {
                                        "Sensitive artifact opening disabled"
                                    } else {
                                        "Open recovered artifact"
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@androidx.compose.runtime.Composable
private fun SourceConnectionPanel(
    sources: List<AttachedSource>,
    source: AttachedSource?,
    onSourceSelected: (AttachedSource) -> Unit,
    recoveryDeviceType: RecoveryDeviceType,
    onRecoveryDeviceTypeSelected: (RecoveryDeviceType) -> Unit,
    onRefresh: () -> Unit,
    onNetworkIphone: () -> Unit,
    onNetworkDevice: () -> Unit,
    onRequestUsbPermission: (AttachedSource) -> Unit,
    onContinue: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect an owned recovery source", style = MaterialTheme.typography.titleMedium)
            if (sources.isEmpty()) {
                Text("Connect an Android phone, iPhone, or MTP/PTP device with a data-capable USB cable.")
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Find USB source")
                }
                OutlinedButton(onClick = onNetworkIphone, modifier = Modifier.fillMaxWidth()) {
                    Text("Recover from iPhone over Wi-Fi")
                }
                OutlinedButton(onClick = onNetworkDevice, modifier = Modifier.fillMaxWidth()) {
                    Text("Recover files from another device over Wi-Fi/Bluetooth")
                }
            } else {
                if (sources.size > 1 || source == null) {
                    Text("Select the external device to back up", style = MaterialTheme.typography.labelLarge)
                    sources.forEach { candidate ->
                        val selected = source?.device?.deviceId == candidate.device.deviceId
                        if (selected) {
                            Button(
                                onClick = { onSourceSelected(candidate) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("${candidate.detected.displayName} · selected")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSourceSelected(candidate) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${candidate.detected.displayName} · ${candidate.detected.platform} · " +
                                        "USB ${candidate.device.deviceId}",
                                )
                            }
                        }
                    }
                }
                if (source == null) {
                    Text("Choose one connected source before requesting USB access.")
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("Refresh connected sources")
                    }
                    return@Column
                }
                Text(source.detected.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${source.detected.platform} · ${source.detected.family} · ${source.detected.physicalConnection}")
                Text("Recovery device type", style = MaterialTheme.typography.labelLarge)
                RecoveryDeviceType.entries.chunked(2).forEach { types ->
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        types.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = recoveryDeviceType == type,
                                onClick = { onRecoveryDeviceTypeSelected(type) },
                                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                            ) {
                                Text(type.label)
                            }
                        }
                    }
                }
                Text(
                    RecoveryProfiles.forDevice(recoveryDeviceType).passwordTarget +
                        " are copied intact without decryption.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Purpose: ${RecoveryProfiles.forDevice(recoveryDeviceType).purposes.joinToString { it.label }}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(LOGICAL_ACQUISITION_LIMIT, style = MaterialTheme.typography.bodySmall)
                if (source.permissionGranted) {
                    Text("USB data access authorized")
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue / retry source handshake")
                    }
                    OutlinedButton(
                        onClick = { onRequestUsbPermission(source) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Recheck USB access")
                    }
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text("Refresh source")
                    }
                } else {
                    Text("Android must approve access to this USB device.")
                    Button(
                        onClick = { onRequestUsbPermission(source) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Authorize USB data access") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun BackupActivityPanel(activity: BackupActivityUi) {
    val progress = if (activity.totalItems > 0) {
        (activity.completedItems.toFloat() / activity.totalItems).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup and export activity", style = MaterialTheme.typography.titleMedium)
            if (activity.title != "Backup and export activity") Text(activity.title)
            if (activity.totalItems > 0 || activity.running) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${activity.completedItems} of ${activity.totalItems} items",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            activity.currentItem?.let { currentItem ->
                Text("Current: $currentItem", style = MaterialTheme.typography.bodySmall)
            }
            if (activity.running && activity.currentItemTotal > 0) {
                LinearProgressIndicator(
                    progress = {
                        (activity.currentItemBytes.toFloat() / activity.currentItemTotal)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Current file: ${formatBytes(activity.currentItemBytes)} / " +
                        formatBytes(activity.currentItemTotal),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (activity.running && activity.currentItem != null && activity.currentItemTotal <= 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Current file size is unavailable.", style = MaterialTheme.typography.bodySmall)
            }
            if (activity.bytesProcessed > 0) {
                Text("Processed: ${formatBytes(activity.bytesProcessed)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                activity.status,
                color = if (activity.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (activity.running && activity.totalItems <= 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun BackupPanel(
    title: String = "Preserve recovered data",
    description: String = "Preserve on this phone, an SD card, or an attached USB drive.",
    onSelectBackup: () -> Unit,
    onChooseTarget: () -> Unit,
    backingUp: Boolean,
    selectedItemCount: Int,
    selectedBytes: Long,
    passwordVaultItemCount: Int,
    targetType: BackupTargetType,
    targetName: String?,
    backupStatus: String,
    onExecuteBackup: () -> Unit,
) {
    val primaryActionLabel = targetType.primaryActionLabel()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("1. Data", style = MaterialTheme.typography.titleSmall)
            Text("$selectedItemCount items selected (${formatBytes(selectedBytes)})")
            if (passwordVaultItemCount > 0) {
                Text(
                    "$passwordVaultItemCount sensitive password artifact(s) selected. Protect the destination account. " +
                        "The app copies them intact and does not preview, parse, or decrypt credentials.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onSelectBackup,
                enabled = !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change selected data") }
            HorizontalDivider()
            Text("2. Destination", style = MaterialTheme.typography.titleSmall)
            Text(targetName ?: "This phone / Downloads")
            Text(
                when (targetType) {
                    BackupTargetType.PHONE_DOWNLOADS ->
                        "The backup will be written directly to this phone's Downloads folder."
                    BackupTargetType.DOCUMENT_TREE ->
                        "The backup will be written directly to the selected folder."
                    BackupTargetType.ONEDRIVE ->
                        "The app builds one verified package, opens OneDrive, and waits for you to choose its folder and confirm Upload."
                    BackupTargetType.GOOGLE_DRIVE ->
                        "The app builds one verified package, opens Google Drive, and waits for you to choose its folder and confirm Upload."
                    BackupTargetType.OTHER_APP ->
                        "The app builds one verified package and hands it to the selected destination app."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onChooseTarget,
                enabled = !backingUp,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose destination") }
            HorizontalDivider()
            Text("3. Preserve", style = MaterialTheme.typography.titleSmall)
            Text(backupStatus, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onExecuteBackup,
                enabled = !backingUp && selectedItemCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (backingUp) "Preparing preservation..." else primaryActionLabel)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TargetMediaWizard(
    onBack: () -> Unit,
    onUsePhoneStorage: () -> Unit,
    onChooseFolder: (String) -> Unit,
    onUseOneDrive: () -> Unit,
    onUseGoogleDrive: () -> Unit,
    onUseOtherApp: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose destination", style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = onUsePhoneStorage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use this phone's Downloads") }
            OutlinedButton(
                onClick = { onChooseFolder("local, SD, USB, or document-provider folder") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose folder from Android picker") }
            OutlinedButton(onClick = onUseOneDrive, modifier = Modifier.fillMaxWidth()) {
                Text("OneDrive")
            }
            OutlinedButton(onClick = onUseGoogleDrive, modifier = Modifier.fillMaxWidth()) {
                Text("Google Drive")
            }
            OutlinedButton(onClick = onUseOtherApp, modifier = Modifier.fillMaxWidth()) {
                Text("Another installed app")
            }
            Text(
                "Folder targets write directly. App targets prepare one package, open the selected app, then let you choose its folder and confirm Upload.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

private const val SOURCE_SYNC_SECTION_INDEX = 3
private const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_STORAGE_ROOT_ID = "primary"

private fun hasPersistedWriteAccess(context: Context, uri: Uri): Boolean {
    if (uri.scheme == "file") return uri.path?.let { path -> java.io.File(path) }?.canWrite() == true
    val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        ?: return false
    if (!permission.isReadPermission || !permission.isWritePermission) return false
    val writable = DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
    if (!writable) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
    return writable
}

@androidx.compose.runtime.Composable
private fun BackupSelectionView(
    entries: List<AuditEntry>,
    selectedIds: Set<Long>,
    onSelectionChanged: (Set<Long>) -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select recovery artifacts", style = MaterialTheme.typography.headlineSmall)
            Text("Choose verified recovery results to preserve on target media.")
            Text("${selectedIds.size} of ${entries.size} items selected")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelectionChanged(entries.mapTo(linkedSetOf()) { it.id }) }) {
                    Text("Select all")
                }
                OutlinedButton(onClick = { onSelectionChanged(emptySet()) }) {
                    Text("Clear")
                }
            }
            if (entries.isEmpty()) {
                Text("No recovered data is available to preserve.")
            } else {
                entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = entry.id in selectedIds,
                            onCheckedChange = { checked ->
                                onSelectionChanged(
                                    if (checked) selectedIds + entry.id else selectedIds - entry.id,
                                )
                            },
                        )
                        Column {
                            Text(entry.displayName())
                            Text(
                                "${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onContinue,
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue to target media") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

private fun categoryDescription(category: ConsentCategory): String = when (category) {
    ConsentCategory.PHOTOS_AND_VIDEOS -> "Photos and videos recovered from the external source."
    ConsentCategory.DOCUMENTS -> "Documents recovered from the external source."
    ConsentCategory.APPLICATION_DATA ->
        "Application data files that the external source exposes through normal USB access."
    ConsentCategory.CONFIGURATION -> "Configuration and settings files exposed by the external source."
    ConsentCategory.LOGS -> "Logs, diagnostics, and crash reports exposed by the external source."
    ConsentCategory.SYSTEM_INFORMATION -> "System-information reports exposed by the external source."
    ConsentCategory.CONTACTS -> "vCard contacts exported by the external source."
    ConsentCategory.CALL_LOGS -> "Call history exported by the external source."
    ConsentCategory.CALENDAR -> "Calendar events exported by the external source."
    ConsentCategory.SELECTED_FOLDERS -> "Legacy selected-folder data retained from an earlier app version."
    ConsentCategory.CLOUD_ACCOUNTS -> "Legacy cloud-provider data retained from an earlier app version."
    ConsentCategory.SMS_EXPORTS -> "User-created SMS export files, not private SMS databases."
    ConsentCategory.CHAT_EXPORTS -> "User-created chat export files, not private chat databases."
    ConsentCategory.EMAIL_EXPORTS -> "User-created email export files, not private mail databases."
    ConsentCategory.NOTIFICATION_EXPORTS -> "Notification records exported by the external source."
    ConsentCategory.PASSWORD_EXPORTS ->
        "Sensitive password vaults, browser credential stores, and credential backups copied without decryption."
    ConsentCategory.VOICEMAIL_EXPORTS ->
        "Voicemail audio or visual-voicemail files explicitly exported from the source phone or carrier app."
}

@androidx.compose.runtime.Composable
private fun TextPreview(
    entry: AuditEntry,
    text: String,
    onBack: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.displayName(), style = MaterialTheme.typography.headlineSmall)
            Text("${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}")
            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back to recovered artifacts") }
            }
            HorizontalDivider()
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun ImagePreview(
    entry: AuditEntry,
    bitmap: Bitmap,
    onBack: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.displayName(), style = MaterialTheme.typography.headlineSmall)
            Text("${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}")
            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBack) { Text("Back to recovered artifacts") }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.displayName(),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun VideoPreview(
    entry: AuditEntry,
    uri: Uri,
    context: Context,
    onBack: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.displayName(), style = MaterialTheme.typography.headlineSmall)
            Text("${entry.category.label()} · ${formatBytes(entry.bytesTransferred)}")
            Text("Stored at: ${entry.storageLocation()}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBack) { Text("Back to recovered artifacts") }
            AndroidView(
                factory = {
                    VideoView(context).apply {
                        setMediaController(MediaController(context))
                        setVideoURI(uri)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        }
    }
}

private fun readPreviewBitmap(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) null else BitmapFactory.decodeStream(input)
        }
    }.getOrNull()
}

private fun isImageLike(entry: AuditEntry): Boolean {
    if (entry.category == ConsentCategory.PASSWORD_EXPORTS) return false
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "tif", "tiff", "webp")
}

private fun isVideoLike(entry: AuditEntry): Boolean {
    if (entry.category == ConsentCategory.PASSWORD_EXPORTS) return false
    val extension = entry.sourceItem.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("3g2", "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")
}

@androidx.compose.runtime.Composable
private fun GenericNetworkRecoveryScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    var status by remember { mutableStateOf("No source discovered yet.") }
    var scanning by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8765") }
    val installPlan = remember {
        CompanionInstallPolicy.plan(
            sourcePlatform = SourcePlatform.IOS,
            appPackage = null,
            isHotspotConnected = true,
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recover files from another device", style = MaterialTheme.typography.titleMedium)
            Text("Wi‑Fi discovery uses the PhoneSync companion protocol. Android hotspot networks can block mDNS/Bonjour discovery, so a direct IP fallback is included when the iPhone joins the hotspot.")
            if (installPlan.requiresInstall) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Compatible companion required", style = MaterialTheme.typography.titleSmall)
                        if (installPlan.requiresActualDevice) {
                            Text("Actual iPhone required: the companion must be built for the real device and installed there.")
                        }
                        if (installPlan.requiresSignedCompanion) {
                            Text("Signed companion required: use Xcode on macOS or a signed IPA from a trusted build pipeline.")
                            Text("Local Network permission required: enable the companion app's Local Network access in Settings > Privacy & Security > Local Network.")
                        }
                        Text(installPlan.instructions)
                        Text("Status: ${installPlan.status}")
                    }
                }
            }
            Button(
                onClick = {
                    scanning = true
                    devices = emptyList()
                    selectedDevice = null
                    status = "Scanning the local network..."
                    IosNetworkRecoveryEngine(context) { name, ip, port ->
                        scope.launch {
                            val device = DiscoveredDevice(name, ip, port)
                            if (device !in devices) devices = devices + device
                            status = "Found ${device.name}"
                            scanning = false
                        }
                    }.startDiscovery()
                    scope.launch {
                        kotlinx.coroutines.delay(5000)
                        if (devices.isEmpty() && scanning) {
                            scanning = false
                            status = "No device found over Wi‑Fi. Android hotspots often block local mDNS discovery; use the direct IP fallback below."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (scanning) "Scanning..." else "Find devices over Wi‑Fi")
            }
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("iPhone IP address") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = manualPort,
                onValueChange = { manualPort = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val directIp = manualIp.trim()
                    val directPort = manualPort.toIntOrNull() ?: 8765
                    if (directIp.isEmpty()) {
                        status = "Enter the iPhone IP address shown by the companion app before trying direct connection."
                        return@Button
                    }
                    if (directPort <= 0 || directPort > 65535) {
                        status = "Port must be between 1 and 65535."
                        return@Button
                    }
                    scope.launch {
                        status = "Connecting directly to $directIp:$directPort..."
                        val fetcher = IosDataFetcher(directIp, directPort)
                        val sourceStatus = fetcher.checkStatus()
                        if (sourceStatus == null) {
                            status = "Direct connection failed. Confirm the companion app is running, Local Network is allowed, and the iPhone is on the same hotspot or Wi‑Fi network."
                            return@launch
                        }
                        val files = fetcher.fetchSharedFiles()
                        val rootDir = File(context.getExternalFilesDir(null), "network-transfer")
                        rootDir.mkdirs()
                        var copiedCount = 0
                        files.forEach { file ->
                            val data = fetcher.fetchFileData(file.path) ?: return@forEach
                            val target = File(rootDir, file.path.replace("/", File.separator))
                            target.parentFile?.mkdirs()
                            target.writeBytes(data)
                            copiedCount += 1
                        }
                        status = if (copiedCount > 0) {
                            "Direct connection complete: ${files.size} shared files copied to ${rootDir.absolutePath}."
                        } else {
                            "Direct connection complete: ${files.size} shared files visible, but none were transferable from the source."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect by direct IP")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    status = "Bluetooth settings opened. Pair a source device, then use Wi‑Fi or a compatible transfer profile."
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Find devices over Bluetooth")
            }
            devices.forEach { device ->
                OutlinedButton(
                    onClick = { selectedDevice = device },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${device.name} · ${device.ipAddress}:${device.port}")
                }
            }
            selectedDevice?.let { device ->
                Button(
                    onClick = {
                        scope.launch {
                            status = "Connecting and requesting shared-file inventory..."
                            val fetcher = IosDataFetcher(device.ipAddress, device.port)
                            val sourceStatus = fetcher.checkStatus()
                            if (sourceStatus == null) {
                                status = "Connection failed. Confirm the companion is running and both devices share the network."
                                return@launch
                            }
                            val files = fetcher.fetchSharedFiles()
                            val rootDir = File(context.getExternalFilesDir(null), "network-transfer")
                            rootDir.mkdirs()
                            var copiedCount = 0
                            files.forEach { file ->
                                val data = fetcher.fetchFileData(file.path) ?: return@forEach
                                val target = File(rootDir, file.path.replace("/", File.separator))
                                target.parentFile?.mkdirs()
                                target.writeBytes(data)
                                copiedCount += 1
                            }
                            status = if (copiedCount > 0) {
                                "Trusted connection complete: ${files.size} shared files copied to ${rootDir.absolutePath}."
                            } else {
                                "Trusted connection complete: ${files.size} shared files visible, but none were transferable from the source."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Trust connection and transfer files")
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "Inventory is real network activity. Backup is limited to files the source protocol serves; it cannot bypass passwords, encryption, permissions, or private device databases.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val PREVIEW_MAX_BYTES = 128 * 1024
private const val IDENTITY_READ_TIMEOUT_MILLIS = 8_000L
private const val MAX_VISIBLE_RECOVERY_ISSUES = 12