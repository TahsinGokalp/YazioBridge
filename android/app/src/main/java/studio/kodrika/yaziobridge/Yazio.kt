package studio.kodrika.yaziobridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Yazio'daki bir yemek kaydı (besinler kaydın tamamı için) */
data class Meal(
    val id: String, val date: String, val timeMs: Long, val daytime: String, val name: String,
    val kcal: Double, val protein: Double, val carb: Double, val fat: Double,
)

/** Bir günün Yazio verisi */
data class Day(val date: String, val meals: List<Meal>, val waterMl: Double)

/*
 * Yazio'dan okuma (RESMÎ OLMAYAN API; topluluk projeleri: juriadams/yazio, saganos/yazio_public_api).
 * - Parola SAKLANMAZ: girişte bir kez gönderilir; dönen erişim/yenileme anahtarı Keystore ile şifreli tutulur.
 * - Ürün besinleri gram başına (değer × gram). Tarifli öğünlerin toplamı Yazio'nun günlük özetiyle eşitlenir
 *   (tarif besinlerinin porsiyon mu bütün tarif mi olduğu belirsiz; böylece toplam Yazio'dakiyle aynı olur).
 */
class Yazio(ctx: Context) {
    private val vault = Vault(ctx)

    class HttpErr(val code: Int, msg: String, val blocked: Boolean = false) : Exception(msg)
    private data class Tok(val access: String, val refresh: String?, val exp: Long)

    fun loggedIn(): Boolean = loadTok() != null
    fun logout() = vault.setSecret(TOK, null)

    private fun loadTok(): Tok? = vault.getSecret(TOK)?.let { runCatching {
        val o = JSONObject(it); Tok(o.getString("a"), o.optString("r").ifEmpty { null }, o.getLong("e"))
    }.getOrNull() }
    private fun saveTok(t: Tok) = vault.setSecret(TOK, JSONObject().put("a", t.access).put("r", t.refresh ?: "").put("e", t.exp).toString())

    /* ---------- HTTP ---------- */

    /** Sürüm engellenmişse (403 version_blocked / 404 / 410) sıradaki sürüm denenir */
    private fun http(method: String, path: String, token: String? = null, body: String? = null, form: Boolean = false): Any? {
        var lastErr: Exception? = null
        for (i in ver until VERSIONS.size) {
            try { return http1(method, HOST + VERSIONS[i] + path, token, body, form).also { ver = i } }
            catch (e: HttpErr) { lastErr = e; if (!(e.code == 410 || e.code == 404 || (e.code == 403 && e.blocked))) throw e }
        }
        throw lastErr ?: IllegalStateException("Yazio API")
    }

