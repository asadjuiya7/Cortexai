package com.cortex.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Runs phone actions directly (no Tasker / MacroDroid). Shared by the popup and the in-app chat. */
object DeviceControl {

    val PROMPT = """You can control this phone. When the user asks you to do something on the phone, reply with ONLY this JSON and nothing else: {"device_action":"<action>","param":"<value>"}. Actions: open_app (param: app name in English such as WhatsApp, YouTube, Camera, Settings, Chrome), flashlight_on, flashlight_off, volume_up, volume_down, mute, unmute, call (param: phone number), send_sms (param: number,message), whatsapp_message (param: number,message with country code), send_email (param: to,subject,body), set_alarm (param: 24-hour HH:MM), set_timer (param: seconds), web_search (param: query), youtube_search (param: query), navigate (param: place), open_url (param: url), battery_status, wifi_settings, bluetooth_settings, go_home, go_back, recents, notifications, lock_screen, screenshot, click_text (param: visible text of the button on screen), type_text (param: text to type), scroll_down, scroll_up. Use an empty param when none is needed. Calls, texts, WhatsApp and email only open the app ready to send, the user taps send themselves. Wi-Fi and Bluetooth can only be opened in settings, apps cannot switch them. If the request is not a phone action, just answer normally in plain text."""

    fun parse(raw: String): Pair<String, String>? {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        return try {
            val o = JSONObject(t)
            val a = o.optString("device_action", "")
            if (a.isEmpty()) null else Pair(a, o.optString("param", ""))
        } catch (e: Exception) {
            null
        }
    }

    fun run(ctx: Context, action: String, param: String): String {
        return try {
            dispatch(ctx, action.trim().lowercase(Locale.ROOT), param.trim())
        } catch (e: Exception) {
            "Sorry, I could not do that."
        }
    }

