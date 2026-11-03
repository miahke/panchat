package com.bitchat.android.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.bitchat.android.MainActivity

/** Routes first launch before constructing radio-dependent chat UI. */
class NetworkLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (NetworkSetupPreferences.from(this).completed) MainActivity::class.java else NetworkSetupActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
