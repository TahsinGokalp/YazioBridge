package studio.kodrika.yaziobridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Yazio → Health Connect.
 * - Her yemek ayrı NutritionRecord, günlük su tek HydrationRecord.
 * - clientRecordId = "yazio-<Yazio kimliği>": aynı kayıt yeniden yazılınca Health Connect üzerine yazar (çift kayıt olmaz).
 *   clientRecordVersion = eşitleme zamanı, böylece Yazio'da düzeltilen değer her zaman güncellenir.
 * - Okuma izni istenmez: bir günde daha önce yazılan kimlikler yerelde tutulur; Yazio'dan silinenler HC'den de silinir.
 */
class Bridge(private val ctx: Context) {
    private val vault = Vault(ctx)
    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(ctx) }

    data class Result(val days: Int, val meals: Int, val kcal: Double, val removed: Int)

    suspend fun hasPermissions(): Boolean = client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    suspend fun sync(yazio: Yazio, days: Int): Result {
        val today = LocalDate.now()
        val data = yazio.read((days - 1 downTo 0).map { today.minusDays(it.toLong()) })
        val zone = ZoneId.systemDefault()
        val version = System.currentTimeMillis()
        var meals = 0; var kcal = 0.0; var removed = 0
        for (d in data) {
            val recs = ArrayList<Record>()
            d.meals.forEach { m ->
                val start = Instant.ofEpochMilli(m.timeMs)
                recs += NutritionRecord(
                    startTime = start, startZoneOffset = zone.rules.getOffset(start),
                    endTime = start.plusSeconds(60), endZoneOffset = zone.rules.getOffset(start),
                    metadata = Metadata.manualEntry(clientRecordId = "yazio-${m.id}", clientRecordVersion = version),
                    name = m.name.take(100), mealType = mealType(m.daytime),
                    energy = Energy.kilocalories(m.kcal.coerceIn(0.0, 99_999.0)),
                    protein = Mass.grams(m.protein.coerceIn(0.0, 99_999.0)),
                    totalCarbohydrate = Mass.grams(m.carb.coerceIn(0.0, 99_999.0)),
                    totalFat = Mass.grams(m.fat.coerceIn(0.0, 99_999.0)),
                )
            }
            if (d.waterMl > 0) {
                val day = LocalDate.parse(d.date)
                val noon = day.atTime(12, 0).atZone(zone).toInstant()
                val end = if (day == today) minOf(noon, Instant.now().minusSeconds(60)).plusSeconds(60) else noon.plusSeconds(60)
                val start = end.minusSeconds(60)
                recs += HydrationRecord(
                    startTime = start, startZoneOffset = zone.rules.getOffset(start),
                    endTime = end, endZoneOffset = zone.rules.getOffset(end),
                    volume = Volume.milliliters(d.waterMl.coerceIn(0.0, 100_000.0)),
                    metadata = Metadata.manualEntry(clientRecordId = "yazio-water-${d.date}", clientRecordVersion = version),
                )
            }
            if (recs.isNotEmpty()) client.insertRecords(recs)

            // Yazio'dan silinenler
            val now = d.meals.map { "yazio-${it.id}" }.toSet()
            val before = written(d.date)
            val gone = (before - now).toList()
            if (gone.isNotEmpty()) { client.deleteRecords(NutritionRecord::class, emptyList(), gone); removed += gone.size }
            if (d.waterMl <= 0 && hadWater(d.date)) client.deleteRecords(HydrationRecord::class, emptyList(), listOf("yazio-water-${d.date}"))
            remember(d.date, now, d.waterMl > 0)

            meals += d.meals.size; kcal += d.meals.sumOf { it.kcal }
        }
        vault.set("last", version.toString())
        return Result(data.size, meals, kcal, removed)
    }

    val lastSync: Long get() = vault.get("last")?.toLongOrNull() ?: 0L

    private fun written(date: String): Set<String> = vault.get("w|$date")?.let { s -> runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull() } ?: emptySet()
    private fun hadWater(date: String) = vault.get("h|$date") == "1"
    private fun remember(date: String, ids: Set<String>, water: Boolean) {
        vault.set("w|$date", JSONArray(ids.toList()).toString()); vault.set("h|$date", if (water) "1" else null)
    }

    private fun mealType(dt: String) = when (dt) {
        "breakfast" -> MealType.MEAL_TYPE_BREAKFAST; "lunch" -> MealType.MEAL_TYPE_LUNCH
        "dinner" -> MealType.MEAL_TYPE_DINNER; "snack" -> MealType.MEAL_TYPE_SNACK; else -> MealType.MEAL_TYPE_UNKNOWN
    }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
        )
    }
}
