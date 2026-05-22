package com.doltu.biofizic.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Android 12+ blochează FGS la boot → ForegroundServiceStartNotAllowedException.
        // Tracking pornește doar din MainActivity (buton Start).
        Log.i(
            "BootReceiver",
            "Boot complet — deschide Biofizic pe ceas și apasă Start (FGS nu pornește de la boot)"
        )
    }
}
