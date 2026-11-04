package com.pcmdk369.superioa.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 11, 20)
        window.navigationBarColor = android.graphics.Color.rgb(7, 11, 20)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4E9BFF),
                    secondary = Color(0xFF61D8FF),
                    background = Color(0xFF070B14),
                    surface = Color(0xFF101A2B),
                    onBackground = Color(0xFFEAF4FF),
                    onSurface = Color(0xFFEAF4FF)
                )
            ) {
                AppRoot()
            }
        }
    }
}
