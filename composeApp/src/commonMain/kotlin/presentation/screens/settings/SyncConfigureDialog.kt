package io.github.vrcmteam.vrcm.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import io.github.vrcmteam.vrcm.presentation.designsystem.AppActivityIndicator
import io.github.vrcmteam.vrcm.presentation.designsystem.AppButton
import io.github.vrcmteam.vrcm.presentation.designsystem.AppButtonStyle
import io.github.vrcmteam.vrcm.presentation.designsystem.AppDialogSurface
import io.github.vrcmteam.vrcm.presentation.designsystem.AppText
import io.github.vrcmteam.vrcm.presentation.designsystem.AppTextField
import io.github.vrcmteam.vrcm.presentation.designsystem.AppTheme
import io.github.vrcmteam.vrcm.presentation.designsystem.AppToggle
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.service.SyncConnectionTestVo
import io.github.vrcmteam.vrcm.service.SyncStatusVo

/**
 * The sync target form, following VRCX's own sync settings page.
 *
 * Same fields in the same order as the desktop `SettingsSyncTab`: the five connection inputs, then
 * TLS verify, plaintext, interval and collector priority. Two semantics are copied deliberately -
 * an empty password keeps the stored one (it is never read back, so a "saved" hint is the only
 * honest way to show one exists), and saving sends every field, not only the changed ones.
 */
@Composable
internal fun SyncConfigureDialog(
    initial: SyncStatusVo,
    submitting: Boolean,
    testing: Boolean,
    testResult: SyncConnectionTestVo?,
    onDismiss: () -> Unit,
    onTest: (String, Int, String, String, String, Boolean, Boolean) -> Unit,
    onSave: (String, Int, String, String, String, Boolean, Boolean, Int, Int) -> Unit,
) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.takeIf { it > 0 }?.toString() ?: "5432") }
    var database by remember { mutableStateOf(initial.database) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf("") }
    var tlsVerify by remember { mutableStateOf(initial.tlsVerify) }
    var allowPlaintext by remember { mutableStateOf(initial.allowPlaintext) }
    var interval by remember {
        mutableStateOf(initial.intervalSeconds.takeIf { it > 0 }?.toString() ?: "60")
    }
    var priority by remember { mutableStateOf(initial.collectorPriority.toString()) }

    val portValue = port.trim().toIntOrNull()
    val intervalValue = interval.trim().toIntOrNull()
    val priorityValue = priority.trim().toIntOrNull() ?: 0
    val problem = when {
        host.isBlank() -> strings.settingsSyncHostRequired
        portValue == null || portValue !in 1..65535 -> strings.settingsSyncPortInvalid
        database.isBlank() -> strings.settingsSyncDatabaseRequired
        user.isBlank() -> strings.settingsSyncUserRequired
        intervalValue == null || intervalValue < 5 -> strings.settingsSyncIntervalInvalid
        else -> null
    }

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        AppDialogSurface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp).heightIn(max = 760.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText(text = strings.settingsSyncConfigure, style = AppTheme.type.title2)
                Spacer(Modifier.height(4.dp))
                AppText(
                    text = strings.settingsSyncConnectionHint,
                    style = AppTheme.type.footnote,
                    color = AppTheme.colors.secondaryLabel,
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { AppText(strings.settingsSyncServer) },
                        placeholder = { AppText("192.168.1.10") },
                        singleLine = true,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit) },
                            label = { AppText(strings.settingsSyncPort) },
                            placeholder = { AppText("5432") },
                            singleLine = true,
                            enabled = !submitting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        AppTextField(
                            value = database,
                            onValueChange = { database = it },
                            label = { AppText(strings.settingsSyncDatabase) },
                            placeholder = { AppText("vrcx0") },
                            singleLine = true,
                            enabled = !submitting,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AppTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { AppText(strings.settingsSyncUser) },
                        placeholder = { AppText("vrcx_sync") },
                        singleLine = true,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { AppText(strings.settingsSyncPasswordKeep) },
                        placeholder = { AppText(if (initial.hasPassword) "••••••••" else "") },
                        singleLine = true,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (initial.hasPassword) {
                        AppText(
                            text = strings.settingsSyncPasswordSaved,
                            style = AppTheme.type.footnote,
                            color = AppTheme.colors.secondaryLabel,
                        )
                    }

                    SwitchRow(
                        title = strings.settingsSyncTlsVerify,
                        description = strings.settingsSyncTlsVerifyHint,
                        checked = tlsVerify,
                        enabled = !submitting,
                        onCheckedChange = { tlsVerify = it },
                    )
                    SwitchRow(
                        title = strings.settingsSyncAllowPlaintext,
                        description = strings.settingsSyncAllowPlaintextHint,
                        checked = allowPlaintext,
                        enabled = !submitting,
                        onCheckedChange = { allowPlaintext = it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextField(
                            value = interval,
                            onValueChange = { interval = it.filter(Char::isDigit) },
                            label = { AppText(strings.settingsSyncInterval) },
                            singleLine = true,
                            enabled = !submitting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        AppTextField(
                            value = priority,
                            onValueChange = { priority = it.filter(Char::isDigit) },
                            label = { AppText(strings.settingsSyncPriority) },
                            singleLine = true,
                            enabled = !submitting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    problem?.let {
                        AppText(
                            text = it,
                            style = AppTheme.type.footnote,
                            color = AppTheme.colors.destructive,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Test before saving, as the desktop page does: a wrong port should not have to be
                    // written into the config to be discovered.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AppButton(
                            onClick = {
                                onTest(
                                    host.trim(),
                                    portValue ?: 0,
                                    user.trim(),
                                    password,
                                    database.trim(),
                                    tlsVerify,
                                    allowPlaintext,
                                )
                            },
                            enabled = !testing && !submitting && problem == null,
                            style = AppButtonStyle.Plain,
                        ) { AppText(strings.settingsSyncTest) }
                        if (testing) {
                            AppActivityIndicator(modifier = Modifier.size(18.dp))
                        }
                        testResult?.let { result ->
                            AppText(
                                text = if (result.ok) {
                                    strings.settingsSyncTestOk.replace(
                                        "%ms%",
                                        result.latencyMs.toString(),
                                    )
                                } else {
                                    result.error ?: strings.settingsSyncTestFailed
                                },
                                style = AppTheme.type.footnote,
                                color = if (result.ok) {
                                    AppTheme.colors.tint
                                } else {
                                    AppTheme.colors.destructive
                                },
                            )
                        }
                    }
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
                        onClick = {
                            onSave(
                                host.trim(),
                                portValue ?: 0,
                                user.trim(),
                                password,
                                database.trim(),
                                tlsVerify,
                                allowPlaintext,
                                intervalValue ?: 0,
                                priorityValue,
                            )
                        },
                        enabled = !submitting && problem == null,
                        modifier = Modifier.weight(1f),
                    ) { AppText(strings.confirm) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AppText(text = title, style = AppTheme.type.body)
            AppText(
                text = description,
                style = AppTheme.type.footnote,
                color = AppTheme.colors.secondaryLabel,
            )
        }
        AppToggle(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