    private fun http1(method: String, url: String, token: String?, body: String?, form: Boolean): Any? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json") }
        }
        try {
            body?.let { c.outputStream.use { o -> o.write(it.toByteArray()) } }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val blocked = txt.contains("version_blocked")
            if (code !in 200..299) throw HttpErr(code, "HTTP $code" + if (blocked) " (API sürümü engelli)" else "", blocked)
            val t = txt.trim()
            return when { t.isEmpty() -> null; t.startsWith("[") -> JSONArray(t); else -> JSONObject(t) }
        } finally { c.disconnect() }
    }

    private fun tokenRequest(fields: Map<String, String>): Tok {
        val all = mapOf("client_id" to CID, "client_secret" to CSEC) + fields
        val r = (runCatching { http("POST", "/oauth/token", body = all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }, form = true) }
            .recoverCatching { e -> if (e is HttpErr && e.code == 401) throw e; http("POST", "/oauth/token", body = JSONObject(all).toString()) }
            .getOrThrow() as? JSONObject) ?: throw IllegalStateException("boş yanıt")
        return Tok(r.getString("access_token"), r.optString("refresh_token").ifEmpty { null },
            System.currentTimeMillis() + r.optLong("expires_in", 3600L) * 1000)
    }

    /** Giriş: başarılıysa null, değilse hata metni. Parola yalnız bu istekte kullanılır. */
    fun login(email: String, password: String): String? = runCatching {
        saveTok(tokenRequest(mapOf("grant_type" to "password", "username" to email.trim(), "password" to password))); null
    }.getOrElse { e -> if (e is HttpErr && (e.code == 400 || e.code == 401)) "E-posta ya da parola hatalı." else "Yazio'ya bağlanılamadı: ${e.message}" }

    private fun validToken(): String? {
        val t = loadTok() ?: return null
        if (System.currentTimeMillis() < t.exp - 60_000) return t.access
        val r = t.refresh ?: return null
        return runCatching { tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to r)).also { saveTok(it) }.access }.getOrNull()
    }

    /* ---------- Okuma ---------- */

    private class Nut(val kcal: Double, val p: Double, val c: Double, val f: Double) {
        operator fun times(x: Double) = Nut(kcal * x, p * x, c * x, f * x)
    }
    private fun JSONObject?.d(k: String): Double = this?.optDouble(k, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
    private fun nut(n: JSONObject?) = Nut(n.d("energy.energy"), n.d("nutrient.protein"), n.d("nutrient.carb"), n.d("nutrient.fat"))

    /** Ürün: ad + gram başına besinler (önbellekli) */
    private fun product(token: String, id: String): Pair<String, Nut>? {
        val key = "yzp|$id"
        vault.get(key)?.let { s -> runCatching { val o = JSONObject(s); return o.getString("n") to Nut(o.getDouble("k"), o.getDouble("p"), o.getDouble("c"), o.getDouble("f")) } }
        val o = runCatching { http("GET", "/products/$id", token) as? JSONObject }.getOrNull() ?: return null
        val name = o.optString("name").ifEmpty { "Yazio" }; val n = nut(o.optJSONObject("nutrients"))
        vault.set(key, JSONObject().put("n", name).put("k", n.kcal).put("p", n.p).put("c", n.c).put("f", n.f).toString())
        return name to n
    }

    private val DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private fun timeOf(date: String, s: String?): Long {
        val t = s?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it.take(19), DT) }.getOrNull() ?: runCatching { LocalDateTime.parse(it.take(19)) }.getOrNull() }
        return (t ?: LocalDate.parse(date).atTime(12, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Verilen günleri okur. Hata olursa istisna fırlatır (mesajı kullanıcıya gösterilir). */
    fun read(days: List<LocalDate>): List<Day> {
        val token = validToken() ?: throw IllegalStateException("Yazio oturumu yok ya da süresi doldu; yeniden giriş yap.")
        return days.map { day -> readDay(token, day.toString()) }
    }

    private fun readDay(token: String, k: String): Day {
        val ci = http("GET", "/user/consumed-items?date=$k", token)
        fun items(name: String): List<JSONObject> = when (ci) {
            is JSONObject -> ci.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
            is JSONArray -> (0 until ci.length()).mapNotNull { ci.optJSONObject(it) }.filter { name.startsWith(it.optString("type").ifEmpty { "product" }) }
            else -> emptyList()
        }
        data class Row(val m: Meal, val recipe: Boolean)
        val rows = ArrayList<Row>()
        fun add(o: JSONObject, kind: String, idx: Int, name: String, n: Nut, recipe: Boolean) {
            val id = o.optString("id").ifEmpty { "$k-$kind-$idx" }
            val dt = o.optString("daytime").ifEmpty { "snack" }
            rows += Row(Meal(id, k, timeOf(k, o.optString("date")), dt, name, n.kcal, n.p, n.c, n.f), recipe)
        }
        items("products").forEachIndexed { i, o ->
            val p = o.optString("product_id").ifEmpty { null }?.let { product(token, it) }
            add(o, "p", i, p?.first ?: "Yazio", (p?.second ?: Nut(0.0, 0.0, 0.0, 0.0)) * o.d("amount"), false)
        }
        items("simple_products").forEachIndexed { i, o ->
            add(o, "s", i, o.optString("name").ifEmpty { "Hızlı kayıt" }, nut(o.optJSONObject("nutrients")), false)
        }
        items("recipe_portions").forEachIndexed { i, o ->
            val r = o.optString("recipe_id").ifEmpty { null }?.let { runCatching { http("GET", "/recipes/$it", token) as? JSONObject }.getOrNull() }
            val portions = o.d("portion_count").takeIf { it > 0 } ?: 1.0
            add(o, "r", i, r?.optString("name")?.ifEmpty { null } ?: "Tarif", nut(r?.optJSONObject("nutrients")) * portions, true)
        }
        // Tarifli öğünler: öğün toplamından tarif dışı kalemler düşülür, kalan tariflere oranla paylaştırılır
        val sum = if (rows.none { it.recipe }) null
            else runCatching { (http("GET", "/user/widgets/daily-summary?date=$k", token) as? JSONObject)?.optJSONObject("meals") }.getOrNull()
        val meals = ArrayList<Meal>()
        rows.groupBy { it.m.daytime }.forEach { (dt, l) ->
            val rec = l.filter { it.recipe }.map { it.m }; val plain = l.filter { !it.recipe }.map { it.m }
            meals += plain
            if (rec.isEmpty()) return@forEach
            val total = sum?.optJSONObject(dt)?.optJSONObject("nutrients")
            if (total == null) { meals += rec; return@forEach }
            fun share(tot: Double, plainSum: Double, recVals: List<Double>): List<Double> {
                val left = (tot - plainSum).coerceAtLeast(0.0); val s = recVals.sum()
                return recVals.map { if (s > 0) left * it / s else left / recVals.size }
            }
            val t = nut(total)
            val k2 = share(t.kcal, plain.sumOf { it.kcal }, rec.map { it.kcal })
            val p2 = share(t.p, plain.sumOf { it.protein }, rec.map { it.protein })
            val c2 = share(t.c, plain.sumOf { it.carb }, rec.map { it.carb })
            val f2 = share(t.f, plain.sumOf { it.fat }, rec.map { it.fat })
            rec.forEachIndexed { i, m -> meals += m.copy(kcal = k2[i], protein = p2[i], carb = c2[i], fat = f2[i]) }
        }
        // Su (mL): nesne ya da kayıt dizisi
        val w = runCatching { http("GET", "/user/water-intake?date=$k", token) }.getOrNull()
        val ml = when (w) {
            is JSONObject -> w.d("water_intake")
            is JSONArray -> (0 until w.length()).mapNotNull { w.optJSONObject(it)?.d("water_intake") }.maxOrNull() ?: 0.0
            else -> 0.0
        }
        return Day(k, meals.filter { it.kcal > 0 || it.protein > 0 }.sortedBy { it.timeMs }, ml)
    }

    companion object {
        private const val HOST = "https://yzapi.yazio.com/"
        /** Açık API sürümleri (yeni sürümler resmî olmayan istemcilere "version_blocked" dönüyor; 2026-10'da v20–22 kapalı) */
        private val VERSIONS = listOf("v19", "v15")
        @Volatile private var ver = 0
        private const val CID = "1_4hiybetvfksgw40o0sog4s884kwc840wwso8go4k8c04goo4c"
        private const val CSEC = "6rok2m65xuskgkgogw40wkkk8sw0osg84s8cggsc4woos4s8o"
        private const val TOK = "yz_tok"
    }
}
