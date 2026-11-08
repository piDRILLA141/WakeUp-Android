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
import ru.podyom.data.enhancedChecks

/** A bounded, non-modal panel. It never handles touches outside its own window. */
class WakeCheckOverlay(private val context:Context,private val scope:CoroutineScope) {
    private val windows=context.getSystemService(WindowManager::class.java)
    private var panel:View?=null
    private var currentId:String?=null
    private var timer:Job?=null
    private var deadline=0L
    fun refresh(run:AlarmRun?,foreground:Boolean) {
        if(run==null || foreground || !enhancedChecks(context) || !Settings.canDrawOverlays(context) ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked || System.currentTimeMillis()>=run.deadline) {close();return}
        if(currentId==run.id && deadline==run.deadline && panel!=null) return
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
        fun open() {runCatching {AlarmNotifications.activity(context,run.id).send()}.onFailure {close()}}
        view.addView(Button(context).apply {
            text=if(run.plan.strictCheck) "ОТКРЫТЬ ПРОВЕРКУ С ЗАДАНИЕМ" else "ДА, Я ПРОСНУЛСЯ"
            minHeight=(56*dp).toInt();isAllCaps=false
            setOnClickListener {
                if(run.plan.strictCheck) open() else scope.launch {
                    runCatching {context.app.engine.confirmCheck(run.id,expectedVersion=run.version)}.onSuccess {close()}.onFailure {countdown.text=it.message}
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
            windows.addView(scroll,params);panel=scroll;currentId=run.id;deadline=run.deadline
            timer=scope.launch {while(isActive) {
                val now=System.currentTimeMillis()
                if(now>=run.deadline || !Settings.canDrawOverlays(context) || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {close();break}
                countdown.text=remainingText(run.deadline,now);delay(250)
            }}
        } catch(e:RuntimeException) {
            context.createDeviceProtectedStorageContext().getSharedPreferences("presentation",Context.MODE_PRIVATE)
                .edit().putString("overlayError",e.javaClass.simpleName).apply()
            close() // The already-posted alarm notification remains the fallback.
        }
    }
    val showing get() = panel != null
    fun close() {
        timer?.cancel();timer=null
        panel?.let {runCatching {windows.removeViewImmediate(it)}};panel=null;currentId=null;deadline=0
    }
}
