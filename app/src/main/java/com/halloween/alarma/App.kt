package com.halloween.alarma

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

// ================================================================ Utilidades

fun t12(h: Int, m: Int): String =
    String.format("%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")

fun intl(c: Context, raw: String): String {
    val digits = raw.filter { it.isDigit() }
    if (raw.trim().startsWith("+")) return digits
    val code = Sound.prefs(c).getString("pais", "") ?: ""
    if (code.isBlank() || digits.startsWith(code)) return digits
    return code + digits.trimStart('0')
}

fun fileName(c: Context, s: String): String {
    try {
        c.contentResolver.query(Uri.parse(s), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "archivo"
        }
    } catch (e: Exception) {
    }
    return "archivo"
}

fun installed(c: Context, pkg: String): Boolean = try {
    c.packageManager.getPackageInfo(pkg, 0)
    true
} catch (e: Exception) {
    false
}

// ================================================================ Alarmas: datos

data class Alarm(
    var id: Int, var hour: Int, var minute: Int, var label: String,
    var enabled: Boolean, var daily: Boolean, var action: String,
    var target: String, var text: String, var speak: Boolean,
    var routine: String, var autoRun: Boolean,
    var contactName: String = "", var contactId: String = "",
    var mode: String = "msg", var media: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("hour", hour).put("minute", minute).put("label", label)
        .put("enabled", enabled).put("daily", daily).put("action", action)
        .put("target", target).put("text", text).put("speak", speak)
        .put("routine", routine).put("autoRun", autoRun)
        .put("contactName", contactName).put("contactId", contactId)
        .put("mode", mode).put("media", media)

    companion object {
        fun fromJson(o: JSONObject): Alarm = Alarm(
            o.getInt("id"), o.getInt("hour"), o.getInt("minute"), o.optString("label"),
            o.optBoolean("enabled", true), o.optBoolean("daily", true),
            o.optString("action", "none"), o.optString("target"), o.optString("text"),
            o.optBoolean("speak", false), o.optString("routine"), o.optBoolean("autoRun", true),
            o.optString("contactName"), o.optString("contactId"),
            o.optString("mode", "msg"), o.optString("media")
        )
    }
}

object Store {
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("alarmas", Context.MODE_PRIVATE)

    fun all(c: Context): MutableList<Alarm> {
        val arr = JSONArray(prefs(c).getString("list", "[]"))
        val out = mutableListOf<Alarm>()
        for (i in 0 until arr.length()) out.add(Alarm.fromJson(arr.getJSONObject(i)))
        return out
    }

    fun saveAll(c: Context, l: List<Alarm>) {
        val arr = JSONArray()
        for (a in l) arr.put(a.toJson())
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    fun get(c: Context, id: Int): Alarm? = all(c).firstOrNull { it.id == id }

    fun upsert(c: Context, a: Alarm) {
        val l = all(c)
        val i = l.indexOfFirst { it.id == a.id }
        if (i >= 0) l[i] = a else l.add(a)
        saveAll(c, l)
    }

    fun remove(c: Context, id: Int) {
        saveAll(c, all(c).filter { it.id != id })
    }
}

fun actionName(k: String): String = when (k) {
    "call" -> "Llamar"
    "whatsapp" -> "WhatsApp"
    "link" -> "Enlace"
    else -> "Solo sonar"
}

fun describe(a: Alarm): String {
    if (a.action == "call") return "Llamar a " + a.contactName
    if (a.action == "whatsapp") {
        val p = when (a.mode) {
            "call" -> "Llamada WhatsApp a "
            "video" -> "Videollamada WhatsApp a "
            else -> "WhatsApp a "
        }
        return p + a.contactName
    }
    if (a.action == "link") return "Abrir enlace"
    return "Solo sonar"
}

// ================================================================ Acciones

fun runAction(c: Context, a: Alarm) {
    try {
        when (a.action) {
            "call" -> {
                val n = "tel:" + a.target.filter { it.isDigit() || it == '+' }
                val i = if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                    Intent(Intent.ACTION_CALL, Uri.parse(n))
                else Intent(Intent.ACTION_DIAL, Uri.parse(n))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(i)
            }
            "whatsapp" -> when (a.mode) {
                "call" -> waCall(c, a, false)
                "video" -> waCall(c, a, true)
                else -> waMessage(c, a)
            }
            "link" -> {
                val u = if (a.target.contains("://")) a.target else "https://" + a.target
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(i)
            }
            else -> {
            }
        }
    } catch (e: Exception) {
        Toast.makeText(c, "No se pudo ejecutar la acción", Toast.LENGTH_LONG).show()
    }
}

fun waPackage(c: Context): String = if (installed(c, "com.whatsapp")) "com.whatsapp" else "com.whatsapp.w4b"

fun waCall(c: Context, a: Alarm, video: Boolean) {
    val mime = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
    else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    var dataId = -1L
    val cur = c.contentResolver.query(
        ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
        ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
        arrayOf(a.contactId, mime), null
    )
    cur?.use { if (it.moveToFirst()) dataId = it.getLong(0) }
    if (dataId < 0L) {
        Toast.makeText(c, "WhatsApp no encontró a ese contacto", Toast.LENGTH_LONG).show()
        return
    }
    val i = Intent(Intent.ACTION_VIEW)
    i.setDataAndType(Uri.parse("content://com.android.contacts/data/" + dataId), mime)
    i.setPackage(waPackage(c))
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    c.startActivity(i)
}

fun waMessage(c: Context, a: Alarm) {
    val num = intl(c, a.target)
    val pkg = waPackage(c)
    val i: Intent
    if (a.media.isNotBlank()) {
        val uri = Uri.parse(a.media)
        val mime = c.contentResolver.getType(uri) ?: "*/*"
        i = Intent(Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(Intent.EXTRA_STREAM, uri)
        if (a.text.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, a.text)
        i.putExtra("jid", num + "@s.whatsapp.net")
        i.setPackage(pkg)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(a.text)))
        i.setPackage(pkg)
    }
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    SendService.arm()
    c.startActivity(i)
}

