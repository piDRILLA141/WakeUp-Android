package ru.podyom.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import ru.podyom.core.SmartTime

class SmartTimeTransformation:VisualTransformation {
    override fun filter(text:androidx.compose.ui.text.AnnotatedString):TransformedText {
        val p=SmartTime.present(text.text)
        return TransformedText(androidx.compose.ui.text.AnnotatedString(p.text),object:OffsetMapping {
            override fun originalToTransformed(offset:Int)=p.originalToVisual[offset.coerceIn(0,text.length)]
            override fun transformedToOriginal(offset:Int)=p.visualToOriginal[offset.coerceIn(0,p.text.length)]
        })
    }
}
@Composable fun SmartTimeInput(value:String,onChange:(String)->Unit,showError:Boolean=false) {
    var field by rememberSaveable(stateSaver=TextFieldValue.Saver) {mutableStateOf(TextFieldValue(value))}
    var visited by remember {mutableStateOf(false)}
    var focused by remember {mutableStateOf(false)}
    LaunchedEffect(value) {if(value!=field.text) field=TextFieldValue(value,TextRange(value.length))}
    val complete=Regex("[0-9]{3,4}|[0-9]{1,2}:[0-9]{2}").matches(value.trim())
    val malformed=value.any {it !in '0'..'9' && it!=':' && !it.isWhitespace()} || value.length>5
    val error=if(showError || complete || malformed || (visited && !focused)) runCatching {SmartTime.parse(value)}.exceptionOrNull()?.message else null
    OutlinedTextField(field,{new->field=new;onChange(new.text)},label={Text("Время · ЧЧ:ММ")},
        textStyle=MaterialTheme.typography.displayMedium,singleLine=true,isError=error!=null,
        supportingText={Text(error?:"Можно ввести 640 или 06:40")},
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),
        visualTransformation=remember {SmartTimeTransformation()},
        modifier=Modifier.fillMaxWidth().onFocusChanged {state->
            if(focused && !state.isFocused) {visited=true;runCatching {SmartTime.normalize(field.text)}.onSuccess {normalized->field=TextFieldValue(normalized,TextRange(normalized.length));onChange(normalized)}}
            focused=state.isFocused
        })
}