    private fun dispatch(ctx: Context, a: String, p: String): String {
        return when (a) {
            "open_app" -> openApp(ctx, p)
            "flashlight_on" -> torch(ctx, true)
            "flashlight_off" -> torch(ctx, false)
            "volume_up" -> volume(ctx, AudioManager.ADJUST_RAISE)
            "volume_down" -> volume(ctx, AudioManager.ADJUST_LOWER)
            "mute" -> volume(ctx, AudioManager.ADJUST_MUTE)
            "unmute" -> volume(ctx, AudioManager.ADJUST_UNMUTE)
            "call" -> {
                start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p))))
                "Opening the dialer for $p. Tap call."
            }
            "send_sms", "sms" -> {
                val parts = splitTwo(p)
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(parts.first)))
                i.putExtra("sms_body", parts.second)
                start(ctx, i)
                "Message is ready. Tap send."
            }
            "whatsapp_message", "whatsapp" -> {
                val parts = splitTwo(p)
                val digits = parts.first.filter { it.isDigit() }
                start(
                    ctx,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + enc(parts.second)))
                )
                "WhatsApp is ready. Tap send."
            }
            "send_email", "email" -> {
                val parts = p.split(",", limit = 3)
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + parts[0].trim()))
                i.putExtra(Intent.EXTRA_SUBJECT, parts.getOrElse(1) { "" }.trim())
                i.putExtra(Intent.EXTRA_TEXT, parts.getOrElse(2) { "" }.trim())
                start(ctx, i)
                "Email is ready. Tap send."
            }
            "set_alarm" -> alarm(ctx, p)
            "set_timer" -> timer(ctx, p)
            "web_search" -> {
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(p))))
                "Searching for $p."
            }
            "youtube_search" -> {
                start(
                    ctx,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(p)))
                )
                "Searching YouTube for $p."
            }
            "navigate" -> navigate(ctx, p)
            "open_url" -> {
                val url = if (p.startsWith("http")) p else "https://$p"
                start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                "Opening $p."
            }
            "battery_status" -> battery(ctx)
            "wifi_on", "wifi_off", "wifi_settings" -> {
                start(ctx, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                "Android does not let apps switch Wi-Fi directly. I opened the Wi-Fi panel, just tap the switch."
            }
            "bluetooth_on", "bluetooth_off", "bluetooth_settings" -> {
                start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                "Android does not let apps switch Bluetooth directly. I opened Bluetooth settings, just tap the switch."
            }
            "go_home" -> global(ctx, AccessibilityService.GLOBAL_ACTION_HOME, "Going home.")
            "go_back" -> global(ctx, AccessibilityService.GLOBAL_ACTION_BACK, "Going back.")
            "recents" -> global(ctx, AccessibilityService.GLOBAL_ACTION_RECENTS, "Showing recent apps.")
            "notifications" -> global(ctx, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opening notifications.")
            "lock_screen" -> global(ctx, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking the screen.")
            "screenshot" -> global(ctx, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Taking a screenshot.")
            "click_text" -> withService(ctx) { if (it.clickText(p)) "Tapped $p." else "I could not find $p on the screen." }
            "type_text" -> withService(ctx) { if (it.typeText(p)) "Typed it." else "Tap a text box first, then ask again." }
            "scroll_down" -> withService(ctx) { if (it.swipe(true)) "Scrolling down." else "That did not work." }
            "scroll_up" -> withService(ctx) { if (it.swipe(false)) "Scrolling up." else "That did not work." }
            else -> "I do not know how to do $a yet."
        }
    }

    private fun start(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun splitTwo(p: String): Pair<String, String> {
        val i = p.indexOf(',')
        return if (i < 0) Pair(p.trim(), "") else Pair(p.substring(0, i).trim(), p.substring(i + 1).trim())
    }

    private fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN)
        main.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val q = name.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return "Which app should I open?"
        var bestPkg: String? = null
        var bestLabel = ""
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val l = label.lowercase(Locale.ROOT)
            if (l == q) {
                bestPkg = ri.activityInfo.packageName
                bestLabel = label
                break
            }
            if (bestPkg == null && l.isNotEmpty() && (l.contains(q) || q.contains(l))) {
                bestPkg = ri.activityInfo.packageName
                bestLabel = label
            }
        }
        if (bestPkg == null) return "I could not find an app called $name."
        val launch = pm.getLaunchIntentForPackage(bestPkg) ?: return "I could not open $name."
        start(ctx, launch)
        return "Opening $bestLabel."
    }

    private fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var id: String? = null
        for (cid in cm.cameraIdList) {
            val flash = cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            if (flash == true) {
                id = cid
                break
            }
        }
        if (id == null) return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun volume(ctx: Context, dir: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val times = if (dir == AudioManager.ADJUST_RAISE || dir == AudioManager.ADJUST_LOWER) 3 else 1
        for (i in 0 until times) {
            val flags = if (i == times - 1) AudioManager.FLAG_SHOW_UI else 0
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, flags)
        }
        return when (dir) {
            AudioManager.ADJUST_RAISE -> "Volume up."
            AudioManager.ADJUST_LOWER -> "Volume down."
            AudioManager.ADJUST_MUTE -> "Muted."
            else -> "Unmuted."
        }
    }

    private fun alarm(ctx: Context, p: String): String {
        val m = Regex("""(\d{1,2})\s*[:.]\s*(\d{2})""").find(p)
            ?: return "Tell me the alarm time, for example 07:30."
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
        i.putExtra(AlarmClock.EXTRA_HOUR, h)
        i.putExtra(AlarmClock.EXTRA_MINUTES, mi)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            start(ctx, i)
        } catch (e: Exception) {
            i.removeExtra(AlarmClock.EXTRA_SKIP_UI)
            start(ctx, i)
        }
        return "Alarm set for " + String.format(Locale.US, "%02d:%02d", h, mi) + "."
    }

    private fun timer(ctx: Context, p: String): String {
        val secs = p.filter { it.isDigit() }.toIntOrNull() ?: return "Tell me the timer length in seconds."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, secs)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            start(ctx, i)
        } catch (e: Exception) {
            i.removeExtra(AlarmClock.EXTRA_SKIP_UI)
            start(ctx, i)
        }
        return "Timer set for $secs seconds."
    }

    private fun navigate(ctx: Context, p: String): String {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + enc(p)))
        nav.setPackage("com.google.android.apps.maps")
        try {
            start(ctx, nav)
        } catch (e: Exception) {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc(p))))
        }
        return "Navigating to $p."
    }

    private fun battery(ctx: Context): String {
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = st?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = st?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0) return "I could not read the battery."
        val pct = level * 100 / scale
        return "Battery is $pct percent" + (if (charging) ", charging." else ".")
    }

    private fun openAccessibility(ctx: Context) {
        try {
            start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
        }
    }

    private fun withService(ctx: Context, block: (CortexAccessibilityService) -> String): String {
        val svc = CortexAccessibilityService.instance
        if (svc == null) {
            openAccessibility(ctx)
            return "Turn on Cortex in Accessibility settings, then ask me again."
        }
        return block(svc)
    }

    private fun global(ctx: Context, action: Int, ok: String): String {
        return withService(ctx) { if (it.performGlobalAction(action)) ok else "That did not work." }
    }
}

/** Lets Cortex press Home/Back, tap buttons, type and scroll in any app. The user turns it on once in Settings. */
class CortexAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CortexAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable) {
                    return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                cur = cur.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** up = true means the finger swipes upward, which scrolls the content down. */
    fun swipe(up: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val yLow = dm.heightPixels * 0.7f
        val yHigh = dm.heightPixels * 0.3f
        val path = Path()
        if (up) {
            path.moveTo(x, yLow)
            path.lineTo(x, yHigh)
        } else {
            path.moveTo(x, yHigh)
            path.lineTo(x, yLow)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
