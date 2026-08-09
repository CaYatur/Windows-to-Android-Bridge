package com.cayatur.winbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Home() } }
    }
}

@Composable
private fun Home() {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp)) {
            Text("WinBridge", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Bağlantı katmanı kuruluyor.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
