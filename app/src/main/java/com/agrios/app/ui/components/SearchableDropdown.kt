package com.agrios.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agrios.app.core.util.LanguageManager

@Composable
fun SearchableDropdown(
    label: String,
    items: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (id: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
    allowManualEntry: Boolean = false,
    onManualEntry: ((String) -> Unit)? = null
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedName = items.find { it.first == selectedId }?.second ?: ""

    OutlinedTextField(
        value = selectedName,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        placeholder = {
            if (items.isEmpty()) {
                Text(LanguageManager.localize("Loading...", "लोड हो रहा है..."))
            } else {
                Text(LanguageManager.localize("Tap to select", "चुनने के लिए टैप करें"))
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable { if (items.isNotEmpty()) showDialog = true },
        enabled = false,
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        supportingText = if (items.isEmpty()) {
            { Text("${items.size} ${LanguageManager.localize("items loaded", "आइटम लोड")}") }
        } else null
    )

    if (showDialog && items.isNotEmpty()) {
        SearchDialog(
            title = label,
            items = items,
            allowManualEntry = allowManualEntry,
            onSelect = { id, name ->
                onSelect(id, name)
                showDialog = false
            },
            onManualEntry = { name ->
                onManualEntry?.invoke(name)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDialog(
    title: String,
    items: List<Pair<String, String>>,
    allowManualEntry: Boolean,
    onSelect: (id: String, name: String) -> Unit,
    onManualEntry: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    val filteredItems = remember(searchQuery, items) {
        if (searchQuery.isBlank()) {
            items.take(50)
        } else {
            items.filter { (_, name) ->
                name.contains(searchQuery, ignoreCase = true)
            }.take(50)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null)
                        }
                    }
                )

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(LanguageManager.localize("Type to search...", "खोजने के लिए टाइप करें...")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Results count
                Text(
                    text = "${filteredItems.size} ${LanguageManager.localize("results", "परिणाम")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // Manual entry section
                if (allowManualEntry) {
                    if (showManualInput) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = manualText,
                                onValueChange = { manualText = it },
                                placeholder = { Text(LanguageManager.localize("Enter name", "नाम दर्ज करें")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { if (manualText.isNotBlank()) onManualEntry(manualText) },
                                enabled = manualText.isNotBlank()
                            ) {
                                Text(LanguageManager.localize("Add", "जोड़ें"))
                            }
                        }
                    } else {
                        TextButton(
                            onClick = { showManualInput = true },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                "➕ ${LanguageManager.localize("Not in list? Add manually", "सूची में नहीं? मैन्युअल जोड़ें")}",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Scrollable list
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredItems) { (id, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            modifier = Modifier
                                .clickable { onSelect(id, name) }
                                .padding(horizontal = 8.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    if (filteredItems.isEmpty()) {
                        item {
                            Text(
                                LanguageManager.localize("No results found", "कोई परिणाम नहीं मिला"),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
