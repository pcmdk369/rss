package com.pcmdk369.superioa.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box {
                    AppRoot()
                    ExtendedFloatingActionButton(
                        onClick = { startActivity(Intent(this@MainActivity, AdminVoiceActivity::class.java)) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        text = { androidx.compose.material3.Text("Admin Voice Mode") }
                    )
                }
            }
        }
    }
}
