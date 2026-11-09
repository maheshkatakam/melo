package com.maheshkatakam.melo.discover

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.maheshkatakam.melo.appContainer

/**
 * Appears as "Save to Melo" in the share sheet. Saves the shared song (e.g. from Shazam)
 * to Discovered and closes straight away, so you stay where you were.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (text.isNullOrBlank() && subject.isNullOrBlank()) {
                Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            } else {
                appContainer.discoveries.saveShared(text, subject)
                Toast.makeText(this, "Saved to Melo › Discovered", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
