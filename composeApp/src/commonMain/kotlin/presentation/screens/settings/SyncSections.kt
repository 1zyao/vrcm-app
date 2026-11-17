package io.github.vrcmteam.vrcm.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.vrcmteam.vrcm.presentation.designsystem.AppActivityIndicator
import io.github.vrcmteam.vrcm.presentation.designsystem.AppButton
import io.github.vrcmteam.vrcm.presentation.designsystem.AppButtonStyle
import io.github.vrcmteam.vrcm.presentation.designsystem.AppProgressBar
import io.github.vrcmteam.vrcm.presentation.designsystem.AppRow
import io.github.vrcmteam.vrcm.presentation.designsystem.AppText
import io.github.vrcmteam.vrcm.presentation.designsystem.AppTheme
import io.github.vrcmteam.vrcm.presentation.designsystem.AppToggle
import io.github.vrcmteam.vrcm.presentation.settings.locale.LocaleStrings
import io.github.vrcmteam.vrcm.presentation.settings.locale.strings
import io.github.vrcmteam.vrcm.service.SyncStatusService
import io.github.vrcmteam.vrcm.service.SyncStatusVo
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 远程数据库同步 — the connection card.
 *
 * Split from the status card because the desktop splits them: this one is what you configure, the
 * other is what the engine reports. Mixing them in a single group made the settings read like one
 * long list of facts, half of which you cannot change.
 */
@Composable
internal fun SyncConnectionSection() {
    val service = koinInject<SyncStatusService>()
    val status by service.status.collectAsState()
    val busy by service.busy.collectAsState()
    val testing by service.testing.collectAsState()
    val testResult by service.testResult.collectAsState()
    val scope = rememberCoroutineScope()
    var showConfigure by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { service.refresh() }
    val s = strings

    SettingsGroup(title = s.settingsSyncConnectionTitle) {
        // Every field below is a *summary*: one editor, in the dialog. Having switches here and the
        // same switches inside the dialog gave two write paths for one setting, which is how a
        // screen starts disagreeing with itself. Testing lives in the dialog for the same reason -
        // that is where you are when you have just changed the address.
        AppRow(
            title = s.settingsSyncServer,
            value = if (status.host.isBlank()) "-"
            else "${status.host}:${status.port} / ${status.database}",
            subtitle = s.settingsSyncConnectionDescription,
            enabled = !busy,
            chevron = true,
            onClick = { showConfigure = true },
        )
        SettingsRowDivider()
        AppRow(
            title = s.settingsSyncTlsVerify,
            value = if (status.tlsVerify) s.settingsSwitchOn else s.settingsSwitchOff,
            enabled = !busy,
            chevron = true,
            onClick = { showConfigure = true },
        )
        SettingsRowDivider()
        AppRow(
            title = s.settingsSyncAllowPlaintext,
            value = if (status.allowPlaintext) s.settingsSwitchOn else s.settingsSwitchOff,
            enabled = !busy,
            chevron = true,
            onClick = { showConfigure = true },
        )
        SettingsRowDivider()
        AppRow(
            title = s.settingsSyncInterval,
            value = fractionOrDash(status.intervalSeconds),
            enabled = !busy,
            chevron = true,
            onClick = { showConfigure = true },
        )
        SettingsRowDivider()
        AppRow(
            title = s.settingsSyncPriority,
            value = fractionOrDash(status.collectorPriority),
            enabled = !busy,
            chevron = true,
            onClick = { showConfigure = true },
        )
        SettingsRowDivider()
        // The enable switch is an action, not a field: it applies immediately, as on the desktop.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(text = s.settingsSyncEnabled, style = AppTheme.type.body)
                testResult?.let { result ->
                    AppText(
                        text = if (result.ok) {
                            s.settingsSyncTestOk.replace("%ms%", result.latencyMs.toString())
                        } else {
                            result.error ?: s.settingsSyncTestFailed
                        },
                        style = AppTheme.type.footnote,
                        color = if (result.ok) AppTheme.colors.tint else AppTheme.colors.destructive,
                    )
                }
            }
            if (testing) {
                AppActivityIndicator(modifier = Modifier.size(16.dp))
            }
            AppToggle(
                checked = status.enabled,
                onCheckedChange = { next -> scope.launch { service.setEnabled(next) } },
                enabled = !busy,
            )
        }
    }

    if (showConfigure) {
        SyncConfigureDialog(
            initial = status,
            submitting = busy,
            testing = testing,
            testResult = testResult,
            onDismiss = { showConfigure = false },
            onTest = { host, port, user, password, database, tlsVerify, allowPlaintext ->
                scope.launch {
                    service.test(host, port, user, password, database, tlsVerify, allowPlaintext)
                }
            },
            onSave = { host, port, user, password, database, tlsVerify, allowPlaintext, interval, priority ->
                showConfigure = false
                scope.launch {
                    service.configure(
                        host = host,
                        port = port,
                        user = user,
                        password = password,
                        database = database,
                        tlsVerify = tlsVerify,
                        allowPlaintext = allowPlaintext,
                        intervalSeconds = interval,
                        collectorPriority = priority,
                    )
                }
            },
        )
    }
}

