package dev.doona.android.prototype.rv.diag

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.samples.sampleText

/**
 * 總覽 (doona web's 系統狀態, `GET /runtime`, `/version`, `/runtime/memory`, `/capabilities`): one card per web card, each
 * with its in-card header. 狀態 is the web's status row (the engine's state, the configuration's revision, uptime, CPU
 * and the last reload); 引擎 the version, API, build, instance and times, its observability profile as the header's tag;
 * 流量計數 the open connections and bytes since the counters began; 記憶體 the process and its cgroup; 資料路徑 honk's
 * datapath on a phone, userspace; 後端能力 every feature honk offers. 匯出狀態 JSON and 重載 end the page. The web's
 * 未開啟的功能 card shows only when honk reports one; on a phone doona turns every feature on. The web's 暫停 and 恢復
 * are the VPN switch on a phone.
 */
@Composable
internal fun RvOverviewSample() {
    val format = rememberRvFormat()
    val reload = format.clock(RV_NOW - RELOADED_AGO)
    val started = format.clock(RV_NOW - UPTIME_S * MS)
    RvDiagPage(sampleText("總覽", "Overview"), caption = sampleText("運作中 / honk 0.9.3", "Active / honk 0.9.3")) {
        RvGutter {
            RvCard {
                RvCardHeader(sampleText("狀態", "Status"))
                RvStatRow(sampleText("組態版本", "Configuration"), "7f3c2a1")
                RvStatRow(sampleText("運作時間", "Uptime"), stringResource(R.string.rv_duration, (UPTIME_S / HOUR_S).toInt(), (UPTIME_S % HOUR_S / MINUTE_S).toInt()))
                RvStatRow(sampleText("CPU 使用率", "CPU"), "3.2%")
                RvStatRow(sampleText("上次重載", "Last reload"), reload + SEPARATOR + sampleText("已完成", "Succeeded"))
            }
            RvCard {
                RvCardHeader(sampleText("引擎", "Engine"), tag = sampleText("完整可觀測", "Full observability"))
                RvStatRow(sampleText("引擎", "Engine"), "honk 0.9.3")
                RvStatRow("API", sampleText("native v1（stable）", "native v1 (stable)"))
                RvStatRow(sampleText("建置", "Build"), "a348c47")
                RvStatRow(sampleText("目標平台", "Target"), "aarch64-linux-android")
                RvStatRow(sampleText("執行個體", "Instance"), "3f9e2c41", valueKeepWords = false)
                RvStatRow(sampleText("啟動時間", "Started"), started)
                RvStatRow(sampleText("組態生效", "Configuration active"), reload)
            }
            RvCard {
                RvCardHeader(sampleText("流量計數", "Traffic counters"))
                RvStatRow(sampleText("TCP 連線", "TCP connections"), "23")
                RvStatRow(sampleText("UDP 連線", "UDP connections"), "4")
                RvStatRow(sampleText("連線總數", "Total connections"), "27")
                RvStatRow(sampleText("上傳", "Upload"), format.bytes(UPLOAD).toString())
                RvStatRow(sampleText("下載", "Download"), format.bytes(DOWNLOAD).toString())
                RvStatRow(sampleText("速率統計間隔", "Rate window"), sampleText("1 秒", "1 s"))
                RvStatRow(sampleText("計數起點", "Counting since"), started + SEPARATOR + sampleText("可見流量", "Visible traffic"))
            }
            RvCard {
                RvCardHeader(sampleText("記憶體", "Memory"))
                RvStatRow(sampleText("常駐記憶體", "Resident memory"), format.bytes(RSS).toString())
                RvStatRow(sampleText("cgroup 已用", "cgroup used"), format.bytes(CGROUP).toString())
                RvStatRow(sampleText("cgroup 上限", "cgroup limit"), sampleText("未設上限", "No limit"))
                RvStatRow(sampleText("cgroup 範圍", "cgroup scope"), sampleText("本服務", "This service"))
                RvStatRow(sampleText("超過高水位次數", "Over high mark"), "0")
                RvStatRow(sampleText("OOM 次數", "OOM events"), "0")
                RvStatRow(sampleText("OOM 終止次數", "OOM kills"), "0")
            }
            RvCard {
                RvCardHeader(sampleText("資料路徑", "Datapath"))
                RvStatRow(sampleText("類型", "Kind"), sampleText("使用者空間", "Userspace"))
                RvStatRow(sampleText("狀態", "State"), sampleText("就緒", "Ready"))
                RvStatRow(sampleText("可見度", "Visibility"), sampleText("部分", "Partial"))
            }
            RvCard {
                RvCardHeader(sampleText("後端能力", "Backend features"))
                FEATURES.forEach { (zh, en) -> RvStatRow(sampleText(zh, en), sampleText("可用", "Available")) }
            }
            RvCard {
                RvDiagNavRow(R.drawable.rv_sym_download, sampleText("匯出狀態 JSON", "Export status JSON"))
                RvDiagNavRow(R.drawable.rv_sym_sync_alt, sampleText("重載", "Reload"))
            }
        }
    }
}

/** What `/capabilities` offers, in the web's 後端能力 order. */
private val FEATURES = listOf(
    "DNS 查詢" to "DNS query",
    "DNS 快取" to "DNS cache",
    "DNS 記錄" to "DNS log",
    "DNS 規則" to "DNS rules",
    "路由追蹤" to "Routing trace",
    "延遲探測" to "Latency probes",
    "出站計數" to "Outbound counters",
    "流量歷史" to "Traffic history",
    "記憶體歷史" to "Memory history",
    "組態驗證" to "Configuration check",
    "重新載入" to "Reload",
)

private const val MS = 1_000L
private const val UPTIME_S = 8_035L
private const val HOUR_S = 3_600L
private const val MINUTE_S = 60L
private const val RELOADED_AGO = 1_309_000L
private const val UPLOAD = 48_204_117L
private const val DOWNLOAD = 1_312_884_906L
private const val RSS = 86_412_288L
private const val CGROUP = 112_197_632L
