package com.ray.pixelbandinfo.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ray.pixelbandinfo.services.KeepAliveService

class StopAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val serviceIntent = Intent(context, KeepAliveService::class.java)
        context.stopService(serviceIntent)
        System.exit(0)
    }
}
