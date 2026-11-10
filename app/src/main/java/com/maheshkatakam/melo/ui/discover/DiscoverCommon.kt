package com.maheshkatakam.melo.ui.discover

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.maheshkatakam.melo.data.db.DiscoveryEntity
import com.maheshkatakam.melo.ui.components.GeneratedCover
import java.io.File
import java.net.URLEncoder

private const val SHAZAM_PACKAGE = "com.shazam.android"

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open that link", Toast.LENGTH_SHORT).show()
    }
}

fun openShazam(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(SHAZAM_PACKAGE)
    if (launch != null) {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(context, "When Shazam finds it, tap Share › Save to Melo", Toast.LENGTH_LONG).show()
    } else {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHAZAM_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            openUrl(context, "https://play.google.com/store/apps/details?id=$SHAZAM_PACKAGE")
        }
    }
}

fun youtubeSearchUrl(d: DiscoveryEntity): String =
    "https://www.youtube.com/results?search_query=" +
        URLEncoder.encode(listOfNotNull(d.title, d.artist).joinToString(" "), "UTF-8")

@Composable
fun DiscoveryArt(d: DiscoveryEntity, shape: Shape, modifier: Modifier = Modifier) {
    Box(modifier.clip(shape)) {
        GeneratedCover(seed = d.title ?: "discovered-${d.id}", modifier = Modifier.fillMaxSize())
        d.artworkPath?.let { path ->
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
