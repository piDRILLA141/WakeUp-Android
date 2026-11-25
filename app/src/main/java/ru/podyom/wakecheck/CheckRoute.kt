package ru.podyom.wakecheck

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import ru.podyom.core.CheckKey
import ru.podyom.ringing.RingingActivity

data class CheckRoute(val key: CheckKey, val version: Int, val source: String) {
    fun intent(context: Context): Intent = Intent(context, RingingActivity::class.java)
        .setData("podyom://wake/${key.runId}/${key.cycle}/${key.index}/$source".toUri())
        .putExtra("runId", key.runId)
        .putExtra("checkCycle", key.cycle)
        .putExtra("checkIndex", key.index)
        .putExtra("checkVersion", version)
        .putExtra("checkSource", source)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    companion object {
        fun from(intent: Intent): CheckRoute? {
            val id = intent.getStringExtra("runId")?.takeIf { it.isNotBlank() } ?: return null
            val cycle = intent.getIntExtra("checkCycle", -1)
            val index = intent.getIntExtra("checkIndex", -1)
            if (cycle < 0 || index < 0) return null
            val source = intent.getStringExtra("checkSource").orEmpty()
            return CheckRoute(CheckKey(id, cycle, index), intent.getIntExtra("checkVersion", -1),
                if (source in setOf("notification", "full_screen", "overlay", "list")) source else "unknown")
        }
    }
}
