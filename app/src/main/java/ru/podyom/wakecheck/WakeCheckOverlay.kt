package ru.podyom.wakecheck

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import ru.podyom.app
import ru.podyom.alarm.AlarmNotifications
import ru.podyom.core.AlarmRun
import ru.podyom.core.checkKey
import ru.podyom.core.CheckConfirmation
import ru.podyom.data.TechnicalEvent
import ru.podyom.data.enhancedChecks

/** A bounded, non-modal panel. It never handles touches outside its own window. */
class WakeCheckOverlay(private val context:Context,private val scope:CoroutineScope) {
    private val windows=context.getSystemService(WindowManager::class.java)
    private var panel:View?=null
    private var shownRun: AlarmRun? = null
    private var timer:Job?=null
    private var deadline=0L
    fun refresh(run:AlarmRun?,foreground:Boolean) {
        if(run==null || foreground || !enhancedChecks(context) || !Settings.canDrawOverlays(context) ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked || System.currentTimeMillis()>=run.deadline) {close();return}
        if (shownRun?.checkKey == run.checkKey && deadline == run.deadline && panel != null) return
        close()
        val dp=context.resources.displayMetrics.density
        val view=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;setPadding((24*dp).toInt(),(24*dp).toInt(),(24*dp).toInt(),(20*dp).toInt())
            background=GradientDrawable().apply {setColor(Color.rgb(21,33,40));cornerRadius=24*dp;setStroke((2*dp).toInt(),Color.rgb(107,228,196))}
        }
        fun text(value:String,size:Float)=TextView(context).apply {text=value;textSize=size;setTextColor(Color.rgb(231,242,242));setPadding(0,0,0,(12*dp).toInt());view.addView(this)}
        text("ПОДЪЁМ · ПРОВЕРКА ПРОБУЖДЕНИЯ",13f)
        text("ТЫ ТОЧНО ПРОСНУЛСЯ?",24f).setTypeface(null,Typeface.BOLD)
        text("Подтверди пробуждение, чтобы будильник не повторился",16f)
        val countdown=text(remainingText(run.deadline,System.currentTimeMillis()),44f)
        fun open() {
            try {
                // Direct user action on a visible, user-approved Android overlay.
                context.startActivity(CheckRoute(run.checkKey, run.version, "overlay").intent(context))
            } catch (_: RuntimeException) {
                context.app.journal.report(TechnicalEvent.LAUNCH_ERROR, run.id, run.version, "overlay")
                AlarmNotifications.failure(context, "Откройте проверку из уведомления")
            }
        }
        view.addView(Button(context).apply {
            text=if(run.plan.strictCheck) "ОТКРЫТЬ ПРОВЕРКУ С ЗАДАНИЕМ" else "ДА, Я ПРОСНУЛСЯ"
            minHeight=(56*dp).toInt();isAllCaps=false
            setOnClickListener {
                if(run.plan.strictCheck) open() else scope.launch {
                    isEnabled = false
                    try {
                        when (context.app.engine.confirmWake(run.checkKey)) {
                            CheckConfirmation.ACCEPTED, CheckConfirmation.ALREADY_CONFIRMED -> {
                                Toast.makeText(context, ru.podyom.wakecheck.wakeConfirmedText(context.app.engine.cancelledBackups(run.id)), Toast.LENGTH_SHORT).show()
                                if (shownRun?.checkKey == run.checkKey) close()
                            }
                            else -> countdown.text = "Проверка уже изменилась"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        countdown.text = "Подтверждение не удалось"
                        isEnabled = true
                    }
                }
            }
        })
        text("Если не подтвердить, сигнал повторится",13f)
        view.addView(Button(context).apply {text="Открыть / аварийное завершение";isAllCaps=false;setOnClickListener {open()}})
        val width=minOf((context.resources.displayMetrics.widthPixels-32*dp).toInt(),(420*dp).toInt())
        val panelBackground=view.background
        view.background=null
        val scroll=ScrollView(context).apply {background=panelBackground;addView(view)}
        val height=minOf((context.resources.displayMetrics.heightPixels-120*dp).toInt(),(560*dp).toInt()).coerceAtLeast((180*dp).toInt())
        val params=WindowManager.LayoutParams(width,height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {gravity=Gravity.CENTER}
        try {
            windows.addView(scroll,params)
            panel = scroll
            shownRun = run
            deadline = run.deadline
            context.app.journal.report(TechnicalEvent.OVERLAY_SHOWN, run.id, run.version, "overlay")
            timer=scope.launch {while(isActive) {
                val now=System.currentTimeMillis()
                if(now>=run.deadline || !Settings.canDrawOverlays(context) || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {close();break}
                countdown.text=remainingText(run.deadline,now);delay(250)
            }}
        } catch(e:RuntimeException) {
            context.app.journal.report(TechnicalEvent.LAUNCH_ERROR, run.id, run.version, "overlay_window")
            context.createDeviceProtectedStorageContext().getSharedPreferences("presentation",Context.MODE_PRIVATE)
                .edit().putString("overlayError",e.javaClass.simpleName).apply()
            close() // The already-posted alarm notification remains the fallback.
        }
    }
    val showing get() = panel != null
    fun close() {
        timer?.cancel();timer=null
        panel?.let {runCatching {windows.removeViewImmediate(it)}};panel = null
        shownRun?.let { context.app.journal.report(TechnicalEvent.OVERLAY_DISMISSED, it.id, it.version, "overlay") }
        shownRun = null
        deadline = 0
    }
}
