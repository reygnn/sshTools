package com.github.reygnn.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Stateless building blocks for the per-app Settings screens. Lobber, Caster and
 * Prodder differ at the screen level (Lobber has an ADB block, Prodder has no
 * working dir), so the *screen* stays per app — only these leaf components are
 * shared here so they can't drift. They take plain data + callbacks and own no
 * ViewModel reference; strings come from this module (`cu_…`), the one label
 * that differs per app (working dir) is passed in.
 */

/** One server profile in the list, with edit/delete actions. */
@Composable
fun ServerRow(name: String, host: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name)
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.cu_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.cu_delete)) }
        }
    }
}

/**
 * Add/edit form for a server profile. The working-dir field is rendered only
 * when [workingDirLabel] is non-null (Prodder has no working dir); its value
 * comes from [workingDir] and changes via [onWorkingDir].
 */
@Composable
fun ServerEditor(
    name: String,
    host: String,
    port: String,
    username: String,
    workingDir: String,
    workingDirLabel: String?,
    onName: (String) -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onUsername: (String) -> Unit,
    onWorkingDir: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name, onValueChange = onName,
                label = { Text(stringResource(R.string.cu_field_server_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = host, onValueChange = onHost,
                label = { Text(stringResource(R.string.cu_field_host)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port, onValueChange = onPort,
                label = { Text(stringResource(R.string.cu_field_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = onUsername,
                label = { Text(stringResource(R.string.cu_field_user)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (workingDirLabel != null) {
                OutlinedTextField(
                    value = workingDir, onValueChange = onWorkingDir,
                    label = { Text(workingDirLabel) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cu_save_server))
                }
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cu_cancel))
                }
            }
        }
    }
}

/** Dropdown to switch the active server profile (shown when more than one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPicker(
    serverNames: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = serverNames.getOrNull(selectedIndex) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.cu_server_picker_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            serverNames.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The shared private-key field: masked by default against shoulder-surfing, with
 * a Show/Hide toggle to reveal it for paste/verify during manual setup.
 */
@Composable
fun KeyField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(stringResource(R.string.cu_field_private_key)) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(stringResource(if (visible) R.string.cu_action_hide else R.string.cu_action_show))
            }
        },
        modifier = modifier.fillMaxWidth().height(160.dp),
    )
}

/**
 * Small status dot. [active] is a fixed semantic green (MATERIAL3RULES A5
 * exception): the theme is dynamic-only Material You, so there is no static
 * scheme to host a named role, and a wallpaper-derived `tertiary` would not
 * reliably read as green/active.
 */
@Composable
fun StatusDot(active: Boolean) {
    val color = if (active) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}