// Toca solo el botón "Enviar" de WhatsApp cuando la app lo programó.
class SendService : AccessibilityService() {
    companion object {
        @Volatile
        var armedUntil = 0L

        fun arm() {
            armedUntil = System.currentTimeMillis() + 25000L
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val root = rootInActiveWindow ?: return
        val node = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send").firstOrNull()
            ?: findByDesc(root)
            ?: return
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armedUntil = 0L
        }
    }

    private fun findByDesc(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val d = n.contentDescription?.toString()?.lowercase() ?: ""
        if (d == "enviar" || d == "send") return n
        for (i in 0 until n.childCount) {
            val r = findByDesc(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    override fun onInterrupt() {
    }
}

// ================================================================ Sonido

object Sound {
    fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun create(c: Context, loop: Boolean): MediaPlayer? {
        val uris = mutableListOf<Uri>()
        val custom = prefs(c).getString("sonido", null)
        if (custom != null) uris.add(Uri.parse(custom))
        uris.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        uris.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        for (u in uris) {
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                p.setDataSource(c, u)
                p.isLooping = loop
                p.prepare()
                p.start()
                return p
            } catch (e: Exception) {
            }
        }
        return null
    }
}

// ================================================================ Programación de alarmas

object Scheduler {
    private fun fire(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c, id, Intent(c, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(c: Context, a: Alarm) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!a.enabled) {
            am.cancel(fire(c, a.id))
            return
        }
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, a.hour)
        cal.set(Calendar.MINUTE, a.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, show), fire(c, a.id))
    }

    fun cancel(c: Context, id: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(fire(c, id))
    }

    fun rescheduleAll(c: Context) {
        for (a in Store.all(c)) if (a.enabled) schedule(c, a)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val a = Store.get(c, id) ?: return
        if (!a.enabled) return
        c.startForegroundService(Intent(c, AlarmService::class.java).putExtra("id", id))
        if (a.daily) {
            Scheduler.schedule(c, a)
        } else {
            a.enabled = false
            Store.upsert(c, a)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.rescheduleAll(c)
        Classes.rescheduleAll(c)
    }
}

class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = i?.getIntExtra("id", -1) ?: -1
        val a = Store.get(this, id)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("alarma", "Alarmas", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)

