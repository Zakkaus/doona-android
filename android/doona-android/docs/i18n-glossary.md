# i18n glossary (en / zh-TW / zh-CN)

Terminology for `android/doona-android/app/src/main/res/values*`. The zh-CN column follows
the doona web catalogue (`src/i18n/locales/zh-CN.json`); the last column gives the web key
that fixes the term, or `—` when the web product has no equivalent. `values-zh-rCN` and
`values-zh-rTW` carry the same keys in the same order. Each table row deliberately holds both
scripts, one per locale column; that mixing is the point of the table.

## Navigation and status

| English | zh-TW | zh-CN | doona web key |
| --- | --- | --- | --- |
| Activity | 活動 | 活动 | `nav.activity` |
| Monitor | 觀測 | 观测 | `hub.traffic` |
| Routing | 路由 | 路由 | `hub.routing` |
| Settings | 設定 | 设置 | `nav.settings` |
| System status | 系統狀態 | 系统状态 | `nav.overview` |
| Policies | 策略 | 策略 | `nav.policies` |
| Nodes | 節點 | 节点 | `nav.nodes` |
| Rules | 規則 | 规则 | `nav.rules` |
| Search | 搜尋 | 搜索 | `shell.shortcutSearch` |
| Connected | 已連線 | 已连接 | `shell.backend.connected` |
| Connect | 連線 | 连接 | `shell.backend.connecting` |
| Disconnect | 斷線 | 断开 | `conn.close` |
| Not connected | 未連線 | 未连接 | — |
| Profile | 連線設定檔 | 配置方案 | `settings.profileName` |

## Routing and policies

| English | zh-TW | zh-CN | doona web key |
| --- | --- | --- | --- |
| Outbound mode | 出站模式 | 出站模式 | `act.mode` |
| Rule | 規則 | 规则 | `ui.rule` |
| Direct | 直連 | 直连 | `act.modeDirectHelp` |
| Global | 全域 | 全局 | `act.global` |
| Global outbound | 全域出站 | 全局出站 | `act.global` |
| Group | 群組 | 组 | `ui.group` |
| Policy group | 策略群組 | 策略组 | `dashboard.policyGroups` |
| Subgroup | 內含群組 | 内含组 | `group.nestedGroups` |
| Manual | 手動選擇 | 手动选择 | `policy.kind.selector` |
| Automatic | 自動選擇 | 自动选择 | `policy.kind.auto` |
| Fastest on average | 平均最快 | 平均最快 | `policy.kind.urltest` |
| First available | 依序備援 | 首个可用 | `group.policy.fallbackHint` |
| Fixed | 固定 | 固定 | `policy.kind.fixed` |
| Pinned | 已固定 | 已固定 | `policy.overridden` |
| Return to automatic | 回到自動 | 恢复自动选择 | `policy.releaseOverride` |
| Includes (filter) | 選入條件 | 包含条件 | `group.byRule` |
| Filter nodes | 篩選節點 | 筛选节点 | `nodes.referenced` |
| Available only | 只顯示可用節點 | 只显示可用节点 | `policy.aliveOnly` |
| Sort / By latency | 排序 / 依延遲 | 排序 / 按延迟 | `policy.sort`, `policy.byLatency` |
| All regions | 全部地區 | 全部地区 | `policy.allRegions` |
| Both (TCP and UDP) | 兩者 | 两者 | `policy.both` |
| Member | 成員 | 成员 | `policy.membersDown` |

## Nodes and subscriptions

