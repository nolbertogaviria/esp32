package com.twister.bridge.nav

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger de instrucciones OsmAnd a archivo para análisis offline.
 *
 * Archivo: <filesDir>/osmand_instructions.log
 * Recuperar con:  adb pull /data/data/com.twister.bridge/files/osmand_instructions.log
 *
 * Formato de línea:
 *   2026-06-01 12:34:56.789 [AIDL]  turnType=2 distanceTo=450m isLeftSide=false → 2(RIGHT)
 *   2026-06-01 12:34:57.012 [NOTIF] pkg=net.osmand id=42 flags=0x2 cat=navigation
 *                                   android.title=«Gira a la derecha» android.text=«en 450 m»
 *                                   android.subText=«» android.bigText=«» EXTRAS=[…]
 *   2026-06-01 12:34:57.014 [PARSE] dir=2(RIGHT) dist=450m road=«Av Corrientes»
 */
object OsmAndInstructionLog {

    const val FILE_NAME = "osmand_instructions.log"
    private const val TAG = "OsmAndLog"
    private const val MAX_BYTES = 2 * 1024 * 1024L   // rotar al superar 2 MB

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** Añade una línea al archivo. Nunca lanza excepción. */
    fun append(context: Context, tag: String, line: String) {
        try {
            val f = File(context.filesDir, FILE_NAME)
            // Rotación simple: renombra el archivo anterior cuando supera el límite
            if (f.exists() && f.length() > MAX_BYTES) {
                f.renameTo(File(context.filesDir, "$FILE_NAME.old"))
            }
            val ts = fmt.format(Date())
            f.appendText("$ts [$tag] $line\n")
        } catch (e: Exception) {
            Log.w(TAG, "Error al escribir log: ${e.message}")
        }
    }

    /** Ruta absoluta al archivo (útil para mostrar en logs al iniciar). */
    fun path(context: Context): String = File(context.filesDir, FILE_NAME).absolutePath
}
