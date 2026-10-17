package com.cortex.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One thing Cortex should do later: say a reminder, or run any phone action. */
data class CortexTask(
    val id: Long,
    val label: String,
    val whenMs: Long,
    val repeat: String, // once, daily, weekly, weekdays
    val action: String, // "remind" or any DeviceControl action
    val param: String,
    val say: String = "", // what Cortex says out loud when the time comes
    val group: String = "" // name of the daily plan this belongs to, if any
)

/** Saves scheduled tasks and sets the Android alarms that wake Cortex at the right time. */
object Scheduler {
    private const val PREFS = "cortex_prefs"
    private const val KEY = "cortex_tasks"
    private const val CHANNEL = "cortex_tasks"
    const val ACTION_FIRE = "com.cortex.app.TASK_FIRE"

    // Actions that still work while the phone is locked.
    private val SAFE_LOCKED = setOf(
        "remind", "flashlight_on", "flashlight_off", "volume_up", "volume_down", "mute", "unmute",
        "battery_status", "set_alarm", "set_timer"
    )

    // ------------------------------------------------------------------ storage

    private fun load(ctx: Context): MutableList<CortexTask> {
        val out = mutableListOf<CortexTask>()
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    CortexTask(
                        o.getLong("id"), o.optString("label"), o.getLong("when"),
                        o.optString("repeat", "once"), o.optString("action"), o.optString("param"),
                        o.optString("say"), o.optString("group")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(ctx: Context, list: List<CortexTask>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject().put("id", t.id).put("label", t.label).put("when", t.whenMs)
                    .put("repeat", t.repeat).put("action", t.action).put("param", t.param)
                    .put("say", t.say).put("group", t.group)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    // ------------------------------------------------------------------ time maths

    /** "in:600" (seconds), "at:09:00" (next time it is 09:00) or "2026-10-18 09:00". Returns null if not understood. */
    fun parseWhen(spec: String, now: Long = System.currentTimeMillis()): Long? {
        val s = spec.trim()
        val lower = s.lowercase(Locale.ROOT)
        if (lower.startsWith("in:")) {
            val secs = lower.substring(3).trim().toLongOrNull() ?: return null
            return if (secs < 0) null else now + secs * 1000L
        }
        if (lower.startsWith("at:")) {
            val m = Regex("""(\d{1,2})\s*[:.]\s*(\d{2})""").find(lower) ?: return null
            val c = Calendar.getInstance()
            c.timeInMillis = now
            c.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt().coerceIn(0, 23))
            c.set(Calendar.MINUTE, m.groupValues[2].toInt().coerceIn(0, 59))
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now + 5000L) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        val m = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})[ T]+(\d{1,2})\s*[:.]\s*(\d{2})""").find(s) ?: return null
        val c = Calendar.getInstance()
        c.set(
            m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
            m.groupValues[4].toInt().coerceIn(0, 23), m.groupValues[5].toInt().coerceIn(0, 59), 0
        )
        c.set(Calendar.MILLISECOND, 0)
        return if (c.timeInMillis <= now) null else c.timeInMillis
    }

    private fun nextAfter(t: CortexTask, from: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t.whenMs
        var guard = 0
        while ((c.timeInMillis <= from || (t.repeat == "weekdays" && isWeekend(c))) && guard < 400) {
            c.add(Calendar.DAY_OF_YEAR, if (t.repeat == "weekly") 7 else 1)
            guard++
        }
        return c.timeInMillis
    }

    private fun isWeekend(c: Calendar): Boolean {
        val d = c.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    private fun whenText(ms: Long): String =
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date(ms))

    // ------------------------------------------------------------------ alarms

    private fun pendingFor(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, TaskReceiver::class.java).setAction(ACTION_FIRE).putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, (id % Int.MAX_VALUE).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun arm(ctx: Context, t: CortexTask): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingFor(ctx, t.id)
        return try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.whenMs, pi)
                true
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.whenMs, pi)
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun disarm(ctx: Context, id: Long) {
        try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingFor(ctx, id))
        } catch (e: Exception) {
        }
    }

    /** Called after a reboot or app update: sets every saved alarm again. */
    fun armAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val keep = mutableListOf<CortexTask>()
        for (t in load(ctx)) {
            if (t.whenMs >= now - 60_000L) {
                keep.add(t)
                arm(ctx, t)
            } else if (t.repeat != "once") {
                val n = t.copy(whenMs = nextAfter(t, now))
                keep.add(n)
                arm(ctx, n)
            } else {
                notify(ctx, "Missed reminder", t.label)
            }
        }
        save(ctx, keep)
    }

    // ------------------------------------------------------------------ commands used by DeviceControl

    /** param = "<when>|<repeat>|<action>|<param>" */
    fun addFromParam(ctx: Context, p: String): String {
        val parts = p.split("|", limit = 5).map { it.trim() }
        if (parts.size < 3) return "Tell me when, how often and what to do."
        val whenMs = parseWhen(parts[0]) ?: return "I did not understand the time. Say for example in 10 minutes, or at 9 in the morning."
        val repeat = when (parts[1].lowercase(Locale.ROOT)) {
            "daily", "everyday", "every day" -> "daily"
            "weekly" -> "weekly"
            "weekdays" -> "weekdays"
            else -> "once"
        }
        val action = parts[2].lowercase(Locale.ROOT)
        val param = parts.getOrElse(3) { "" }
        if (action == "schedule" || action == "list_tasks" || action == "cancel_task") return "I cannot schedule that."
        if (action == "remind" && param.isEmpty()) return "What should I remind you about?"
        val label = if (action == "remind") param else (action.replace("_", " ") + if (param.isNotEmpty()) " $param" else "")
        val list = load(ctx)
        val say = parts.getOrElse(4) { "" }
        val t = CortexTask(System.currentTimeMillis(), label.take(120), whenMs, repeat, action, param, say)
        list.add(t)
        save(ctx, list)
        val exact = arm(ctx, t)
        val rep = if (repeat == "once") "" else ", $repeat"
        val note = if (exact) "" else " Allow exact alarms for Cortex in Settings so it runs on time."
        val voice = if (action == "remind") " I will say it out loud then." else " At that time I will announce it out loud, then do it."
        return "Scheduled: $label, ${whenText(whenMs)}$rep.$voice$note"
    }

    fun describeAll(ctx: Context): String {
        val list = load(ctx).sortedBy { it.whenMs }
        if (list.isEmpty()) return "You have no scheduled tasks."
        val sb = StringBuilder("You have ${list.size} scheduled: ")
        sb.append(list.take(6).joinToString("; ") { "${it.label} at ${whenText(it.whenMs)}" + if (it.repeat != "once") " (${it.repeat})" else "" })
        if (list.size > 6) sb.append("; and ${list.size - 6} more")
        return sb.toString() + "."
    }

    fun cancel(ctx: Context, what: String): String {
        val q = what.trim().lowercase(Locale.ROOT)
        val list = load(ctx)
        if (list.isEmpty()) return "You have no scheduled tasks."
        val gone = if (q.isEmpty() || q == "all" || q == "everything") list.toList()
        else list.filter { it.label.lowercase(Locale.ROOT).contains(q) || it.param.lowercase(Locale.ROOT).contains(q) }
        if (gone.isEmpty()) return "I could not find a scheduled task like \"$what\"."
        for (t in gone) disarm(ctx, t.id)
        save(ctx, list.filter { it !in gone })
        return if (gone.size == 1) "Cancelled: ${gone[0].label}." else "Cancelled ${gone.size} tasks."
    }

    // ------------------------------------------------------------------ running a task when its time comes

    fun fire(ctx: Context, id: Long) {
        val all = load(ctx)
        val t = all.find { it.id == id } ?: return
        val main = Handler(Looper.getMainLooper())
        try {
            if (t.action == "remind") {
                val say = t.say.ifEmpty { "Reminder: " + t.param }
                notify(ctx, "Cortex reminder", t.param)
                speak(ctx, say)
            } else {
                // Say out loud what is about to happen, then do it.
                val announce = t.say.ifEmpty { defaultSay(t) }
                notify(ctx, "Cortex: " + t.label, announce)
                speak(ctx, announce)
                val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked && t.action !in SAFE_LOCKED) {
                    val warn = "The phone is locked, so I could not do this: ${t.label}. Please unlock it."
                    notify(ctx, "Cortex could not run a task", warn)
                    speak(ctx, warn)
                } else {
                    var result = "Done."
                    val latch = CountDownLatch(1)
                    main.post {
                        try {
                            result = DeviceControl.run(ctx, t.action, t.param)
                        } catch (e: Exception) {
                            result = "That did not work."
                        }
                        latch.countDown()
                    }
                    latch.await(8, TimeUnit.SECONDS)
                    notify(ctx, "Cortex: " + t.label, result)
                }
            }
        } finally {
            val rest = load(ctx).filter { it.id != id }.toMutableList()
            if (t.repeat != "once") {
                val n = t.copy(whenMs = nextAfter(t, System.currentTimeMillis() + 1000L))
                rest.add(n)
                arm(ctx, n)
            }
            save(ctx, rest)
        }
    }

    private fun defaultSay(t: CortexTask): String {
        val i = t.param.indexOf(',')
        val who = if (i < 0) t.param else t.param.substring(0, i).trim()
        val msg = if (i < 0) "" else t.param.substring(i + 1).trim()
        return when (t.action) {
            "call" -> "It is time to call $who."
            "whatsapp_message", "whatsapp" -> "It is time to send a WhatsApp message to $who" + if (msg.isNotEmpty()) ": $msg." else "."
            "send_sms", "sms" -> "It is time to send a message to $who" + if (msg.isNotEmpty()) ": $msg." else "."
            else -> "It is time to " + t.label + "."
        }
    }

    // ------------------------------------------------------------------ daily plans (a saved routine, spoken at each time)

    /** param = "<name>|<repeat>|HH:MM text;HH:MM text;..." Replaces an older plan with the same name. */
    fun savePlan(ctx: Context, p: String): String {
        val parts = p.split("|", limit = 3).map { it.trim() }
        if (parts.size < 3 || parts[0].isEmpty()) return "Tell me the plan name and its times, for example 07:00 wake up, 09:00 gym."
        val name = parts[0].take(40)
        val repeat = when (parts[1].lowercase(Locale.ROOT)) {
            "weekly" -> "weekly"
            "weekdays" -> "weekdays"
            "once" -> "once"
            else -> "daily"
        }
        val rest = load(ctx).toMutableList()
        for (old in rest.filter { it.group.equals(name, ignoreCase = true) }) disarm(ctx, old.id)
        rest.removeAll { it.group.equals(name, ignoreCase = true) }
        val items = parts[2].split(";", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        var n = 0
        var allExact = true
        val base = System.currentTimeMillis()
        for (item in items) {
            val m = Regex("""^(\d{1,2})\s*[:.]\s*(\d{2})\s*[-–:]?\s*(.+)$""").find(item) ?: continue
            val whenMs = parseWhen("at:" + m.groupValues[1] + ":" + m.groupValues[2]) ?: continue
            val text = m.groupValues[3].trim()
            val t = CortexTask(base + n, text.take(120), whenMs, repeat, "remind", text, "", name)
            rest.add(t)
            if (!arm(ctx, t)) allExact = false
            n++
        }
        save(ctx, rest)
        if (n == 0) return "I could not find any times. Say for example 07:00 wake up, 09:00 gym."
        val note = if (allExact) "" else " Allow exact alarms for Cortex in Settings so it runs on time."
        return "Plan \"$name\" is saved with $n items, $repeat. I will say each one out loud at its time.$note"
    }

    fun showPlan(ctx: Context, nameIn: String): String {
        val name = nameIn.trim()
        val grouped = load(ctx).filter { it.group.isNotEmpty() && (name.isEmpty() || it.group.equals(name, ignoreCase = true)) }
        if (grouped.isEmpty()) return if (name.isEmpty()) "You have no saved plans." else "I could not find a plan called $name."
        val sb = StringBuilder()
        for (g in grouped.map { it.group }.distinct()) {
            val items = grouped.filter { it.group == g }.sortedBy { Calendar.getInstance().apply { timeInMillis = it.whenMs }.let { c -> c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) } }
            sb.append(g).append(": ")
            sb.append(items.joinToString("; ") { SimpleDateFormat("HH:mm", Locale.US).format(Date(it.whenMs)) + " " + it.label })
            sb.append(". ")
        }
        return sb.toString().trim()
    }

    fun deletePlan(ctx: Context, nameIn: String): String {
        val name = nameIn.trim()
        val list = load(ctx)
        val gone = list.filter { it.group.isNotEmpty() && (name.isEmpty() || name.equals("all", true) || it.group.equals(name, ignoreCase = true)) }
        if (gone.isEmpty()) return "I could not find a plan called $name."
        for (t in gone) disarm(ctx, t.id)
        save(ctx, list.filter { it !in gone })
        return "Plan deleted."
    }

    private fun notify(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cortex tasks", NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        } catch (e: Exception) {
        }
    }

    /** Speaks the reminder out loud (waits for it to finish, up to 15 seconds). */
    private fun speak(ctx: Context, text: String) {
        val done = CountDownLatch(1)
        val holder = arrayOfNulls<TextToSpeech>(1)
        Handler(Looper.getMainLooper()).post {
            try {
                holder[0] = TextToSpeech(ctx) { status ->
                    val t = holder[0]
                    if (status != TextToSpeech.SUCCESS || t == null) {
                        done.countDown()
                    } else {
                        val urdu = text.any { it in '؀'..'ۿ' }
                        t.language = if (urdu) Locale("ur", "PK") else Locale.US
                        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                done.countDown()
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                done.countDown()
                            }
                        })
                        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cortex-task")
                    }
                }
            } catch (e: Exception) {
                done.countDown()
            }
        }
        done.await(15, TimeUnit.SECONDS)
        Handler(Looper.getMainLooper()).post {
            try {
                holder[0]?.shutdown()
            } catch (e: Exception) {
            }
        }
    }

    /** Text added to the AI prompt so it knows the time and how to schedule. */
    fun promptHelp(): String {
        val now = SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return " SCHEDULING (do it later, saved by Cortex): use the action schedule with param \"<when>|<repeat>|<action>|<param>|<say>\". " +
            "when is in:<seconds> (for example in:600 for 10 minutes), or at:HH:MM in 24-hour time (the next time that clock time comes), or YYYY-MM-DD HH:MM for a specific date such as tomorrow. " +
            "repeat is once, daily, weekly or weekdays. action is remind (param: the sentence to remind) or any other action above, for example call with param Sajjad, or whatsapp_message with param Sajjad,Good morning, or send_sms. " +
            "say is a short sentence in the USER'S OWN language that Cortex speaks out loud when the time comes, BEFORE it does the action, for example Sajjad ko call karne ka waqt ho gaya hai. Always include say for calls and messages. " +
            "Example: {\"device_action\":\"schedule\",\"param\":\"at:15:30|once|call|Sajjad|Sajjad ko call karne ka waqt ho gaya hai\"}. " +
            "list_tasks lists scheduled tasks. cancel_task (param: a word from the task, or all) removes them. " +
            "DAILY PLANS: when the user describes a routine, schedule or timetable to keep (for example the morning schedule), use plan_save with param \"<plan name>|<repeat>|HH:MM text;HH:MM text\", example: {\"device_action\":\"plan_save\",\"param\":\"Morning|daily|07:00 uth jao;08:00 nashta;09:00 gym\"}. Cortex saves it and says each item out loud at its time. plan_show (param: plan name, or empty for all) reads a plan back, plan_delete (param: plan name) removes it. " +
            "After any scheduling action you will get a [PHONE] result: then tell the user out loud, in their own language, exactly what you set and when, and for calls or messages say clearly that you will announce it by voice at that time and then do it. " +
            "The phone's current local time is $now."
    }
}

/** Android calls this when a scheduled time arrives. */
class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext
        val lock = try {
            (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "cortex:task").apply { acquire(55_000L) }
        } catch (e: Exception) {
            null
        }
        Thread {
            try {
                Scheduler.fire(app, id)
            } catch (e: Exception) {
            } finally {
                try {
                    if (lock != null && lock.isHeld) lock.release()
                } catch (e: Exception) {
                }
                pending.finish()
            }
        }.start()
    }
}

/** Sets the alarms again after the phone restarts or the app is updated. */
class TaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Scheduler.armAll(context.applicationContext)
        } catch (e: Exception) {
        }
    }
}
