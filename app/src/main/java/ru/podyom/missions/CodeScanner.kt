package ru.podyom.missions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable fun CodeScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf<String?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted=it }
    val callback by rememberUpdatedState(onCode)
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    Column(modifier, verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!permitted) { Text("Камера нужна только для локального сканирования кода."); Button(onClick={request.launch(Manifest.permission.CAMERA)}) {Text("Разрешить камеру")} }
        else AndroidView(factory={preview},modifier=Modifier.fillMaxWidth().weight(1f))
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
    DisposableEffect(permitted, lifecycle) {
        if(!permitted) onDispose {} else {
            val executor = Executors.newSingleThreadExecutor()
            val main = ContextCompat.getMainExecutor(context)
            val future = ProcessCameraProvider.getInstance(context)
            var disposed = false
            var provider: ProcessCameraProvider? = null
            var previewUse: Preview? = null
            var analysisUse: ImageAnalysis? = null
            future.addListener({
                if(!disposed) try {
                    provider = future.get()
                    previewUse = Preview.Builder().build().also { it.surfaceProvider=preview.surfaceProvider }
                    val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE,BarcodeFormat.EAN_13,BarcodeFormat.EAN_8,BarcodeFormat.CODE_128,BarcodeFormat.CODE_39,BarcodeFormat.UPC_A,BarcodeFormat.ITF,BarcodeFormat.DATA_MATRIX))) }
                    var lastValue = ""; var lastAt = 0L
                    analysisUse = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { analysis ->
                        analysis.setAnalyzer(executor) { image ->
                            try {
                                val plane = image.planes[0]; val buffer = plane.buffer; val luminance = ByteArray(image.width*image.height)
                                for(y in 0 until image.height) for(x in 0 until image.width) luminance[y*image.width+x]=buffer.get(y*plane.rowStride+x*plane.pixelStride)
                                var source: LuminanceSource = PlanarYUVLuminanceSource(luminance,image.width,image.height,0,0,image.width,image.height,false)
                                var decoded: String? = null
                                repeat(2) {
                                    if(decoded==null) try { decoded=reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text } catch (_: NotFoundException) { if(source.isRotateSupported) source=source.rotateCounterClockwise() } finally {reader.reset()}
                                }
                                decoded?.let { value -> if(value.length<=4096 && (value!=lastValue || System.currentTimeMillis()-lastAt>1500)) {
                                    lastValue=value;lastAt=System.currentTimeMillis();main.execute {if(!disposed) callback(value)}
                                } }
                            } catch (_: Exception) { /* Unreadable frame is not a match. */ } finally { image.close() }
                        }
                    }
                    provider!!.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,previewUse,analysisUse)
                } catch (_: Exception) { error="Камера недоступна. Проверьте разрешение или выберите другое задание." }
            },main)
            onDispose { disposed=true; analysisUse?.clearAnalyzer(); previewUse?.let { provider?.unbind(it) }; analysisUse?.let {provider?.unbind(it)}; executor.shutdown() }
        }
    }
}
