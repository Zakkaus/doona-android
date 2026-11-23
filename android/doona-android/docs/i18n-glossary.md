# i18n glossary (en / zh-TW / zh-CN)

Terminology for `android/doona-android/app/src/main/res/values*` and
`app/src/debug/res/values*`, aligned with the doona web catalogues
(`/home/zakk/code/doona/src/i18n/locales/*.json`) and, where the web app has no
equivalent, the lite app (`apps/lite/src/i18n`). The **doona key** column names the
catalogue entry that fixes the term; `lite:` marks a lite-app key. This table
deliberately holds both scripts, one per locale column.

Rows marked **owner check** are terms where the aligned word would change an approved RV
screen (`values-zh-rTW/strings_rv*.xml`); the change is not applied. See
[`i18n-doona-alignment.md`](i18n-doona-alignment.md).

| Concept | English | zh-TW | zh-CN | doona key | Android resources |
| --- | --- | --- | --- | --- | --- |
| Node | Node | 節點 | 节点 | `nav.nodes` | `nav_nodes`, `rv_nodes_title` |
| Subscription | Subscription | 訂閱 | 订阅 | `nodes.addProvider` | `sub_title`, `rv_subscriptions` |
| Outbound | Outbound | 出站 | 出站 | `act.mode` | `act_mode`, `act_global` |
| Rule | Rule | 規則 | 规则 | `nav.rules` | `nav_rules`, `mode_rule`, `rv_mode_rule` |
| Rule set | Rule set | — | — | — | none in doona or the app |
| Policy | Policy | 策略 | 策略 | `nav.policies` | `nav_policies` |
| Policy group | Policy group | 策略群組 | 策略组 | `dashboard.policyGroups` | `rv_policy_groups` (owner check) |
| Main node | Main node | 主節點 | 主节点 | `act.setup.connectionHint` | `rv_main_node`, `rv_nodes_use` (owner check) |
| Proxy | Proxy | 代理 | 代理 | `rule.template.group.proxy` | `rv_stats_proxy`, `rv_policy_no_proxy` |
| Direct | Direct | 直連 | 直连 | `mode.direct` | `mode_direct`, `rv_stats_direct` |
| Reject | Reject | 拒絕 | 拒绝 | `rule.dns.action.rejectQuery` | none (the app has no reject target) |
| Block | Block | 封鎖 | 屏蔽 | `rule.template.blockAds` | `rv_policy_block_ads` (owner check) |
| Global | Global | 全域 | 全局 | `mode.global` | `mode_global`, `act_global` |
| Rule mode | Rule | 規則 | 规则 | `mode.rule` | `mode_rule`, `rv_mode_rule` |
| Latency | Latency | 延遲 | 延迟 | `act.latency` | `act_latency`, `rv_stats_latency` |
| Test latency | Test latency | 測試延遲 | 测试延迟 | `lite:testLatency` | `rv_test_latency` |
| Traffic | Traffic | 流量 | 流量 | `act.traffic` | `rv_traffic_title`, `rv_stats_quota` |
| Upload | Upload | 上傳 | 上传 | `ui.upload` | `ui_upload` |
| Download | Download | 下載 | 下载 | `ui.download` | `ui_download` |
| Connection | Connection | 連線 | 连接 | `nav.connections` | `rv_nav_connections`, `rv_this_connection` |
| Connections | Connections | 連線 | 连接 | `nav.connections` | `act_active`, `rv_conn_summary` |
| Disconnect | Disconnect | 中斷連線 | 断开连接 | `event.disconnected` | `vpn_action_disconnect`, `vpn_failed_title` |
| DNS | DNS | DNS | DNS | `nav.dns` | `rv_settings_dns_server` |
| Per-app | Per-app | 依 App | 按应用 | — | `perapp_title`, `rv_settings_per_app` |
| LAN | LAN | 區網 | 局域网 | `rule.processHelp` | `rv_policy_lan`, `rv_policy_lan_kind` |
| Update | Update | 更新 | 更新 | `nodes.refresh` | `sub_update`, `rv_menu_update` |
| Import | Import | 匯入 | 导入 | `config.revisions.origin.import` | `rv_add_import`, `rv_add_importing` |
| Export | Export | 匯出 | 导出 | `log.export` | `rv_stats_export`, `rv_effective_export` |
| Diagnostics | Diagnostics | 診斷 | 诊断 | `config.diagnostics` | `rv_stats_export`, `rv_settings_export_diagnostics` |
| Log level | Log level | 日誌等級 | 日志级别 | `settings.logLevel` | `rv_settings_log_level` |
| Settings | Settings | 設定 | 设置 | `nav.settings` | `hub_settings` |
| Theme | Theme | 主題 | 主题 | `ui.theme` | `rv_settings_theme` |
| Light | Light | 亮色 | 浅色 | `theme.light` | `rv_settings_theme_light` (owner check) |
| Dark | Dark | 暗色 | 深色 | `theme.dark` | `rv_settings_theme_dark` (owner check) |
| Follow system | Follow system | 跟隨系統 | 跟随系统 | `theme.system` | `rv_settings_theme_system` |
| Language | Language | 語言 | 语言 | `ui.lang` | `rv_settings_language` |
| About | About | 關於 | 关于 | `settings.about` | `rv_settings_group_about` |
| Advanced | Advanced | 進階 | 高级 | `nodes.manual.advanced` | `rv_settings_group_advanced` |
| Version | Version | 版本 | 版本 | `ov.config` | `rv_settings_version` |
| Pinned | Pinned | 已手動固定 | 已手动固定 | `policy.overridden` | `policy_pinned` |
| Return to automatic | Back to automatic | 恢復自動選擇 | 恢复自动选择 | `policy.releaseOverride` | `policy_unpin` |
| Includes (filter) | Includes | 選入條件 | 选入条件 | `policy.includes` | `policy_includes` |
| No groups | No groups | 沒有群組 | 暂无组 | `policy.empty` | `policy_empty_title` |
| Subgroup | Groups (nested) | 子群組 | 子组 | `group.nestedGroups` | `policy_groups_inside` |
| First available | First available | 依序備援 | 按顺序备用 | `policy.kind.fallback` | `policy_kind_fallback` |
| Manual | Manual | 手動選擇 | 手动选择 | `policy.kind.selector` | `policy_kind_selector` |
| Automatic | Automatic | 自動選擇 | 自动选择 | `policy.kind.auto` | `policy_kind_auto` |
| Fastest on average | Fastest on average | 平均最快 | 平均最快 | `policy.kind.urltest` | `policy_kind_urltest` |
| Fixed | Fixed | 固定 | 固定 | `policy.kind.fixed` | `policy_kind_fixed` |
| Test all | Test all | 測試全部 | 测试全部 | `policy.probeAll` | `policy_test_all` |
| Filter nodes | Filter nodes | 篩選節點 | 筛选节点 | `policy.filter` | `policy_filter` |
| Available only | Available only | 只顯示可用節點 | 只显示可用节点 | `policy.aliveOnly` | `policy_alive_only` |
| All regions | All regions | 全部地區 | 全部地区 | `policy.allRegions` | `policy_all_regions` |
| Sort | Sort | 排序 | 排序 | `policy.sort` | `policy_sort` |
| By latency | By latency | 依延遲 | 按延迟 | `policy.byLatency` | `policy_by_latency` |
| Both | Both | 兩者 | 两者 | `policy.both` | `policy_both` |
| Active connections | Active connections | 活動連線 | 活动连接 | `act.active` | `act_active` |
| In use | In use | 使用中 | 使用中 | `conn.latency.inUse` | `rv_nodes_in_use` (owner check) |
| Port | Port | 連接埠 | 端口 | `nodes.manual.port` | `rv_nodes_port` (owner check) |
| Protocol | Protocol | 協定 | 协议 | `nodes.latency.byProtocol` | `rv_nodes_protocol` |
| Median | Median | 中位數 | 中位数 | `dns.chart.median` | `rv_stats_median` |
| Timed out | Timed out | 逾時 | 超时 | `policy.probeReason.deadline` | `rv_nodes_timed_out` |
| Statistics | Statistics | 統計 | 统计 | `dns.tab.stats` | `rv_stats` |
| Save | Save | 儲存 | 保存 | `settings.save` | `rv_save` |
| Close connection | Close connection | 關閉連線 | 关闭连接 | `conn.close` | `rv_conn_close` |
| Subscription quota | Subscription quota | 訂閱額度 | 订阅额度 | `dashboard.providerBudget` | `rv_stats_quota` (label: Subscription data) |
| Expires | Expires | 到期 | 到期 | `nodes.expires` | `rv_expires`, `rv_sub_expires` |
| Updated | Updated | 更新時間 | 更新时间 | `nodes.updated` | `sub_updated`, `rv_updated` |

## Notes

- `values-zh-rCN` and `values-zh-rTW` (main and debug) carry the same resource names as
  their `values` counterpart, minus entries marked `translatable="false"` (`app_name`,
  `ui_tcp`, `ui_udp`, `act_rate`) and names whose text is not language-dependent.
- Chinese plurals keep only `<item quantity="other">`; Android requires no other
  quantity for `zh-rTW` or `zh-rCN`.
- `light`/`dark`: the web catalogue (`theme.light` 亮色, `theme.dark` 暗色) and the lite
  app (淺色, 深色) disagree; the RV screen follows the lite wording, which needs the
  owner's confirmation before the catalogue is called consistent.
