package ru.podyom.missions

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.podyom.core.*

@Composable fun SensorMission(kind: MissionKind, onMovement: (Int) -> Unit, fallback: () -> Unit) {
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    val manager=remember {context.getSystemService(SensorManager::class.java)}
    val sensor=remember(kind) { if(kind==MissionKind.SHAKE) manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    var permission by remember {mutableStateOf(Build.VERSION.SDK_INT<29 || ContextCompat.checkSelfPermission(context,Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED)}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {permission=it}
    val callback by rememberUpdatedState(onMovement)
    var registered by remember {mutableStateOf(true)}
    Column {
        Text(if(kind==MissionKind.SHAKE) "Несильно встряхивайте телефон. Держите его над безопасной поверхностью." else "Пройдите несколько шагов. Считаются только новые движения.")
        if(sensor==null || !registered) { Text("Датчик недоступен"); OutlinedButton(onClick=fallback) {Text("Заменить математикой")} }
        else if(kind==MissionKind.STEPS && !permission) { Button(onClick={request.launch(Manifest.permission.ACTIVITY_RECOGNITION)}) {Text("Разрешить подсчёт шагов")}; TextButton(onClick=fallback) {Text("Выбрать математику")} }
    }
    DisposableEffect(kind,sensor,permission,owner) {
        val shake=ShakeDetector(); var steps=StepCounter()
        val listener=object:SensorEventListener {
            override fun onAccuracyChanged(sensor:Sensor?, accuracy:Int) {}
            override fun onSensorChanged(event:SensorEvent) {
                when(event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> if(shake.sample(event.values[0],event.values[1],event.values[2],event.timestamp/1_000_000)) callback(1)
                    Sensor.TYPE_STEP_DETECTOR -> callback(1)
                    Sensor.TYPE_STEP_COUNTER -> steps.sample(event.values[0].toInt()).takeIf {it>0}?.let(callback)
                }
            }
        }
        fun register() { if(sensor!=null && (kind!=MissionKind.STEPS || permission)) { steps=StepCounter(); registered=try {manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_GAME)} catch (_:SecurityException) {false} } }
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_RESUME) register() else if(event==Lifecycle.Event.ON_PAUSE) manager.unregisterListener(listener)}
        owner.lifecycle.addObserver(observer); if(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose {manager.unregisterListener(listener);owner.lifecycle.removeObserver(observer)}
    }
}
