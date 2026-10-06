package com.pickaudio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ThirdPartyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            listOf("NOTICE.md", "DEPENDENCIES.txt", "QuickJS-MIT.txt", "Apache-2.0.txt", "UPSTREAM-NOTICES.txt")
                .joinToString("\n\n") { name -> context.assets.open("third-party/$name").bufferedReader().use { it.readText() } }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(com.pickaudio.R.string.third_party_title)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (text == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else Text(text!!, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(com.pickaudio.R.string.action_done)) } })
}
