package com.twister.bridge.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TwisterDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "twister_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_WHATSAPP = "whatsapp_notifications"
        
        private const val KEY_ID = "id"
        private const val KEY_SENDER = "sender"
        private const val KEY_MESSAGE = "message"
        private const val KEY_TIMESTAMP = "timestamp"

        @Volatile
        private var instance: TwisterDbHelper? = null

        fun getInstance(context: Context): TwisterDbHelper {
            return instance ?: synchronized(this) {
                instance ?: TwisterDbHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_WHATSAPP + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_SENDER + " TEXT,"
                + KEY_MESSAGE + " TEXT,"
                + KEY_TIMESTAMP + " INTEGER" + ")")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_WHATSAPP")
        onCreate(db)
    }

    fun insertNotification(sender: String, message: String) {
        try {
            val db = this.writableDatabase
            val values = ContentValues().apply {
                put(KEY_SENDER, sender)
                put(KEY_MESSAGE, message)
                put(KEY_TIMESTAMP, System.currentTimeMillis())
            }
            db.insert(TABLE_WHATSAPP, null, values)
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllNotifications(): List<WhatsAppNotif> {
        val list = mutableListOf<WhatsAppNotif>()
        val selectQuery = "SELECT * FROM $TABLE_WHATSAPP ORDER BY $KEY_TIMESTAMP DESC"
        try {
            val db = this.readableDatabase
            val cursor = db.rawQuery(selectQuery, null)
            
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(KEY_ID)
                val senderIndex = cursor.getColumnIndex(KEY_SENDER)
                val messageIndex = cursor.getColumnIndex(KEY_MESSAGE)
                val timestampIndex = cursor.getColumnIndex(KEY_TIMESTAMP)
                
                if (idIndex >= 0 && senderIndex >= 0 && messageIndex >= 0 && timestampIndex >= 0) {
                    do {
                        val id = cursor.getLong(idIndex)
                        val sender = cursor.getString(senderIndex) ?: ""
                        val message = cursor.getString(messageIndex) ?: ""
                        val timestamp = cursor.getLong(timestampIndex)
                        list.add(WhatsAppNotif(id, sender, message, timestamp))
                    } while (cursor.moveToNext())
                }
            }
            cursor.close()
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun deleteNotification(id: Long) {
        try {
            val db = this.writableDatabase
            db.delete(TABLE_WHATSAPP, "$KEY_ID = ?", arrayOf(id.toString()))
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteAllNotifications() {
        try {
            val db = this.writableDatabase
            db.delete(TABLE_WHATSAPP, null, null)
            db.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
