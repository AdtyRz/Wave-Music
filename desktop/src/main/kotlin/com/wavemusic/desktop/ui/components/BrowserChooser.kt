package com.wavemusic.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wavemusic.desktop.auth.LoginBrowser

/**
 * "Sign in with [Firefox ▾]" next to the browser sign-in button.
 *
 * Several browsers can be installed at once, and the helper used to always launch the first one
 * it found — Edge on Windows, Firefox on Linux — even when the account lived in Chrome. The
 * choice applies to the throwaway profile that browser opens; nothing is read from the user's
 * normal profile.
 */
@Composable
fun BrowserChooser(
    browsers: List<LoginBrowser>,
    selected: LoginBrowser?,
    onSelect: (LoginBrowser) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (browsers.size < 2) return

    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Sign in with",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected?.name ?: "Choose browser")
                Spacer(Modifier.size(4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                browsers.forEach { browser ->
                    DropdownMenuItem(
                        text = { Text(browser.name) },
                        onClick = {
                            onSelect(browser)
                            expanded = false
                        },
                        trailingIcon = {
                            if (browser == selected) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        },
                    )
                }
            }
        }
    }
}
