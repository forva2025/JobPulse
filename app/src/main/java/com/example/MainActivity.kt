package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.notifications.JobNotificationManager
import com.example.ui.JobPulseApp
import com.example.ui.theme.JobPulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        JobNotificationManager.ensureChannels(applicationContext)
        setContent {
            JobPulseTheme {
                JobPulseApp()
            }
        }
    }
}
