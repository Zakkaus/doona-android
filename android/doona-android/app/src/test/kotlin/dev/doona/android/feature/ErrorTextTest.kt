package dev.doona.android.feature

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ErrorTextTest {
    /** A failure banner says why with [reasonRes]: no two codes share a line, so no reason hides another. */
    @Test
    fun `every error code has its own reason line`() {
        val reasons = ErrorCode.entries.map { it.reasonRes() }
        assertEquals(ErrorCode.entries.size, reasons.toSet().size)
    }

    /** Each code reads its own sentence in every locale, so swapping two resources or two sentences fails here. */
    @Test
    fun `each error code says its own sentence in every locale`() {
        val expected = mapOf(
            ErrorCode.InvalidArgument to Triple(R.string.rv_reason_invalid_argument, "這個值無效。", "这个值无效。"),
            ErrorCode.NotFound to Triple(R.string.rv_reason_not_found, "這個項目已不存在。", "这个项目已不存在。"),
            ErrorCode.Unsupported to Triple(R.string.rv_reason_unsupported, "honk 不支援這項操作。", "honk 不支持此操作。"),
            ErrorCode.DuplicateProviderName to Triple(R.string.rv_reason_duplicate_provider_name, "已有同名訂閱，請換個名稱。", "已有同名订阅，请换个名称。"),
            ErrorCode.FetchFailed to Triple(R.string.rv_reason_fetch_failed, "伺服器沒有回應。", "服务器没有响应。"),
            ErrorCode.TooLarge to Triple(R.string.rv_reason_too_large, "訂閱內容太大。", "订阅内容太大。"),
            ErrorCode.RequestTooLarge to Triple(R.string.rv_reason_request_too_large, "請求內容太大。", "请求内容太大。"),
            ErrorCode.ParseFailed to Triple(R.string.rv_reason_parse_failed, "無法解析訂閱內容。", "无法解析订阅内容。"),
            ErrorCode.NoNodes to Triple(R.string.rv_reason_no_nodes, "訂閱中沒有節點。", "订阅中没有节点。"),
            ErrorCode.Busy to Triple(R.string.rv_reason_busy, "另一項變更正在進行。", "另一项更改正在进行。"),
            ErrorCode.Internal to Triple(R.string.rv_reason_internal, "doona 發生內部錯誤。", "doona 发生内部错误。"),
            ErrorCode.LocalNetwork to Triple(R.string.rv_reason_local_network, "無法連線至區域網路上的位址。", "无法连接局域网中的地址。"),
            ErrorCode.DnsFailed to Triple(R.string.rv_reason_dns_failed, "DNS 查詢失敗。", "DNS 查询失败。"),
            ErrorCode.StorageFull to Triple(R.string.rv_reason_storage_full, "手機儲存空間不足。", "手机存储空间不足。"),
            ErrorCode.WriteFailed to Triple(R.string.rv_reason_write_failed, "無法寫入 doona 的資料檔。", "无法写入 doona 的数据文件。"),
            ErrorCode.Conflict to Triple(R.string.rv_reason_conflict, "已先套用較新的設定檔。", "已先应用较新的配置文件。"),
            ErrorCode.SourceLimit to Triple(R.string.rv_reason_source_limit, "檔案過多或過大。", "文件过多或过大。"),
            ErrorCode.NonNativeSource to Triple(R.string.rv_reason_non_native_source, "這不是 honk 設定檔。", "这不是 honk 配置文件。"),
            ErrorCode.ActivationFailed to Triple(R.string.rv_reason_activation_failed, "設定檔無法啟用。", "配置文件无法启用。"),
        )
        val english = mapOf(
            ErrorCode.InvalidArgument to "The value is not valid.",
            ErrorCode.NotFound to "It no longer exists.",
            ErrorCode.Unsupported to "honk does not support this action.",
            ErrorCode.DuplicateProviderName to "A subscription already uses this name. Choose another name.",
            ErrorCode.FetchFailed to "Its server did not answer.",
            ErrorCode.TooLarge to "Its content is too large.",
            ErrorCode.RequestTooLarge to "The request is too large.",
            ErrorCode.ParseFailed to "Its content could not be read.",
            ErrorCode.NoNodes to "It has no nodes.",
            ErrorCode.Busy to "Another change is in progress.",
            ErrorCode.Internal to "doona hit an internal error.",
            ErrorCode.LocalNetwork to "An address on the local network could not be reached.",
            ErrorCode.DnsFailed to "The DNS lookup failed.",
            ErrorCode.StorageFull to "The phone's storage is full.",
            ErrorCode.WriteFailed to "doona could not write its data file.",
            ErrorCode.Conflict to "A newer configuration was applied first.",
            ErrorCode.SourceLimit to "There are too many files, or they are too large.",
            ErrorCode.NonNativeSource to "It is not a honk configuration.",
            ErrorCode.ActivationFailed to "The configuration could not be activated.",
        )
        assertEquals(ErrorCode.entries.toSet(), expected.keys)
        val (tw, cn, en) = listOf(Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).map(::localized)
        for ((code, line) in expected) {
            val (res, zhTw, zhCn) = line
            assertEquals(code.name, res, code.reasonRes())
            assertEquals(code.name, zhTw, tw.getString(code.reasonRes()))
            assertEquals(code.name, zhCn, cn.getString(code.reasonRes()))
            assertEquals(code.name, english.getValue(code), en.getString(code.reasonRes()))
        }
    }

    private fun localized(locale: Locale): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
        return app.createConfigurationContext(config)
    }
}
