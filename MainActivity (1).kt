package com.example.myai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Msg(val fromUser: Boolean, val text: String, val source: String = "")

object Engine {
    const val MODEL = "claude-sonnet-5-5"
    var apiKey = ""
    private var local: LlmInference? = null

    fun resetLocal() { local = null }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun askCloud(history: List<Msg>): String {
        val msgs = JSONArray()
        history.forEach {
            msgs.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
        }
        val body = JSONObject().put("model", MODEL).put("max_tokens", 1000).put("messages", msgs)
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        val res = JSONObject(conn.inputStream.bufferedReader().readText())
        return res.getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun askLocal(ctx: Context, history: List<Msg>): String {
        val file = File(ctx.filesDir, "model.task")
        if (!file.exists()) return "النموذج المحلي غير مثبت. اضغط زر «نموذج محلي» واختر ملف model.task من جوالك."
        if (local == null) {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(1024)
                .build()
            local = LlmInference.createFromOptions(ctx, opts)
        }
        val prompt = history.joinToString("\n") { (if (it.fromUser) "User: " else "Assistant: ") + it.text } + "\nAssistant:"
        return local!!.generateResponse(prompt)
    }

    suspend fun ask(ctx: Context, history: List<Msg>): Msg = withContext(Dispatchers.IO) {
        if (apiKey.isNotBlank() && isOnline(ctx)) {
            try { return@withContext Msg(false, askCloud(history), "☁️ أونلاين") } catch (_: Exception) { }
        }
        try { Msg(false, askLocal(ctx, history), "📱 محلي") }
        catch (e: Exception) { Msg(false, "حصل خطأ: ${e.message}", "⚠️") }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ChatScreen() } }
    }
}

@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }
    val chat = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(prefs.getString("k", "") ?: "") }
    var status by remember { mutableStateOf("") }
    Engine.apiKey = key

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = "جاري نسخ النموذج..."
            try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { i ->
                        File(ctx.filesDir, "model.task").outputStream().use { o -> i.copyTo(o) }
                    }
                }
                Engine.resetLocal()
                status = "تم تثبيت النموذج المحلي ✅"
            } catch (e: Exception) { status = "فشل النسخ: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                key, { key = it; prefs.edit().putString("k", it).apply() },
                Modifier.weight(1f), singleLine = true,
                label = { Text("مفتاح Claude API") },
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("نموذج محلي") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat) { m ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        if (m.source.isNotEmpty()) Text(m.source, style = MaterialTheme.typography.labelSmall)
                        Text(m.text)
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("اكتب رسالتك...") })
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && input.isNotBlank(), onClick = {
                chat.add(Msg(true, input)); input = ""; busy = true
                scope.launch {
                    chat.add(Engine.ask(ctx, chat.toList()))
                    busy = false
                }
            }) { Text("إرسال") }
        }
    }
}
