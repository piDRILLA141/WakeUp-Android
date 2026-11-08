package ru.podyom.ringing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.podyom.App
import ru.podyom.core.*
import ru.podyom.data.model

class RingingViewModel(application: Application):AndroidViewModel(application) {
    private val app=application as App
    val runs=app.runtime.dao().observeRuns().map {list->list.map {it.model()}}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val message=MutableStateFlow("")
    private fun action(block:suspend()->Unit) {viewModelScope.launch {try {block()} catch(e:Exception) {if(e is CancellationException) throw e;message.value=e.message?:"Ошибка действия"}}}
    suspend fun profile(key:String)=app.engine.profile(key)
    fun dismissQuick(id:String)=action {app.engine.update(id,WakeMachine::dismissQuick)}
    fun start(id:String)=action {app.engine.update(id,WakeMachine::startMission)}
    fun fallback(id:String)=action {app.engine.update(id,WakeMachine::fallback)}
    fun snooze(id:String)=action {app.engine.update(id,WakeMachine::snooze)}
    fun cancel(id:String)=action {app.engine.update(id) {r,t->WakeMachine.cancel(r,t,true)}}
    fun confirm(id:String,answer:String)=action {app.engine.confirmCheck(id,answer);message.value=""}
    fun answer(id:String,token:Int,value:String)=action {
        val snapshot=app.runtime.dao().run(id)?.model()?:return@action
        val private=app.engine.profile(snapshot.plan.privateKey)
        app.engine.update(id) {r,t->
            if(r.version!=token || r.state!=RunState.MISSION_RUNNING) r else {
                val valid=when(r.mission.kind) {
                    MissionKind.MATH -> value.toIntOrNull()==Missions.math(Missions.challengeSeed(r),r.mission.difficulty).answer
                    MissionKind.TEXT -> private!=null && Missions.textMatches(private.phrase,value)
                    MissionKind.QR -> private!=null && Missions.qrMatches(private.qr,value)
                    MissionKind.MEMORY -> value==Missions.memory(Missions.challengeSeed(r),r.mission.grid,r.mission.length).joinToString(",")
                    else -> false
                }
                if(valid) {message.value="";WakeMachine.progress(r,token,t)} else {message.value="Не совпало. Попробуйте ещё раз";r}
            }
        }
    }
    fun movement(id:String,index:Int,cycle:Int,kind:MissionKind,count:Int)=action {
        app.engine.update(id) {r,t->
            if(r.state!=RunState.MISSION_RUNNING || r.missionIndex!=index || r.cycle!=cycle || r.mission.kind!=kind) r
            else { var next=r; repeat(count.coerceIn(1,20)) {if(next.missionIndex==index && next.state==RunState.MISSION_RUNNING) next=WakeMachine.progress(next,next.version,t)}; next }
        }
    }
}