        val ringIntent = Intent(this, RingActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ring = PendingIntent.getActivity(
            this, id, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 99999,
            Intent(this, AlarmService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = a?.label?.ifBlank { "Alarma" } ?: "Alarma"
        val n = Notification.Builder(this, "alarma")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("Toca para abrir")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(ring)
            .setFullScreenIntent(ring, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        // Con el permiso "mostrar sobre otras apps" la pantalla se abre sola
        if (a != null && Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(this, RingActivity::class.java).putExtra("id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
        startSound(a, title)
        handler.postDelayed({ stopSelf() }, 120000L)
        return START_NOT_STICKY
    }

    private fun startSound(a: Alarm?, title: String) {
        player?.release()
        player = Sound.create(this, true)
        if (a != null && a.speak) {
            val texto = if (a.routine.isBlank()) "Es hora de $title" else "Buenos días. " + a.routine
            handler.postDelayed({
                player?.setVolume(0.15f, 0.15f)
                tts = TextToSpeech(this) { st ->
                    if (st == TextToSpeech.SUCCESS) {
                        tts?.language = Locale("es")
                        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "rutina")
                    }
                }
            }, 3000L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

// ================================================================ Horario universitario

data class Subject(
    var id: Int, var name: String, var days: Int,
    var sh: Int, var sm: Int, var eh: Int, var em: Int
)

object Classes {
    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("clases", Context.MODE_PRIVATE)

    fun ini(c: Context): Long = prefs(c).getLong("ini", 0L)
    fun fin(c: Context): Long = prefs(c).getLong("fin", 0L)

    fun setDate(c: Context, start: Boolean, ms: Long) {
        prefs(c).edit().putLong(if (start) "ini" else "fin", ms).apply()
    }

    fun list(c: Context): MutableList<Subject> {
        val arr = JSONArray(prefs(c).getString("materias", "[]"))
        val out = mutableListOf<Subject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Subject(
                    o.getInt("id"), o.optString("name"), o.optInt("days"),
                    o.optInt("sh"), o.optInt("sm"), o.optInt("eh"), o.optInt("em")
                )
            )
        }
        return out
    }

    fun saveList(c: Context, l: List<Subject>) {
        val arr = JSONArray()
        for (s in l) {
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("days", s.days)
                    .put("sh", s.sh).put("sm", s.sm).put("eh", s.eh).put("em", s.em)
            )
        }
        prefs(c).edit().putString("materias", arr.toString()).apply()
    }

    fun upsert(c: Context, s: Subject) {
        val l = list(c)
        val i = l.indexOfFirst { it.id == s.id }
        if (i >= 0) l[i] = s else l.add(s)
        saveList(c, l)
        schedule(c, s)
    }

    fun remove(c: Context, id: Int) {
        saveList(c, list(c).filter { it.id != id })
        cancel(c, id)
    }

    private fun pending(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c, id, Intent(c, ClassReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun cancel(c: Context, id: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(c, id))
    }

    // Próximo aviso: 30 minutos antes de la clase, dentro del semestre
    fun next(c: Context, s: Subject): Long {
        val sIni = ini(c)
        val sFin = fin(c)
        if (sIni == 0L || sFin == 0L || s.days == 0) return -1L
        val now = System.currentTimeMillis()
        for (d in 0..400) {
            val day = Calendar.getInstance()
            day.add(Calendar.DAY_OF_YEAR, d)
            day.set(Calendar.HOUR_OF_DAY, s.sh)
            day.set(Calendar.MINUTE, s.sm)
            day.set(Calendar.SECOND, 0)
            day.set(Calendar.MILLISECOND, 0)
            val startMs = day.timeInMillis
            if (startMs < sIni || startMs >= sFin + 86400000L) continue
            if (((s.days shr (day.get(Calendar.DAY_OF_WEEK) - 1)) and 1) == 0) continue
            val r = startMs - 1800000L
            if (r > now) return r
        }
        return -1L
    }

    fun schedule(c: Context, s: Subject) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, s.id)
        am.cancel(pi)
        val t = next(c, s)
        if (t < 0L) return
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    fun rescheduleAll(c: Context) {
        for (s in list(c)) schedule(c, s)
    }
}

class ClassReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val s = Classes.list(c).firstOrNull { it.id == id } ?: return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("clases", "Clases", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, "clases")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Clase en 30 min: " + s.name)
            .setContentText(t12(s.sh, s.sm) + " – " + t12(s.eh, s.em))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(s.id, n)
        Classes.schedule(c, s)
    }
}

// ================================================================ Estilo

object Ui {
    val BG = Color.parseColor("#0A0A0A")
    val CARD = Color.parseColor("#1C1C1C")
    val ORANGE = Color.parseColor("#FF7A00")
    val ORANGE_DARK = Color.parseColor("#B34F00")
    val PURPLE = Color.parseColor("#7B2FF7")
    val GREEN = Color.parseColor("#2E9E4F")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun text(
        c: Context, s: String, size: Float, color: Int = Color.WHITE,
        bold: Boolean = false, g: Int = Gravity.CENTER
    ): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.gravity = g
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    fun button(
        c: Context, s: String, color: Int = ORANGE, tc: Int = Color.BLACK, onClick: () -> Unit
    ): Button {
        val b = Button(c)
        b.text = s
        b.setTextColor(tc)
        b.transformationMethod = null
        b.setTypeface(null, Typeface.BOLD)
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(c, 14).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(c, 6), 0, dp(c, 6))
        b.layoutParams = lp
        return b
    }

    fun style(c: Context, b: Button, sel: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(c, 14).toFloat()
        if (sel) {
            bg.setColor(PURPLE)
            b.setTextColor(Color.WHITE)
        } else {
            bg.setColor(CARD)
            bg.setStroke(dp(c, 1), ORANGE_DARK)
            b.setTextColor(ORANGE)
        }
        b.background = bg
    }

    fun chip(c: Context, s: String, sel: Boolean, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = s
        b.transformationMethod = null
        b.textSize = 12f
        b.setTypeface(null, Typeface.BOLD)
        style(c, b, sel)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3))
        b.layoutParams = lp
        return b
    }

    fun edit(c: Context, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(c)
        e.hint = hint
        e.inputType = type
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.GRAY)
        return e
    }

    fun watch(e: EditText, f: (String) -> Unit) {
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                f(s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
            }
        })
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(CARD)
        bg.cornerRadius = dp(c, 16).toFloat()
        bg.setStroke(dp(c, 1), ORANGE_DARK)
        l.background = bg
        l.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(c, 8), 0, dp(c, 8))
        l.layoutParams = lp
        return l
    }
}

// ================================================================ Pantalla de alarma sonando

class RingActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    private fun stopAlarm() {
        stopService(Intent(this, AlarmService::class.java))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val a = Store.get(this, intent.getIntExtra("id", -1))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Ui.BG)
        root.setPadding(48, 48, 48, 48)
        root.addView(Ui.text(this, "🎃", 64f))
        root.addView(Ui.text(this, t12(a?.hour ?: 0, a?.minute ?: 0), 48f, Ui.ORANGE, true))
        root.addView(Ui.text(this, a?.label?.ifBlank { "Alarma" } ?: "Alarma", 24f))
        if (a != null && a.action != "none") {
            root.addView(Ui.button(this, "Ejecutar: " + describe(a)) {
                stopAlarm()
                runAction(this, a)
            })
        }
        root.addView(Ui.button(this, "Detener alarma") {
            stopAlarm()
            finish()
        })
        setContentView(root)
        if (b == null && a != null && a.autoRun && a.action != "none") {
            h.postDelayed({
                stopAlarm()
                runAction(this, a)
                finish()
            }, if (a.speak) 10000L else 1500L)
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

// ================================================================ Pantalla principal

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val panels = mutableListOf<View>()
    private val tabBtns = mutableListOf<Button>()

    private lateinit var alarmList: LinearLayout
    private lateinit var classList: LinearLayout
    private lateinit var semStartBtn: Button
    private lateinit var semEndBtn: Button
    private lateinit var soundLabel: TextView

    private var contactCb: ((String, String, String) -> Unit)? = null
    private var mediaCb: ((String) -> Unit)? = null
    private var soundCb: (() -> Unit)? = null

    // cronómetro
    private var swRun = false
    private var swStart = 0L
    private var swAcc = 0L
    private var lapN = 0
    private var lapLast = 0L
    private lateinit var swText: TextView
    private lateinit var lapBox: LinearLayout
    private val swTick = object : Runnable {
        override fun run() {
            val extra = if (swRun) SystemClock.elapsedRealtime() - swStart else 0L
            swText.text = fmt(swAcc + extra)
            if (swRun) h.postDelayed(this, 50L)
        }
    }

    // temporizador
    private var cd: CountDownTimer? = null
    private var timerPlayer: MediaPlayer? = null

    // pulsos
    private var pulsing = false
    private var pulses = 0
    private var pulseRun: Runnable? = null
    private var tone: ToneGenerator? = null

    private var testPlayer: MediaPlayer? = null

    private val dayNames = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
    private val dayLetters = listOf("L", "M", "X", "J", "V", "S", "D")
    private val calDays = listOf(2, 3, 4, 5, 6, 7, 1)

    private fun fmt(ms: Long): String {
        val t = ms / 10
        return String.format("%02d:%02d.%02d", t / 6000, (t / 100) % 60, t % 100)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val perms = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        val title = Ui.text(this, "🎃 Hall o’ clock", 22f, Ui.ORANGE, true)
        title.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8))
        root.addView(title)

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        panels.add(alarmsPanel())
        panels.add(stopwatchPanel())
        panels.add(timerPanel())
        panels.add(pulsePanel())
        panels.add(classesPanel())
        panels.add(settingsPanel())
        for (p in panels) frame.addView(p)

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val tabs = LinearLayout(this)
        val names = listOf("Alarmas", "Cronómetro", "Temporizador", "Pulsos", "Clases", "Ajustes")
        names.forEachIndexed { idx, n ->
            val bt = Ui.chip(this, n, false) { show(idx) }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(6, 6, 6, 6)
            bt.layoutParams = lp
            tabBtns.add(bt)
            tabs.addView(bt)
        }
        hs.addView(tabs)
        root.addView(hs)
        setContentView(root)
        show(0)

        if (!Settings.canDrawOverlays(this) && !Sound.prefs(this).getBoolean("askedOverlay", false)) {
            Sound.prefs(this).edit().putBoolean("askedOverlay", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Un permiso importante")
                .setMessage(
                    "Para que llamar o enviar WhatsApp se ejecute solo cuando suene la alarma, " +
                        "activa «Mostrar sobre otras apps» para Hall o’ clock."
                )
                .setPositiveButton("Abrir ajustes") { _, _ -> openOverlaySettings() }
                .setNegativeButton("Luego", null)
                .show()
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
        )
    }

    override fun onResume() {
        super.onResume()
        refreshAlarms()
        refreshClasses()
        refreshSound()
    }

    override fun onDestroy() {
        cd?.cancel()
        pulsing = false
        tone?.release()
        timerPlayer?.release()
        testPlayer?.release()
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun show(i: Int) {
        panels.forEachIndexed { j, p -> p.visibility = if (j == i) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { j, bt -> Ui.style(this, bt, j == i) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        val u = data.data ?: return
        when (req) {
            10 -> {
    android.util.Log.d("CONTACTOS_DEBUG", "URI recibida: $u")

    val cur = contentResolver.query(
        u,
        null,
        null,
        null,
        null
    )

    cur?.use { cursor ->
        android.util.Log.d(
            "CONTACTOS_DEBUG",
            "Columnas recibidas: ${cursor.columnNames.joinToString()}"
        )

        if (cursor.moveToFirst()) {
            for (i in cursor.columnNames.indices) {
                val columnName = cursor.getColumnName(i)
                val value = cursor.getString(i)

                android.util.Log.d(
                    "CONTACTOS_DEBUG",
                    "$columnName = $value"
                )
            }

            val nameIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )

            val numberIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val idIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            )

            android.util.Log.d(
                "CONTACTOS_DEBUG",
                "Índices: nombre=$nameIndex, número=$numberIndex, contacto=$idIndex"
            )

            val name = if (nameIndex >= 0) {
                cursor.getString(nameIndex) ?: ""
            } else {
                ""
            }

            val number = if (numberIndex >= 0) {
                cursor.getString(numberIndex) ?: ""
            } else {
                ""
            }

            val contactId = if (idIndex >= 0) {
                cursor.getString(idIndex) ?: ""
            } else {
                ""
            }

            android.util.Log.d(
                "CONTACTOS_DEBUG",
                "RESULTADO: nombre=$name, número=$number, ID=$contactId"
            )

            contactCb?.invoke(name, number, contactId)
        } else {
            android.util.Log.e(
                "CONTACTOS_DEBUG",
                "La consulta no devolvió registros."
            )
        }
    } ?: android.util.Log.e(
        "CONTACTOS_DEBUG",
        "La consulta devolvió un Cursor nulo."
    )
}
            11 -> {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                mediaCb?.invoke(u.toString())
            }
            12 -> {
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                Sound.prefs(this).edit().putString("sonido", u.toString()).apply()
                soundCb?.invoke()
            }
        }
    }

    private fun pickFile(req: Int, mimes: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, req)
    }

    // ------------------------------------------------------------ Alarmas

    private fun alarmsPanel(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        col.addView(Ui.button(this, "+ Nueva alarma") { editAlarm(null) })
        alarmList = LinearLayout(this)
        alarmList.orientation = LinearLayout.VERTICAL
        col.addView(alarmList)
        sv.addView(col)
        return sv
    }

    private fun refreshAlarms() {
        alarmList.removeAllViews()
        val l = Store.all(this).sortedBy { it.hour * 60 + it.minute }
        if (l.isEmpty()) alarmList.addView(Ui.text(this, "Sin alarmas todavía", 16f, Color.GRAY))
        for (a in l) {
            val card = Ui.card(this)
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.addView(Ui.text(this, t12(a.hour, a.minute), 32f, Ui.ORANGE, true, Gravity.START))
            val sub = listOfNotNull(
                a.label.ifBlank { null },
                if (a.daily) "diaria" else "una vez",
                describe(a),
                if (a.speak) "con voz" else null
            ).joinToString(" · ")
            info.addView(Ui.text(this, sub, 14f, Color.LTGRAY, false, Gravity.START))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this)
            sw.isChecked = a.enabled
            sw.setOnCheckedChangeListener { _, on ->
                a.enabled = on
                Store.upsert(this, a)
                if (on) Scheduler.schedule(this, a) else Scheduler.cancel(this, a.id)
            }
            row.addView(sw)
            card.addView(row)
            val btns = LinearLayout(this)
            btns.addView(Ui.button(this, "Editar") { editAlarm(a) }, LinearLayout.LayoutParams(0, -2, 1f))
            btns.addView(Ui.button(this, "Borrar") {
                Scheduler.cancel(this, a.id)
                Store.remove(this, a.id)
                refreshAlarms()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(btns)
            alarmList.addView(card)
        }
    }

    private fun editAlarm(old: Alarm?) {
        val a = old ?: Alarm(
            (System.currentTimeMillis() % 1000000000L).toInt(), 7, 0, "",
            true, true, "none", "", "", false, "", true
        )
        var hh = a.hour
        var mm = a.minute
        var act = a.action
        var mode = a.mode
        var cName = a.contactName
        var cId = a.contactId
        var cNum = if (a.action == "link") "" else a.target
        var link = if (a.action == "link") a.target else ""
        var msg = a.text
        var media = a.media

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0)

        val label = Ui.edit(this, "Nombre (opcional)")
        label.setText(a.label)
        val timeBtn = Ui.button(this, "") {}
        timeBtn.text = "⏰  " + t12(hh, mm)
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, hr, mn ->
                hh = hr
                mm = mn
                timeBtn.text = "⏰  " + t12(hh, mm)
            }, hh, mm, false).show()
        }
        val daily = CheckBox(this)
        daily.text = "Repetir todos los días"
        daily.isChecked = a.daily

        val chips = LinearLayout(this)
        val extra = LinearLayout(this)
        extra.orientation = LinearLayout.VERTICAL

        val speak = CheckBox(this)
        speak.text = "Hablar la rutina del día (opcional)"
        speak.isChecked = a.speak
        val routine = Ui.edit(this, "Lo que dirá la voz")
        routine.setText(a.routine)
        routine.visibility = if (a.speak) View.VISIBLE else View.GONE
        speak.setOnCheckedChangeListener { _, on -> routine.visibility = if (on) View.VISIBLE else View.GONE }
        val auto = CheckBox(this)
        auto.text = "Ejecutar la acción automáticamente"
        auto.isChecked = a.autoRun

        fun render() {
            chips.removeAllViews()
            for (k in listOf("none", "call", "whatsapp", "link")) {
                chips.addView(Ui.chip(this, actionName(k), act == k) {
                    act = k
                    render()
                })
            }
            extra.removeAllViews()
            auto.visibility = if (act == "none") View.GONE else View.VISIBLE
            if (act == "call" || act == "whatsapp") {
                if (act == "whatsapp") {
                    val modes = LinearLayout(this)
                    for ((k, n) in listOf("msg" to "Mensaje", "call" to "Llamada", "video" to "Videollamada")) {
                        modes.addView(Ui.chip(this, n, mode == k) {
                            mode = k
                            render()
                        })
                    }
                    extra.addView(modes)
                }
                extra.addView(Ui.button(this, "📇  Contactar", Ui.GREEN, Color.WHITE) {
                    contactCb = { n, num, id ->
                        cName = n
                        cNum = num
                        cId = id
                        render()
                    }
                    startActivityForResult(
                        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 10
                    )
                })
                val who = if (cName.isBlank()) "Sin contacto elegido" else "Contacto: " + cName + "\n" + cNum
                extra.addView(Ui.text(this, who, 15f, if (cName.isBlank()) Color.GRAY else Color.WHITE))
            }
            if (act == "whatsapp" && mode == "msg") {
                val m = Ui.edit(this, "Mensaje (o pie de la imagen)")
                m.setText(msg)
                Ui.watch(m) { msg = it }
                extra.addView(m)
                extra.addView(Ui.button(this, "📎  Adjuntar imagen, audio o video") {
                    mediaCb = { u ->
                        media = u
                        render()
                    }
                    pickFile(11, arrayOf("image/*", "audio/*", "video/*"))
                })
                if (media.isNotBlank()) {
                    extra.addView(Ui.text(this, "Adjunto: " + fileName(this, media), 14f))
                    extra.addView(Ui.button(this, "Quitar adjunto") {
                        media = ""
                        render()
                    })
                }
            }
            if (act == "link") {
                val e = Ui.edit(this, "Enlace (ej. instagram.com/usuario)")
                e.setText(link)
                Ui.watch(e) { link = it }
                extra.addView(e)
            }
        }

        col.addView(label)
        col.addView(timeBtn)
        col.addView(daily)
        col.addView(Ui.text(this, "¿Qué hará a esa hora?", 16f, Ui.ORANGE, true, Gravity.START))
        col.addView(chips)
        col.addView(extra)
        col.addView(speak)
        col.addView(routine)
        col.addView(auto)
        render()
        val sv = ScrollView(this)
        sv.addView(col)

        val dlg = AlertDialog.Builder(this)
            .setTitle("Alarma")
            .setView(sv)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if ((act == "call" || act == "whatsapp") && cId.isBlank()) {
                Toast.makeText(this, "Elige un contacto con el botón verde", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (act == "link" && link.isBlank()) {
                Toast.makeText(this, "Escribe el enlace", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            a.hour = hh
            a.minute = mm
            a.label = label.text.toString()
            a.daily = daily.isChecked
            a.action = act
            a.mode = mode
            a.contactName = cName
            a.contactId = cId
            a.target = if (act == "link") link.trim() else cNum
            a.text = msg
            a.media = media
            a.speak = speak.isChecked
            a.routine = routine.text.toString()
            a.autoRun = auto.isChecked
            a.enabled = true
            Store.upsert(this, a)
            Scheduler.schedule(this, a)
            refreshAlarms()
            dlg.dismiss()
            Toast.makeText(this, "Alarma guardada", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ Cronómetro

    private fun stopwatchPanel(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), 0)
        swText = Ui.text(this, "00:00.00", 54f, Ui.ORANGE, true)
        col.addView(swText)
        val row = LinearLayout(this)
        val go = Ui.button(this, "Iniciar") {}
        val lap = Ui.button(this, "Vuelta", Ui.PURPLE, Color.WHITE) {}
        go.setOnClickListener {
            if (swRun) {
                swAcc += SystemClock.elapsedRealtime() - swStart
                swRun = false
                go.text = "Continuar"
            } else {
                swStart = SystemClock.elapsedRealtime()
                swRun = true
                go.text = "Pausar"
                h.post(swTick)
            }
        }
        lap.setOnClickListener {
            if (swRun) {
                val total = swAcc + (SystemClock.elapsedRealtime() - swStart)
                lapN++
                val line = String.format("Vuelta %d    %s    (%s)", lapN, fmt(total - lapLast), fmt(total))
                lapLast = total
                lapBox.addView(Ui.text(this, line, 16f, Color.WHITE, false, Gravity.START), 0)
            }
        }
        row.addView(go, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(lap, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row)
        col.addView(Ui.button(this, "Reiniciar", Ui.CARD, Ui.ORANGE) {
            swRun = false
            swAcc = 0L
            lapN = 0
            lapLast = 0L
            swText.text = fmt(0L)
            lapBox.removeAllViews()
            go.text = "Iniciar"
        })
        lapBox = LinearLayout(this)
        lapBox.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(this)
        sv.addView(lapBox)
        col.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    // ------------------------------------------------------------ Temporizador

    private fun stopTimerSound() {
        timerPlayer?.release()
        timerPlayer = null
    }

    private fun timerPanel(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0)
        val mins = Ui.edit(this, "Minutos", InputType.TYPE_CLASS_NUMBER)
        val secs = Ui.edit(this, "Segundos", InputType.TYPE_CLASS_NUMBER)
        val disp = Ui.text(this, "00:00", 54f, Ui.ORANGE, true)
        val go = Ui.button(this, "Iniciar") {}
        go.setOnClickListener {
            if (timerPlayer != null) {
                stopTimerSound()
                go.text = "Iniciar"
                disp.text = "00:00"
                return@setOnClickListener
            }
            if (cd != null) {
                cd?.cancel()
                cd = null
                go.text = "Iniciar"
                disp.text = "00:00"
                return@setOnClickListener
            }
            val total = ((mins.text.toString().toLongOrNull() ?: 0L) * 60L +
                (secs.text.toString().toLongOrNull() ?: 0L)) * 1000L
            if (total <= 0L) return@setOnClickListener
            go.text = "Cancelar"
            cd = object : CountDownTimer(total, 200L) {
                override fun onTick(ms: Long) {
                    val s = (ms + 999L) / 1000L
                    disp.text = String.format("%02d:%02d", s / 60, s % 60)
                }

                override fun onFinish() {
                    disp.text = "¡Tiempo!"
                    go.text = "Silenciar"
                    cd = null
                    stopTimerSound()
                    timerPlayer = Sound.create(this@MainActivity, true)
                    h.postDelayed({
                        if (timerPlayer != null) {
                            stopTimerSound()
                            go.text = "Iniciar"
                        }
                    }, 30000L)
                }
            }.start()
        }
        col.addView(mins)
        col.addView(secs)
        col.addView(disp)
        col.addView(go)
        return col
    }

    // ------------------------------------------------------------ Pulsos

    private fun pulsePanel(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0)
        val every = Ui.edit(this, "Cada cuántos segundos", InputType.TYPE_CLASS_NUMBER)
        every.setText("10")
        val total = Ui.edit(this, "Número de pulsos (0 = sin límite)", InputType.TYPE_CLASS_NUMBER)
        total.setText("0")
        val disp = Ui.text(this, "Pulsos: 0", 28f)
        val go = Ui.button(this, "Iniciar") {}
        go.setOnClickListener {
            if (pulsing) {
                pulsing = false
                pulseRun?.let { h.removeCallbacks(it) }
                go.text = "Iniciar"
                return@setOnClickListener
            }
            val sec = (every.text.toString().toLongOrNull() ?: 10L).coerceAtLeast(1L)
            val max = total.text.toString().toIntOrNull() ?: 0
            pulses = 0
            pulsing = true
            go.text = "Detener"
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            val r = object : Runnable {
                override fun run() {
                    if (!pulsing) return
                    pulses++
                    disp.text = "Pulsos: " + pulses
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    if (max > 0 && pulses >= max) {
                        pulsing = false
                        go.text = "Iniciar"
                    } else {
                        h.postDelayed(this, sec * 1000L)
                    }
                }
            }
            pulseRun = r
            h.post(r)
        }
        col.addView(every)
        col.addView(total)
        col.addView(disp)
        col.addView(go)
        return col
    }

    // ------------------------------------------------------------ Clases

    private fun fmtDate(ms: Long): String {
        if (ms == 0L) return "sin elegir"
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return String.format("%02d/%02d/%04d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
    }

    private fun daysText(mask: Int): String =
        calDays.indices.filter { ((mask shr (calDays[it] - 1)) and 1) == 1 }
            .joinToString(" ") { dayNames[it] }

    private fun classesPanel(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0)
        col.addView(Ui.text(this, "Horario universitario", 18f, Ui.ORANGE, true, Gravity.START))
        col.addView(
            Ui.text(this, "Te aviso 30 minutos antes de cada clase, solo dentro del semestre.", 13f, Color.GRAY, false, Gravity.START)
        )
        semStartBtn = Ui.button(this, "", Ui.PURPLE, Color.WHITE) { pickDate(true) }
        semEndBtn = Ui.button(this, "", Ui.PURPLE, Color.WHITE) { pickDate(false) }
        col.addView(semStartBtn)
        col.addView(semEndBtn)
        col.addView(Ui.button(this, "+ Nueva materia") { editSubject(null) })
        classList = LinearLayout(this)
        classList.orientation = LinearLayout.VERTICAL
        col.addView(classList)
        sv.addView(col)
        return sv
    }

    private fun pickDate(isStart: Boolean) {
        val cur = Calendar.getInstance()
        val v = if (isStart) Classes.ini(this) else Classes.fin(this)
        if (v != 0L) cur.timeInMillis = v
        DatePickerDialog(this, { _, y, m, d ->
            val c = Calendar.getInstance()
            c.set(y, m, d, 0, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            Classes.setDate(this, isStart, c.timeInMillis)
            Classes.rescheduleAll(this)
            refreshClasses()
        }, cur.get(Calendar.YEAR), cur.get(Calendar.MONTH), cur.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun refreshClasses() {
        semStartBtn.text = "Inicio del semestre: " + fmtDate(Classes.ini(this))
        semEndBtn.text = "Fin del semestre: " + fmtDate(Classes.fin(this))
        classList.removeAllViews()
        val l = Classes.list(this)
        if (l.isEmpty()) classList.addView(Ui.text(this, "Sin materias todavía", 16f, Color.GRAY))
        for (s in l) {
            val card = Ui.card(this)
            card.addView(Ui.text(this, s.name, 20f, Ui.ORANGE, true, Gravity.START))
            card.addView(
                Ui.text(
                    this, daysText(s.days) + " · " + t12(s.sh, s.sm) + " – " + t12(s.eh, s.em),
                    14f, Color.LTGRAY, false, Gravity.START
                )
            )
            val btns = LinearLayout(this)
            btns.addView(Ui.button(this, "Editar") { editSubject(s) }, LinearLayout.LayoutParams(0, -2, 1f))
            btns.addView(Ui.button(this, "Borrar") {
                Classes.remove(this, s.id)
                refreshClasses()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(btns)
            classList.addView(card)
        }
    }

    private fun editSubject(old: Subject?) {
        val s = old ?: Subject((System.currentTimeMillis() % 1000000000L).toInt(), "", 0, 8, 0, 9, 0)
        var sh = s.sh
        var sm = s.sm
        var eh = s.eh
        var em = s.em
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0)
        val name = Ui.edit(this, "Materia (ej. Cálculo)")
        name.setText(s.name)
        col.addView(name)
        col.addView(Ui.text(this, "Días de clase", 15f, Ui.ORANGE, true, Gravity.START))
        val row = LinearLayout(this)
        val boxes = mutableListOf<CheckBox>()
        for (i in calDays.indices) {
            val cb = CheckBox(this)
            cb.text = dayLetters[i]
            cb.isChecked = ((s.days shr (calDays[i] - 1)) and 1) == 1
            boxes.add(cb)
            row.addView(cb, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(row)
        val startBtn = Ui.button(this, "") {}
        val endBtn = Ui.button(this, "") {}
        startBtn.text = "Empieza: " + t12(sh, sm)
        endBtn.text = "Termina: " + t12(eh, em)
        startBtn.setOnClickListener {
            TimePickerDialog(this, { _, hr, mn ->
                sh = hr
                sm = mn
                startBtn.text = "Empieza: " + t12(sh, sm)
            }, sh, sm, false).show()
        }
        endBtn.setOnClickListener {
            TimePickerDialog(this, { _, hr, mn ->
                eh = hr
                em = mn
                endBtn.text = "Termina: " + t12(eh, em)
            }, eh, em, false).show()
        }
        col.addView(startBtn)
        col.addView(endBtn)
        val sv = ScrollView(this)
        sv.addView(col)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Materia")
            .setView(sv)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var mask = 0
            for (i in boxes.indices) if (boxes[i].isChecked) mask = mask or (1 shl (calDays[i] - 1))
            if (name.text.toString().isBlank() || mask == 0) {
                Toast.makeText(this, "Escribe la materia y elige al menos un día", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            s.name = name.text.toString().trim()
            s.days = mask
            s.sh = sh
            s.sm = sm
            s.eh = eh
            s.em = em
            Classes.upsert(this, s)
            refreshClasses()
            dlg.dismiss()
            if (Classes.ini(this) == 0L || Classes.fin(this) == 0L) {
                Toast.makeText(this, "Falta elegir inicio y fin del semestre", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------ Ajustes

    private fun refreshSound() {
        val s = Sound.prefs(this).getString("sonido", null)
        soundLabel.text = "Sonido actual: " + (if (s == null) "predeterminado" else fileName(this, s))
    }

    private fun settingsPanel(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16))
        col.addView(Ui.text(this, "Sonido de la alarma", 18f, Ui.ORANGE, true, Gravity.START))
        soundLabel = Ui.text(this, "", 14f, Color.LTGRAY, false, Gravity.START)
        col.addView(soundLabel)
        col.addView(Ui.button(this, "🎵  Elegir canción del dispositivo") {
            soundCb = { refreshSound() }
            pickFile(12, arrayOf("audio/*"))
        })
        col.addView(Ui.button(this, "Probar sonido", Ui.PURPLE, Color.WHITE) {
            testPlayer?.release()
            testPlayer = Sound.create(this, false)
            h.postDelayed({
                testPlayer?.release()
                testPlayer = null
            }, 6000L)
        })
        col.addView(Ui.button(this, "Usar sonido predeterminado", Ui.CARD, Ui.ORANGE) {
            Sound.prefs(this).edit().remove("sonido").apply()
            refreshSound()
        })

        col.addView(Ui.text(this, "WhatsApp", 18f, Ui.ORANGE, true, Gravity.START))
        col.addView(
            Ui.text(
                this,
                "Código de país para números guardados sin «+» (ej. 52 México, 57 Colombia, 34 España):",
                13f, Color.GRAY, false, Gravity.START
            )
        )
        val pais = Ui.edit(this, "Código de país", InputType.TYPE_CLASS_NUMBER)
        pais.setText(Sound.prefs(this).getString("pais", ""))
        Ui.watch(pais) { Sound.prefs(this).edit().putString("pais", it.trim()).apply() }
        col.addView(pais)

        col.addView(Ui.text(this, "Permisos", 18f, Ui.ORANGE, true, Gravity.START))
        col.addView(
            Ui.text(
                this,
                "• «Mostrar sobre otras apps»: para que las acciones se ejecuten solas.\n" +
                    "• Accesibilidad → Hall o’ clock: para que toque «Enviar» en WhatsApp. " +
                    "Si Android lo bloquea, ve a Información de la app → ⋮ → «Permitir ajustes restringidos».",
                13f, Color.GRAY, false, Gravity.START
            )
        )
        col.addView(Ui.button(this, "Permitir mostrar sobre otras apps") { openOverlaySettings() })
        col.addView(Ui.button(this, "Abrir ajustes de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        sv.addView(col)
        return sv
    }
}
