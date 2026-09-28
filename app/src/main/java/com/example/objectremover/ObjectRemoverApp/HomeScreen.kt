package com.example.objectremover.objectremoverapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.objectremover.HomeAccentPurple
import com.example.objectremover.IconGlyph
import com.example.objectremover.drawImageGlyph

@Composable
fun HomeScreen(
    onPickPhoto: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconGlyph(
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    ) { drawImageGlyph(it) }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onPickPhoto,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HomeAccentPurple,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(text = "Select an image",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
