package ru.podyom.missions

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.podyom.core.PhotoMatcher
import ru.podyom.data.PhotoReference
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun PhotoThumbnail(reference: PhotoReference?) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, reference?.thumbnail) {
        value = withContext(Dispatchers.Default) { decodePhotoThumbnail(reference?.thumbnail) }
    }
    // Rendering may retain a bitmap through the next frame. The small preview is reclaimed with its composition.
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Миниатюра эталона", modifier = Modifier.size(96.dp)) }
}

@Composable
fun PhotoCamera(
    reference: PhotoReference?,
    onClose: () -> Unit,
    onReference: (PhotoReference) -> Unit = {},
    onMatch: () -> Unit = {},
    onError: () -> Unit = {},
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (reference == null) "Съёмка эталона" else "Фотозадание", style = MaterialTheme.typography.headlineSmall)
                Text("Наведите камеру на неподвижный предмет с заметными деталями. Держите телефон неподвижно.")
                PhotoCameraPanel(reference, onReference, onMatch, onError)
                TextButton(onClick = onClose) { Text("Закрыть камеру") }
            }
        }
    }
}

@Composable
private fun PhotoCameraPanel(
    reference: PhotoReference?, onReference: (PhotoReference) -> Unit, onMatch: () -> Unit, onError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(hasPermission()) }
    var resumed by remember { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    val currentError by rememberUpdatedState(onError)
    val currentReference by rememberUpdatedState(onReference)
    val currentMatch by rememberUpdatedState(onMatch)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { permission = hasPermission(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    if (!permission) {
        Text("Разрешите камеру для съёмки. Если доступ выключен в настройках, можно вернуться к заданию и выбрать математику.")
        Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
        return
    }
    if (!resumed) return
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var ready by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableIntStateOf(0) }
    val ticket = remember { AtomicInteger(0) }
    val waiting = remember { AtomicInteger(0) }

    DisposableEffect(lifecycle, preview, reference) {
        val live = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var previewUse: Preview? = null
        var analysisUse: ImageAnalysis? = null
        val cameraState = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { state ->
            ready = state.type == androidx.camera.core.CameraState.Type.OPEN && state.error == null
            if (state.error != null) {
                message = "Камера занята или недоступна. Закройте другие приложения с камерой либо выберите математику."
                currentError()
            }
        }
        future.addListener({
            if (live.get()) try {
                val activeProvider = future.get()
                provider = activeProvider
                val display = Preview.Builder().build().also {
                    it.surfaceProvider = preview.surfaceProvider
                    previewUse = it
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysisUse = analysis
                analysis.setAnalyzer(executor) { image ->
                    val requested = waiting.getAndSet(0)
                    if (requested == 0 || !live.get() || ticket.get() != requested) {
                        image.close()
                        return@setAnalyzer
                    }
                    val result = try {
                        val frame = processAndClose(image) { it.copyLuma() }
                        processPhoto(frame, reference) { !live.get() || ticket.get() != requested }
                    } catch (_: CancellationException) {
                        return@setAnalyzer
                    } catch (_: Exception) { PhotoOutcome.Unreadable }
                    main.execute {
                        if (live.get() && ticket.compareAndSet(requested, 0)) {
                            shot = 0
                            when (result) {
                                is PhotoOutcome.Reference -> currentReference(result.value)
                                is PhotoOutcome.Compared -> if (result.result.accepted) currentMatch()
                                    else message = photoHelp(result.result.problem)
                                is PhotoOutcome.Quality -> message = photoHelp(result.problem)
                                PhotoOutcome.Unreadable -> { message = "Не удалось прочитать кадр. Попробуйте ещё раз."; currentError() }
                            }
                        }
                    }
                }
                camera = activeProvider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, display, analysis)
                camera?.cameraInfo?.cameraState?.observe(lifecycle, cameraState)
            } catch (_: Exception) {
                message = "Не удалось открыть камеру. Проверьте доступ или выберите математику."
                currentError()
            }
        }, main)

        onDispose {
            live.set(false)
            ticket.set(0)
            waiting.set(0)
            camera?.cameraInfo?.cameraState?.removeObserver(cameraState)
            camera?.let { if (torch) runCatching { it.cameraControl.enableTorch(false) } }
            analysisUse?.clearAnalyzer()
            val active = listOfNotNull(previewUse, analysisUse)
            if (active.isNotEmpty()) provider?.unbind(*active.toTypedArray())
            executor.shutdown()
        }
    }
    LaunchedEffect(shot) {
        if (shot == 0) return@LaunchedEffect
        delay(8_000)
        if (ticket.compareAndSet(shot, 0)) {
            waiting.set(0)
            shot = 0
            message = "Не удалось получить кадр вовремя. Попробуйте ещё раз."
            currentError()
        }
    }

    AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().height(200.dp))
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val lamp = camera
    if (lamp?.cameraInfo?.hasFlashUnit() == true) {
        TextButton(onClick = {
            val wanted = !torch
            val result = lamp.cameraControl.enableTorch(wanted)
            result.addListener({
                runCatching { result.get() }.onSuccess { torch = wanted }.onFailure { message = "Фонарик недоступен." }
            }, ContextCompat.getMainExecutor(context))
        }) { Text(if (torch) "Выключить фонарик" else "Включить фонарик") }
    }
    Button(enabled = ready && shot == 0, onClick = {
        val session = sequence.incrementAndGet().let { if (it == 0) sequence.incrementAndGet() else it }
        message = null
        ticket.set(session)
        waiting.set(session)
        shot = session
    }) { Text(if (shot == 0) "Сделать снимок" else "Обработка…") }
}

private val sequence = AtomicInteger()

private fun photoHelp(problem: PhotoMatcher.Problem?) = when (problem) {
    PhotoMatcher.Problem.LIGHT -> "Слишком темно или светло. Измените освещение или включите фонарик."
    PhotoMatcher.Problem.DETAILS -> "Мало резких деталей. Приблизьтесь к предмету и попробуйте без движения."
    PhotoMatcher.Problem.INVALID -> "Эталон повреждён. Снимите его заново или используйте математику."
    else -> "Совпадение не подтверждено. Наведите камеру на тот же предмет с похожего ракурса."
}

@Composable
fun PhotoMission(reference: PhotoReference?, onMatch: () -> Unit, fallback: () -> Unit, onError: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    if (reference == null) {
        Text("Эталон недоступен. В редакторе можно снять его заново, а сейчас пройти математику.")
    } else {
        Text("Сфотографируйте тот же неподвижный предмет.")
        Button(onClick = { open = true }) { Text("Открыть камеру") }
    }
    TextButton(onClick = fallback) { Text("Заменить математикой") }
    if (open && reference != null) {
        PhotoCamera(reference, onClose = { open = false }, onMatch = { open = false; onMatch() }, onError = onError)
    }
}
