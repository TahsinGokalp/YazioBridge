package studio.kodrika.yaziobridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
private fun Screen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val yazio = remember { Yazio(ctx) }
    val hcStatus = remember { HealthConnectClient.getSdkStatus(ctx) }
    val bridge = remember { if (hcStatus == HealthConnectClient.SDK_AVAILABLE) Bridge(ctx) else null }

    var loggedIn by remember { mutableStateOf(yazio.loggedIn()) }
    var granted by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableLongStateOf(bridge?.lastSync ?: 0L) }
    val vault = remember { Vault(ctx) }
    var days by remember { mutableIntStateOf(vault.get("days")?.toIntOrNull() ?: 7) }
    var bg by remember { mutableStateOf(SyncWorker.enabled(ctx)) }
    var bgErr by remember { mutableStateOf(vault.get("bgErr")) }

    val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { g ->
        granted = g.containsAll(Bridge.PERMISSIONS)
    }

    fun sync() {
        val b = bridge ?: return
        if (busy) return
        busy = true; err = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { b.sync(yazio, days) } }
                .onSuccess { r ->
                    last = b.lastSync; vault.set("bgErr", null); bgErr = null
                    msg = "${r.days} gün, ${r.meals} yemek (${r.kcal.toInt()} kcal) Health Connect'e yazıldı" + if (r.removed > 0) "; ${r.removed} silinen kayıt kaldırıldı." else "."
                }
                .onFailure { e ->
                    err = (e.message ?: e.toString())
                    if (e is Yazio.HttpErr && e.code == 401) { yazio.logout(); loggedIn = false; err = "Yazio oturumu geçersiz; yeniden giriş yap." }
                }
            busy = false
        }
    }

    // Açılışta izinleri kontrol et; hazırsa otomatik eşitle
    LaunchedEffect(loggedIn) {
        val b = bridge ?: return@LaunchedEffect
        granted = b.hasPermissions()
        if (loggedIn && granted == true) { SyncWorker.apply(ctx); sync() }
    }
    LaunchedEffect(granted) { if (granted == true && loggedIn) { SyncWorker.apply(ctx); if (last == 0L) sync() } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Yazio Köprüsü", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Yazio'daki yemekleri ve suyu Health Connect'e yazar. Diğer uygulamalar oradan okur.", style = MaterialTheme.typography.bodyMedium)

        if (bridge == null) {
            CardBox("Health Connect yok") {
                Text("Bu telefonda Health Connect kurulu değil ya da güncellenmesi gerekiyor.")
                Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }) { Text("Play Store'da aç") }
            }
            return@Column
        }

        // 1. Yazio girişi
        CardBox("1. Yazio hesabı") {
            if (loggedIn) {
                Text("Bağlı ✓")
                OutlinedButton({ yazio.logout(); loggedIn = false; msg = null }) { Text("Çıkış yap") }
            } else {
                var email by remember { mutableStateOf("") }
                var pass by remember { mutableStateOf("") }
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("E-posta") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Parola") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("Parola saklanmaz; yalnız oturum anahtarı telefonda şifreli tutulur.", style = MaterialTheme.typography.bodySmall)
                Button({
                    busy = true; err = null
                    scope.launch {
                        val e = withContext(Dispatchers.IO) { yazio.login(email, pass) }
                        busy = false
                        if (e == null) { pass = ""; loggedIn = true } else err = e
                    }
                }, enabled = !busy && email.isNotBlank() && pass.isNotBlank()) { Text("Giriş yap") }
            }
        }

        // 2. Health Connect izni
        CardBox("2. Health Connect izni") {
            when (granted) {
                true -> Text("Beslenme ve su yazma izni var ✓")
                false -> {
                    Text("Beslenme ve su yazma izni gerekiyor (okuma izni istenmez).")
                    Button({ permLauncher.launch(Bridge.PERMISSIONS) }) { Text("İzin ver") }
                }
                null -> Text("Kontrol ediliyor…")
            }
        }

        // 3. Eşitleme
        CardBox("3. Eşitle") {
            Text("Kaç gün geriye:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30).forEach { n -> FilterChip(days == n, { days = n; vault.set("days", n.toString()) }, label = { Text(if (n == 1) "Bugün" else "$n gün") }) }
            }
            Button(::sync, Modifier.fillMaxWidth(), enabled = loggedIn && granted == true && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Şimdi eşitle")
            }
            Text(if (last > 0) "Son eşitleme: " + SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("tr")).format(Date(last)) else "Henüz eşitlenmedi",
                style = MaterialTheme.typography.bodySmall)
            Text("Uygulama her açıldığında " + (if (days == 1) "bugün" else "son $days gün") + " otomatik eşitlenir.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Arka planda 6 saatte bir eşitle")
                    Text("İnternet varken; en fazla son 7 gün.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(bg, { bg = it; SyncWorker.setEnabled(ctx, it) })
            }
            bgErr?.let { Text("Son arka plan eşitlemesi başarısız: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Not: Yazio'nun resmî bir veri arayüzü yok; Yazio değişiklik yaparsa eşitleme durabilir. " +
            "Yazio Samsung Health'e de bağlıysa ve Samsung Health beslenmeyi Health Connect'e yazmaya başlarsa yemekler iki kez görünebilir.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CardBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
