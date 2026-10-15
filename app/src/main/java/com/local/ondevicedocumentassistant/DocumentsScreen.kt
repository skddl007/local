package com.local.ondevicedocumentassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.local.ondevicedocumentassistant.data.DocumentEntity
import com.local.ondevicedocumentassistant.data.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ACCEPTED_MIME_TYPES = arrayOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "application/pdf",
)

@Composable
fun DocumentsScreen() {
    val context = LocalContext.current
    val repository = DocumentRepository(context)
    val documents by repository.observeDocuments().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType !in setOf("image/jpeg", "image/png", "image/webp", "application/pdf")) {
                return@forEach
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch { repository.addUri(uri) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Documents", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { picker.launch(ACCEPTED_MIME_TYPES) }) { Text("Add") }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(documents, key = { it.id }) { document ->
                DocumentRow(document, context)
            }
        }
    }
}

@Composable
private fun DocumentRow(document: DocumentEntity, context: Context) {
    val mimeType = context.contentResolver.getType(Uri.parse(document.fileUri))
    val bitmap by produceState<Bitmap?>(initialValue = null, document.fileUri) {
        value = if (mimeType?.startsWith("image/") == true) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(Uri.parse(document.fileUri))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }
        } else null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Document thumbnail",
                modifier = Modifier.size(64.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(Uri.parse(document.fileUri).lastPathSegment ?: "Selected document")
            Text("${document.docType} · ${document.processingStatus}")
        }
    }
}