# Privacy

doona has no account, no analytics and no advertising. It sends nothing to its developers.

## What leaves the phone

doona connects to these places:

- **Your subscriptions.** doona downloads each subscription link you add, directly from the address in the link, not
  through a proxy. With auto-update on (the default), it downloads a subscription again once it is older than the update
  interval (12 hours by default), whether or not doona is connected.
- **Your proxies.** While doona is connected, apps' traffic goes through the proxies your subscriptions list, or
  directly, as your rules decide. DNS lookups go to the servers set in Settings › DNS (by default 1.1.1.1 and 8.8.8.8).
  doona looks up the host names of proxies, latency tests and subscriptions with your phone network's DNS servers,
  directly.
- **Latency tests.** A latency test fetches `https://www.gstatic.com/generate_204` through each node it tests.
- **GeoIP and GeoSite data.** To update the data its rules use, doona asks GitHub's API for the latest release of
  [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) and for its `release` branch, then downloads
  the files from `raw.githubusercontent.com`, `cdn.jsdelivr.net` or `testingcf.jsdelivr.net`, all through your main
  proxy. This happens when you tap Update, and by itself while doona is connected, on the schedule set under
  Auto-update: Off, Daily or Weekly (the default).
- **Check for updates.** Only when you tap it, doona asks GitHub's API for this repository's latest release
  (`GET https://api.github.com/repos/Zakkaus/doona-android/releases/latest`). A newer release is shown in Settings;
  tapping it opens the releases page in your browser. doona downloads nothing itself.

Requests to subscriptions and for GeoIP and GeoSite data carry the User-Agent `doona/<version> (clash.meta; mihomo)`, or
the User-Agent you set for that subscription. Check for updates sends `doona-android`.

## Reports

Report a problem builds a file on the phone. It leaves the phone only if you share it, and only to the app you share
it with. The report always contains doona's version, the Android version, and the phone's maker and model. By default
it also contains app names, and leaves out proxy addresses, the phone's own IP addresses and the domains you visited;
you choose these before you share it. It never contains subscription links or tokens.

Report a problem also offers a GitHub issue. Its page opens in your browser with doona's version, the Android version
and the phone's maker and model filled in; after a failure it also lists recent events, under the same choices as the
report.

## Settings and data on the phone

Your subscriptions, settings and logs stay in doona's private storage on the phone. Uninstalling doona deletes them.

---

# 隱私

doona 沒有帳號、沒有分析、沒有廣告，不會傳送任何資料給開發者。

## 會離開手機的資料

doona 會連到下列位置：

- **你的訂閱。** doona 直接從訂閱網址下載你新增的每個訂閱，不經由代理。開啟自動更新時（預設開啟），訂閱超過更新間隔（預設 12 小時）就會重新下載，不論 doona 是否連線。
- **你的代理。** 連線時，App 的流量依你的規則經由訂閱裡的代理或直接連線送出。DNS 查詢送到「設定 › DNS」所設的伺服器（預設
  1.1.1.1 與 8.8.8.8）。代理、延遲測試與訂閱的主機名稱，doona 直接用手機網路的 DNS 伺服器查詢。
- **延遲測試。** 延遲測試會經由每個受測節點讀取 `https://www.gstatic.com/generate_204`。
- **GeoIP 與 GeoSite 資料。** 更新規則所用的資料時，doona 向 GitHub API 查詢
  [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) 的最新版本與 `release` 分支，再從
  `raw.githubusercontent.com`、`cdn.jsdelivr.net` 或 `testingcf.jsdelivr.net` 下載檔案，全程經由主節點。你點「更新」時會這樣做，
  連線期間也會依「自動更新」所設的頻率自動執行：關閉、每天或每週（預設）。
- **檢查更新。** 只有你點「檢查更新」時，doona 才向 GitHub API 查詢本專案的最新版本
  （`GET https://api.github.com/repos/Zakkaus/doona-android/releases/latest`）。有新版時會顯示在設定頁，點擊後才用瀏覽器開啟版本頁。
  doona 本身不下載任何檔案。

訂閱與 GeoIP、GeoSite 資料的請求帶有 User-Agent `doona/<版本> (clash.meta; mihomo)`，或你為該訂閱設定的 User-Agent。檢查更新送出
`doona-android`。

## 回報問題

「回報問題」只在手機上產生檔案。只有你分享時它才會離開手機，而且只送到你選擇的 App。回報一律包含 doona 版本、Android 版本、手機廠牌與型號。
預設也包含 App 名稱，不包含代理位址、手機本身的 IP 位址與你造訪的網域；分享前可以更改這些選項。回報一律不含訂閱網址與 Token。

「回報問題」也可以建立 GitHub issue。它會用瀏覽器開啟頁面，並預先填入 doona 版本、Android 版本、手機廠牌與型號；發生錯誤後還會列出最近的事件，
遮蔽選項與回報相同。

## 手機上的設定與資料

訂閱、設定與日誌都存在 doona 的私有儲存空間，解除安裝 doona 就會刪除。

---

# 隐私

doona 没有账号、没有分析、没有广告，不会向开发者发送任何数据。

## 会离开手机的数据

doona 会连接下列位置：

- **你的订阅。** doona 直接从订阅地址下载你添加的每个订阅，不经过代理。开启自动更新时（默认开启），订阅超过更新间隔（默认 12 小时）就会重新下载，无论 doona 是否连接。
- **你的代理。** 连接时，App 的流量按你的规则经由订阅里的代理或直接连接发出。DNS 查询发往「设置 › DNS」所设的服务器（默认
  1.1.1.1 和 8.8.8.8）。代理、延迟测试和订阅的主机名，doona 直接用手机网络的 DNS 服务器查询。
- **延迟测试。** 延迟测试会经由每个受测节点读取 `https://www.gstatic.com/generate_204`。
- **GeoIP 与 GeoSite 数据。** 更新规则所用的数据时，doona 向 GitHub API 查询
  [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) 的最新版本与 `release` 分支，再从
  `raw.githubusercontent.com`、`cdn.jsdelivr.net` 或 `testingcf.jsdelivr.net` 下载文件，全程经由主节点。你点「更新」时会这样做，
  连接期间也会按「自动更新」所设的频率自动执行：关闭、每天或每周（默认）。
- **检查更新。** 只有你点「检查更新」时，doona 才向 GitHub API 查询本项目的最新版本
  （`GET https://api.github.com/repos/Zakkaus/doona-android/releases/latest`）。有新版时会显示在设置页，点击后才用浏览器打开版本页。
  doona 本身不下载任何文件。

订阅和 GeoIP、GeoSite 数据的请求带有 User-Agent `doona/<版本> (clash.meta; mihomo)`，或你为该订阅设置的 User-Agent。检查更新发送
`doona-android`。

## 报告问题

「报告问题」只在手机上生成文件。只有你分享时它才会离开手机，而且只发送到你选择的 App。报告始终包含 doona 版本、Android 版本、手机厂商与型号。
默认也包含 App 名称，不包含代理地址、手机本身的 IP 地址和你访问的域名；分享前可以更改这些选项。报告始终不含订阅地址和 Token。

「报告问题」也可以创建 GitHub issue。它会用浏览器打开页面，并预先填入 doona 版本、Android 版本、手机厂商与型号；发生错误后还会列出最近的事件，
遮蔽选项与报告相同。

## 手机上的设置与数据

订阅、设置和日志都存在 doona 的私有存储空间，卸载 doona 就会删除。