/**
 * 同步状态 — the status card.
 *
 * Everything here is what the engine reports (`sync__status`, `sync__bootstrap_progress`); nothing is
 * inferred locally. Rows that only exist in some states (first sync, per-table detail, device list)
 * are hidden rather than shown empty, which is what the desktop does as well.
 */
@Composable
internal fun SyncStatusCard() {
    val service = koinInject<SyncStatusService>()
    val status by service.status.collectAsState()
    val busy by service.busy.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { service.refresh() }
    val s = strings

    SettingsGroup(title = s.settingsSyncStatusTitle) {
        AppRow(
            title = s.settingsSyncPhase,
            value = buildString {
                append(phaseLabel(status))
                status.nextCycleInSecs?.let { secs ->
                    append(" · ")
                    append(s.settingsSyncNextSync.replace("%secs%", secs.toString()))
                }
            },
            subtitle = buildString {
                status.error?.let { append(it) }
                if (status.pendingOutbox > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(s.settingsSyncPending.replace("%count%", status.pendingOutbox.toString()))
                }
            }.ifBlank { null },
            trailing = {
                if (busy) AppActivityIndicator(modifier = Modifier.size(18.dp))
            },
            chevron = true,
            enabled = status.configured,
            onClick = { scope.launch { service.triggerNow() } },
        )
        SettingsRowDivider()
        AppRow(
            title = s.settingsSyncLastCycle,
            value = status.lastCycleAt.ifBlank { "-" },
        )
        if (status.deviceId.isNotBlank()) {
            SettingsRowDivider()
            AppRow(
                title = s.settingsSyncDeviceId,
                value = status.thisDeviceLabel(status.deviceId),
            )
        }
        SettingsRowDivider()
        AppRow(
            title = s.settingsSyncLastPushed,
            value = s.settingsSyncOpsCount.replace("%count%", status.lastPushedOps.toString()),
        )
        SettingsRowDivider()
        AppRow(
            title = s.settingsSyncLastPulled,
            value = s.settingsSyncOpsCount.replace("%count%", status.lastPulledOps.toString()),
        )
        if (status.bootstrapRunning) {
            SettingsRowDivider()
            BootstrapProgressBlock(status = status, strings = s)
        }
        if (status.enabled) {
            SettingsRowDivider()
            AppRow(
                title = s.settingsSyncCycleTables,
                value = if (status.lastCycleTables.isEmpty()) {
                    s.settingsSyncCycleTablesEmpty
                } else {
                    status.lastCycleTables.joinToString(" · ") { row ->
                        "${row.table} ↑${row.pushed} ↓${row.pulled}"
                    }
                },
            )
        }
        if (status.cycles.isNotEmpty()) {
            SettingsRowDivider()
            MultilineRow(
                title = s.settingsSyncHistoryTitle,
                lines = status.cycles.take(4).map { cycle ->
                    val outcome = cycle.error
                        ?: if (cycle.pushed == 0 && cycle.pulled == 0) {
                            s.settingsSyncHistoryNoChange
                        } else {
                            "↑${cycle.pushed} ↓${cycle.pulled}"
                        }
                    "${cycle.at}  $outcome  ${cycle.durationMs}ms"
                },
            )
        }
        if (status.devices.isNotEmpty()) {
            SettingsRowDivider()
            MultilineRow(
                title = s.settingsSyncDevices,
                lines = status.devices.map { device ->
                    buildString {
                        append(device.name.ifBlank { device.deviceId.take(8) })
                        if (device.deviceId == status.deviceId) {
                            append(" · ${s.settingsSyncThisDeviceTag}")
                        }
                        if (device.ipAddress.isNotBlank()) append(" · ${device.ipAddress}")
                        if (device.role.isNotBlank()) {
                            append(" · ${device.role.uppercase()}·P${device.priority}")
                        }
                        if (device.appVersion.isNotBlank()) append(" · ${device.appVersion}")
                        append(" · ")
                        append(
                            if (device.online) {
                                s.settingsSyncOnline
                            } else {
                                s.settingsSyncSeenSeconds
                                    .replace("%count%", device.seenSecondsAgo.toString())
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * The first-run backfill, with the bars the desktop shows.
 *
 * A plain "15/56 tables" line reads like a frozen number; a bar plus the current table name makes it
 * obvious the engine is working through 400k rows rather than stuck.
 */
@Composable
private fun BootstrapProgressBlock(status: SyncStatusVo, strings: LocaleStrings) {
    val tablesFraction = status.bootstrapTablesFraction
    val tableRowsFraction = status.bootstrapTableRowsFraction
    val rowsFraction = status.bootstrapRowsFraction

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppActivityIndicator(modifier = Modifier.size(14.dp))
            AppText(
                text = strings.settingsSyncBootstrap + " · " + bootstrapPhaseLabel(strings, status.bootstrapPhase),
                style = AppTheme.type.footnote,
                modifier = Modifier.weight(1f),
            )
            tablesFraction?.let {
                AppText(
                    text = strings.settingsSyncProgressTablesOf
                        .replace("%percent%", percent(it).toString()),
                    style = AppTheme.type.footnote,
                    color = AppTheme.colors.secondaryLabel,
                )
            }
        }
        // Indeterminate while the totals are still unknown, determinate as soon as they arrive.
        if (tablesFraction == null) {
            AppProgressBar()
        } else {
            AppProgressBar(progress = { tablesFraction })
        }
        if (status.bootstrapCurrentTable.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(
                    text = status.bootstrapCurrentTable,
                    style = AppTheme.type.footnote,
                    color = AppTheme.colors.secondaryLabel,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                AppText(
                    text = tableRowsFraction?.let {
                        strings.settingsSyncProgressTableRowsOf
                            .replace("%done%", status.bootstrapCurrentTableRowsDone.toString())
                            .replace("%total%", status.bootstrapCurrentTableRowsTotal.toString())
                            .replace("%percent%", percent(it).toString())
                    } ?: strings.settingsSyncProgressRows
                        .replace("%count%", status.bootstrapCurrentTableRowsDone.toString()),
                    style = AppTheme.type.footnote,
                    color = AppTheme.colors.secondaryLabel,
                )
            }
        }
        tableRowsFraction?.let { AppProgressBar(progress = { it }) }
        rowsFraction?.let {
            AppText(
                text = strings.settingsSyncProgressRowsOf
                    .replace("%done%", status.bootstrapRowsDone.toString())
                    .replace("%total%", status.bootstrapRowsTotal.toString())
                    .replace("%percent%", percent(it).toString()),
                style = AppTheme.type.footnote,
                color = AppTheme.colors.secondaryLabel,
            )
        } ?: AppText(
            text = strings.settingsSyncProgressRows
                .replace("%count%", status.bootstrapRowsDone.toString()),
            style = AppTheme.type.footnote,
            color = AppTheme.colors.secondaryLabel,
        )
    }
}

private fun percent(fraction: Float): Int = (fraction * 100f).toInt()

private fun bootstrapPhaseLabel(strings: LocaleStrings, phase: String): String = when (phase) {
    "push" -> strings.settingsSyncPhasePush
    "merge" -> strings.settingsSyncPhaseMerge
    "reconciling" -> strings.settingsSyncPhaseReconciling
    "done" -> strings.settingsSyncPhaseDone
    "bootstrap" -> strings.settingsSyncPhaseBootstrap
    else -> strings.settingsSyncPhaseRunning
}

/** Row values are shown as-is; 0 means "not read yet", which should not look like a real 0. */
private fun fractionOrDash(value: Int): String = if (value > 0) value.toString() else "-"

/** A row whose value is several lines (history, device list). `AppRow` is single-line by design. */
@Composable
private fun MultilineRow(title: String, lines: List<String>) {    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppText(text = title, style = AppTheme.type.body)
        lines.forEach { line ->
            AppText(
                text = line,
                style = AppTheme.type.footnote,
                color = AppTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun SyncSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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

@Composable
private fun phaseLabel(status: SyncStatusVo): String = when (status.phase) {
    "idle" -> strings.settingsSyncPhaseIdle
    "running" -> strings.settingsSyncPhaseRunning
    "bootstrap" -> strings.settingsSyncPhaseBootstrap
    "push" -> strings.settingsSyncPhasePush
    "merge" -> strings.settingsSyncPhaseMerge
    "reconciling" -> strings.settingsSyncPhaseReconciling
    "error" -> strings.settingsSyncPhaseError
    "disabled" -> strings.settingsSyncPhaseDisabled
    "done" -> strings.settingsSyncPhaseDone
    "" -> strings.settingsSyncPhaseIdle
    else -> status.phase
}

@Composable
private fun SyncStatusVo.thisDeviceLabel(deviceId: String): String =
    strings.settingsSyncThisDevice.replace("%id%", deviceId.take(8))

