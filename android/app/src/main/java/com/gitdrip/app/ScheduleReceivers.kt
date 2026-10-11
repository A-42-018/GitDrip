package com.gitdrip.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gitdrip.app.data.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val r = goAsync()
    CoroutineScope(Dispatchers.IO).launch { try { block() } catch (_: Exception) { } finally { r.finish() } }
}

/** Exact alarm -> run next batch for that schedule; also slot retries and result-check alarms. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        when (i.action) {
            Scheduler.ACTION_FIRE, Scheduler.ACTION_RETRY -> {
                val id = i.getLongExtra(Scheduler.EXTRA_ID, -1L); if (id < 0) return
                async { try { Scheduler.fire(app, id) } catch (e: Exception) { try { Scheduler.recordError(app, id, e) } catch (_: Exception) { } } }
            }
            Scheduler.ACTION_AUTO -> {
                val id = i.getLongExtra(Scheduler.EXTRA_SLOT, -1L); if (id < 0) return
                async { Scheduler.fireAuto(app, id) }
            }
            Scheduler.ACTION_PLAN -> async { Scheduler.syncAuto(app) }
            Scheduler.ACTION_CHECK -> {
                val req = i.getStringExtra(Scheduler.EXTRA_REQ) ?: return
                val step = i.getIntExtra(Scheduler.EXTRA_STEP, 0); val t0 = i.getLongExtra(Scheduler.EXTRA_T0, System.currentTimeMillis())
                async { Scheduler.check(app, req, step, t0) }
            }
        }
    }
}

/** Boot, package update, clock / timezone change: alarms are lost or shifted, so re-arm (+ run-now catch-up). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> async { Scheduler.restore(c.applicationContext) }
        }
    }
}
