package com.plugin.vpnservice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import java.util.Arrays

import app.tauri.plugin.JSObject

class TauriVpnService : VpnService() {
    companion object {
        @JvmField var triggerCallback: (String, JSObject) -> Unit = { _, _ -> }
        @JvmField var self: TauriVpnService? = null
        @JvmField var ipv4Addr: String? = null
        @JvmField var routes: Array<String> = emptyArray()
        @JvmField var dns: String? = null

        const val IPV4_ADDR = "IPV4_ADDR"
        const val ROUTES = "ROUTES"
        const val DNS = "DNS"
        const val DISALLOWED_APPLICATIONS = "DISALLOWED_APPLICATIONS"
        const val MTU = "MTU"

        private const val NOTIFICATION_CHANNEL_ID = "easytier_vpn_channel"
        private const val NOTIFICATION_ID = 1356
    }

    private lateinit var vpnInterface: ParcelFileDescriptor

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        println("vpn on start command ${intent?.getExtras()} $intent")
        startVpnForegroundService()
        var args = intent?.getExtras()
        ipv4Addr = args?.getString(IPV4_ADDR)
        routes = args?.getStringArray(ROUTES) ?: emptyArray()
        dns = args?.getString(DNS)

        vpnInterface = createVpnInterface(args)
        println("vpn created ${vpnInterface.fd}")

