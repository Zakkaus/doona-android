# Glossary

One concept, one word per locale. "Web key" is the doona web locale key that sets the term; "-" means doona web has no key and the app's own existing usage decides.

| Concept | en | zh-TW | zh-CN | Web key |
|---|---|---|---|---|
| Block (ads, QUIC) | Block | 封鎖 | 屏蔽 | rule.template.blockAds, blockQuic |
| Block ads | Block ads | 封鎖廣告 | 屏蔽广告 | rule.template.blockAds |
| Block QUIC | Block QUIC | 封鎖 QUIC | 屏蔽 QUIC | rule.template.blockQuic |
| Site blocked by the GFW (censorship, not a filter) | blocked | 被封鎖 | 被封锁 | - |
| Local network | local network | 區域網路 (never 區網) | 局域网 | - (web writes 區網 once in rule.processHelp; do not copy) |
| Route / go through | through, via | 經由 (not 經過, not 進) | 经由 | nodes.fetchViaNode |
| Bypass proxy | not through proxy | 不經代理 | 不经代理 | - |
| Strategy group | group | 群組 / 策略群組 (never 組, 策略組) | 组 / 策略组 | dashboard.allGroups |
| Open-ended list link | View all | 檢視全部 | 查看全部 | act.viewAll |
| Matched rule (label) | Matched rule | 命中規則 | 命中规则 | conn.editRule |
| Match by (rule field) | Match by | 依據 | 依据 | rule.kind |
| Match (rule vs traffic, verb) | match | 比對 | 匹配 | - |
| Fastest on average | Fastest | 平均最快 | 平均最快 | policy.kind.urltest |
| Restore a revision | Restore | 復原 | 恢复 | config.revisions.origin.activate |
| Return to automatic | Return to automatic | 恢復自動選擇 | 恢复自动选择 | policy.backToAutomatic |
| Configuration file | configuration | 設定檔 | 配置文件 | act.readOnlyDocs |
| Main configuration file | Entry file | 主設定檔 | 主配置文件 | act.modeReadOnlyAction |
| Effective configuration | Effective config | 生效組態 (owner Q5) | 生效配置 | config.acceptedDiagnostics |
| Process | process | 行程 | 进程 | rule.processHelp |
| Port | Port | 連接埠 | 端口 | config.global.tproxy_port |
| Log (noun) | Log | 日誌 | 日志 | config.global.log_file |
| Record, history entry | record | 記錄 (owner Q4) | 记录 | config.revisions.createdAt |
| Return (data) | return | 回傳 | 返回 | config.validateNoMain |
| Save | Save | 儲存 | 保存 | - |
| Disconnect (state and action) | Disconnect | 中斷連線 (never 斷線) | 断开连接 | event.disconnected |
| Outbound mode | Outbound mode | 出站模式 | 出站模式 | act.mode |
| Global | Global | 全域 | 全局 | act.global |
| Direct | Direct | 直連 | 直连 | mode.direct |
| Permission | permission | 權限 | 权限 | ui.backend.permissionDenied |
| Share | Share | 分享 | 分享 | nodes.editNodeMissing |
| Add (a subscription, a rule to a list) | Add | 新增 | 添加 | act.setup.nodes |
| Create (new group, new rule) | New | 新增 | 新建 | rule.add, config.newSource |
| Not found | Not found | 找不到 | 找不到 | ui.backend.resourceNotFound |
| Not yet (empty state) | yet | 尚未 / 尚無 | 尚未 / 尚无 | act.noSubscriptions |
| In use | In use | 使用中 | 使用中 | conn.latency.inUse |
| Run (a program keeps working) | run | 運作 | 运行 | - |
| Search keyword | keyword | 關鍵字 | 关键词 (rule kind: 关键字, as web) | rule.kind.domainKeyword |
| App | app | App (owner Q1) | 应用 | settings.install (web: 應用程式) |
| Quick Settings panel | Quick Settings | 快速設定 | 快捷设置 | - (Android system names) |
| Not now (dialog) | Not now | 暫不 | 暂不 | - |
| Fetch / obtain | get | 取得 | 获取 | - |
