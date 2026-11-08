package org.wolpmessenger

import android.app.Application
import org.wolpmessenger.p1.Timings

class P1App : Application() {
    override fun onCreate() {
        super.onCreate()
        Timings.mark("application.onCreate")
    }
}
