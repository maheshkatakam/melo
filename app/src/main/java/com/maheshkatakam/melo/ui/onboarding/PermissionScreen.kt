package com.maheshkatakam.melo.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.theme.Coral
import com.maheshkatakam.melo.ui.theme.Violet

@Composable
fun PermissionScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val audioKey = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        denied = result[audioKey] != true
        container.music.onPermissionChanged()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(Coral, Violet))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Melo", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your offline music player that tunes the sound to your mood.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Feature(Icons.Rounded.GraphicEq, "Mood sound modes", "Happy, Party, Chill and more, each with its own bass and EQ.")
        Feature(Icons.Rounded.AutoAwesome, "English lyrics, even for regional songs", "An on-device AI listens and writes them for you.")
        Feature(Icons.Rounded.WifiOff, "Fully offline", "Your music never leaves your phone.")
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { launcher.launch(permissions.toTypedArray()) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Allow access to my music") }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Melo needs access to your audio files to play them. If the prompt doesn't appear, allow it in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Open app settings") }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