        var event_data = JSObject()
        event_data.put("fd", vpnInterface.fd)
        triggerCallback("vpn_service_start", event_data)
        EasyTierVpnTileService.requestStateUpdate(this)

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        self = this
        println("vpn on create")
    }

    override fun onDestroy() {
        println("vpn on destroy")
        disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        self = null
        EasyTierVpnTileService.requestStateUpdate(this)
        super.onDestroy()
    }

    override fun onRevoke() {
        println("vpn on revoke")
        disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        self = null
        EasyTierVpnTileService.requestStateUpdate(this)
        super.onRevoke()
    }

    private fun disconnect() {
        if (self == this && this::vpnInterface.isInitialized) {
            triggerCallback("vpn_service_stop", JSObject())
            vpnInterface.close()
        }
        clearStatus()
    }

    private fun clearStatus() {
        ipv4Addr = null
        routes = emptyArray()
        dns = null
    }

    private fun startVpnForegroundService() {
        createNotificationChannel()

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("EasyTier VPN is running")
            .setContentText("VPN connection is active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EasyTier VPN",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createVpnInterface(args: Bundle?): ParcelFileDescriptor {
        var builder = Builder()
                .setSession("TauriVpnService")
                .setBlocking(false)
        
        var mtu = args?.getInt(MTU) ?: 1500
        var ipv4Addr = args?.getString(IPV4_ADDR) ?: "10.126.126.1/24"
        var dns: String? = args?.getString(DNS)
        var routes = args?.getStringArray(ROUTES) ?: emptyArray()
        var disallowedApplications = args?.getStringArray(DISALLOWED_APPLICATIONS) ?: emptyArray()

        println("vpn create vpn interface. mtu: $mtu, ipv4Addr: $ipv4Addr, dns:" +
            "$dns, routes: ${java.util.Arrays.toString(routes)}," +
            "disallowedApplications:  ${java.util.Arrays.toString(disallowedApplications)}")

        val ipParts = ipv4Addr.split("/")
        if (ipParts.size != 2) throw IllegalArgumentException("Invalid IP addr string")
        builder.addAddress(ipParts[0], ipParts[1].toInt())
        builder.addAddress("fd00::1", 128)

        builder.setMtu(mtu)
        dns?.let { builder.addDnsServer(it) }

        for (route in routes) {
            val ipParts = route.split("/")
            if (ipParts.size != 2) throw IllegalArgumentException("Invalid route cidr string")
            builder.addRoute(ipParts[0], ipParts[1].toInt())
        }
        
        var allowedCount = 0
        try {
            val pm = packageManager
            val installedPackages = pm.getInstalledPackages(0)
            for (pkgInfo in installedPackages) {
                val pkgName = pkgInfo.packageName ?: continue
                if (shouldRouteApp(pkgName, packageName)) {
                    try {
                        builder.addAllowedApplication(pkgName)
                        allowedCount++
                        println("VPN allowed app: $pkgName")
                    } catch (e: Exception) {
                        println("Failed to add allowed app $pkgName: ${e.message}")
                    }
                }
            }
            println("VPN configured with $allowedCount allowed applications.")
        } catch (e: Exception) {
            println("Error querying installed packages: ${e.message}")
        }

        if (allowedCount > 0) {
            try {
                builder.addRoute("0.0.0.0", 0)
                println("VPN added default route 0.0.0.0/0 for allowed applications")
            } catch (e: Exception) {
                println("Failed to add 0.0.0.0/0 route: ${e.message}")
            }
            try {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")
                println("VPN added DNS servers 8.8.8.8 and 1.1.1.1")
            } catch (e: Exception) {
                println("Failed to add default DNS servers: ${e.message}")
            }
        } else {
            for (app in disallowedApplications) {
                try {
                    builder.addDisallowedApplication(app)
                } catch (e: Exception) {
                    println("Failed to add disallowed app $app: ${e.message}")
                }
            }
        }

        return builder.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.setMetered(false)
            }
        }
        .establish()
        ?: throw IllegalStateException("Failed to init VpnService")
    }

    private fun shouldRouteApp(pkg: String, myPkg: String): Boolean {
        if (pkg == myPkg) {
            return false
        }

        val pkgLower = pkg.lowercase()

        // 1. Domestic safety blacklist: never route these domestic apps
        val domesticBlacklist = arrayOf(
            "tencent", "wechat", "weixin", "alipay", "taobao", "tmall", "jd", "jingdong",
            "bilibili", "douyin", "aweme", "kuaishou", "sina", "weibo", "amap", "autonavi",
            "baidu", "meituan", "dianping", "eleme", "pinduoduo", "xunlei", "zhihu", "xiaohongshu",
            "netease", "163", "dingtalk", "feishu", "wps", "kingsoft", "unionpay", "icbc", "ccb",
            "boc", "abchina", "cmbchina", "bank", "cmbc", "spdb", "cib"
        )
        for (item in domesticBlacklist) {
            if (pkgLower.contains(item)) {
                return false
            }
        }

        // 2. Google apps & Chrome & Play Store
        if (pkgLower.startsWith("com.google.") 
            || pkgLower == "com.google" 
            || pkgLower == "com.android.chrome" 
            || pkgLower == "com.android.vending"
            || pkgLower.contains("chrome")
            || pkgLower.contains("chromium")) {
            return true
        }

        // 3. Browsers (for web science surfing)
        val browsers = arrayOf(
            "com.microsoft.emmx",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.kiwibrowser.browser",
            "mark.via.gp",
            "org.torproject.torbrowser"
        )
        for (b in browsers) {
            if (pkgLower == b) {
                return true
            }
        }

        // 3. Twitter / X
        if (pkgLower.contains("twitter")) {
            return true
        }

        // 4. Telegram & third-party clients
        if (pkgLower.contains("telegram") || pkgLower == "nekox.messenger" || pkgLower == "org.thunderdog.challegram") {
            return true
        }

        // 5. TikTok (international version only)
        if (pkgLower.contains("musically") || pkgLower.contains("ugc.trill")) {
            return true
        }

        // 6. GitHub
        if (pkgLower.contains("github")) {
            return true
        }

        // 7. AI applications
        val aiApps = arrayOf(
            "com.openai.chatgpt",
            "com.anthropic.claude",
            "ai.perplexity.app.android",
            "com.poe.android",
            "com.microsoft.copilot",
            "com.microsoft.bing"
        )
        for (ai in aiApps) {
            if (pkgLower == ai) {
                return true
            }
        }

        // 8. Foreign social, streaming & tools
        val foreignApps = arrayOf(
            "com.discord",
            "com.reddit.frontpage",
            "com.instagram.android",
            "com.instagram.barcelona",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.facebook.lite",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "jp.naver.line.android",
            "com.spotify.music",
            "com.netflix.mediaclient",
            "org.wikipedia",
            "com.medium.reader",
            "com.quora.android",
            "com.duckduckgo.mobile.android",
            "notion.id",
            "com.slack",
            "tv.twitch.android.app",
            "com.valvesoftware.android.steam.community"
        )
        for (foreign in foreignApps) {
            if (pkgLower == foreign) {
                return true
            }
        }

        if (pkgLower.startsWith("ch.proton") || pkgLower.startsWith("me.proton")) {
            return true
        }

        // 9. Home NAS & Self-hosted & Remote tools (Home network access via EasyTier)
        val homeApps = arrayOf(
            "io.homeassistant.companion.android",
            "app.immich.mobile",
            "org.jellyfin.mobile",
            "org.jellyfin.androidtv",
            "com.plexapp.android",
            "com.mb.android",
            "com.audiobookshelf.app",
            "com.server.auditor.ssh.client",
            "com.sonelli.juicessh",
            "com.microsoft.rdc.androidx",
            "com.microsoft.rdc.android",
            "com.termux"
        )
        for (home in homeApps) {
            if (pkgLower == home) {
                return true
            }
        }

        if (pkgLower.startsWith("com.synology.")) {
            return true
        }

        // fnOS / Feiniu NAS apps
        if (pkgLower.contains("fnos") || pkgLower.contains("fnnas") || pkgLower.contains("feiniu")) {
            return true
        }

        return false
    }

}
