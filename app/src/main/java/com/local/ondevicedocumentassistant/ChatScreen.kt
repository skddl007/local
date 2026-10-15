package com.local.ondevicedocumentassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.local.ondevicedocumentassistant.data.AppDatabase
import com.local.ondevicedocumentassistant.query.DocumentQueryEngine
import com.local.ondevicedocumentassistant.query.QueryAnswer
import kotlinx.coroutines.launch

private data class ChatMessage(val question: String, val answer: QueryAnswer)

@Composable
fun ChatScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var question by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val engine = remember {
        val database = AppDatabase.get(context)
        DocumentQueryEngine(database.documentFieldDao(), database.documentChunkDao())
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Chat", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages) { message ->
                Text("You: ${message.question}", style = MaterialTheme.typography.labelLarge)
                Text("Assistant: ${message.answer.text}")
                message.answer.citations.distinct().forEach { citation ->
                    Text(
                        "Source: ${citation.documentType}" +
                            (citation.pageNumber?.let { ", page $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                label = { Text("Ask about your documents") },
                enabled = !isSearching,
                singleLine = true,
            )
            Button(
                onClick = {
                    val submitted = question.trim()
                    if (submitted.isNotEmpty()) {
                        question = ""
                        isSearching = true
                        scope.launch {
                            val answer = engine.answer(submitted)
                            if (answer.requiresAuthentication) {
                                (context as? MainActivity)?.authenticateForSensitiveContent {
                                    messages += ChatMessage(submitted, answer)
                                }
                            } else {
                                messages += ChatMessage(submitted, answer)
                            }
                            isSearching = false
                        }
                    }
                },
                enabled = !isSearching && question.isNotBlank(),
            ) { Text("Ask") }
        }
    }
}
