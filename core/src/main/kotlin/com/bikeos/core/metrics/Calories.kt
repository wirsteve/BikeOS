package com.bikeos.core.metrics

/**
 * Calorie estimates.
 *
 * Work-based: mechanical work (kJ) divided by gross efficiency (~24%) and converted to kcal.
 * 1 kcal = 4.184 kJ so kcal = kJ / (4.184 * 0.24) ≈ kJ * 0.996. This is the most accurate
 * method when a real power reading is available, and is what BikeOS uses by default.
 *
 * HR-based: Keytel et al. (2005) regression, used only when a heart-rate strap and a
 * rider profile are available and the user prefers it.
 */
object Calories {
    private const val GROSS_EFFICIENCY = 0.24
    private const val KJ_PER_KCAL = 4.184

    fun fromWorkKj(kj: Double): Double = kj / (KJ_PER_KCAL * GROSS_EFFICIENCY)

    enum class Sex { MALE, FEMALE }

    /** kcal burned per minute at the given heart rate (Keytel 2005, without VO2max). */
    fun keytelKcalPerMinute(hr: Int, weightKg: Double, ageYears: Int, sex: Sex): Double {
        val kj = when (sex) {
            Sex.MALE -> -55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * ageYears
            Sex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * ageYears
        }
        return (kj / KJ_PER_KCAL).coerceAtLeast(0.0)
    }
}
