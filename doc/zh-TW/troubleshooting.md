# Android 上的 doona VPN 疑難排解

[English](../en/troubleshooting.md) | 繁體中文

下文的設定路徑以 Pixel 為準。選項名稱和位置會因 Android 版本和手機廠商而不同；路徑不同時，請在 **設定** 中搜尋選項名稱。

## VPN 在背景執行時中斷

使用手機時 VPN 正常，離開 doona 後就停止。

1. 在 Pixel 上開啟 **設定 > 應用程式 > 應用程式電池用量**，選擇 **doona**。如果找不到這個清單，改為開啟 **設定 > 應用程式 > 查看所有應用程式 > doona > 應用程式電池用量**。請參閱 [Pixel 說明][battery-help]。
2. 在 **允許在背景使用** 下，為 doona 改選 **無限制** 試試看。這是[系統的電池最佳化設定][battery-setting]，不會更動整支手機的 **省電模式**。
3. 重新連線，再做一次先前導致中斷的操作。如果沒有改善，請改回 **最佳化**，以免額外耗電。

## 手機一直在背景停止 doona

已調整 Android 對 doona 的電池最佳化，VPN 仍會停止。

1. 在手機廠商的電池或應用程式管理設定中，尋找個別應用程式的控制項，例如背景活動、休眠應用程式或自動啟動。
2. 如果其中有限制 doona 的項目，允許 doona 在背景執行，或把它移出休眠應用程式清單。各廠商的操作方式請見 [Don't Kill My App][vendor]。
3. 開啟 doona 重新連線，關閉螢幕後再測試一次。不要更動其他應用程式的設定。

## VPN 不會自動重新連線

VPN 意外停止後，必須開啟 doona 才能重新連線。

1. 在 doona 中連線一次；如果 Android 詢問是否允許建立 VPN 連線，請同意。
2. 在 Pixel 上開啟 **設定 > 網路和網際網路 > VPN**，輕觸 **doona** 旁的齒輪圖示，開啟 **一律開啟的 VPN**。請參閱 [Android 說明][vpn-help]。
3. 確認 doona 的 **活動** 顯示 **已連線**。doona 接受由 Android 發起的啟動；在 Android 啟動的工作階段中，VPN 引擎停止後 doona 會重試。手動啟動的工作階段在引擎停止後不會重試。

## VPN 中斷時無法上網

VPN 中斷時應用程式無法連上網際網路；或即使 VPN 已連線，不經過 VPN 的應用程式仍無法連線。

1. 在 Pixel 上開啟 **設定 > 網路和網際網路 > VPN**，輕觸 **doona** 旁的齒輪圖示。
2. 若要禁止應用程式在 VPN 以外連線，先開啟 **一律開啟的 VPN**，再開啟 **封鎖未使用 VPN 的連線**。如果 Android 顯示警告，請確認。doona 不會因為這項設定已開啟就拒絕啟動 VPN。Android 會封鎖 VPN 以外的流量，包括你用個別應用程式路由排除在 VPN 外的其他應用程式。請參閱 [Android 的封鎖連線說明][lockdown]。
3. 如果某個應用程式需要上網，請把它納入 VPN，並確認 doona 的 **活動** 顯示 **已連線**。只有在你願意讓應用程式在 VPN 以外連線時，才關閉 **封鎖未使用 VPN 的連線**。**一律開啟的 VPN** 可以保持開啟。

## 重新開機後 VPN 沒有開啟

手機重新啟動後，VPN 仍是關閉的。

1. 重新啟動前先設定好[一律開啟的 VPN](#vpn-不會自動重新連線)。doona 沒有另外的開機自動啟動功能，而是[由 Android 啟動一律開啟的 VPN][always-on]。
2. 重新啟動後解鎖手機，等 Wi-Fi 或行動數據連上，再確認 doona 的 **活動** 顯示 **已連線**。
3. 如果 VPN 仍未開啟，確認 Android 的一律開啟 VPN 仍是 doona。如果一律開啟已關閉，請在 doona 的 **活動** 連線。

## 在 Wi-Fi 和行動數據之間切換時連線卡住

手機切換網路後，網頁、通話或下載停住不動。

1. 在 Pixel 上查看 **設定 > 網路和網際網路 > 網際網路**，確認已有連線。請參閱 [Pixel 的連線說明][wifi-help]。
2. 新網路連上後，再重試網頁、通話或下載。網路變更時，doona 會嘗試更新它用來解析代理伺服器主機名稱的 DNS 伺服器。這項更新不會把既有連線移到新網路上。
3. 如果新的連線仍然失敗，請在 doona 的 **活動** 重新連線後再試。

## 公共 Wi-Fi 要求登入

手機已連上公共 Wi-Fi，但必須先在登入頁面（captive portal）登入或接受條款，才能上網。

1. 開啟 Android 的 **登入 Wi-Fi 網路** 通知，在那裡完成登入；doona 本身不會顯示登入提示。
2. 如果 Android 沒有顯示提示，請重新加入這個網路：在 Pixel 上開啟 **設定 > 網路和網際網路 > 網際網路**，再選一次這個網路。請參閱 [Pixel 的公共 Wi-Fi 登入說明][wifi-help]。
3. Android 完成登入後，再重試連線。如果登入頁面仍然打不開，請改用行動數據或其他網路，不要關閉 VPN 防護。

## 私人 DNS 出錯或規則沒有比對到網域

網站無法透過網域名稱載入；或在使用私人 DNS 供應商主機名稱時，某個網域的規則沒有比對到。

1. 在 Pixel 上開啟 **設定 > 網路和網際網路 > 私人 DNS**，記下目前的設定和主機名稱。請參閱 [Android 說明][private-dns]。
2. 如果網路政策允許，選擇 **自動**，輕觸 **儲存** 後再試。doona 不接受連往它提供給 Android 的那個 DNS 伺服器的 DNS-over-TLS 連線。無法使用加密 DNS 時，**自動** 可能改用一般的未加密 DNS。
3. 如果需要指定供應商，測試完請改回 **私人 DNS 供應商主機名稱**。如果某個應用程式使用加密 DNS 而繞過 doona 的 DNS 伺服器，doona 可能無從得知網域名稱。在規則模式下，不需要網域名稱的規則仍可能比對成功；沒有任何規則比對成功時，改由主要代理群組處理。

## 只支援 IPv6 的網站無法載入

需要 IPv6 的網站在不開 VPN 時可以使用，但在經由 doona 路由的應用程式中無法使用。

1. 如果網站或服務有支援 IPv4 的版本，請改用該版本。doona VPN 只傳送 IPv4。對經由它路由的應用程式，[Android 會封鎖 IPv6][ipv6]，不會讓 IPv6 流量繞過通道送出。
2. 如果服務必須使用 IPv6，而你又需要 VPN 防護，請改用支援 IPv6 的 VPN。

## 手機閒置後流量卡住

手機未接電源且螢幕關閉一段時間後，連線停止回應。手機閒置時，[Android 的 Doze 模式][doze]可能會暫停網路存取。

1. 喚醒並解鎖手機，再重試網頁或連線。
2. 如果 VPN 已停止，請開啟 doona 的 **活動** 重新連線。
3. 如果問題反覆出現，請對 doona 試試[電池最佳化的步驟](#vpn-在背景執行時中斷)後再測試。豁免只會解除部分限制，不會關閉 Doze。

[battery-help]: https://support.google.com/pixelphone/answer/7015477?hl=en
[battery-setting]: https://developer.android.com/reference/android/provider/Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
[vendor]: https://dontkillmyapp.com/
[vpn-help]: https://support.google.com/android/answer/9089766?hl=en
[lockdown]: https://developer.android.com/develop/connectivity/vpn#blocked_connections
[always-on]: https://developer.android.com/develop/connectivity/vpn#always-on
[wifi-help]: https://support.google.com/pixelphone/answer/6183600?hl=en
[private-dns]: https://support.google.com/android/answer/9654714?hl=en
[ipv6]: https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int)
[doze]: https://developer.android.com/training/monitoring-device-state/doze-standby
