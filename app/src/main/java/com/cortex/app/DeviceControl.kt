package com.cortex.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.ContactsContract
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

    val PROMPT = """You can control this phone. When the user asks you to do something on the phone, reply with ONLY this JSON and nothing else: {"device_action":"<action>","param":"<value>"}. Actions: open_app (param: app name in English such as WhatsApp, YouTube, Camera, Settings, Chrome), flashlight_on, flashlight_off, volume_up, volume_down, mute, unmute, call (param: contact name exactly as the user said it, written in English letters, or a phone number), send_sms (param: contact name or number,message), whatsapp_message (param: contact name or number with country code,message), send_email (param: to,subject,body), set_alarm (param: 24-hour HH:MM), set_timer (param: seconds), web_search (param: query, Google in the browser), youtube_search (param: query), app_search (param: app name,query - opens THAT app and searches inside it, e.g. YouTube,lofi music or Daraz,shoes or Play Store,calculator or Spotify,arijit or WhatsApp,Ali - use this whenever the user names an app to search in; use web_search only when no app is named), navigate (param: place), open_url (param: url), battery_status, wifi_settings, bluetooth_settings, go_home, go_back, recents, notifications, lock_screen, screenshot, click_text (param: visible text of the button on screen), type_text (param: text to type), scroll_down, scroll_up, read_screen (reads the text visible on screen). Use an empty param when none is needed. You can work in several steps: after each action you will receive a user message starting with [PHONE] giving the result and the visible screen text. Keep replying with ONE JSON action at a time until the whole task is done, then reply with a short plain-text answer. Never ask the user to tap anything yourself. For weather, news or facts: use web_search, then read_screen, then answer from the screen text. For WhatsApp: use whatsapp_message (it opens the chat, fills the text and sends it by itself), then confirm. For SMS: use send_sms (it opens the message with the text filled in), then click_text with Send to actually send it, then confirm. To use an app: open_app, then click_text, type_text, scroll or read_screen as needed. If a click fails, call read_screen and try another label. Calls go through directly when permission is given. If a [PHONE] result says "Which contact?" it lists several people with that name: the user will answer with the full name, then repeat the SAME action (call, whatsapp_message or send_sms) with that full name and, for messages, the same message text as before. For whatsapp_message the app presses Send by itself, so do not click Send yourself; after it, just confirm briefly. Wi-Fi and Bluetooth can only be opened in settings, apps cannot switch them. If the request is not a phone action, just answer normally in plain text."""

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
                val pk = pickContact(ctx, p)
                if (pk.number == null) {
                    pk.problem ?: "I could not find $p in your contacts."
                } else {
                    val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    start(ctx, Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(pk.number))))
                    val who = pk.name ?: p
                    if (granted) "Calling $who." else "Opening the dialer for $who. Tap call."
                }
            }
            "send_sms", "sms" -> {
                val parts = splitTwo(p)
                val pk = pickContact(ctx, parts.first)
                if (pk.number == null) {
                    pk.problem ?: "I could not find ${parts.first} in your contacts."
                } else {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(pk.number)))
                    i.putExtra("sms_body", parts.second)
                    start(ctx, i)
                    "Message to ${pk.name ?: parts.first} is ready. Tap send."
                }
            }
            "whatsapp_message", "whatsapp" -> {
                val parts = splitTwo(p)
                val pk = pickContact(ctx, parts.first)
                if (pk.number == null) {
                    pk.problem ?: "I could not find ${parts.first} in your contacts."
                } else {
                    val digits = pk.number.filter { it.isDigit() }
                    val uri = Uri.parse("https://wa.me/" + digits + "?text=" + enc(parts.second))
                    val wa = Intent(Intent.ACTION_VIEW, uri)
                    wa.setPackage("com.whatsapp")
                    try {
                        start(ctx, wa)
                    } catch (e: Exception) {
                        start(ctx, Intent(Intent.ACTION_VIEW, uri))
                    }
                    val svc = CortexAccessibilityService.instance
                    val who = pk.name ?: parts.first
                    if (svc != null && parts.second.isNotEmpty()) {
                        // Press WhatsApp's Send button once the chat has opened.
                        Thread {
                            try {
                                Thread.sleep(3500)
                                var ok = svc.clickText("Send")
                                if (!ok) {
                                    Thread.sleep(1500)
                                    ok = svc.clickText("Send")
                                }
                            } catch (e: Exception) {
                            }
                        }.start()
                        "Sending your WhatsApp message to $who."
                    } else {
                        "WhatsApp is ready for $who. Tap send."
                    }
                }
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
            "app_search" -> appSearch(ctx, p)
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
            "read_screen" -> withService(ctx) { val t = it.screenText(); if (t.isEmpty()) "The screen has no readable text." else "Screen: $t" }
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

    private class Pick(val number: String?, val name: String?, val problem: String?)

    /**
     * Finds ONE contact for a spoken name. When several different people match (for example three Sajjads)
     * nothing is called; instead the names are listed so the user can say the full name.
     */
    private fun pickContact(ctx: Context, p: String): Pick {
        val q = p.trim()
        if (q.isEmpty()) return Pick(null, null, "Who should I contact?")
        val digitCount = q.count { it.isDigit() }
        if (digitCount >= 6 && digitCount >= q.length - 4) return Pick(q, q, null)
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Pick(null, null, "I need the Contacts permission. Please allow Contacts for Cortex in the app settings.")
        }
        val words = q.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val key = words.maxByOrNull { it.length } ?: q.lowercase(Locale.ROOT)
        val byName = LinkedHashMap<String, Pair<String, Int>>() // display name -> (number, mobile rank)
        try {
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$key%"), null
            )
            cur?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val num = it.getString(1) ?: continue
                    val type = it.getInt(2)
                    val low = name.lowercase(Locale.ROOT)
                    if (!words.all { w -> low.contains(w) }) continue
                    val rank = if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) 0 else 1
                    val old = byName[name]
                    if (old == null || rank < old.second) byName[name] = Pair(num, rank)
                }
            }
        } catch (e: Exception) {
            return Pick(null, null, "I could not read your contacts.")
        }
        if (byName.isEmpty()) return Pick(null, null, "I could not find $q in your contacts.")
        val names = byName.keys.toList()
        if (names.size == 1) return Pick(byName[names[0]]!!.first, names[0], null)
        val exact = names.filter { it.equals(q, ignoreCase = true) }
        if (exact.size == 1) return Pick(byName[exact[0]]!!.first, exact[0], null)
        // Same person saved twice under different names: one number, no need to ask.
        val tails = names.map { n -> byName[n]!!.first.filter { c -> c.isDigit() }.takeLast(10) }.toSet()
        if (tails.size == 1) return Pick(byName[names[0]]!!.first, names[0], null)
        val shown = names.take(5).joinToString(", ")
        val more = if (names.size > 5) " and ${names.size - 5} more" else ""
        return Pick(null, null, "Which contact? I found ${names.size}: $shown$more. Tell me the full name.")
    }

    /** A phone number as-is, or a contact name looked up in the address book. */
    private fun resolveNumber(ctx: Context, p: String): String? {
        val q = p.trim()
        val digits = q.count { it.isDigit() }
        if (digits >= 6 && digits >= q.length - 4) return q
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$q%"), null
            )
            var best: String? = null
            var bestLen = Int.MAX_VALUE
            cur?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val num = it.getString(1) ?: continue
                    if (name.length < bestLen) {
                        bestLen = name.length
                        best = num
                    }
                }
            }
            best
        } catch (e: Exception) {
            null
        }
    }

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

    /** "App,query": opens that app and searches inside it. Known apps use a direct link; others use the screen. */
    private fun appSearch(ctx: Context, p: String): String {
        val parts = splitTwo(p)
        val app = parts.first
        val q = parts.second
        if (app.isEmpty() || q.isEmpty()) return "Tell me the app and what to search, for example YouTube and lofi music."
        val a = app.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), "").trim()
        fun view(uri: String): String {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            return "Searching $app for $q."
        }
        return try {
            when {
                a == "chrome" || a == "browser" || a == "google" || a == "internet" -> view("https://www.google.com/search?q=" + enc(q))
                a.contains("youtube") -> view("https://www.youtube.com/results?search_query=" + enc(q))
                a.contains("play store") || a.contains("playstore") || a.contains("google play") -> view("market://search?q=" + enc(q))
                a.contains("maps") -> view("geo:0,0?q=" + enc(q))
                a.contains("spotify") -> view("spotify:search:" + Uri.encode(q))
                a.contains("amazon") -> view("https://www.amazon.com/s?k=" + enc(q))
                a.contains("daraz") -> view("https://www.daraz.pk/catalog/?q=" + enc(q))
                a.contains("facebook") || a == "fb" || a == "lite" -> view("https://www.facebook.com/search/top?q=" + enc(q))
                a == "twitter" || a == "x" -> view("https://twitter.com/search?q=" + enc(q))
                a.contains("tiktok") -> view("https://www.tiktok.com/search?q=" + enc(q))
                a.contains("linkedin") -> view("https://www.linkedin.com/search/results/all/?keywords=" + enc(q))
                a.contains("pinterest") -> view("https://www.pinterest.com/search/pins/?q=" + enc(q))
                a.contains("wikipedia") -> view("https://en.wikipedia.org/w/index.php?search=" + enc(q))
                else -> {
                    val svc = CortexAccessibilityService.instance
                    if (svc == null) {
                        openAccessibility(ctx)
                        "Turn on Cortex in Accessibility settings, then ask me again."
                    } else {
                        val opened = openApp(ctx, app)
                        if (opened.startsWith("I could not")) {
                            opened
                        } else {
                            // The screen steps need waiting, so they run in the background.
                            Thread {
                                try {
                                    Thread.sleep(2800)
                                    var ok = svc.searchInApp(q)
                                    if (!ok) {
                                        Thread.sleep(1500)
                                        ok = svc.searchInApp(q)
                                    }
                                } catch (e: Exception) {
                                }
                            }.start()
                            "Searching $app for $q."
                        }
                    }
                }
            }
        } catch (e: Exception) {
            "I could not search in $app."
        }
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

    /** Visible text on the current screen (for reading results back to the AI). */
    fun screenText(): String {
        val root = rootInActiveWindow ?: return ""
        val out = LinkedHashSet<String>()
        var total = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || total > 1800) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length < 400 && out.add(t)) total += t.length
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.joinToString(" | ").take(1800)
    }

    private fun findEditable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root, 0) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun isSearchy(n: AccessibilityNodeInfo): Boolean {
        val words = listOf("search", "find", "खोज", "تلاش", "सर्च")
        val bag = StringBuilder()
        n.text?.let { bag.append(it).append(' ') }
        n.contentDescription?.let { bag.append(it).append(' ') }
        if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.let { bag.append(it).append(' ') }
        n.viewIdResourceName?.let { bag.append(it) }
        val t = bag.toString().lowercase(Locale.ROOT)
        return words.any { t.contains(it) }
    }

    private fun findSearchNode(n: AccessibilityNodeInfo?, depth: Int, editableOnly: Boolean): AccessibilityNodeInfo? {
        if (n == null || depth > 40) return null
        if (isSearchy(n) && (!editableOnly || n.isEditable)) return n
        for (i in 0 until n.childCount) {
            val r = findSearchNode(n.getChild(i), depth + 1, editableOnly)
            if (r != null) return r
        }
        return null
    }

    private fun clickUp(n: AccessibilityNodeInfo?): Boolean {
        var cur = n
        while (cur != null) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        return false
    }

    /** Finds the search box (or the search button) of the app on screen, types the query and presses search. */
    fun searchInApp(query: String): Boolean {
        var root = rootInActiveWindow ?: return false
        var field = findSearchNode(root, 0, true) ?: findEditable(root, 0)
        if (field == null) {
            val btn = findSearchNode(root, 0, false) ?: return false
            clickUp(btn)
            Thread.sleep(1000)
            root = rootInActiveWindow ?: return false
            field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root, 0) ?: return false
        } else {
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(500)
        }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        Thread.sleep(500)
        var pressed = false
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            pressed = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        if (!pressed) {
            val r2 = rootInActiveWindow
            val go = r2?.let { findSearchNode(it, 0, false) }
            if (go != null && go != field) pressed = clickUp(go)
        }
        return pressed
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
