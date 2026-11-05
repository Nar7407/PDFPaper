package com.pdfpaper.app.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pdfpaper.app.signatures.CertificateSummary
import com.pdfpaper.app.signatures.DigitalCertificateStore
import com.pdfpaper.app.ui.UiTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Certificate setup and options for a digital signature. The certificate and
 * key are imported once, protected by the Android Keystore; each signing
 * operation later asks the viewer for a device-lock confirmation.
 */
@Composable
fun DigitalSignatureDialog(
    onDismiss: () -> Unit,
    onContinue: (
        certificate: CertificateSummary,
        reason: String,
        location: String,
        visible: Boolean
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var certificates by remember { mutableStateOf<List<CertificateSummary>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingP12 by remember { mutableStateOf<ByteArray?>(null) }
    var pendingName by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CertificateSummary?>(null) }

    var reason by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(true) }

    fun refresh() {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DigitalCertificateStore(context).load()
            }
            result.fold(
                onSuccess = { list ->
                    certificates = list
                    if (selectedId == null && list.isNotEmpty()) selectedId = list.first().id
                    loading = false
                },
                onFailure = {
                    loading = false
                    error = it.message ?: "Saved certificates could not be read."
                }
            )
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            error = null
            busy = true
            scope.launch {
                val read = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                            readAtMost(stream, MAX_PKCS12_BYTES)
                        } ?: error("The file could not be opened.")
                        bytes
                    }
                }
                busy = false
                read.fold(
                    onSuccess = { bytes ->
                        pendingP12 = bytes
                        pendingName = uri.lastPathSegment?.substringAfterLast('/')
                        password = ""
                        passwordError = null
                        showPassword = true
                    },
                    onFailure = { failure ->
                        error = failure.message ?: "The file could not be read."
                    }
                )
            }
        }
    }

    val selected = certificates.firstOrNull { it.id == selectedId }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag(UiTags.DIGITAL_SIGNATURE_DIALOG)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (!busy) onDismiss() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close digital signature setup")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Digital signature",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Your certificate and key never leave this device.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Certificate",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { picker.launch(PKCS12_MIME_TYPES) },
                            enabled = !busy,
                            modifier = Modifier.testTag(UiTags.CERTIFICATE_IMPORT)
                        ) {
                            Icon(
                                Icons.Outlined.FileOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Import .p12")
                        }
                    }

                    when {
                        loading -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Reading saved certificates...")
                            }
                        }

                        certificates.isEmpty() -> {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Lock, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "No signing certificate yet",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Import a PKCS#12 file (.p12 or .pfx) with its private key. " +
                                            "The password is used once for the import and is never saved.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        else -> {
                            certificates.forEach { certificate ->
                                val chosen = certificate.id == selectedId
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedId = certificate.id }
                                        .testTag(UiTags.certificateItem(certificate.id)),
                                    border = if (chosen) {
                                        androidx.compose.foundation.BorderStroke(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        RadioButton(
                                            selected = chosen,
                                            onClick = { selectedId = certificate.id }
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(top = 2.dp)
                                        ) {
                                            Text(
                                                certificate.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (certificate.commonName != null &&
                                                certificate.commonName != certificate.label
                                            ) {
                                                Text(
                                                    certificate.commonName,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            Text(
                                                certificate.subject,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                "${certificate.keyAlgorithm} ${certificate.keySize}-bit · " +
                                                    "valid until ${formatCertificateDate(certificate.notAfter)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (certificate.isExpired || certificate.isNotYetValid) {
                                                Text(
                                                    if (certificate.isExpired) {
                                                        "This certificate has expired."
                                                    } else {
                                                        "This certificate is not valid yet."
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                        IconButton(onClick = { deleteTarget = certificate }) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = "Remove saved certificate"
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }

                    if (busy) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Importing certificate...", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(14.dp))

                    Text(
                        "Signature details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(200) },
                        label = { Text("Reason (optional)") },
                        placeholder = { Text("e.g. Approved the contract") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.DIGITAL_REASON)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it.take(120) },
                        label = { Text("Location (optional)") },
                        placeholder = { Text("e.g. Pune") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(UiTags.DIGITAL_LOCATION)
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Visible signature", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (visible) {
                                    "Next you will place the signature panel on a page."
                                } else {
                                    "The signature is hidden in the file but still covers the document."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = visible,
                            onCheckedChange = { visible = it },
                            modifier = Modifier.testTag(UiTags.DIGITAL_VISIBLE_SWITCH)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            DIGITAL_SIGNATURE_WARNING,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(11.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You will confirm with your device lock before the signature is written. " +
                            "The file is checked on this device after signing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The certificate password is never stored; only the key material, " +
                            "encrypted by an Android Keystore key, is kept on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { if (!busy) onDismiss() }) { Text("Cancel") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = selected != null && !busy,
                            onClick = {
                                val certificate = selected ?: return@Button
                                onContinue(certificate, reason, location, visible)
                            },
                            modifier = Modifier.testTag(UiTags.DIGITAL_CONTINUE)
                        ) {
                            Icon(Icons.Outlined.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (visible) "Place signature" else "Sign PDF")
                        }
                    }
                }
            }
        }
    }

    if (showPassword) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    showPassword = false
                    pendingP12 = null
                    pendingName = null
                    password = ""
                    passwordError = null
                }
            },
            title = { Text("Certificate password") },
            text = {
                Column {
                    Text(
                        pendingName?.let { "Enter the password for $it." }
                            ?: "Enter the password for this PKCS#12 file.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            passwordError = null
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiTags.CERTIFICATE_PASSWORD)
                    )
                    passwordError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Used once for this import and never saved.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty() && !busy,
                    onClick = {
                        val bytes = pendingP12 ?: return@TextButton
                        busy = true
                        passwordError = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DigitalCertificateStore(context).import(bytes, password.toCharArray())
                            }
                            busy = false
                            result.fold(
                                onSuccess = { certificate ->
                                    showPassword = false
                                    pendingP12 = null
                                    pendingName = null
                                    password = ""
                                    selectedId = certificate.id
                                    refresh()
                                },
                                onFailure = { failure ->
                                    passwordError = failure.message
                                        ?: "Could not import this certificate."
                                }
                            )
                            bytes.fill(0)
                        }
                    }
                ) { Text(if (busy) "Importing..." else "Import") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!busy) {
                            showPassword = false
                            pendingP12 = null
                            pendingName = null
                            password = ""
                            passwordError = null
                        }
                    }
                ) { Text("Cancel") }
            }
        )
    }

    val remove = deleteTarget
    if (remove != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove certificate?") },
            text = {
                Text(
                    "The saved private key for \"${remove.label}\" will be deleted from this " +
                        "device. Signatures that were already written into PDFs are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DigitalCertificateStore(context).delete(remove.id)
                            }
                            busy = false
                            result.fold(
                                onSuccess = { list ->
                                    certificates = list
                                    if (selectedId == remove.id) {
                                        selectedId = list.firstOrNull()?.id
                                    }
                                },
                                onFailure = {
                                    error = it.message ?: "The certificate could not be removed."
                                }
                            )
                        }
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Keep") }
            }
        )
    }
}

private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        require(total <= limit) { "That PKCS#12 file is too large (maximum 16 MB)." }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private val CERTIFICATE_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun formatCertificateDate(epochMillis: Long): String =
    CERTIFICATE_DATE.format(Instant.ofEpochMilli(epochMillis))

private const val MAX_PKCS12_BYTES = 16 * 1024 * 1024

private val PKCS12_MIME_TYPES = arrayOf(
    "application/x-pkcs12",
    "application/x-pfx",
    "application/octet-stream",
    "*/*"
)
