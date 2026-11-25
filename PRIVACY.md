# Privacy

doona has no account, no analytics and no advertising. It does not send anything about you or your phone to its
developers.

## What leaves the phone

doona connects to these places, and only these:

- **Your subscriptions.** doona downloads each subscription link you add, directly from the address in the link.
- **Your proxies.** While doona is connected, apps' traffic goes through the proxies your subscriptions list, or
  directly, as your rules decide. DNS lookups go to the servers set in Settings › DNS (by default 1.1.1.1 and 8.8.8.8).
- **Latency tests.** A latency test fetches `https://www.gstatic.com/generate_204` through each proxy it tests.
- **GeoIP and GeoSite data.** To update the data the rules read, doona asks GitHub's API for the latest release of
  [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) and downloads it from
  `raw.githubusercontent.com` or `cdn.jsdelivr.net`, through your main proxy. This happens when you tap Update, and
  once a week by itself while doona is connected.
- **Check for updates.** Only when you tap it, doona asks GitHub's API for this repository's latest release
  (`GET https://api.github.com/repos/Zakkaus/doona-android/releases/latest`). A newer release opens in your browser;
  doona downloads nothing itself.

## Reports

Report a problem builds a file on the phone. It leaves the phone only if you share it, and only to the app you share
it with. Before you share it, you choose what it leaves out; it never contains subscription links or tokens.

## Settings and data on the phone

Your subscriptions, settings and logs stay in doona's private storage on the phone. Uninstalling doona deletes them.

---

# 隱私

doona 沒有帳號、沒有分析、沒有廣告，不會把你或手機的任何資料傳給開發者。

## 會離開手機的資料

doona 只會連到下列位置：

- **你的訂閱。** doona 直接從訂閱網址下載你新增的每個訂閱。
- **你的代理。** 連線時，App 的流量依你的規則經由訂閱裡的代理或直接連線送出。DNS 查詢送到「設定 › DNS」所設的伺服器（預設
  1.1.1.1 和 8.8.8.8）。
- **延遲測試。** 延遲測試會經由每個受測代理讀取 `https://www.gstatic.com/generate_204`。
- **GeoIP 與 GeoSite 資料。** 更新規則所用的資料時，doona 向 GitHub API 查詢
  [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) 的最新版本，再經由主節點從
  `raw.githubusercontent.com` 或 `cdn.jsdelivr.net` 下載。你點「更新」時會這樣做，連線期間也會每週自動更新一次。
- **檢查更新。** 只有你點「檢查更新」時，doona 才向 GitHub API 查詢本專案的最新版本
  （`GET https://api.github.com/repos/Zakkaus/doona-android/releases/latest`）。有新版時會用瀏覽器打開，doona 本身不下載任何檔案。

## 回報問題

「回報問題」只在手機上產生檔案。只有你分享時它才會離開手機，而且只送到你選擇的 App。分享前你可以選擇遮蔽哪些內容；回報一律不含訂閱網址和
Token。

## 手機上的設定與資料

訂閱、設定和日誌都存在 doona 的私有儲存空間，解除安裝 doona 就會刪除。
