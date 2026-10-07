package com.pickaudio.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MusicSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, onClear: () -> Unit = { onValueChange("") }, onSearch: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onValueChange, singleLine = true, modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(12.dp),
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Default.Close, "清除搜索") } },
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }))
        if (onSearch != null) FilledIconButton(onClick = onSearch, enabled = value.isNotBlank(), modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "搜索歌曲")
        }
    }
}

@Composable
fun SelectionActions(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
