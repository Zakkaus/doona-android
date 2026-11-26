package dev.doona.android.prototype.rv.policy

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvFieldStack
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRowRemove
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsAddRow
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.feature.model.RvDnsServer

/**
 * DNS 伺服器 (config plan row 17; no prototype, sample only): the servers dns.dae declares in `dns.upstream`, in file
 * order, each its name over its address; a tap opens it ([onOpen]). With [edit] each server ends with the remove
 * control and 新增伺服器 ends the card; 新增伺服器 opens [adding], whose 新增 hands [onCreate] the server. A removal asks first; a server a DNS rule still names is refused by the caller through [banner], as a group
 * is. [adding] is the new server's sheet, typed through [onAdding] (null closes it).
 */
@Composable
internal fun RvDnsServersPage(
    servers: List<RvDnsServer>,
    onBack: () -> Unit,
    onOpen: (RvDnsServer) -> Unit = {},
    edit: RvListEdit? = null,
    banner: RvNotice? = null,
    adding: RvDnsServer? = null,
    onAdding: (RvDnsServer?) -> Unit = {},
    onCreate: (RvDnsServer) -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_dns_servers)
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter(banner = banner) {
            RvCard {
                servers.forEach { server ->
                    RvSettingsNavRow(
                        server.name,
                        R.drawable.rv_sym_dns,
                        { onOpen(server) },
                        subtitle = server.address,
                        subtitleKeepWords = false,
                        remove = edit?.let { RvRowRemove { deleting = server.name } },
                        removeLabel = stringResource(R.string.rv_list_remove, server.name),
                    )
                }
                if (edit != null) RvSettingsAddRow(stringResource(R.string.rv_dns_server_new), { onAdding(RvDnsServer("", "")) }, disc = true)
            }
        }
    }
    RvConfirmSheet(
        visible = deleting != null,
        title = stringResource(R.string.rv_list_delete_title, deleting.orEmpty()),
        body = stringResource(R.string.rv_dns_server_delete_body),
        confirm = stringResource(R.string.rv_rule_delete_confirm),
        dismiss = stringResource(R.string.rv_rule_delete_cancel),
        onConfirm = {
            deleting?.let { edit?.onRemove(it) }
            deleting = null
        },
        onDismiss = { deleting = null },
        titleKeepWords = false,
        bodyKeepWords = false,
    )
    AddServerSheet(servers, adding, onAdding, onCreate)
}

/**
 * 新增伺服器: the server's name, which DNS rules send to, and its address in honk's form. 新增 takes a name no server
 * has and an address with one of honk's DNS schemes (`DnsProtocol`); each field says what is wrong with it.
 */
@Composable
private fun AddServerSheet(servers: List<RvDnsServer>, adding: RvDnsServer?, onAdding: (RvDnsServer?) -> Unit, onCreate: (RvDnsServer) -> Unit) {
    val name = adding?.name?.trim().orEmpty()
    val address = adding?.address?.trim().orEmpty()
    val taken = servers.any { it.name == name }
    val badAddress = address.isNotEmpty() && !rvDnsAddressValid(address)
    RvConfirmSheet(
        visible = adding != null,
        title = stringResource(R.string.rv_dns_server_new),
        body = null,
        confirm = stringResource(R.string.rv_list_create),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = {
            onCreate(RvDnsServer(name, address))
            onAdding(null)
        },
        onDismiss = { onAdding(null) },
        confirmEnabled = name.isNotEmpty() && !taken && address.isNotEmpty() && !badAddress,
    ) {
        adding?.let { draft -> RvFieldStack { AddServerFields(draft, taken, badAddress, onAdding) } }
    }
}

@Composable
private fun AddServerFields(draft: RvDnsServer, taken: Boolean, badAddress: Boolean, onAdding: (RvDnsServer?) -> Unit) {
    RvTextField(
        draft.name,
        { onAdding(draft.copy(name = it)) },
        label = stringResource(R.string.rv_group_edit_name),
        error = if (taken) stringResource(R.string.rv_dns_server_name_taken) else null,
    )
    RvTextField(
        draft.address,
        { onAdding(draft.copy(address = it)) },
        label = stringResource(R.string.rv_dns_server_address),
        error = if (badAddress) stringResource(R.string.rv_dns_server_address_bad) else null,
    )
}

/** honk's DNS upstream schemes (`DnsProtocol`, and `tcp+udp`), each followed by a host. */
internal fun rvDnsAddressValid(address: String): Boolean {
    val scheme = address.substringBefore("://", missingDelimiterValue = "")
    val rest = address.substringAfter("://", missingDelimiterValue = "")
    return scheme.lowercase() in DNS_SCHEMES && rest.isNotEmpty() && rest.none { it.isWhitespace() }
}

private val DNS_SCHEMES = setOf("udp", "tcp", "tcp+udp", "tls", "https", "h3", "quic")
