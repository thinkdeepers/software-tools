package com.cursor.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.cursor.mobile.navigation.CursorNavHost
import com.cursor.mobile.ui.theme.CursorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CursorApp
        setContent {
            CursorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CursorNavHost(container = app.container)
                }
            }
        }
    }
}
