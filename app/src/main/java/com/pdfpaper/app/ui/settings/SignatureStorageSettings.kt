package com.pdfpaper.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.signatures.CertificateSummary
import com.pdfpaper.app.signatures.DigitalCertificateStore
import com.pdfpaper.app.signatures.SavedSignature
import com.pdfpaper.app.signatures.SavedSignatureStore
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.viewer.SignaturePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings section for the encrypted signature material created by the Sign
 * tool. Removing an item deletes only the local saved copy; existing PDF
 * signatures are unaffected.
 */
@Composable
fun SignatureStorageSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<List<SavedSignature>>(emptyList()) }
    var certificates by remember { mutableStateOf<List<CertificateSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteSignature by remember { mutableStateOf<SavedSignature?>(null) }
    var deleteCertificate by remember { mutableStateOf<CertificateSummary?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val savedResult = SavedSignatureStore(context).load()
                val certificateResult = DigitalCertificateStore(context).load()
                Triple(savedResult, certificateResult, null)
            }
            saved = result.first.getOrDefault(emptyList())
            certificates = result.second.getOrDefault(emptyList())
            error = (result.first.exceptionOrNull() ?: result.second.exceptionOrNull())?.message
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.SETTINGS_SIGNATURES)
    ) {
        Text(
            "Signatures",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Saved visual signatures and imported signing certificates. Both are protected " +
                "by a non-exportable Android Keystore key and never leave this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Reading saved signatures...", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(6.dp))
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Draw,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Saved visual signatures",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            saved.size.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (saved.isEmpty()) {
                        Text(
                            "No saved signatures yet. Draw or type one in Viewer > Sign and tick " +
                                "\"Save this signature for later\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        saved.forEach { item ->
                            SavedSignatureRow(
                                item = item,
                                onDelete = { deleteSignature = item }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Signing certificates",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            certificates.size.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (certificates.isEmpty()) {
                        Text(
                            "No signing certificates imported. Import a .p12 or .pfx file from " +
                                "Viewer > Sign > Digital signature.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        certificates.forEach { certificate ->
                            CertificateRow(
                                certificate = certificate,
                                onDelete = { deleteCertificate = certificate }
                            )
                        }
                    }
                }
            }
        }
    }

    deleteSignature?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteSignature = null },
            title = { Text("Delete saved signature?") },
            text = {
                Text(
                    "The saved \"${target.title}\" signature will be removed from this device. " +
                        "Signatures already placed in PDFs are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSignature = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                SavedSignatureStore(context).delete(target.id)
                            }
                            result.fold(
                                onSuccess = { saved = it },
                                onFailure = {
                                    error = it.message ?: "The signature could not be deleted."
                                }
                            )
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSignature = null }) { Text("Keep") }
            }
        )
    }

    deleteCertificate?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCertificate = null },
            title = { Text("Delete signing certificate?") },
            text = {
                Text(
                    "The private key for \"${target.label}\" will be deleted from this device. " +
                        "You will need to import the PKCS#12 file again to sign with it."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCertificate = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                DigitalCertificateStore(context).delete(target.id)
                            }
                            result.fold(
                                onSuccess = { certificates = it },
                                onFailure = {
                                    error = it.message ?: "The certificate could not be deleted."
                                }
                            )
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCertificate = null }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun SavedSignatureRow(
    item: SavedSignature,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SignaturePreview(
            draft = item.draft,
            modifier = Modifier
                .width(112.dp)
                .height(42.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(5.dp)),
            compact = true
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${item.kindLabel} · ${formatSavedDate(item.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag(UiTags.settingsSavedSignature(item.id))
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete saved signature")
        }
    }
}

@Composable
private fun CertificateRow(
    certificate: CertificateSummary,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                certificate.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${certificate.keyAlgorithm} ${certificate.keySize}-bit · " +
                    (if (certificate.isExpired) {
                        "expired ${formatSavedDate(certificate.notAfter)}"
                    } else {
                        "valid until ${formatSavedDate(certificate.notAfter)}"
                    }),
                style = MaterialTheme.typography.labelSmall,
                color = if (certificate.isExpired) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.testTag(UiTags.settingsCertificate(certificate.id))
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete signing certificate")
        }
    }
}

private val SAVED_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun formatSavedDate(epochMillis: Long): String =
    SAVED_DATE.format(Instant.ofEpochMilli(epochMillis))
