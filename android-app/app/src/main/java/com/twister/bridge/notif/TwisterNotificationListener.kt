package com.twister.bridge.notif

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.twister.bridge.protocol.TwisterNotifSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.twister.bridge.service.TwisterForegroundService

class TwisterNotificationListener : NotificationListenerService() {

    companion object {
        /** Referencia a la instancia activa del listener (null si no conectado). */
        @Volatile var instance: TwisterNotificationListener? = null

        private const val OSMAND_FREE_PKG   = "net.osmand"
        private const val OSMAND_PLUS_PKG   = "net.osmand.plus"

        /** Packages que generan instrucciones de navegacion GPS (TURN). */
        private val navPackages = setOf(OSMAND_FREE_PKG, OSMAND_PLUS_PKG)
    }

    private val allowedPackages = setOf(
        "com.whatsapp",
        "org.telegram.messenger",
        "com.google.android.dialer"
    )

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (mapSource(sbn.packageName) == null) return
        logNotifHistory(sbn, "REMOVED")
        syncActiveNotifications()
    }

    private fun logNotifHistory(sbn: StatusBarNotification, event: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text  = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            File(filesDir, "notif_history.log")
                .appendText("$ts | $event | ${sbn.packageName} | $title | $text\n")
        } catch (e: Exception) {
            Log.w("TwisterNotif", "History log error: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val flags = sbn.notification.flags
        val isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0

        // Log diagnostico para navPackages ANTES de cualquier filtro
        if (sbn.packageName in navPackages) {
            val extras = sbn.notification.extras
            val t = extras.getString("android.title") ?: "<null>"
            val x = extras.getCharSequence("android.text")?.toString() ?: "<null>"
            Log.i("TwisterNav", "pkg=${sbn.packageName} flags=0x${flags.toString(16)}" +
                    " groupSummary=$isGroupSummary id=${sbn.id}" +
                    " title='$t' text='$x'")
            if (!isGroupSummary) {
                handleNavNotif(sbn)
            }
            return
        }

        if (isGroupSummary) return
        logNotifHistory(sbn, "POSTED")

        val source = mapSource(sbn.packageName) ?: return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: "(sin titulo)"
        val text = extractText(extras)

        Log.i("TwisterNotif", "Notif [${sbn.packageName}] title=$title text=$text")

        if (source == TwisterNotifSource.WHATSAPP) {
            val db = com.twister.bridge.db.TwisterDbHelper.getInstance(applicationContext)
            Thread {
                db.insertNotification(title, text)
            }.start()
        }

        val intent = Intent(TwisterForegroundService.ACTION_FORWARD_NOTIF)
            .setPackage(packageName)
            .putExtra(TwisterForegroundService.EXTRA_NOTIF_SOURCE, source.raw.toInt())
            .putExtra(TwisterForegroundService.EXTRA_NOTIF_TITLE, title.take(32))
            .putExtra(TwisterForegroundService.EXTRA_NOTIF_BODY, text.take(64))
        sendBroadcast(intent)
    }

    // ── Navegacion GPS (OsmAnd) ──────────────────────────────────────────────────

    /**
     * Handler de notificaciones de OsmAnd (fallback cuando el bridge AIDL no está activo).
     * OsmAnd publica la instrucción en android.title y la distancia en android.text/subText.
     */
    private fun handleNavNotif(sbn: StatusBarNotification) {
        val extras  = sbn.notification.extras
        val title   = extras.getString("android.title")?.trim() ?: return
        val text    = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence("android.subText")?.toString()?.trim() ?: ""

        Log.i("TwisterNotif", "OsmAnd raw: title='$title' text='$text' bigText='$bigText' subText='$subText'")

        // Log completo al archivo: todos los extras del Bundle para análisis
        val extrasDump = extras.keySet()
            .sortedWith(compareBy({ if (it.startsWith("android.")) 0 else 1 }, { it }))
            .joinToString(" ") { k ->
                val v = try { extras.get(k)?.toString()?.take(120) ?: "<null>" }
                        catch (_: Exception) { "<err>" }
                "$k=«$v»"
            }
        com.twister.bridge.nav.OsmAndInstructionLog.append(
            applicationContext, "NOTIF",
            "pkg=${sbn.packageName} id=${sbn.id} flags=0x${sbn.notification.flags.toString(16)}" +
            " cat=${sbn.notification.category ?: "-"}\n" +
            "        title=«$title» text=«$text» subText=«$subText» bigText=«${bigText.take(80)}»\n" +
            "        EXTRAS=[$extrasDump]"
        )

        // Si el bridge AIDL ya gestiona OsmAnd con datos estructurados, ignorar notificacion
        if (com.twister.bridge.nav.OsmAndNavigationBridge.active) {
            Log.d("TwisterNotif", "OsmAnd AIDL activo, omitiendo notificacion")
            return
        }

        // OsmAnd title: "{dist_al_giro} • {instrucción}"  ej. "50 m • Gira a la izquierda en"
        // Caso transitorio: "0 m • " (sin instrucción) → ignorar
        val titleParts     = title.split("•", limit = 2)
        val maneuverDist   = titleParts[0].trim()                       // "50 m"
        val instrFromTitle = titleParts.getOrElse(1) { "" }.trim()     // "Gira a la izquierda en"

        if (instrFromTitle.isBlank()) {
            Log.d("TwisterNotif", "OsmAnd: estado transitorio '0 m •', ignorando")
            return
        }

        // BigText primera línea: "{instrucción} {nombre de calle} {dist_siguiente_tramo}"
        // ej. "Gira a la izquierda en Calle 13 60 m"
        val bigFirstLine = bigText.lines().firstOrNull()?.trim() ?: ""

        val direction  = parseDirection(instrFromTitle)
        val exitNumber = if (direction == 6) parseExitNumber(instrFromTitle) else 0
        val distanceM  = parseDistanceMeters(maneuverDist) ?: 0
        val roadName   = extractRoadNameFromBigText(instrFromTitle, bigFirstLine)

        Log.i("TwisterNotif", "OsmAnd TURN: dir=$direction exit=$exitNumber dist=${distanceM}m road='$roadName'")
        com.twister.bridge.nav.OsmAndInstructionLog.append(
            applicationContext, "PARSE",
            "dir=$direction exit=$exitNumber dist=${distanceM}m road=«road» " +
            "(manDist=«$maneuverDist» instr=«$instrFromTitle» bigLine=«$bigFirstLine»)"
        )
        val intent = Intent(TwisterForegroundService.ACTION_FORWARD_TURN)
            .setPackage(packageName)
            .putExtra(TwisterForegroundService.EXTRA_TURN_DIR,  direction)
            .putExtra(TwisterForegroundService.EXTRA_TURN_DIST, distanceM)
            .putExtra(TwisterForegroundService.EXTRA_TURN_ROAD, roadName.take(40))
            .putExtra(TwisterForegroundService.EXTRA_TURN_EXIT, exitNumber)
        sendBroadcast(intent)
    }

    /**
     * Detecta la dirección de giro en el texto de la notificación.
     * Soporta múltiples formas verbales en español e inglés.
     * Valores (enum ESP32): STRAIGHT=0, LEFT=1, RIGHT=2, UTURN=3, EXIT_LEFT=4, EXIT_RIGHT=5
     */
    private fun parseDirection(text: String): Int {
        val t = text.lowercase()

        // Señales de izquierda / derecha (presencia en cualquier contexto)
        val hasLeft  = "izquierda" in t || " left" in t || "izq." in t
        val hasRight = "derecha"   in t || " right" in t
        // Señales de salida / incorporación / rampa
        val hasExit  = "salida" in t || "exit" in t || "rampa" in t ||
                       "ramp" in t   || "incorpora" in t || "merge" in t

        return when {
            // ── Giro en U ───────────────────────────────────────────────
            "giro en u" in t || "u-turn" in t || "da la vuelta" in t ||
            "haz un giro" in t || "vuelta en u" in t || "media vuelta" in t -> 3  // UTURN

            // ── Rotonda / glorieta ───────────────────────────────────────
            // Devuelve 6 (ROUNDABOUT); el número de salida lo extrae parseExitNumber()
            "rotonda" in t || "roundabout" in t || "glorieta" in t -> 6

            // ── Salidas de autopista / rampa ─────────────────────────────
            hasExit && hasLeft  -> 4   // EXIT_LEFT
            hasExit && hasRight -> 5   // EXIT_RIGHT
            hasExit             -> 5   // EXIT_RIGHT (sin dirección explícita → derecha por defecto)

            // ── Giros normales ────────────────────────────────────────────
            hasLeft  -> 1   // LEFT
            hasRight -> 2   // RIGHT

            // ── Recto / destino / sin coincidencia ────────────────────────
            else -> 0  // STRAIGHT
        }
    }

    /**
     * Extrae el número de salida de una instrucción de rotonda.
     * Soporta "1.ª salida", "2nd exit", "primera salida", etc.
     */
    private fun parseExitNumber(text: String): Int {
        val t = text.lowercase()
        Regex("""(\d+)\s*[.ºª]""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return when {
            "primera" in t || "1st" in t -> 1
            "segunda" in t || "2nd" in t -> 2
            "tercera" in t || "3rd" in t -> 3
            "cuarta"  in t || "4th" in t -> 4
            "quinta"  in t || "5th" in t -> 5
            "sexta"   in t || "6th" in t -> 6
            "septima" in t || "7th" in t -> 7
            else -> 0
        }
    }

    /**
     * Extrae la distancia en metros de cadenas como "120 m", "1.2 km", "1,2 km".
     */
    private fun parseDistanceMeters(text: String): Int? {
        val t = text.lowercase()
        // "1.2 km" o "1,2 km"
        Regex("""(\d+)[.,](\d)\s*km""").find(t)?.let {
            val whole = it.groupValues[1].toIntOrNull() ?: return@let
            val frac  = it.groupValues[2].toIntOrNull() ?: return@let
            return whole * 1000 + frac * 100
        }
        // "2 km"
        Regex("""(\d+)\s*km\b""").find(t)?.let {
            return (it.groupValues[1].toIntOrNull() ?: return@let) * 1000
        }
        // "120 m"
        Regex("""(\d+)\s*m\b""").find(t)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    /**
     * Extrae el nombre de la vía desde la primera línea del bigText.
     * BigText: "{instrucción} {nombre de calle} {dist_siguiente_tramo}"
     * Ej: "Gira a la izquierda en Calle 13 60 m" → "Calle 13"
     */
    private fun extractRoadNameFromBigText(instrPhrase: String, bigLine: String): String {
        if (bigLine.isBlank()) return ""
        // Quitar el prefijo de instrucción (ignorando mayúsculas)
        val stripped = if (bigLine.lowercase().startsWith(instrPhrase.lowercase())) {
            bigLine.drop(instrPhrase.length).trim()
        } else {
            // Buscar "en <vía>" como fallback
            Regex("""(?:en |onto |on |hacia )(.+)""", RegexOption.IGNORE_CASE)
                .find(bigLine)?.groupValues?.get(1)?.trim() ?: bigLine
        }
        // Quitar la distancia del siguiente tramo al final: "Calle 13 60 m" → "Calle 13"
        return stripped
            .replace(Regex("""\s+\d+[,.]\d+\s*km\s*$"""), "")
            .replace(Regex("""\s+\d+\s*km\s*$"""), "")
            .replace(Regex("""\s+\d+\s*m\s*$"""), "")
            .trim()
    }

    /**
     * WhatsApp y Telegram usan MessagingStyle: el texto real está en android.messages[].
     * Fallback a android.text / android.bigText para otras apps.
     */
    private fun extractText(extras: Bundle): String {
        // 1) MessagingStyle: último mensaje del array android.messages
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val messages = extras.getParcelableArray("android.messages")
            if (!messages.isNullOrEmpty()) {
                val last = messages.last()
                if (last is Bundle) {
                    val msgText = last.getCharSequence("text")?.toString()
                    if (!msgText.isNullOrBlank()) return msgText
                }
            }
        }
        // 2) BigText (notificaciones expandidas)
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        if (!bigText.isNullOrBlank()) return bigText
        // 3) Texto normal
        return extras.getCharSequence("android.text")?.toString() ?: ""
    }

    private fun mapSource(pkg: String): TwisterNotifSource? = when (pkg) {
        "com.whatsapp"            -> TwisterNotifSource.WHATSAPP
        "org.telegram.messenger"  -> TwisterNotifSource.TELEGRAM
        "com.google.android.dialer" -> TwisterNotifSource.CALL
        else -> null
    }

    /**
     * Consulta las notificaciones activas del sistema y reenvía las de apps permitidas
     * al servicio BLE (hasta 3 más recientes, de más antigua a más nueva para que la
     * última quede visible en la moto).
     */
    /** Mantiene compatibilidad con llamadas existentes. */
    fun forwardActiveNotifications() = syncActiveNotifications()

    /**
     * Devuelve el texto de la notificación de navegación activa de OsmAnd, o null si no hay.
     * Usado por el heartbeat de TwisterForegroundService para polling periódico.
     */
    fun getActiveNavText(): String? {
        return try {
            getActiveNotifications()
                ?.firstOrNull {
                    it.packageName in navPackages &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
                }
                ?.let { sbn ->
                    val extras  = sbn.notification.extras
                    val title   = extras.getString("android.title")?.trim() ?: return@let null
                    val text    = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
                    val bigText = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""
                    val subText = extras.getCharSequence("android.subText")?.toString()?.trim() ?: ""
                    listOf(title, bigText.ifBlank { text }, subText)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .take(80)
                        .takeIf { it.isNotBlank() }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Envía ACTION_SYNC_NOTIFS al servicio con la lista completa de notificaciones
     * actualmente activas (máx. 5, de más antigua a más nueva). El servicio limpia
     * la cola del ESP32 (CLEAR_NOTIF) y reenvía todas, dejando la más reciente en idx=0.
     */
    fun syncActiveNotifications() {
        try {
            val active = getActiveNotifications() ?: return
            val filtered = active
                .filter { mapSource(it.packageName) != null }
                .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .takeLast(5)

            val sources = IntArray(filtered.size)
            val titles  = Array(filtered.size) { "" }
            val bodies  = Array(filtered.size) { "" }

            filtered.forEachIndexed { i, sbn ->
                val source = mapSource(sbn.packageName) ?: return@forEachIndexed
                val extras = sbn.notification.extras
                sources[i] = source.raw.toInt()
                titles[i]  = (extras.getString("android.title") ?: "(sin titulo)").take(32)
                bodies[i]  = extractText(extras).take(64)
                Log.i("TwisterNotif", "Sync [$i] ${sbn.packageName} | ${titles[i]}")
            }

            val intent = Intent(TwisterForegroundService.ACTION_SYNC_NOTIFS)
                .setPackage(packageName)
                .putExtra(TwisterForegroundService.EXTRA_NOTIF_SOURCES, sources)
                .putExtra(TwisterForegroundService.EXTRA_NOTIF_TITLES, titles)
                .putExtra(TwisterForegroundService.EXTRA_NOTIF_BODIES, bodies)
            sendBroadcast(intent)
        } catch (e: SecurityException) {
            Log.w("TwisterNotif", "Sin acceso a notificaciones activas: ${e.message}")
        }
    }
}


