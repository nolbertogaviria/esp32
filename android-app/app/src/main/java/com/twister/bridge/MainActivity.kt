package com.twister.bridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.twister.bridge.service.TwisterForegroundService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val serviceIntent = Intent(this, TwisterForegroundService::class.java)
        startForegroundService(serviceIntent)

        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

        finish()
    }
}
