# Troubleshoot the doona VPN on Android

The Settings paths below are for Pixel. Names and locations can vary by Android version and phone maker. If a path differs, search Settings for the option name.

## VPN stops in the background

The VPN works while you use the phone but stops after you leave doona.

1. On Pixel, open **Settings > Apps > App battery usage** and select **doona**. If the list is missing, open **Settings > Apps > See all apps > doona > App battery usage** instead. See [Pixel Help][battery-help].
2. Under **Allow background usage**, try **Unrestricted** for doona. This is a [system battery optimization setting][battery-setting], not a change to Battery Saver for the whole phone.
3. Reconnect and repeat what caused the drop. If the change does not help, restore **Optimized** to avoid extra battery use.

## The phone keeps stopping doona in the background

The VPN still stops after you adjust Android's battery optimization for doona.

1. Check your phone maker's battery or app management settings for per-app controls. Look for background activity, sleeping apps, or automatic startup.
2. If a control restricts doona, allow it to run in the background or remove it from the sleeping-app list. Find instructions for your phone maker at [Don't Kill My App][vendor].
3. Open doona, reconnect, and test again with the screen off. Leave settings for other apps unchanged.

## VPN does not reconnect automatically

The VPN stops unexpectedly and you have to open doona to reconnect.

1. Connect in doona once and approve Android's VPN request if prompted.
2. On Pixel, open **Settings > Network & internet > VPN**. Tap the gear next to **doona** and turn on **Always-on VPN**. See [Android Help][vpn-help].
3. Check **Activity** in doona for **Connected**. The app accepts starts from Android and retries if the VPN engine stops during a session started by Android. A manually started session does not retry after an engine stop.

## Internet stops when the VPN disconnects

Apps cannot reach the internet when the VPN is down, or an app outside the VPN cannot connect even while it is up.

1. On Pixel, open **Settings > Network & internet > VPN** and tap the gear next to **doona**.
2. To prevent apps from connecting outside the VPN, turn on **Always-on VPN**, then turn on **Block connections without VPN**. Confirm Android's warning if prompted. The app does not reject VPN starts because this setting is on. Android blocks traffic outside the VPN, including from other apps you excluded with per-app routing. See [Android's blocked-connections guide][lockdown].
3. If an app needs internet access, include it in the VPN and check **Activity** in doona for **Connected**. Turn off **Block connections without VPN** only if you're willing to let apps connect outside the VPN. You can leave **Always-on VPN** on.

## VPN is off after a reboot

The phone restarts but the VPN stays off.

1. Set up [Always-on VPN](#vpn-does-not-reconnect-automatically) before restarting. The app has no separate start-on-boot feature; [Android starts the Always-on VPN][always-on].
2. After restarting, unlock the phone, wait for Wi-Fi or mobile data, and check that **Activity** in doona shows **Connected**.
3. If the VPN is still off, check that Android still lists doona as the Always-on VPN. If Always-on is off, connect from **Activity** in doona.

## Connections stall when switching between Wi-Fi and mobile data

A page, call, or download stalls after the phone changes networks.

1. On Pixel, check **Settings > Network & internet > Internet** for a connection. See [Pixel's connection help][wifi-help].
2. Retry the page, call, or download once the new network is up. The app tries to update the DNS servers it uses to look up proxy server names when the network changes. That update does not move an existing connection to the new network.
3. If new connections still fail, reconnect from **Activity** in doona and try again.

## Public Wi-Fi asks you to sign in

The phone joins public Wi-Fi but cannot get online until you sign in or accept terms on a page called a captive portal.

1. Open Android's **Sign in to Wi-Fi network** notification and complete the sign-in there; doona does not show a sign-in prompt of its own.
2. If Android shows no prompt, rejoin the network. On Pixel, open **Settings > Network & internet > Internet** and select the network again. See [Pixel's public Wi-Fi sign-in help][wifi-help].
3. Once Android finishes signing in, retry your connection. If the sign-in page still won't open, use mobile data or a different network rather than turning off VPN protection.

## Private DNS errors or rules miss a domain

Sites fail to load by name, or a rule for a domain misses while you use a Private DNS provider hostname.

1. On Pixel, open **Settings > Network & internet > Private DNS**. Note the current setting and hostname. See [Android Help][private-dns].
2. If your network policy allows it, select **Automatic**, tap **Save**, and retry. The app declines DNS-over-TLS connections to the DNS server it presents to Android. **Automatic** can fall back to regular, unencrypted DNS when encrypted DNS is unavailable.
3. If you need a specific provider, restore **Private DNS provider hostname** after the test. If an app uses encrypted DNS that skips doona's DNS server, doona may not learn the domain name. In rule mode, a rule that does not need a domain name can still match. If no rule matches, the main proxy group is the fallback.

## IPv6-only sites do not load

A site that requires IPv6 works without the VPN but not in an app routed through doona.

1. Use an IPv4-capable version of the site or service if one is available. The doona VPN carries only IPv4. [Android blocks IPv6][ipv6] for apps routed through it rather than sending it around the tunnel.
2. If the service requires IPv6 and you need VPN protection, use a VPN that carries IPv6.

## Traffic stalls after the phone has been idle

A connection stops responding after the phone sits unplugged with its screen off. [Android's Doze mode][doze] can suspend network access while the phone is idle.

1. Wake and unlock the phone, then retry the page or connection.
2. If the VPN has stopped, open **Activity** in doona and reconnect.
3. If the issue repeats, try the [battery optimization steps](#vpn-stops-in-the-background) for doona and test again. An exemption lifts only some restrictions; it does not turn Doze off.

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
