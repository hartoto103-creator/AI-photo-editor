package com.example.objectremover
import com.example.objectremover.objectremoverapp.ObjectRemoverApp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        setContent { ObjectRemoverApp() }
    }
}