| English | zh-TW | zh-CN | doona web key |
| --- | --- | --- | --- |
| Node | 節點 | 节点 | `config.draftNode` |
| Subscription | 訂閱 | 订阅 | `nodes.kind.subscription` |
| Add subscription | 新增訂閱 | 新增订阅 | `nodes.addProvider` |
| Subscription URL | 訂閱網址 | 订阅网址 | `nodes.url` |
| Import | 匯入 | 汇入 | `act.setup.nodesHint` |
| Link | 連結 | 连结 | `nodes.addNodeHelp` |
| Create | 建立 | 建立 | `group.create` |
| Update / Updated | 更新 / 更新於 | 更新 / 更新于 | `settings.geodataUpdated` |
| Expires / Expired | 到期 / 已過期 | 到期 / 已过期 | `nodes.expires`, `ui.backend.eventCursorExpired` |
| Traffic used | 已用 | 已用 | `act.traffic` |

## Metrics

| English | zh-TW | zh-CN | doona web key |
| --- | --- | --- | --- |
| Latency | 延遲 | 延迟 | `act.latency` |
| Fast / Fair / Slow | 良好 / 普通 / 緩慢 | 快速 / 一般 / 缓慢 | `latency.good` (app only) |
| Not tested | 未測試 | 未测试 | `rule.untested` |
| CPU usage | CPU 使用率 | CPU 使用率 | `act.cpu` |
| Upload / Download | 上傳 / 下載 | 上传 / 下载 | `widgets.outbounds` |
| Active connections | 活動連線 | 活动连接 | `act.active` |

## Platform and permissions

| English | zh-TW | zh-CN | doona web key |
| --- | --- | --- | --- |
| App | App | 应用 | — |
| Device | 裝置 | 设备 | — |
| Home screen | 主畫面 | 主屏幕 | — |
| Save | 儲存 | 保存 | `settings.save` |
| Change | 變更 | 更改 | `config.globalNote` |
| Local network | 區網 | 局域网 | `rule.processHelp` |
| Address | 位址 | 地址 | — |
| Access | 存取 | 访问 | — |
| Built-in | 內建 | 内置 | `settings.geodataResetHelp` |
| Incompatible | 不相容 | 不兼容 | — |
| Core (VPN binary) | 核心 | 核心 | — |
| Per-app | 依 App | 按应用 | — |
| Checked (checkbox) | 勾選 | 勾选 | `nodes.deleteSelected` |

## Deliberate differences from `opencc -c t2s.json`

The zh-CN files start from character conversion of zh-TW, so every remaining difference is a
Mainland term choice. Grouped:

- Settings vocabulary: `設定`→`设置`, `開啟設定`→`打开设置`, `儲存`→`保存`, `選填`→`可选`,
  `重設`→`重置`.
- Group and connection vocabulary: `群組`→`组`, `新增`→`添加`, `建立`→`创建`, `連線`→`连接`,
  `斷線`→`断开`, `中斷連線`→`断开连接`, `連線設定檔`→`配置方案`.
- Scope and wording: `全域`→`全局`, `區網`→`局域网`, `存取`→`访问`, `位址`→`地址`,
  `裝置`→`设备`, `主畫面`→`主屏幕`, `App`→`应用`, `連結`→`链接`, `匯入`→`导入`,
  `關鍵字`→`关键词`, `符合`→`匹配`, `清單`→`列表`, `內建`→`内置`, `不相容`→`不兼容`,
  `暫時不要`→`暂不`.
- Meaning, not transliteration: `依序備援`→`首个可用` (en "First available"),
  `選入條件`→`包含条件` (en "Includes"), `測試全部`→`全部测试`, `回到自動`→`恢复自动选择`
  (web `policy.releaseOverride`), `良好`→`快速`, `普通`→`一般` (en "Fast", "Fair"),
  `找不到項目`→`未找到项目`, `目前`→`当前`, `依延遲`→`按延迟`, `再試`→`重试`,
  `內含群組`→`内含组`.
- Sentences where zh-TW's casual phrasing became an explicit platform statement:
  `已安裝的 App 都不走 VPN`→`已安装的应用都没有设置为使用 VPN`, and
  `清單中的 App 都沒有安裝。`→`列表中的应用均未安装。`.
- Plurals keep only `<item quantity="other">`, as in zh-TW: Chinese has no singular form, and
  Android requires only `other` for zh-rCN.
