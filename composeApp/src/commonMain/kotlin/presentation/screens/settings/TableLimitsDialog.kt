package io.github.vrcmteam.vrcm.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.vrcmteam.vrcm.presentation.designsystem.AppButton
import io.github.vrcmteam.vrcm.presentation.designsystem.AppButtonStyle
import io.github.vrcmteam.vrcm.presentation.designsystem.AppDialogSurface
import io.github.vrcmteam.vrcm.presentation.designsystem.AppText
import io.github.vrcmteam.vrcm.presentation.designsystem.AppTextField
import io.github.vrcmteam.vrcm.presentation.designsystem.AppTheme
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.service.TableLimitsVo

/**
 * The two limits that decide how much VRCX keeps and how far a search reaches.
 *
 * Field names and keys are the desktop's (`maxTableSize_v2`, `searchLimit`), written through
 * `app__config_set_values` rather than a settings table of our own.
 */
@Composable
internal fun TableLimitsDialog(
    initial: TableLimitsVo,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (maxTableSize: Int, searchLimit: Int) -> Unit,
) {
    var maxTableSize by remember { mutableStateOf(initial.maxTableSize.toString()) }
    var searchLimit by remember { mutableStateOf(initial.searchLimit.toString()) }

    val maxValue = maxTableSize.trim().toIntOrNull()
    val searchValue = searchLimit.trim().toIntOrNull()
    val problem = when {
        maxValue == null || maxValue < 1 -> strings.settingsTableMaxSizeInvalid
        searchValue == null || searchValue < 1 -> strings.settingsTableSearchLimitInvalid
        else -> null
    }

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        AppDialogSurface(modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 460.dp)) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText(text = strings.settingsTableLimits, style = AppTheme.type.title2)
                Spacer(Modifier.height(4.dp))
                AppText(
                    text = strings.settingsTableLimitsHint,
                    style = AppTheme.type.footnote,
                    color = AppTheme.colors.secondaryLabel,
                )
                Spacer(Modifier.height(12.dp))
                AppTextField(
                    value = maxTableSize,
                    onValueChange = { maxTableSize = it.filter(Char::isDigit) },
                    label = { AppText(strings.settingsTableMaxSize) },
                    singleLine = true,
                    enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = searchLimit,
                    onValueChange = { searchLimit = it.filter(Char::isDigit) },
                    label = { AppText(strings.settingsTableSearchLimit) },
                    singleLine = true,
                    enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    AppText(
                        text = it,
                        style = AppTheme.type.footnote,
                        color = AppTheme.colors.destructive,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppButton(
                        onClick = onDismiss,
                        enabled = !submitting,
                        style = AppButtonStyle.Plain,
                        modifier = Modifier.weight(1f),
                    ) { AppText(strings.cancel) }
                    AppButton(
                        onClick = { onSave(maxValue ?: 0, searchValue ?: 0) },
                        enabled = !submitting && problem == null,
                        modifier = Modifier.weight(1f),
                    ) { AppText(strings.confirm) }
                }
            }
        }
    }
}
