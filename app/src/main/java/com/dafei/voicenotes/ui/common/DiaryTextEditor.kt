package com.dafei.voicenotes.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val DiaryTextFieldHeight = 300.dp

@Composable
fun DiaryTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(DiaryTextFieldHeight),
        placeholder = { Text(placeholder) },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = false
    )
}
