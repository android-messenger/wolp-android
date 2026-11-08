package org.wolpmessenger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import org.wolpmessenger.p1.ClientCreds
import org.wolpmessenger.p1.P1Log
import org.wolpmessenger.p1.Timings
import kotlin.concurrent.thread

/** S1 §3 gate (P1 subset): app.<host> is not loaded before credentials exist. */
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timings.mark("splash.onCreate")
        setContentView(TextView(this).apply { text = "Wolp P1: проверка устройства…"; textSize = 18f; setPadding(48, 200, 48, 48) })
        thread(name = "p1-creds") {
            val r = runCatching { ClientCreds.ensure(applicationContext, BuildConfig.WOLP_HOST) }
            Timings.mark("credsReady(ok=${r.isSuccess})")
            runOnUiThread {
                if (r.isFailure) {
                    P1Log.w("creds: ensure failed", r.exceptionOrNull())
                    setContentView(TextView(this).apply { text = "Нет связи со стендом: ${r.exceptionOrNull()}"; setPadding(48, 200, 48, 48) })
                    return@runOnUiThread
                }
                startActivity(Intent(this, MainActivity::class.java).setData(intent.data))
                finish()
            }
        }
    }
}
