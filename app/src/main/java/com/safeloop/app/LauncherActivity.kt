package com.safeloop.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** The public launcher accepts no commands. Notifications enter MainActivity through private pending intents. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
