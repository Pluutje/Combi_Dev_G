package app.aaps.plugins.aps.openAPSSMB

import android.os.Environment
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSAIMI.StepService
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Scanner
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.plugins.aps.openAPSAIMI.Therapy

@Singleton
data class Bolus_Basaal1(val BolusViaBasaal: Boolean, val BasaalStand: Float , val ResterendeTijd: Float)
data class Extra_Insuline1(val ExtraIns_AanUit: Boolean, val ExtraIns_waarde: Float ,val CfTijd: Double,val CfIns: Float, val ExtraIns_tijd: Int)
data class Resistentie_class(val resistentie: Double, val log: String)
data class uur_minuut(val uur: Int, val minuut: Int)


class DetermineBasalDynBasaal @Inject constructor(
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    val persistenceLayer: PersistenceLayer,
    val dateUtil: DateUtil
) {
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var tddCalculator: TddCalculator


    private var StapRetentie: Int = 0
    private var Automation_SMB_Controle: Int = 0
    private var SMBWachtTrigger: Long = System.currentTimeMillis()/(60 * 1000)
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS/")
    private val ActExtraIns = File(externalDir, "ANALYSE/Act-extra-ins.txt")
    private val BolusViaBasaal = File(externalDir, "ANALYSE/Bolus-via-basaal.txt")
    private val csvfile = File(externalDir, "ANALYSE/analyse.csv")

    private val consoleError = mutableListOf<String>()
    private val consoleLog = mutableListOf<String>()

    private var laagCarbNote = false
    private var laagCarbruntime: Long = 0
    private var gemCarbNote = false
    private var gemCarbruntime: Long = 0
    private var hoogCarbNote = false
    private var hoogCarbruntime: Long = 0
    private var stopNote = false
    private var stopruntime: Long = 0
    private var BstNote = false
    private var Bstruntime: Long = 0
 //   private val calendarInstance = Calendar.getInstance()

 //   private var Basaal: Double = 0.5

    val therapy = Therapy(persistenceLayer,).also {
        it.updateStatesBasedOnTherapyEvents()
    }

    private fun Double.toFixed2(): String = DecimalFormat("0.00#").format(round(this, 2))

    fun round_basal(value: Double): Double = value

    // Rounds value to 'digits' decimal places
    // different for negative numbers fun round(value: Double, digits: Int): Double = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN()) return Double.NaN
        val scale = 10.0.pow(digits.toDouble())
        return Math.round(value * scale) / scale
    }

    fun Double.withoutZeros(): String = DecimalFormat("0.##").format(this)
    //fun round(value: Double): Int = value.roundToInt()
    fun round(value: Double): Int {
        if (value.isNaN()) return 0
        val scale = 10.0.pow(2.0)
        return (Math.round(value * scale) / scale).toInt()
    }

    fun refreshTime() :uur_minuut{
        val calendarInstance = Calendar.getInstance() // Nieuwe tijd ophalen

        val uur = calendarInstance[Calendar.HOUR_OF_DAY]
        val minuut = calendarInstance[Calendar.MINUTE]
        return uur_minuut(uur,minuut)
    }

    fun ActExtraIns(profile: OapsProfile):Extra_Insuline1 {

        val tijdNu = System.currentTimeMillis()/(60 * 1000)
        var extra_insuline_tijdstip = "0"
        var extra_insuline_tijd = "0"
        var extra_insuline_percentage = "0"
        var extra_insuline: Boolean
        var corr_factor: Float
        var Cf_overall: Float
        var Cf_tijd : Double


        var extra_insuline_check = "0"

        try {
            val sc = Scanner(ActExtraIns)
            var teller = 1
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                if (teller == 1) { extra_insuline_check = line}
                if (teller == 2) { extra_insuline_tijdstip = line}
                if (teller == 3) { extra_insuline_tijd = line}
                if (teller == 4) { extra_insuline_percentage = line}

                teller += 1
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        var verstreken_tijd = (tijdNu - extra_insuline_tijdstip.toFloat()).toInt()
        var resterendeTijd = extra_insuline_tijd.toInt() - verstreken_tijd


        if (verstreken_tijd < extra_insuline_tijd.toInt() && extra_insuline_check == "checked") {
            extra_insuline = true
            corr_factor = (extra_insuline_percentage.toFloat())

            var Max_Cf_tijd = 1.0 + profile.BolusBoostSterkte.toDouble()/100
            var Min_Cf_tijd = 1.0 - profile.BolusBoostSterkte.toDouble()/100
            var Offset_tijd = extra_insuline_tijd.toFloat()/2 + profile.BolusBoostDeltaT.toFloat()
            var Slope_tijd = 3
            Cf_tijd = Min_Cf_tijd + (Max_Cf_tijd - Min_Cf_tijd)/(1 + Math.pow((verstreken_tijd.toDouble() / Offset_tijd) , Slope_tijd.toDouble()))
            Cf_overall = round(corr_factor * Cf_tijd,2).toFloat()
        } else {
            extra_insuline = false
            Cf_overall = 100.0f
            Cf_tijd = 1.0
            corr_factor = 100.0f
            resterendeTijd = 0
        }

        return Extra_Insuline1(extra_insuline,Cf_overall,Cf_tijd,corr_factor,resterendeTijd)
    }

    fun BolusViaBasaal(): Bolus_Basaal1 {

        val tijdNu = System.currentTimeMillis()/(60 * 1000)
        var bolus_basaal_check = "0"
        var bolus_basaal_tijdstip = "0"
        var bolus_basaal_tijd = "0"
        var insuline = "0"

        var bolus_via_basaal: Boolean
        var temp_basaal: Float


        try {
            val sc = Scanner(BolusViaBasaal)
            var teller = 1
            while (sc.hasNextLine()) {
                val line = sc.nextLine()
                if (teller == 1) { bolus_basaal_check = line}
                if (teller == 2) { bolus_basaal_tijdstip = line}
                if (teller == 3) { bolus_basaal_tijd = line}
                if (teller == 4) { insuline = line}

                teller += 1
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        var verstreken_tijd_bolus = tijdNu.toInt() - bolus_basaal_tijdstip.toInt()
        var rest_tijd = bolus_basaal_tijd.toInt() - verstreken_tijd_bolus
        if (verstreken_tijd_bolus <= ((bolus_basaal_tijd.toInt())+1) && bolus_basaal_check == "checked") {
            bolus_via_basaal = true
            temp_basaal = insuline.toFloat() * 60 / bolus_basaal_tijd.toFloat()

        } else {
            bolus_via_basaal = false
            temp_basaal = 0.0f
        }

        return Bolus_Basaal1(bolus_via_basaal,temp_basaal,rest_tijd.toFloat())

    }

    fun logBgHistory(startHour: Long, endHour: Long, uren: Long): Double {

        //    val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
        val startTime = dateUtil.now() - T.hours(hour = startHour).msecs()
        val endTime = dateUtil.now() - T.hours(hour = endHour).msecs()

        val bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)
        var bgAverage = 0.0
        if (bgReadings.size >= 8 * uren) {
            bgAverage = (bgReadings.sumOf { it.value }) / (bgReadings.size * 18)
        }

        return bgAverage
    }
    fun calculateCorrectionFactor(bgGem: Double, targetProfiel: Double, macht: Double): Double {
        var cf = Math.pow(bgGem / (targetProfiel / 18), macht)
        if (cf < 0.1) cf = 1.0

        return cf
    }


    fun Resistentie(profile: OapsProfile): Resistentie_class {
        // var log_resistentie = " ﴿ Resistentie ﴾" + "\n"
        var log_resistentie = ""

        if (!profile.resistentie) {
            log_resistentie = log_resistentie + " → Resistentie correctie uit " + "\n"
            return Resistentie_class(1.0,log_resistentie)
        }


        log_resistentie = log_resistentie + " → Resistentie correctie aan " + "\n"

        var ResistentieCfEff = 0.0
        var resistentie_percentage = 100
        val (uurVanDag,minuten) = refreshTime()

        //var uurVanDag = calendarInstance[Calendar.HOUR_OF_DAY]
        //var minuten = calendarInstance[Calendar.MINUTE]
        val (NachtStartUur, NachtStartMinuut) = profile.NachtStart.split(":").map { it.toInt() }
        val (OchtendStartUur, OchtendStartMinuut) = profile.OchtendStart.split(":").map { it.toInt() }
            if (isInTijdBereik(uurVanDag, minuten, NachtStartUur, NachtStartMinuut, OchtendStartUur, OchtendStartMinuut)) {
                resistentie_percentage = profile.nachtResistentiePerc
                log_resistentie = log_resistentie + " ● Nacht: Resistentie sterkte: " + resistentie_percentage + "%" + "\n"
              } else {
                resistentie_percentage = profile.dagResistentiePerc
                log_resistentie = log_resistentie + " ● Dag: Resistentie sterkte: " + resistentie_percentage + "%" + "\n"
              }

            var macht =  Math.pow(resistentie_percentage.toDouble(), 1.4)/2800

            var target_profiel = 102.0  // 5,7 mmol/l

            val numPairs = profile.ResistentieDagen // Hier kies je hoeveel paren je wilt gebruiken
            val uren = profile.ResistentieUren

            val x = uren.toLong()         // Constante waarde voor ± x
            val intervals = mutableListOf<Pair<Long, Long>>()

            for (i in 1..numPairs) {
                val base = (24 * i).toLong()  // Verhoogt telkens met 24: 24, 48, 72, ...
                intervals.add(Pair(base , base - x))
            }

            val correctionFactors = mutableListOf<Double>()

            for ((index, interval) in intervals.take(numPairs).withIndex()) {
                val bgGem = logBgHistory(interval.first, interval.second, x)
                val cf = calculateCorrectionFactor(bgGem, target_profiel, macht)
                log_resistentie = log_resistentie + " → Dag" + (index + 1) + ": Bg gem: " + round(bgGem, 1) + "→ perc = " + (cf * 100).toInt() + "%" + "\n"
                correctionFactors.add(cf)

            }
// Bereken CfEff met het gekozen aantal correctiefactoren
            var tot_gew_gem = 0
            for (i in 0 until numPairs) {
                val divisor = when (i) {
                    0   -> 60
                    1   -> 25
                    2   -> 10
                    3   -> 5
                    4   -> 3
                    5    -> 2
                    else -> 1 // Aanpassen voor extra correctiefactoren indien nodig
                }
                ResistentieCfEff += correctionFactors[i] * divisor
                tot_gew_gem += divisor
            }

        ResistentieCfEff = ResistentieCfEff / tot_gew_gem.toDouble()

        val minRes = profile.minResistentiePerc.toDouble()/100
        val maxRes = profile.maxResistentiePerc.toDouble()/100

        ResistentieCfEff = ResistentieCfEff.coerceIn(minRes, maxRes)

       if (ResistentieCfEff > minRes && ResistentieCfEff < maxRes){
            log_resistentie = log_resistentie + " »» Cf_eff = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"
        } else {
            log_resistentie = log_resistentie + " »» Cf_eff (begrensd) = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"
        }

        return Resistentie_class(ResistentieCfEff,log_resistentie)

    }


    fun DynamischBasaal(profile: OapsProfile): Double {

       val p6 = 0.0000000928
       val p5 = -0.000006986
       val p4 = 0.0001958
       val p3 = -0.002388
       val p2 = 0.0119596
       val p1 = -0.0406674

       // Basis basaalstand     base_basal_rate_label

        var tddlaatste24uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 24).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
        if (tddlaatste24uur < 15.0) {tddlaatste24uur = profile.standaardTDD}
       var tddlaatste12uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 12).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
        if (tddlaatste12uur < 7.5) {tddlaatste12uur = profile.standaardTDD/2}
       var tddlaatste6uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 6).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
        if (tddlaatste6uur < 3.75) {tddlaatste6uur = profile.standaardTDD/4}

        val (uurVanDag,minuten) = refreshTime()
        val TijdNu = uurVanDag.toDouble() + minuten.toDouble()/60

    //    val TTD_weegFactor = profile.TDD_weegfactor
    //    var gemTDD:Double = 0.5
    //    if (TTD_weegFactor == 1) {gemTDD = (1*tddlaatste24uur/24 + 2*tddlaatste12uur/12 + 3*tddlaatste6uur/6) / (3*6)}
    //    if (TTD_weegFactor == 2) {gemTDD = (2*tddlaatste24uur/24 + 2*tddlaatste12uur/12 + 2*tddlaatste6uur/6) / (3*6)}
    //    if (TTD_weegFactor == 3) {gemTDD = (3*tddlaatste24uur/24 + 2*tddlaatste12uur/12 + 1*tddlaatste6uur/6) / (3*6)}

    //    gemTDD = gemTDD * profile.basaalTDDPerc.toDouble()/100

        val TTD_weegFactor = profile.TDDweegfactor
        var gemTDD: Double

// Bereken gewogen gemiddelde direct, zonder extra datastructuren
        gemTDD = when (TTD_weegFactor) {
            1 -> (1 * tddlaatste24uur / 24 + 2 * tddlaatste12uur / 12 + 3 * tddlaatste6uur / 6) / 6.0
            2 -> (2 * tddlaatste24uur / 24 + 2 * tddlaatste12uur / 12 + 2 * tddlaatste6uur / 6) / 6.0
            3 -> (3 * tddlaatste24uur / 24 + 2 * tddlaatste12uur / 12 + 1 * tddlaatste6uur / 6) / 6.0
            else -> profile.standaardTDD/24 // Standaardwaarde als TTD_weegFactor ongeldige waarde heeft
        }
// Pas basaalpercentage toe
        gemTDD *= profile.basaalTDDPerc.toDouble() / 100


    //    var  Dyn_Basaal = p6*Math.pow(TijdNu,6.0) + p5*Math.pow(TijdNu,5.0) + p4*Math.pow(TijdNu,4.0) + p3*Math.pow(TijdNu,3.0)
    //    Dyn_Basaal = Dyn_Basaal + p2*Math.pow(TijdNu,2.0) + p1*Math.pow(TijdNu,1.0) + gemTDD

        var Dyn_Basaal = (((((p6 * TijdNu + p5) * TijdNu + p4) * TijdNu + p3) * TijdNu + p2) * TijdNu + p1) * TijdNu + gemTDD

        val (res,log) = Resistentie(profile)
        Dyn_Basaal = Dyn_Basaal * res

        return Dyn_Basaal.coerceIn(profile.minbasaal, profile.maxbasaal)
     }




    // Functie om te controleren of de huidige tijd binnen het tijdsbereik valt
    fun isInTijdBereik(hh: Int, mm: Int, startUur: Int, startMinuut: Int, eindUur: Int, eindMinuut: Int): Boolean {
        val startInMinuten = startUur * 60 + startMinuut
        val eindInMinuten = eindUur * 60 + eindMinuut
        val huidigeTijdInMinuten = hh * 60 + mm

        // Als het eindtijdstip voor middernacht is (bijvoorbeeld van 23:00 tot 05:00), moeten we dat apart behandelen
        return if (eindInMinuten < startInMinuten) {
            // Tijdsbereik over de middernacht (bijvoorbeeld 23:00 tot 05:00)
            huidigeTijdInMinuten >= startInMinuten || huidigeTijdInMinuten < eindInMinuten
        } else {
            // Normale tijdsbereik (bijvoorbeeld van 08:00 tot 17:00)
            huidigeTijdInMinuten in startInMinuten..eindInMinuten
        }
    }

    // we expect BG to rise or fall at the rate of BGI,
    // adjusted by the rate at which BG would need to rise /
    // fall to get eventualBG to target over 2 hours
    fun calculate_expected_delta(targetBg: Double, eventualBg: Double, bgi: Double): Double {
        // (hours * mins_per_hour) / 5 = how many 5 minute periods in 2h = 24
        val fiveMinBlocks = (2 * 60) / 5
        val targetDelta = targetBg - eventualBg
        return /* expectedDelta */ round(bgi + (targetDelta / fiveMinBlocks), 1)
    }

    fun convert_bg(value: Double): String =
        profileUtil.fromMgdlToStringInUnits(value).replace("-0.0", "0.0")
    //DecimalFormat("0.#").format(profileUtil.fromMgdlToUnits(value))
    //if (profile.out_units === "mmol/L") round(value / 18, 1).toFixed(1);
    //else Math.round(value);

    fun enable_smb(profile: OapsProfile, microBolusAllowed: Boolean, meal_data: MealData, target_bg: Double): Boolean {
        // disable SMB when a high temptarget is set
        if (!microBolusAllowed) {
            consoleError.add("SMB disabled (!microBolusAllowed)")
            return false
        } else if (!profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
            consoleError.add("SMB disabled due to high temptarget of $target_bg")
            return false
        }

        // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
        if (profile.enableSMB_always) {
            consoleError.add("SMB enabled due to enableSMB_always")
            return true
        }


        // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
        if (profile.enableSMB_with_temptarget && (profile.temptargetSet && target_bg < 100)) {
            consoleError.add("SMB enabled for temptarget of ${convert_bg(target_bg)}")
            return true
        }

        consoleError.add("SMB disabled (no enableSMB preferences active or no condition satisfied)")
        return false
    }

    fun reason(rT: RT, msg: String) {
        if (rT.reason.toString().isNotEmpty()) rT.reason.append(". ")
        rT.reason.append(msg)
        consoleError.add(msg)
    }

    private fun getMaxSafeBasal(profile: OapsProfile): Double =
        min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * DynamischBasaal(profile)))
    //      min(profile.max_basal, min(profile.max_daily_safety_multiplier * profile.max_daily_basal, profile.current_basal_safety_multiplier * basaal))

    fun setTempBasal(_rate: Double, duration: Int, profile: OapsProfile, rT: RT, currenttemp: CurrentTemp): RT {
        //var maxSafeBasal = Math.min(profile.max_basal, 3 * profile.max_daily_basal, 4 * profile.current_basal);

        val maxSafeBasal = getMaxSafeBasal(profile)
        var rate = _rate
        if (rate < 0) rate = 0.0
        else if (rate > maxSafeBasal) rate = maxSafeBasal

        val suggestedRate = round_basal(rate)
        if (currenttemp.duration > (duration - 10) && currenttemp.duration <= 120 && suggestedRate <= currenttemp.rate * 1.2 && suggestedRate >= currenttemp.rate * 0.8 && duration > 0) {
            rT.reason.append(" ${currenttemp.duration}m left and ${currenttemp.rate.withoutZeros()} ~ req ${suggestedRate.withoutZeros()}U/hr: no temp required")
            return rT
        }

        if (suggestedRate == DynamischBasaal(profile)) {
            if (profile.skip_neutral_temps) {
                if (currenttemp.duration > 0) {
                    reason(rT, "Suggested rate is same as profile rate, a temp basal is active, canceling current temp")
                    rT.duration = 0
                    rT.rate = 0.0
                    return rT
                } else {
                    reason(rT, "Suggested rate is same as profile rate, no temp basal is active, doing nothing")
                    return rT
                }
            } else {
                reason(rT, "Setting neutral temp basal of ${DynamischBasaal(profile)}U/hr")
                rT.duration = duration
                rT.rate = suggestedRate
                return rT
            }
        } else {
            rT.duration = duration
            rT.rate = suggestedRate
            return rT
        }
    }

    fun determine_basal(
        glucose_status: GlucoseStatus, currenttemp: CurrentTemp, iob_data_array: Array<IobTotal>, profile: OapsProfile, autosens_data: AutosensResult, meal_data: MealData,
        microBolusAllowed: Boolean, currentTime: Long, flatBGsDetected: Boolean, dynIsfMode: Boolean
    ): RT {
        consoleError.clear()
        consoleLog.clear()
        var rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            consoleLog = consoleLog,
            consoleError = consoleError
        )

        // TODO eliminate
        val deliverAt = currentTime

        // TODO eliminate
        var profile_current_basal = round_basal(DynamischBasaal(profile))
        profile_current_basal = DynamischBasaal(profile)
        var basal = profile_current_basal

        // TODO eliminate
        val systemTime = currentTime

        // TODO eliminate
        val bgTime = glucose_status.date
        val minAgo = round((systemTime - bgTime) / 60.0 / 1000.0, 1)
        // TODO eliminate
        val bg = glucose_status.glucose
        // TODO eliminate
        val noise = glucose_status.noise

        val (bolus_basaal_AanUit,bolus_basaal_waarde, rest_tijd_basaal) = BolusViaBasaal()
        val (extraIns_AanUit,extraIns_Factor,Cf_tijd,Cf_Ins :Float ,rest_tijd) = ActExtraIns(profile)

        if (bolus_basaal_AanUit) {
            basal = round_basal(bolus_basaal_waarde.toDouble())
            consoleError.add(" ﴿―― Bolus via basaal ――﴾")
            consoleError.add(" → basaal: " + round(basal,2) + "(u/h)")
            consoleError.add(" → nog $rest_tijd_basaal minuten resterend")
            consoleError.add(" ﴿―――――――――――――――﴾")

            rT.reason.append("=> Insuline via basaal:  $bolus_basaal_waarde u/h")
            rT.deliverAt = deliverAt
            rT.duration = 30
            rT.rate = basal
            return rT
        }



        // 38 is an xDrip error state that usually indicates sensor failure
        // all other BG values between 11 and 37 mg/dL reflect non-error-code BG values, so we should zero temp for those
        if (bg <= 10 || bg == 38.0 || noise >= 3) {  //Dexcom is in ??? mode or calibrating, or xDrip reports high noise
            rT.reason.append("CGM is calibrating, in ??? state, or noise is high")
        }
        if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
            rT.reason.append("If current system time $systemTime is correct, then BG data is too old. The last BG data was read ${minAgo}m ago at $bgTime")
            // if BG is too old/noisy, or is changing less than 1 mg/dL/5m for 45m, cancel any high temps and shorten any long zero temps
        } else if (bg > 60 && flatBGsDetected) {
            rT.reason.append("Error: CGM data is unchanged for the past ~45m")
        }
        if (bg <= 10 || bg == 38.0 || noise >= 3 || minAgo > 12 || minAgo < -5 || (bg > 60 && flatBGsDetected)) {
            if (currenttemp.rate > basal) { // high temp is running
                rT.reason.append(". Replacing high temp basal of ${currenttemp.rate} with neutral temp of $basal")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = basal
                return rT
            } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) { //shorten long zero temps to 30m
                rT.reason.append(". Shortening " + currenttemp.duration + "m long zero temp to 30m. ")
                rT.deliverAt = deliverAt
                rT.duration = 30
                rT.rate = 0.0
                return rT
            } else { //do nothing.
                rT.reason.append(". Temp ${currenttemp.rate} <= current basal ${round(basal, 2)}U/hr; doing nothing. ")
                return rT
            }
        }

        // TODO eliminate
        val max_iob = profile.max_iob // maximum amount of non-bolus IOB OpenAPS will ever deliver

        // if min and max are set, then set target to their average
        var target_bg = (profile.min_bg + profile.max_bg) / 2
        var min_bg = profile.min_bg
        var max_bg = profile.max_bg

        var sensitivityRatio: Double
        val high_temptarget_raises_sensitivity = profile.exercise_mode || profile.high_temptarget_raises_sensitivity
        val normalTarget = 100 // evaluate high/low temptarget against 100, not scheduled target (which might change)
        // when temptarget is 160 mg/dL, run 50% basal (120 = 75%; 140 = 60%),  80 mg/dL with low_temptarget_lowers_sensitivity would give 1.5x basal, but is limited to autosens_max (1.2x by default)
        val halfBasalTarget = profile.half_basal_exercise_target


        if (high_temptarget_raises_sensitivity && profile.temptargetSet && target_bg > normalTarget
            || profile.low_temptarget_lowers_sensitivity && profile.temptargetSet && target_bg < normalTarget
        ) {
            // w/ target 100, temp target 110 = .89, 120 = 0.8, 140 = 0.67, 160 = .57, and 200 = .44
            // e.g.: Sensitivity ratio set to 0.8 based on temp target of 120; Adjusting basal from 1.65 to 1.35; ISF from 58.9 to 73.6
            //sensitivityRatio = 2/(2+(target_bg-normalTarget)/40);
            val c = (halfBasalTarget - normalTarget).toDouble()
            sensitivityRatio = c / (c + target_bg - normalTarget)
            // limit sensitivityRatio to profile.autosens_max (1.2x by default)
            sensitivityRatio = min(sensitivityRatio, profile.autosens_max)
            sensitivityRatio = round(sensitivityRatio, 2)
            consoleLog.add("Sensitivity ratio set to $sensitivityRatio based on temp target of $target_bg; ")
        } else {
            sensitivityRatio = autosens_data.ratio
            consoleLog.add("Autosens ratio: $sensitivityRatio; ")
        }
        basal = DynamischBasaal(profile) * sensitivityRatio
        basal = round_basal(basal)
        if (basal != profile_current_basal)
            consoleLog.add("Adjusting basal from $profile_current_basal to $basal; ")
        else
            consoleLog.add("Basal unchanged: $basal; ")

        // adjust min, max, and target BG for sensitivity, such that 50% increase in ISF raises target from 100 to 120
        if (profile.temptargetSet) {
            //console.log("Temp Target set, not adjusting with autosens; ");
        } else {
            if (profile.sensitivity_raises_target && autosens_data.ratio < 1 || profile.resistance_lowers_target && autosens_data.ratio > 1) {
                // with a target of 100, default 0.7-1.2 autosens min/max range would allow a 93-117 target range
                min_bg = round((min_bg - 60) / autosens_data.ratio, 0) + 60
                max_bg = round((max_bg - 60) / autosens_data.ratio, 0) + 60
                var new_target_bg = round((target_bg - 60) / autosens_data.ratio, 0) + 60
                // don't allow target_bg below 80
                new_target_bg = max(80.0, new_target_bg)
                if (target_bg == new_target_bg)
                    consoleLog.add("target_bg unchanged: $new_target_bg; ")
                else
                    consoleLog.add("target_bg from $target_bg to $new_target_bg; ")

                target_bg = new_target_bg

            }
        }
        var target_profiel = target_bg

      // Dag - Nacht

        val (hourOfDay,minuut) = refreshTime()
        //var hourOfDay = calendarInstance[Calendar.HOUR_OF_DAY]
        //var minuut = calendarInstance[Calendar.MINUTE]
        var minuutTxt = ""
        if (minuut < 10){
            minuutTxt = "0" + minuut.toString()
        } else {
            minuutTxt = minuut.toString()
        }

        // Split de tijden in uren en minuten voor de verschillende delen van de dag
        val (OchtendStartUur, OchtendStartMinuut) = profile.OchtendStart.split(":").map { it.toInt() }
        val (MiddagStartUur, MiddagStartMinuut) = profile.MiddagStart.split(":").map { it.toInt() }
        val (AvondStartUur, AvondStartMinuut) = profile.AvondStart.split(":").map { it.toInt() }
        val (NachtStartUur, NachtStartMinuut) = profile.NachtStart.split(":").map { it.toInt() }

// Boolean variabelen voor de verschillende dagdelen
        var isOchtend = false
        var isMiddag = false
        var isAvond = false
        var isNacht = false
        var DeelvanDag = ""

// Controleer welk tijdsinterval van toepassing is en zet de booleans
        when {
            isInTijdBereik(hourOfDay, minuut, OchtendStartUur, OchtendStartMinuut, MiddagStartUur, MiddagStartMinuut) -> {
                isOchtend = true
                DeelvanDag = "Ochtend"
            }
            isInTijdBereik(hourOfDay, minuut, MiddagStartUur, MiddagStartMinuut, AvondStartUur, AvondStartMinuut) -> {
                isMiddag = true
                DeelvanDag = "Middag"
            }
            isInTijdBereik(hourOfDay, minuut, AvondStartUur, AvondStartMinuut, NachtStartUur, NachtStartMinuut) -> {
                isAvond = true
                DeelvanDag = "Avond"
            }
            isInTijdBereik(hourOfDay, minuut, NachtStartUur, NachtStartMinuut, OchtendStartUur, OchtendStartMinuut) -> {
                isNacht = true
                DeelvanDag = "Nacht"
            }
        }


        var log_DagNacht = " ﴿ Deel van Dag ﴾" + "\n"
        log_DagNacht = log_DagNacht + " ● Ochtendstart tijd: " + profile.OchtendStart +  "\n"
        log_DagNacht = log_DagNacht + " ● Middagstart tijd: " + profile.MiddagStart +  "\n"
        log_DagNacht = log_DagNacht + " ● Avondstart tijd: " + profile.AvondStart +  "\n"
        log_DagNacht = log_DagNacht + " ● Nachtstart tijd: " + profile.NachtStart +  "\n"
        log_DagNacht = log_DagNacht + " ● Tijd: " + hourOfDay.toString() + ":" + minuutTxt + " → Deel v/d Dag = " + DeelvanDag + "\n"
        log_DagNacht = log_DagNacht + " \n"

// Einde Dag Nacht

// Stappen
        var log_Stappen = " ﴿ Stappen ﴾" + "\n"
        val min5Stap = profile.stap5minuten
        val min30Stap = ((min5Stap * 30 / 5)/1.6).toInt()
        //   val min60Stap = ((min5Stap * 60 / 5)/2.6).toInt()
        var stap_perc = 100f
// Variabelen om de actieve duur en huidige status bij te houden
        if (profile.Stappen) {
            val thresholds = mapOf(
                " 5 minuten" to min5Stap,
                "30 minuten" to min30Stap  //,
                //  "60 minuten" to min60Stap
            )
            var allThresholdsMet = true

            // Controleer de drempels
            thresholds.forEach { (label, threshold) ->
                val steps = when (label) {
                    " 5 minuten" -> StepService.getRecentStepCount5Min()
                    "30 minuten" -> StepService.getRecentStepCount30Min()
                    //   "60 minuten" -> StepService.getRecentStepCount60Min()
                    else -> 0
                }
                log_Stappen += " ● $label: $steps stappen ${if (steps >= threshold) ">= drempel ($threshold)" else "< drempel ($threshold)"}\n"
                if (steps < threshold) allThresholdsMet = false
            }

            if (allThresholdsMet) {
                StapRetentie = (StapRetentie + 1).coerceAtMost(profile.stapretentie) // Limiteer
                log_Stappen += " ↗ Drempel overschreden. ($StapRetentie maal).\n"
            } else {
                log_Stappen += " → Drempel niet overschreden.\n"
                if (StapRetentie > 0) {
                    StapRetentie = StapRetentie -1
                 //   log_Stappen += "  - Nog $StapRetentie maal verhoogd target.\n"
                } // Verlaag actieve duur als deze nog actief is

            }

            // Verhoog target
            if (StapRetentie > 0) {
                if (allThresholdsMet) {
                    stap_perc = profile.stapactiviteteitPerc.toFloat()
                    log_Stappen += " ● Overschrijding drempels → Insuline perc. $stap_perc %.\n"
                    target_bg += 9
                } else {
                    stap_perc = profile.stapactiviteteitPerc.toFloat()
                    log_Stappen += " ● nog $StapRetentie * retentie → Insuline perc. $stap_perc %.\n"
                    target_bg += 9
                }


            } else {
                stap_perc = 100f
                log_Stappen += " ● Geen activiteit → Insuline perc. $stap_perc %.\n"

            }

         //   log_Stappen += " Retentie teller: $StapRetentie keer.\n"
        } else {
            log_Stappen += " ● Stappen correctie uitgeschakeld.\n"
        }
        val display_Stap_perc = stap_perc.toInt()


   // einde stappen

        val iobArray = iob_data_array
        val iob_data = iobArray[0]

        val tick: String

        tick = if (glucose_status.delta > -0.5) {
            "+" + round(glucose_status.delta)
        } else {
            round(glucose_status.delta).toString()
        }
        val minDelta = min(glucose_status.delta, glucose_status.shortAvgDelta)
        val minAvgDelta = min(glucose_status.shortAvgDelta, glucose_status.longAvgDelta)
        val maxDelta = max(glucose_status.delta, max(glucose_status.shortAvgDelta, glucose_status.longAvgDelta))


        var startTime = dateUtil.now() -  T.mins(min = 50).msecs()     //T.hours(hour = 1).msecs()
        var endTime = dateUtil.now()
        var bgReadings = persistenceLayer.getBgReadingsDataFromTimeToTime(startTime, endTime, false)
        var delta15 = 0.0
        var delta15_oud = 0.0
        var bg_act = round(bgReadings[0].value/18,2)
        var delta5 = 0f
        var delta30 = 0f
        if (bgReadings.size >= 7) {
            delta15 = (bgReadings[0].value - bgReadings[3].value)
            delta15_oud = (bgReadings[1].value - bgReadings[4].value)
            bg_act = round(bgReadings[0].value/18,2)
            delta5 = (bgReadings[0].value - bgReadings[1].value).toFloat()
            delta30 = (bgReadings[0].value - bgReadings[6].value).toFloat()
        }

/*  // Resistentie code
        var log_resistentie = " ﴿ Resistentie ﴾" + "\n"
        var ResistentieCfEff = 0.0
        if   (profile.resistentie) {
            log_resistentie = log_resistentie + " ● Resistentie aan: " + profile.resistentie + "\n"
            var resistentie_percentage = 100
            if (!isNacht) {
                resistentie_percentage = profile.dagResistentiePerc
                log_resistentie = log_resistentie + " ● Dag: Resistentie sterkte: " + resistentie_percentage + "%" + "\n"
            } else {
                resistentie_percentage = profile.nachtResistentiePerc
                log_resistentie = log_resistentie + " ● Nacht: Resistentie sterkte: " + resistentie_percentage + "%" + "\n"
            }

            var macht =  Math.pow(resistentie_percentage.toDouble(), 1.4)/2800

            target_profiel = target_profiel + 9  // 9mg/dl = 0,5 mmol/l

            val numPairs = profile.ResistentieDagen // Hier kies je hoeveel paren je wilt gebruiken
            val uren = profile.ResistentieUren

            val x = uren.toLong()         // Constante waarde voor ± x
            val intervals = mutableListOf<Pair<Long, Long>>()

            for (i in 1..numPairs) {
                val base = (24 * i).toLong()  // Verhoogt telkens met 24: 24, 48, 72, ...
                intervals.add(Pair(base , base - x))
            }

            val correctionFactors = mutableListOf<Double>()

            for ((index, interval) in intervals.take(numPairs).withIndex()) {
                val bgGem = logBgHistory(interval.first, interval.second, x)
                val cf = calculateCorrectionFactor(bgGem, target_profiel, macht)
                log_resistentie = log_resistentie + " → Dag" + (index + 1) + ": Bg gem: " + round(bgGem, 1) + "→ perc = " + (cf * 100).toInt() + "%" + "\n"
                correctionFactors.add(cf)

            }
// Bereken CfEff met het gekozen aantal correctiefactoren
            var tot_gew_gem = 0
            for (i in 0 until numPairs) {
                val divisor = when (i) {
                    0   -> 60
                    1   -> 25
                    2   -> 10
                    3   -> 5
                    4   -> 3
                    5    -> 2
                    else -> 1 // Aanpassen voor extra correctiefactoren indien nodig
                }
                ResistentieCfEff += correctionFactors[i] * divisor
                tot_gew_gem += divisor
            }
            if (ResistentieCfEff > 0.0) {
                ResistentieCfEff = ResistentieCfEff / tot_gew_gem
            } else {
                ResistentieCfEff = 1.0
            }

            if (bg_act >= target_profiel / 18) {
                log_resistentie = log_resistentie + " »» Cf_eff = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"

            } else {
                if (ResistentieCfEff <= 1.0) {
                    log_resistentie = log_resistentie + " »» Bg (" + round(bg_act, 1) + ") < target (" + round(target_profiel / 18, 1) + ")" + "\n"
                    log_resistentie = log_resistentie + " »» Cf_eff = " + (ResistentieCfEff * 100).toInt() + "%" + "\n"

                } else {
                    log_resistentie = log_resistentie + " »» Bg (" + round(bg_act, 1) + ") < target (" + round(target_profiel / 18, 1) + ")" + "\n"
                    log_resistentie = log_resistentie + " »» Cf_eff van: " + (ResistentieCfEff * 100).toInt() + "% naar: 100%" + "\n"
                    ResistentieCfEff = 1.0
                }
            }

        } else {
            log_resistentie = log_resistentie + " ● Resistentie correctie uit " + "\n"
        }
        var ResistentieCfEff_info = (ResistentieCfEff *100).toInt()
// Einde Resistentie code   */


        var Persistent_ISF_perc: Double
        var Display_Persistent_perc: Int
        var Persistent_Drempel: Double

        if (!isNacht) {
            Persistent_Drempel = profile.PersistentDagDrempel
        } else {
            Persistent_Drempel = profile.PersistentNachtDrempel
        }

        var Persistent_info: String
        var Pers_grensL = profile.PersistentGrens * 18
        var Pers_grensH = (profile.PersistentGrens * 18) + 2
        var log_Persistent = " ﴿ Persistent hoog ﴾" + "\n"
        if (delta5>-Pers_grensL && delta5<Pers_grensH && delta15>-Pers_grensL-1 && delta15<Pers_grensH+1 && delta30>-Pers_grensL-2 && delta30<Pers_grensH+2 && bg_act > Persistent_Drempel) {
            Persistent_ISF_perc = (((bg_act - Persistent_Drempel) / 10.0) + 1.0)*100
            Display_Persistent_perc = Persistent_ISF_perc.toInt()
            log_Persistent = log_Persistent + " → Persistent hoge Bg gedetecteerd" + "\n"
            log_Persistent = log_Persistent + " ● " + DeelvanDag + " → Drempel = " + round(Persistent_Drempel,1) + "\n"
            log_Persistent = log_Persistent + " ● Bg= " + round(bg_act, 1) + " → Insuline perc = " + Display_Persistent_perc + "%" + "\n"

            Persistent_info = " ● Persistent hoge Bg → Insuline perc = " + Display_Persistent_perc + "%"
        } else {
            Persistent_ISF_perc = 100.0
            Display_Persistent_perc = Persistent_ISF_perc.toInt()
            log_Persistent = log_Persistent + " ● geen Persistent hoge Bg gedetecteerd" + "\n"
            log_Persistent = log_Persistent + " ● " + DeelvanDag + " → Drempel = " + round(Persistent_Drempel,1) + "\n"

            Persistent_info = " ● Bg niet Persistent hoog → perc = 100%"
        }

        var DeelvanDag_factor = 1.0  // Standaardwaarde

// Afhankelijk van het dagdeel, stel de factor in op basis van het profiel
        DeelvanDag_factor = when {
            isOchtend -> profile.bg_PercOchtend.toDouble() / 100
            isMiddag -> profile.bg_PercMiddag.toDouble() / 100
            isAvond -> profile.bg_PercAvond.toDouble() / 100
            isNacht -> profile.bg_PercNacht.toDouble() / 100
            else -> 1.0  // Als geen enkele voorwaarde waar is, stel het in op de standaardwaarde 1.0
        }

        var Bg_ISF_perc = 100.0
        var Display_Bg_ISF_perc = 100

        var Delta_Target = bg_act - (target_bg/18)
        var helling = (10 + Delta_Target) * 1.0
        var Bg_ISF_factor = (Delta_Target/helling * 1.5 + 1) * DeelvanDag_factor

        Bg_ISF_perc = Bg_ISF_factor * 100
        Display_Bg_ISF_perc = Bg_ISF_perc.toInt()



        // UAM-boost Bg en hypo correctie en IOB correctie

        var min_Cf_UamBg = 0.9
        var max_Cf_UamBg = 1.1
        var offset_UamBg = (target_bg/18 + 1)
        var slope_UamBg = 7.0

        var Cf_UAMBoost_Bg: Double
        Cf_UAMBoost_Bg = max_Cf_UamBg + (min_Cf_UamBg - max_Cf_UamBg)/(1 + Math.pow((bg_act / offset_UamBg) , slope_UamBg))

        var min_Cf_IOB = 0.6
        var max_Cf_IOB = 1.1
        var offset_IOB = 2.0
        var slope_IOB = 6.0

        var Cf_IOB: Double
        Cf_IOB = min_Cf_IOB + (max_Cf_IOB - min_Cf_IOB)/(1 + Math.pow(((iob_data.iob + slope_IOB/2) / (offset_IOB + slope_IOB/2)) , slope_IOB))

        // einde UAM-boost Bg en hypo correctie

        // code nieuwe uam-boost
        var uam_boost_percentage = profile.newuamboostPerc.toDouble()

        var drempel_uam = profile.newuamboostDrempel * 18
        var  rest_uam: Double
        var display_UAM_Perc: Int
        var log_UAMBoost = " ﴿ UAM boost ﴾" + "\n"
        if (delta15 >= drempel_uam && !isNacht) {
            rest_uam = delta15 - drempel_uam
            var extra = 10 * Math.pow((delta15 - delta15_oud), (1.0 / 3.0))
            uam_boost_percentage = uam_boost_percentage + rest_uam.toInt() + extra.toInt()
            uam_boost_percentage = uam_boost_percentage * Cf_UAMBoost_Bg
            uam_boost_percentage = uam_boost_percentage * Cf_IOB
            display_UAM_Perc = uam_boost_percentage.toInt()

            log_UAMBoost = log_UAMBoost + " ● UAM-Boost perc = " + display_UAM_Perc + "%" + "\n"
            log_UAMBoost = log_UAMBoost + " ● ∆15=" + round(delta15/18,2) + " >= " + round(drempel_uam/18,2) + "\n"
            log_UAMBoost = log_UAMBoost + " ● Bg correctie = " + round(Cf_UAMBoost_Bg,2) + "\n"
            log_UAMBoost = log_UAMBoost + " ● IOB correctie = " + round(Cf_IOB,2) + "\n"



        } else {
            uam_boost_percentage = 100.0
            display_UAM_Perc = uam_boost_percentage.toInt()

            if (!isNacht){
                log_UAMBoost = log_UAMBoost + " ● ∆15=" + round(delta15/18,2) + " < " + round(drempel_uam/18,2) + " → Niet geactiveerd" + "\n"

            } else {
                log_UAMBoost = log_UAMBoost + " ● Nacht → UAM Boost Niet geactiveerd" + "\n"

            }

            log_UAMBoost = log_UAMBoost + " ● UAM-Boost perc = " + display_UAM_Perc + "%" + "\n"


        }

        //einde code nieuwe uam-boost

        // hypo correctie code
        var log_hypo = " ﴿ Hypo Correctie ﴾" + "\n"
        var hypo_corr_percentage = 100
        if (bg_act < target_bg/18){
            hypo_corr_percentage = profile.hypoPerc
            log_hypo = log_hypo + " ● Bg (" + round(bg_act,1).toString() + ") < Target (" + round(target_bg/18,1).toString() + ") → Hypo % = " + hypo_corr_percentage.toString() +"%"
        } else {
            hypo_corr_percentage = 100
            log_hypo = log_hypo + " ● Geen Hypo detectie → perc = " + hypo_corr_percentage.toString() +"%"
        }
        log_hypo = log_hypo + "\n"


        // einde hypo correctie

        // Automation Note code
        stopNote = therapy.findActivestopEvents(System.currentTimeMillis()).blockingGet()
        stopruntime = therapy.getTimeElapsedSinceLastEvent("stop")
        if (stopruntime < 0) {stopruntime = 180}
        laagCarbNote = therapy.findActivelaag_CarbEvents(System.currentTimeMillis()).blockingGet()
        laagCarbruntime = therapy.getTimeElapsedSinceLastEvent("LKh")
        if (laagCarbruntime < 0) {laagCarbruntime = 180}
        gemCarbNote = therapy.findActivegem_CarbEvents(System.currentTimeMillis()).blockingGet()
        gemCarbruntime = therapy.getTimeElapsedSinceLastEvent("MKh")
        if (gemCarbruntime < 0) {gemCarbruntime = 180}
        hoogCarbNote = therapy.findActivehoog_CarbEvents(System.currentTimeMillis()).blockingGet()
        hoogCarbruntime = therapy.getTimeElapsedSinceLastEvent("HKh")
        if (hoogCarbruntime < 0) {hoogCarbruntime = 180}
        BstNote = therapy.findBst_Events(System.currentTimeMillis()).blockingGet()
        Bstruntime = therapy.getTimeElapsedSinceLastEvent("Boost")
        if (Bstruntime < 0) {Bstruntime = 180}

        var Automation_Note_perc = 100
        var Automation_Note_SMB = 0.0
        var Auto_SMB = false
        var Note_correctie = " ● Boost en SMB niet actief" + "\n"

        if (laagCarbNote && laagCarbruntime < stopruntime && laagCarbruntime < Bstruntime && laagCarbruntime < gemCarbruntime && laagCarbruntime < gemCarbruntime) {
            if (laagCarbruntime < 6 && Automation_SMB_Controle <0.5) {
                Automation_Note_SMB = profile.LKhboostsmb
                Auto_SMB = true
                Automation_SMB_Controle = 1
            }
            Automation_Note_perc = profile.LKhbooststerkte
            Note_correctie = "  ● laagCarb actief perc = " + Automation_Note_perc + "%" + "\n"
            Note_correctie = Note_correctie  + "  ● Extra SMB = " + Automation_Note_SMB + "eh" + "\n"
        }
        if (gemCarbNote && gemCarbruntime < stopruntime && gemCarbruntime < Bstruntime && gemCarbruntime < laagCarbruntime  && gemCarbruntime < hoogCarbruntime) {
            if (gemCarbruntime < 6 && Automation_SMB_Controle <0.5) {
                Automation_Note_SMB = profile.MKhboostsmb
                Auto_SMB = true
                Automation_SMB_Controle = 1
            }
            Automation_Note_perc = profile.MKhbooststerkte
            Note_correctie = "  ● gemCarb actief perc = " + Automation_Note_perc + "%" + "\n"
            Note_correctie = Note_correctie  + "  ● Extra SMB = " + Automation_Note_SMB + "eh" + "\n"
        }
        if (hoogCarbNote && hoogCarbruntime < stopruntime && hoogCarbruntime < Bstruntime && hoogCarbruntime < laagCarbruntime && hoogCarbruntime < gemCarbruntime) {
            if (hoogCarbruntime < 6 && Automation_SMB_Controle <0.5) {
                Automation_Note_SMB = profile.HKhboostsmb
                Auto_SMB = true
                Automation_SMB_Controle = 1
            }
            Automation_Note_perc = profile.HKhbooststerkte
            Note_correctie = "  ● hoogCarb actief perc = " + Automation_Note_perc + "%" + "\n"
            Note_correctie = Note_correctie  + "  ● Extra SMB = " + Automation_Note_SMB + "eh" + "\n"
        }
        if (BstNote && Bstruntime < stopruntime && Bstruntime < laagCarbruntime && Bstruntime < gemCarbruntime && Bstruntime < hoogCarbruntime) {

            Automation_Note_perc = profile.Bstbooststerkte
            Note_correctie = "  ● Boost actief perc = " + Automation_Note_perc + "%" + "\n"
            Note_correctie = Note_correctie  + "  ● Uitsluitend Boost geen Extra SMB " + "\n"
        }

        if (laagCarbruntime > 10 && gemCarbruntime > 10 && hoogCarbruntime > 10 ) {
            Automation_SMB_Controle = 0

        }

        var log_Note_Automation = " ﴿ Automation Boost ﴾" + "\n"
        log_Note_Automation = log_Note_Automation + " LowCarb = " + laagCarbNote + " Runtime = " + laagCarbruntime + "\n"
        log_Note_Automation = log_Note_Automation + " MediumCarb = " + gemCarbNote + " Runtime = " + gemCarbruntime + "\n"
        log_Note_Automation = log_Note_Automation + " HihgCarb = " + hoogCarbNote + " Runtime = " + hoogCarbruntime + "\n"
        log_Note_Automation = log_Note_Automation + " BoostTime = " + BstNote + " Runtime = " + Bstruntime + "\n"
        log_Note_Automation = log_Note_Automation + " stopTime = " + stopNote + " Runtime = " + stopruntime + "\n"
        log_Note_Automation = log_Note_Automation + Note_correctie
        log_Note_Automation = log_Note_Automation + "  ● Check dubbel SMB = " + Automation_SMB_Controle + "\n"

        var display_Automation_Note_perc = Automation_Note_perc



// Einde Automation Note code


// IOB - BG code
        var log_BG_IOB = " ﴿ Bg/IOB Correctie ﴾" + "\n"
        var BG_IOB_percentage = 100
        if ((bg_act-5)/(iob_data.iob+1) > 2){
            BG_IOB_percentage = profile.BgIOBPerc
            log_BG_IOB = log_BG_IOB + " ● Hoge Bg (" + round(bg_act,1).toString() + ") Lage IOB (" + round(iob_data.iob,1).toString() + ") → Bg_IOB % = " + BG_IOB_percentage.toString() +"%"
        } else {
            BG_IOB_percentage = 100
            log_BG_IOB = log_BG_IOB + " ● Geen Bg_IOB correctie → perc = " + BG_IOB_percentage.toString() +"%"
        }
        log_BG_IOB = log_BG_IOB + "\n"




// Einde IOB - Bg code


        var info_cf = 100

        if (extraIns_AanUit) {
            info_cf = extraIns_Factor.toInt()
            log_UAMBoost = log_UAMBoost + " Bolus-Boost actief → perc.=$info_cf%" + "\n"

            if (uam_boost_percentage > 100) {
                log_UAMBoost = log_UAMBoost + " UAM-Boost zou " + display_UAM_Perc + " % geweest zijn" + "\n"
            }

        } else {
            info_cf = uam_boost_percentage.toInt()
            if (isNacht) {
                log_UAMBoost = log_UAMBoost + " ● Nachttijd → Boost niet actief" + "\n"
            }

        }

        var log_ExtraIns = ""
        if (extraIns_AanUit) {
            var display_Cf_Ins = Cf_Ins.toInt()
            log_ExtraIns = " \n" + " ﴿ Extra bolus insuline  ―――――﴾" + "\n"
            log_ExtraIns = log_ExtraIns + " → Nog $rest_tijd minuten resterend" + "\n"
            log_ExtraIns = log_ExtraIns + " ● Opgegeven perc = " + display_Cf_Ins + "%" + "\n"
            log_ExtraIns = log_ExtraIns + " ● Dynamische factor = " + round(Cf_tijd,2) + "\n"
            log_ExtraIns = log_ExtraIns + " ● Overall perc = $info_cf%" + "\n"

            uam_boost_percentage = 100.0
        }
        var Bg_ISF_cf = Bg_ISF_perc/100
        var Persistent_ISF_cf = Persistent_ISF_perc/100
        var uam_boost_cf = uam_boost_percentage/100
        var extraIns_cf = extraIns_Factor.toDouble()/100
        var stap_cf = stap_perc.toDouble()/100
        var Automation_Note_cf = Automation_Note_perc.toDouble()/100
        var hypo_corr_cf = hypo_corr_percentage.toDouble() /100
        var bg_iob_cf = BG_IOB_percentage.toDouble() /100

        var overall_cf = Bg_ISF_cf * Persistent_ISF_cf * uam_boost_cf * extraIns_cf * sensitivityRatio * stap_cf *  Automation_Note_cf * hypo_corr_cf * bg_iob_cf




        if (overall_cf > (profile.maxBoostPerc/100).toDouble()) {
            overall_cf = (profile.maxBoostPerc/100).toDouble()
        }


        var display_sens_perc = (sensitivityRatio*100).toInt()
        var sens = profile.sens


        // Extra SMB code test
        var SMB_perc = profile.SMBversterkerPerc
        var SMB_wachttijd = profile.SMBversterkerWachttijd
        var log_SMB_versterker = " ﴿ SMB versterker ﴾" + "\n"
        var smb_factor = 1.0
        if ((System.currentTimeMillis()/(60 * 1000) - SMBWachtTrigger) > SMB_wachttijd && delta15 >= 15 && delta15 >= (delta15_oud + 3)  && bg_act > (target_bg/18 + 0)){
            SMBWachtTrigger = System.currentTimeMillis()/(60 * 1000)

            log_SMB_versterker = log_SMB_versterker + "Geactiveerd SMB perc: $SMB_perc %" + "\n"
            smb_factor = SMB_perc.toDouble() /100
        } else {
            var SMB_tijd = SMB_wachttijd - (System.currentTimeMillis()/(60 * 1000) - SMBWachtTrigger)
            if (SMB_tijd >= 0) {
                log_SMB_versterker = log_SMB_versterker + " Nog " + (SMB_tijd).toString() + " min. tot volgende versterking" + "\n"
            }  else {
                SMB_tijd = SMB_tijd * -1
                log_SMB_versterker = log_SMB_versterker + (SMB_tijd).toString() + " min. sinds laatste versterking" + "\n"
            }
        }



        var Display_overall_perc = (overall_cf *100).toInt()
        sens = sens / overall_cf

// Dynamisch basaal
        val basaal = DynamischBasaal(profile)     //  profile.current_basal  vervangen door  DynamischBasaal(profile)

       // val tddlaatste24uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 24).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
       // val tddlaatste12uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 12).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
       // val tddlaatste6uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 6).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0

        var tddlaatste24uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 24).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
        if (tddlaatste24uur < 15.0) {tddlaatste24uur = profile.standaardTDD}
        var tddlaatste12uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 12).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
        if (tddlaatste12uur < 7.5) {tddlaatste12uur = profile.standaardTDD/2}
        var tddlaatste6uur = tddCalculator.calculateInterval(dateUtil.now()- T.hours(hour = 6).msecs(),dateUtil.now(),true)?.totalAmount ?: 0.0
        if (tddlaatste6uur < 3.75) {tddlaatste6uur = profile.standaardTDD/4}

        val (res,log_resistentie) = Resistentie(profile)
        val ResistentieCfEff_info = (res *100).toInt()

        consoleError.add(" ")
        consoleError.add("֎====== Dyn ֎۝֎ Basaal ====֎")
        consoleError.add(" ")
        consoleError.add(" ● Dyn Basaal: " + round(basaal,2))
        consoleError.add(" ")
        consoleError.add(" ● Basaal tov TDD: " + profile.basaalTDDPerc + "%")
        consoleError.add("  → 24 uur: " + round(tddlaatste24uur/24*profile.basaalTDDPerc.toDouble()/100,2))
        consoleError.add("  → 12 uur: " + round(tddlaatste12uur/12*profile.basaalTDDPerc.toDouble()/100,2))
        consoleError.add("  →  6 uur: " + round(tddlaatste6uur/6*profile.basaalTDDPerc.toDouble()/100,2))
        consoleError.add("\n")
        consoleError.add(log_resistentie)
        consoleError.add("֎======  ISF ֎۝֎ INFO ======֎")
        consoleError.add(" ")
        //    consoleError.add(" ﴿ ISF info samenvatting――﴾")
        consoleError.add(" ● Bg= " + round(bg_act, 1) + " → perc = " + Display_Bg_ISF_perc + "%")
        consoleError.add(Persistent_info)
        consoleError.add(" ● UAM/Bolus correctie: → perc = " + info_cf + "%")
        consoleError.add(" ● Bg/IOB correctie: → perc = " + BG_IOB_percentage + "%")
    //    consoleError.add(" ● Resistentie correctie: → perc = " + ResistentieCfEff_info + "%")
        consoleError.add(" ● Stappen correctie: → perc = " + display_Stap_perc + "%")
        consoleError.add(" ● Auto Sens perc = " + display_sens_perc + "%")
        consoleError.add(" ● Hypo perc = " + hypo_corr_percentage + "%")
        consoleError.add(" ● Automation Boost perc = " + display_Automation_Note_perc + "%")
        consoleError.add(" ")
        //  consoleError.add(log_overall)
        consoleError.add(" ● Overall correctie perc = " + Display_overall_perc + "%")
        consoleError.add(" ● Profiel ISF: " + round(profile.sens/18, 1) + " Aangepast naar: " + round(sens/18, 1))
        consoleError.add(" ")
        consoleError.add("֎===========֎۝֎===========֎")
        consoleError.add(" ")
        consoleError.add("֎======== DETAILS ========֎")
        consoleError.add(" ")
        consoleError.add(log_DagNacht)
        consoleError.add(log_hypo)
        consoleError.add(log_Persistent)
        consoleError.add(log_UAMBoost)
        consoleError.add(log_BG_IOB)
        consoleError.add(log_Note_Automation)

        consoleError.add(log_SMB_versterker)
        consoleError.add(log_ExtraIns)
        consoleError.add(log_Stappen)
        consoleError.add(" ﴿――――――――――――――――――﴾" + "\n")

        val prof_perc = round(1000/profile.carb_ratio,0)
        consoleError.add("\n" + "\n")
        consoleError.add(" ֎========= PROFIEL =========֎")
        consoleError.add(" ● profiel percentage: " + prof_perc.toString() + " %")
        consoleError.add(" ● functie (nog) niet funtioneel")
        consoleError.add(" ֎===========֎\u06DD֎===========֎")
        consoleError.add("\n" + "\n")

// user_action


// log data in csv

        val iob_act = iob_data.iob.toString()
        val dateStr = (dateUtil.dateAndTimeString(dateUtil.now())).toString()
        val isf = round((sens/18),2).toString()
        val delta15txt = delta15.toString()
        val delta15oudtxt = delta15_oud.toString()
        val BgFactortxt = Display_Bg_ISF_perc.toString()
        val bolusBoosttxt = extraIns_Factor.toInt()
        val uamBoosttxt = display_UAM_Perc.toString()
        val BgIOBtxt = BG_IOB_percentage.toString()
        val persBoosttxt = Display_Persistent_perc.toString()
        val autoSenstxt = display_sens_perc.toString()
        val smbFactortxt = round(smb_factor,2).toString()
        val stapPerctxt = display_Stap_perc.toString()
        val overalltxt = (overall_cf*100).toInt().toString()
        val resistentietxt = ResistentieCfEff_info.toString()
        val hypoperctxt = hypo_corr_percentage.toString()
        val AutoSMBtxt = Automation_Note_SMB.toString()
        val Autoperctxt = Automation_Note_perc.toString()

        val headerRow = "datum,bg,isf,iob,delta15,delta15oud,Bgperc,maaltijdBoost,uamBoost,Bg_IOB,persistent,hypo,sens,resistentie,stap,noteperc,overall,BolusSMB,smbF\n"
        val valuesToRecord = "$dateStr,$bg_act,$isf,$iob_act,$delta15txt,$delta15oudtxt,$BgFactortxt,$bolusBoosttxt,$uamBoosttxt,$BgIOBtxt,$persBoosttxt,$hypoperctxt,$autoSenstxt,$resistentietxt,$stapPerctxt,$Autoperctxt,$overalltxt,$AutoSMBtxt,$smbFactortxt"


        if (!csvfile.exists()) {
            csvfile.createNewFile()
            csvfile.appendText(headerRow)
        }
        csvfile.appendText(valuesToRecord + "\n")


// einde log data in csv
        //    consoleError.add("CR:${profile.carb_ratio}")



        //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
        val bgi = round((-iob_data.activity * sens * 5), 2)
        // project deviations for 30 minutes
        var deviation = round(30 / 5 * (minDelta - bgi))
        // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
        if (deviation < 0) {
            deviation = round((30 / 5) * (minAvgDelta - bgi))
            // and if deviation is still negative, use long_avgdelta
            if (deviation < 0) {
                deviation = round((30 / 5) * (glucose_status.longAvgDelta - bgi))
            }
        }

        // calculate the naive (bolus calculator math) eventual BG based on net IOB and sensitivity
        val naive_eventualBG =
             if (iob_data.iob > 0) round(bg - (iob_data.iob * sens), 0)
             else  // if IOB is negative, be more conservative and use the lower of sens, profile.sens
                 round(bg - (iob_data.iob * min(sens, profile.sens)), 0)

        // and adjust it for the deviation above
        var eventualBG = naive_eventualBG + deviation

        // raise target for noisy / raw CGM data
        if (bg > max_bg && profile.adv_target_adjustments && !profile.temptargetSet) {
            // with target=100, as BG rises from 100 to 160, adjustedTarget drops from 100 to 80
            val adjustedMinBG = round(max(80.0, min_bg - (bg - min_bg) / 3.0), 0)
            val adjustedTargetBG = round(max(80.0, target_bg - (bg - target_bg) / 3.0), 0)
            val adjustedMaxBG = round(max(80.0, max_bg - (bg - max_bg) / 3.0), 0)
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedMinBG, don’t use it
            //console.error("naive_eventualBG:",naive_eventualBG+", eventualBG:",eventualBG);
            if (eventualBG > adjustedMinBG && naive_eventualBG > adjustedMinBG && min_bg > adjustedMinBG) {
                consoleLog.add("Adjusting targets for high BG: min_bg from $min_bg to $adjustedMinBG; ")
                min_bg = adjustedMinBG
            } else {
                consoleLog.add("min_bg unchanged: $min_bg; ")
            }
            // if eventualBG, naive_eventualBG, and target_bg aren't all above adjustedTargetBG, don’t use it
            if (eventualBG > adjustedTargetBG && naive_eventualBG > adjustedTargetBG && target_bg > adjustedTargetBG) {
                consoleLog.add("target_bg from $target_bg to $adjustedTargetBG; ")
                target_bg = adjustedTargetBG
            } else {
                consoleLog.add("target_bg unchanged: $target_bg; ")
            }
            // if eventualBG, naive_eventualBG, and max_bg aren't all above adjustedMaxBG, don’t use it
            if (eventualBG > adjustedMaxBG && naive_eventualBG > adjustedMaxBG && max_bg > adjustedMaxBG) {
                consoleError.add("max_bg from $max_bg to $adjustedMaxBG")
                max_bg = adjustedMaxBG
            } else {
                consoleError.add("max_bg unchanged: $max_bg")
            }
        }

        val expectedDelta = calculate_expected_delta(target_bg, eventualBG, bgi)

        // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
        var threshold = min_bg - 0.5 * (min_bg - 40)
        if (profile.lgsThreshold != null) {
            val lgsThreshold = profile.lgsThreshold ?: error("lgsThreshold missing")
            if (lgsThreshold > threshold) {
                consoleError.add("Threshold set from ${convert_bg(threshold)} to ${convert_bg(lgsThreshold.toDouble())}; ")
                threshold = lgsThreshold.toDouble()
            }
        }

        //console.error(reservoir_data);  

        rT = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = dynIsfMode,
            timestamp = currentTime,
            bg = bg,
            tick = tick,
            eventualBG = eventualBG,
            targetBG = target_bg,
            insulinReq = 0.0,
            deliverAt = deliverAt, // The time at which the microbolus should be delivered
            sensitivityRatio = sensitivityRatio, // autosens ratio (fraction of normal basal)
            consoleLog = consoleLog,
            consoleError = consoleError,
            variable_sens = sens // profile.variable_sens
        )
        if (Auto_SMB){
            rT.reason.append(". Geef extra SMB $Automation_Note_SMB u")
            rT.units = Automation_Note_SMB
            return rT
        }


        // generate predicted future BGs based on IOB, COB, and current absorption rate

        var COBpredBGs = mutableListOf<Double>()
        var aCOBpredBGs = mutableListOf<Double>()
        var IOBpredBGs = mutableListOf<Double>()
        var UAMpredBGs = mutableListOf<Double>()
        var ZTpredBGs = mutableListOf<Double>()
        COBpredBGs.add(bg)
        aCOBpredBGs.add(bg)
        IOBpredBGs.add(bg)
        ZTpredBGs.add(bg)
        UAMpredBGs.add(bg)

        var enableSMB = enable_smb(profile, microBolusAllowed, meal_data, target_bg)

        // enable UAM (if enabled in preferences)
        val enableUAM = profile.enableUAM

        //console.error(meal_data);
        // carb impact and duration are 0 unless changed below
        var ci: Double
        val cid: Double
        // calculate current carb absorption rate, and how long to absorb all carbs
        // CI = current carb impact on BG in mg/dL/5m
        ci = round((minDelta - bgi), 1)
        val uci = round((minDelta - bgi), 1)
        // ISF (mg/dL/U) / CR (g/U) = CSF (mg/dL/g)

        // TODO: remove commented-out code for old behavior
        //if (profile.temptargetSet) {
        // if temptargetSet, use unadjusted profile.sens to allow activity mode sensitivityRatio to adjust CR
        //var csf = profile.sens / profile.carb_ratio;
        //} else {
        // otherwise, use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments
        // so that autotuned CR is still in effect even when basals and ISF are being adjusted by autosens
        //var csf = sens / profile.carb_ratio;
        //}
        // use autosens-adjusted sens to counteract autosens meal insulin dosing adjustments so that
        // autotuned CR is still in effect even when basals and ISF are being adjusted by TT or autosens
        // this avoids overdosing insulin for large meals when low temp targets are active
        val csf = round(sens / profile.carb_ratio,1)
        //       consoleError.add("profile.sens: ${profile.sens}, sens: $sens, CSF: $csf")

        val maxCarbAbsorptionRate = 30 // g/h; maximum rate to assume carbs will absorb if no CI observed
        // limit Carb Impact to maxCarbAbsorptionRate * csf in mg/dL per 5m
        val maxCI = round(maxCarbAbsorptionRate * csf * 5 / 60, 1)
        if (ci > maxCI) {
            //           consoleError.add("Limiting carb impact from $ci to $maxCI mg/dL/5m ( $maxCarbAbsorptionRate g/h )")
            ci = maxCI
        }
        var remainingCATimeMin = 3.0 // h; duration of expected not-yet-observed carb absorption
        // adjust remainingCATime (instead of CR) for autosens if sensitivityRatio defined
        remainingCATimeMin = remainingCATimeMin / sensitivityRatio
        // 20 g/h means that anything <= 60g will get a remainingCATimeMin, 80g will get 4h, and 120g 6h
        // when actual absorption ramps up it will take over from remainingCATime
        val assumedCarbAbsorptionRate = 20 // g/h; maximum rate to assume carbs will absorb if no CI observed
        var remainingCATime = remainingCATimeMin
        if (meal_data.carbs != 0.0) {
            // if carbs * assumedCarbAbsorptionRate > remainingCATimeMin, raise it
            // so <= 90g is assumed to take 3h, and 120g=4h
            remainingCATimeMin = Math.max(remainingCATimeMin, meal_data.mealCOB / assumedCarbAbsorptionRate)
            val lastCarbAge = round((systemTime - meal_data.lastCarbTime) / 60000.0)
            //console.error(meal_data.lastCarbTime, lastCarbAge);

            val fractionCOBAbsorbed = (meal_data.carbs - meal_data.mealCOB) / meal_data.carbs
            remainingCATime = remainingCATimeMin + 1.5 * lastCarbAge / 60
            remainingCATime = round(remainingCATime, 1)
            //console.error(fractionCOBAbsorbed, remainingCATimeAdjustment, remainingCATime)
            consoleError.add("Last carbs " + lastCarbAge + "minutes ago; remainingCATime:" + remainingCATime + "hours;" + round(fractionCOBAbsorbed * 100) + "% carbs absorbed")
        }

        // calculate the number of carbs absorbed over remainingCATime hours at current CI
        // CI (mg/dL/5m) * (5m)/5 (m) * 60 (min/hr) * 4 (h) / 2 (linear decay factor) = total carb impact (mg/dL)
        val totalCI = Math.max(0.0, ci / 5 * 60 * remainingCATime / 2)
        // totalCI (mg/dL) / CSF (mg/dL/g) = total carbs absorbed (g)
        val totalCA = totalCI / csf
        val remainingCarbsCap: Int // default to 90
        remainingCarbsCap = min(90, profile.remainingCarbsCap)
        var remainingCarbs = max(0.0, meal_data.mealCOB - totalCA)
        remainingCarbs = Math.min(remainingCarbsCap.toDouble(), remainingCarbs)
        // assume remainingCarbs will absorb in a /\ shaped bilinear curve
        // peaking at remainingCATime / 2 and ending at remainingCATime hours
        // area of the /\ triangle is the same as a remainingCIpeak-height rectangle out to remainingCATime/2
        // remainingCIpeak (mg/dL/5m) = remainingCarbs (g) * CSF (mg/dL/g) * 5 (m/5m) * 1h/60m / (remainingCATime/2) (h)
        val remainingCIpeak = remainingCarbs * csf * 5 / 60 / (remainingCATime / 2)
        //console.error(profile.min_5m_carbimpact,ci,totalCI,totalCA,remainingCarbs,remainingCI,remainingCATime);

        // calculate peak deviation in last hour, and slope from that to current deviation
        val slopeFromMaxDeviation = round(meal_data.slopeFromMaxDeviation, 2)
        // calculate lowest deviation in last hour, and slope from that to current deviation
        val slopeFromMinDeviation = round(meal_data.slopeFromMinDeviation, 2)
        // assume deviations will drop back down at least at 1/3 the rate they ramped up
        val slopeFromDeviations = Math.min(slopeFromMaxDeviation, -slopeFromMinDeviation / 3)
        //console.error(slopeFromMaxDeviation);

        val aci = 10
        //5m data points = g * (1U/10g) * (40mg/dL/1U) / (mg/dL/5m)
        // duration (in 5m data points) = COB (g) * CSF (mg/dL/g) / ci (mg/dL/5m)
        // limit cid to remainingCATime hours: the reset goes to remainingCI
        if (ci == 0.0) {
            // avoid divide by zero
            cid = 0.0
        } else {
            cid = min(remainingCATime * 60 / 5 / 2, Math.max(0.0, meal_data.mealCOB * csf / ci))
        }
        val acid = max(0.0, meal_data.mealCOB * csf / aci)
        // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
        //      consoleError.add("Carb Impact: $ci mg/dL per 5m; CI Duration: ${round(cid * 5 / 60 * 2, 1)} hours; remaining CI (~2h peak): ${round(remainingCIpeak, 1)} mg/dL per 5m")
        //console.error("Accel. Carb Impact:",aci,"mg/dL per 5m; ACI Duration:",round(acid*5/60*2,1),"hours");
        var minIOBPredBG = 999.0
        var minCOBPredBG = 999.0
        var minUAMPredBG = 999.0
        var minGuardBG: Double
        var minCOBGuardBG = 999.0
        var minUAMGuardBG = 999.0
        var minIOBGuardBG = 999.0
        var minZTGuardBG = 999.0
        var minPredBG: Double
        var avgPredBG: Double
        var IOBpredBG: Double = eventualBG
        var maxIOBPredBG = bg
        var maxCOBPredBG = bg
        //var maxUAMPredBG = bg
        //var maxPredBG = bg;
        //var eventualPredBG = bg
        val lastIOBpredBG: Double
        var lastCOBpredBG: Double? = null
        var lastUAMpredBG: Double? = null
        //var lastZTpredBG: Int
        var UAMduration = 0.0
        var remainingCItotal = 0.0
        val remainingCIs = mutableListOf<Int>()
        val predCIs = mutableListOf<Int>()
        var UAMpredBG: Double? = null
        var COBpredBG: Double? = null
        var aCOBpredBG: Double?
        iobArray.forEach { iobTick ->
            //console.error(iobTick);
            val predBGI: Double = round((-iobTick.activity * sens * 5), 2)
            val IOBpredBGI: Double = predBGI

            iobTick.iobWithZeroTemp ?: error("iobTick.iobWithZeroTemp missing")
            // try to find where is crashing https://console.firebase.google.com/u/0/project/androidaps-c34f8/crashlytics/app/android:info.nightscout.androidaps/issues/950cdbaf63d545afe6d680281bb141e5?versions=3.3.0-dev-d%20(1500)&time=last-thirty-days&types=crash&sessionEventKey=673BF7DD032300013D4704707A053273_2017608123846397475
            if (iobTick.iobWithZeroTemp!!.activity.isNaN() || sens.isNaN())
                fabricPrivacy.logCustom("iobTick.iobWithZeroTemp!!.activity=${iobTick.iobWithZeroTemp!!.activity} sens=$sens")
            val predZTBGI =  round((-iobTick.iobWithZeroTemp!!.activity * sens * 5), 2)
            val predUAMBGI = predBGI

            // for IOBpredBGs, predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            val predDev: Double = ci * (1 - min(1.0, IOBpredBGs.size / (60.0 / 5.0)))
            IOBpredBG = IOBpredBGs[IOBpredBGs.size - 1] + IOBpredBGI + predDev
            // calculate predBGs with long zero temp without deviations
            val ZTpredBG = ZTpredBGs[ZTpredBGs.size - 1] + predZTBGI
            // for COBpredBGs, predicted carb impact drops linearly from current carb impact down to zero
            // eventually accounting for all carbs (if they can be absorbed over DIA)
            val predCI: Double = max(0.0, max(0.0, ci) * (1 - COBpredBGs.size / max(cid * 2, 1.0)))
            val predACI = max(0.0, max(0, aci) * (1 - COBpredBGs.size / max(acid * 2, 1.0)))
            // if any carbs aren't absorbed after remainingCATime hours, assume they'll absorb in a /\ shaped
            // bilinear curve peaking at remainingCIpeak at remainingCATime/2 hours (remainingCATime/2*12 * 5m)
            // and ending at remainingCATime h (remainingCATime*12 * 5m intervals)
            val intervals = Math.min(COBpredBGs.size.toDouble(), ((remainingCATime * 12) - COBpredBGs.size))
            val remainingCI = Math.max(0.0, intervals / (remainingCATime / 2 * 12) * remainingCIpeak)
            remainingCItotal += predCI + remainingCI
            remainingCIs.add(round(remainingCI))
            predCIs.add(round(predCI))
            //console.log(round(predCI,1)+"+"+round(remainingCI,1)+" ");
            COBpredBG = COBpredBGs[COBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predCI + remainingCI
            aCOBpredBG = aCOBpredBGs[aCOBpredBGs.size - 1] + predBGI + min(0.0, predDev) + predACI
            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            val predUCIslope = max(0.0, uci + (UAMpredBGs.size * slopeFromDeviations))
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            val predUCImax = max(0.0, uci * (1 - UAMpredBGs.size / max(3.0 * 60 / 5, 1.0)))
            //console.error(predUCIslope, predUCImax);
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            val predUCI = min(predUCIslope, predUCImax)
            if (predUCI > 0) {
                //console.error(UAMpredBGs.length,slopeFromDeviations, predUCI);
                UAMduration = round((UAMpredBGs.size + 1) * 5 / 60.0, 1)
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.size - 1] + predUAMBGI + min(0.0, predDev) + predUCI
            //console.error(predBGI, predCI, predUCI);
            // truncate all BG predictions at 4 hours
            if (IOBpredBGs.size < 48) IOBpredBGs.add(IOBpredBG)
            if (COBpredBGs.size < 48) COBpredBGs.add(COBpredBG!!)
            if (aCOBpredBGs.size < 48) aCOBpredBGs.add(aCOBpredBG!!)
            if (UAMpredBGs.size < 48) UAMpredBGs.add(UAMpredBG!!)
            if (ZTpredBGs.size < 48) ZTpredBGs.add(ZTpredBG)
            // calculate minGuardBGs without a wait from COB, UAM, IOB predBGs
            if (COBpredBG!! < minCOBGuardBG) minCOBGuardBG = round(COBpredBG!!).toDouble()
            if (UAMpredBG!! < minUAMGuardBG) minUAMGuardBG = round(UAMpredBG!!).toDouble()
            if (IOBpredBG < minIOBGuardBG) minIOBGuardBG = IOBpredBG
            if (ZTpredBG < minZTGuardBG) minZTGuardBG = round(ZTpredBG, 0)

            // set minPredBGs starting when currently-dosed insulin activity will peak
            // look ahead 60m (regardless of insulin type) so as to be less aggressive on slower insulins
            // add 30m to allow for insulin delivery (SMBs or temps)
            val insulinPeakTime = 90
            val insulinPeak5m = (insulinPeakTime / 60.0) * 12.0
            //console.error(insulinPeakTime, insulinPeak5m, profile.insulinPeakTime, profile.curve);

            // wait 90m before setting minIOBPredBG
            if (IOBpredBGs.size > insulinPeak5m && (IOBpredBG < minIOBPredBG)) minIOBPredBG = round(IOBpredBG, 0)
            if (IOBpredBG > maxIOBPredBG) maxIOBPredBG = IOBpredBG
            // wait 85-105m before setting COB and 60m for UAM minPredBGs
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBGs.size > insulinPeak5m && (COBpredBG!! < minCOBPredBG)) minCOBPredBG = round(COBpredBG!!, 0)
            if ((cid != 0.0 || remainingCIpeak > 0) && COBpredBG!! > maxIOBPredBG) maxCOBPredBG = COBpredBG as Double
            if (enableUAM && UAMpredBGs.size > 12 && (UAMpredBG!! < minUAMPredBG)) minUAMPredBG = round(UAMpredBG!!, 0)
            //if (enableUAM && UAMpredBG!! > maxIOBPredBG) maxUAMPredBG = UAMpredBG!!
        }
        // set eventualBG to include effect of carbs
        //console.error("PredBGs:",JSON.stringify(predBGs));
        if (meal_data.mealCOB > 0) {
            consoleError.add("predCIs (mg/dL/5m):" + predCIs.joinToString(separator = " "))
            consoleError.add("remainingCIs:      " + remainingCIs.joinToString(separator = " "))
        }
        rT.predBGs = Predictions()
        IOBpredBGs = IOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in IOBpredBGs.size - 1 downTo 13) {
            if (IOBpredBGs[i - 1] != IOBpredBGs[i]) break
            else IOBpredBGs.removeAt(IOBpredBGs.lastIndex)
        }
        rT.predBGs?.IOB = IOBpredBGs.map { it.toInt() }
        lastIOBpredBG = round(IOBpredBGs[IOBpredBGs.size - 1]).toDouble()
        ZTpredBGs = ZTpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
        for (i in ZTpredBGs.size - 1 downTo 7) {
            // stop displaying ZTpredBGs once they're rising and above target
            if (ZTpredBGs[i - 1] >= ZTpredBGs[i] || ZTpredBGs[i] <= target_bg) break
            else ZTpredBGs.removeAt(ZTpredBGs.lastIndex)
        }
        rT.predBGs?.ZT = ZTpredBGs.map { it.toInt() }
        if (meal_data.mealCOB > 0) {
            aCOBpredBGs = aCOBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in aCOBpredBGs.size - 1 downTo 13) {
                if (aCOBpredBGs[i - 1] != aCOBpredBGs[i]) break
                else aCOBpredBGs.removeAt(aCOBpredBGs.lastIndex)
            }
        }
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            COBpredBGs = COBpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
            for (i in COBpredBGs.size - 1 downTo 13) {
                if (COBpredBGs[i - 1] != COBpredBGs[i]) break
                else COBpredBGs.removeAt(COBpredBGs.lastIndex)
            }
            rT.predBGs?.COB = COBpredBGs.map { it.toInt() }
            lastCOBpredBG = COBpredBGs[COBpredBGs.size - 1]
            eventualBG = max(eventualBG, round(COBpredBGs[COBpredBGs.size - 1], 0))
        }
        if (ci > 0 || remainingCIpeak > 0) {
            if (enableUAM) {
                UAMpredBGs = UAMpredBGs.map { round(min(401.0, max(39.0, it)), 0) }.toMutableList()
                for (i in UAMpredBGs.size - 1 downTo 13) {
                    if (UAMpredBGs[i - 1] != UAMpredBGs[i]) break
                    else UAMpredBGs.removeAt(UAMpredBGs.lastIndex)
                }
                rT.predBGs?.UAM = UAMpredBGs.map { it.toInt() }
                lastUAMpredBG = UAMpredBGs[UAMpredBGs.size - 1]
                eventualBG = max(eventualBG, round(UAMpredBGs[UAMpredBGs.size - 1], 0))
            }

            // set eventualBG based on COB or UAM predBGs
            rT.eventualBG = eventualBG
        }

        consoleError.add("UAM Impact: $uci mg/dL per 5m; UAM Duration: $UAMduration hours")
        consoleLog.add("EventualBG is $eventualBG ;")

        minIOBPredBG = max(39.0, minIOBPredBG)
        minCOBPredBG = max(39.0, minCOBPredBG)
        minUAMPredBG = max(39.0, minUAMPredBG)
        minPredBG = round(minIOBPredBG, 0)

        val fSensBG = min(minPredBG, bg)

        var future_sens = 0.0

        val fractionCarbsLeft = meal_data.mealCOB / meal_data.carbs
        // if we have COB and UAM is enabled, average both
        if (minUAMPredBG < 999 && minCOBPredBG < 999) {
            // weight COBpredBG vs. UAMpredBG based on how many carbs remain as COB
            avgPredBG = round((1 - fractionCarbsLeft) * UAMpredBG!! + fractionCarbsLeft * COBpredBG!!, 0)
            // if UAM is disabled, average IOB and COB
        } else if (minCOBPredBG < 999) {
            avgPredBG = round((IOBpredBG + COBpredBG!!) / 2.0, 0)
            // if we have UAM but no COB, average IOB and UAM
        } else if (minUAMPredBG < 999) {
            avgPredBG = round((IOBpredBG + UAMpredBG!!) / 2.0, 0)
        } else {
            avgPredBG = round(IOBpredBG, 0)
        }
        // if avgPredBG is below minZTGuardBG, bring it up to that level
        if (minZTGuardBG > avgPredBG) {
            avgPredBG = minZTGuardBG
        }

        // if we have both minCOBGuardBG and minUAMGuardBG, blend according to fractionCarbsLeft
        if ((cid > 0.0 || remainingCIpeak > 0)) {
            if (enableUAM) {
                minGuardBG = fractionCarbsLeft * minCOBGuardBG + (1 - fractionCarbsLeft) * minUAMGuardBG
            } else {
                minGuardBG = minCOBGuardBG
            }
        } else if (enableUAM) {
            minGuardBG = minUAMGuardBG
        } else {
            minGuardBG = minIOBGuardBG
        }
        minGuardBG = round(minGuardBG, 0)
        //console.error(minCOBGuardBG, minUAMGuardBG, minIOBGuardBG, minGuardBG);

        var minZTUAMPredBG = minUAMPredBG
        // if minZTGuardBG is below threshold, bring down any super-high minUAMPredBG by averaging
        // this helps prevent UAM from giving too much insulin in case absorption falls off suddenly
        if (minZTGuardBG < threshold) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
            // if minZTGuardBG is between threshold and target, blend in the averaging
        } else if (minZTGuardBG < target_bg) {
            // target 100, threshold 70, minZTGuardBG 85 gives 50%: (85-70) / (100-70)
            val blendPct = (minZTGuardBG - threshold) / (target_bg - threshold)
            val blendedMinZTGuardBG = minUAMPredBG * blendPct + minZTGuardBG * (1 - blendPct)
            minZTUAMPredBG = (minUAMPredBG + blendedMinZTGuardBG) / 2.0
            //minZTUAMPredBG = minUAMPredBG - target_bg + minZTGuardBG;
            // if minUAMPredBG is below minZTGuardBG, bring minUAMPredBG up by averaging
            // this allows more insulin if lastUAMPredBG is below target, but minZTGuardBG is still high
        } else if (minZTGuardBG > minUAMPredBG) {
            minZTUAMPredBG = (minUAMPredBG + minZTGuardBG) / 2.0
        }
        minZTUAMPredBG = round(minZTUAMPredBG, 0)
        //console.error("minUAMPredBG:",minUAMPredBG,"minZTGuardBG:",minZTGuardBG,"minZTUAMPredBG:",minZTUAMPredBG);
        // if any carbs have been entered recently
        if (meal_data.carbs != 0.0) {

            // if UAM is disabled, use max of minIOBPredBG, minCOBPredBG
            if (!enableUAM && minCOBPredBG < 999) {
                minPredBG = round(max(minIOBPredBG, minCOBPredBG), 0)
                // if we have COB, use minCOBPredBG, or blendedMinPredBG if it's higher
            } else if (minCOBPredBG < 999) {
                // calculate blendedMinPredBG based on how many carbs remain as COB
                val blendedMinPredBG = fractionCarbsLeft * minCOBPredBG + (1 - fractionCarbsLeft) * minZTUAMPredBG
                // if blendedMinPredBG > minCOBPredBG, use that instead
                minPredBG = round(max(minIOBPredBG, max(minCOBPredBG, blendedMinPredBG)), 0)
                // if carbs have been entered, but have expired, use minUAMPredBG
            } else if (enableUAM) {
                minPredBG = minZTUAMPredBG
            } else {
                minPredBG = minGuardBG
            }
            // in pure UAM mode, use the higher of minIOBPredBG,minUAMPredBG
        } else if (enableUAM) {
            minPredBG = round(max(minIOBPredBG, minZTUAMPredBG), 0)
        }
        // make sure minPredBG isn't higher than avgPredBG
        minPredBG = min(minPredBG, avgPredBG)

        consoleLog.add("minPredBG: $minPredBG minIOBPredBG: $minIOBPredBG minZTGuardBG: $minZTGuardBG")
        if (minCOBPredBG < 999) {
            consoleLog.add(" minCOBPredBG: $minCOBPredBG")
        }
        if (minUAMPredBG < 999) {
            consoleLog.add(" minUAMPredBG: $minUAMPredBG")
        }
        consoleError.add(" avgPredBG: $avgPredBG COB: ${meal_data.mealCOB} / ${meal_data.carbs}")
        // But if the COB line falls off a cliff, don't trust UAM too much:
        // use maxCOBPredBG if it's been set and lower than minPredBG
        if (maxCOBPredBG > bg) {
            minPredBG = min(minPredBG, maxCOBPredBG)
        }

        rT.COB = meal_data.mealCOB
        rT.IOB = iob_data.iob
        rT.reason.append(
            "COB: ${round(meal_data.mealCOB, 1).withoutZeros()}, Dev: ${convert_bg(deviation.toDouble())}, BGI: ${convert_bg(bgi)}, ISF: ${convert_bg(sens)}, CR: ${
                round(profile.carb_ratio, 2)
                    .withoutZeros()
            }, Target: ${convert_bg(target_bg)}, minPredBG ${convert_bg(minPredBG)}, minGuardBG ${convert_bg(minGuardBG)}, IOBpredBG ${convert_bg(lastIOBpredBG)}"
        )
        if (lastCOBpredBG != null) {
            rT.reason.append(", COBpredBG " + convert_bg(lastCOBpredBG.toDouble()))
        }
        if (lastUAMpredBG != null) {
            rT.reason.append(", UAMpredBG " + convert_bg(lastUAMpredBG.toDouble()))
        }
        rT.reason.append("; ")
        // use naive_eventualBG if above 40, but switch to minGuardBG if both eventualBGs hit floor of 39
        var carbsReqBG = naive_eventualBG
        if (carbsReqBG < 40) {
            carbsReqBG = min(minGuardBG, carbsReqBG)
        }
        var bgUndershoot: Double = threshold - carbsReqBG
        // calculate how long until COB (or IOB) predBGs drop below min_bg
        var minutesAboveMinBG = 240
        var minutesAboveThreshold = 240
        if (meal_data.mealCOB > 0 && (ci > 0 || remainingCIpeak > 0)) {
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], min_bg);
                if (COBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in COBpredBGs.indices) {
                //console.error(COBpredBGs[i], threshold);
                if (COBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        } else {
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], min_bg);
                if (IOBpredBGs[i] < min_bg) {
                    minutesAboveMinBG = 5 * i
                    break
                }
            }
            for (i in IOBpredBGs.indices) {
                //console.error(IOBpredBGs[i], threshold);
                if (IOBpredBGs[i] < threshold) {
                    minutesAboveThreshold = 5 * i
                    break
                }
            }
        }

        if (enableSMB && minGuardBG < threshold) {
            consoleError.add("minGuardBG ${convert_bg(minGuardBG)} projected below ${convert_bg(threshold)} - disabling SMB")
            //rT.reason += "minGuardBG "+minGuardBG+"<"+threshold+": SMB disabled; ";
            enableSMB = false
        }
        if (maxDelta > 0.20 * bg) {
            consoleError.add("maxDelta ${convert_bg(maxDelta)} > 20% of BG ${convert_bg(bg)} - disabling SMB")
            rT.reason.append("maxDelta " + convert_bg(maxDelta) + " > 20% of BG " + convert_bg(bg) + ": SMB disabled; ")
            enableSMB = false
        }

        consoleError.add("BG projected to remain above ${convert_bg(min_bg)} for $minutesAboveMinBG minutes")
        if (minutesAboveThreshold < 240 || minutesAboveMinBG < 60) {
            consoleError.add("BG projected to remain above ${convert_bg(threshold)} for $minutesAboveThreshold minutes")
        }
        // include at least minutesAboveThreshold worth of zero temps in calculating carbsReq
        // always include at least 30m worth of zero temp (carbs to 80, low temp up to target)
        val zeroTempDuration = minutesAboveThreshold
        // BG undershoot, minus effect of zero temps until hitting min_bg, converted to grams, minus COB
        val zeroTempEffectDouble = DynamischBasaal(profile) * sens * zeroTempDuration / 60
        // don't count the last 25% of COB against carbsReq
        val COBforCarbsReq = max(0.0, meal_data.mealCOB - 0.25 * meal_data.carbs)
        val carbsReq = round(((bgUndershoot - zeroTempEffectDouble) / csf - COBforCarbsReq))
        val zeroTempEffect = round(zeroTempEffectDouble)
        consoleError.add("naive_eventualBG: $naive_eventualBG bgUndershoot: $bgUndershoot zeroTempDuration $zeroTempDuration zeroTempEffect: $zeroTempEffect carbsReq: $carbsReq")
        if (carbsReq >= profile.carbsReqThreshold && minutesAboveThreshold <= 45) {
            rT.carbsReq = carbsReq
            rT.carbsReqWithin = minutesAboveThreshold
            rT.reason.append("$carbsReq add\'l carbs req w/in ${minutesAboveThreshold}m; ")
        }

        // don't low glucose suspend if IOB is already super negative and BG is rising faster than predicted
        if (bg < threshold && iob_data.iob < -DynamischBasaal(profile) * 20 / 60 && minDelta > 0 && minDelta > expectedDelta) {
            rT.reason.append("IOB ${iob_data.iob} < ${round(-DynamischBasaal(profile) * 20 / 60, 2)}")
            rT.reason.append(" and minDelta ${convert_bg(minDelta)} > expectedDelta ${convert_bg(expectedDelta)}; ")
            // predictive low glucose suspend mode: BG is / is projected to be < threshold
        } else if (bg < threshold || minGuardBG < threshold) {
            rT.reason.append("minGuardBG " + convert_bg(minGuardBG) + "<" + convert_bg(threshold))
            bgUndershoot = target_bg - minGuardBG
            val worstCaseInsulinReq = bgUndershoot / sens
            var durationReq = round(60 * worstCaseInsulinReq / DynamischBasaal(profile))
            durationReq = round(durationReq / 30.0) * 30
            // always set a 30-120m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
            durationReq = min(120, max(30, durationReq))
            return setTempBasal(0.0, durationReq, profile, rT, currenttemp)
        }

        // if not in LGS mode, cancel temps before the top of the hour to reduce beeping/vibration
        // console.error(profile.skip_neutral_temps, rT.deliverAt.getMinutes());
        val minutes = Instant.ofEpochMilli(rT.deliverAt!!).atZone(ZoneId.systemDefault()).toLocalDateTime().minute
        if (profile.skip_neutral_temps && minutes >= 55) {
            rT.reason.append("; Canceling temp at " + minutes + "m past the hour. ")
            return setTempBasal(0.0, 0, profile, rT, currenttemp)
        }

        if (eventualBG < min_bg) { // if eventual BG is below target:
            rT.reason.append("Eventual BG ${convert_bg(eventualBG)} < ${convert_bg(min_bg)}")
            // if 5m or 30m avg BG is rising faster than expected delta
            if (minDelta > expectedDelta && minDelta > 0 && carbsReq == 0) {
                // if naive_eventualBG < 40, set a 30m zero temp (oref0-pump-loop will let any longer SMB zero temp run)
                if (naive_eventualBG < 40) {
                    rT.reason.append(", naive_eventualBG < 40. ")
                    return setTempBasal(0.0, 30, profile, rT, currenttemp)
                }
                if (glucose_status.delta > minDelta) {
                    rT.reason.append(", but Delta ${convert_bg(tick.toDouble())} > expectedDelta ${convert_bg(expectedDelta)}")
                } else {
                    rT.reason.append(", but Min. Delta ${minDelta.toFixed2()} > Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }

            // calculate 30m low-temp required to get projected BG up to target
            // multiply by 2 to low-temp faster for increased hypo safety
            var insulinReq = 2 * min(0.0, (eventualBG - target_bg) / sens)
            insulinReq = round(insulinReq, 2)
            // calculate naiveInsulinReq based on naive_eventualBG
            var naiveInsulinReq = min(0.0, (naive_eventualBG - target_bg) / sens)
            naiveInsulinReq = round(naiveInsulinReq, 2)
            if (minDelta < 0 && minDelta > expectedDelta) {
                // if we're barely falling, newinsulinReq should be barely negative
                val newinsulinReq = round((insulinReq * (minDelta / expectedDelta)), 2)
                //console.error("Increasing insulinReq from " + insulinReq + " to " + newinsulinReq);
                insulinReq = newinsulinReq
            }
            // rate required to deliver insulinReq less insulin over 30m:
            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)

            // if required temp < existing temp basal
            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            // if current temp would deliver a lot (30% of basal) less than the required insulin,
            // by both normal and naive calculations, then raise the rate
            val minInsulinReq = Math.min(insulinReq, naiveInsulinReq)
            if (insulinScheduled < minInsulinReq - basal * 0.3) {
                rT.reason.append(", ${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} is a lot less than needed. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
            if (currenttemp.duration > 5 && rate >= currenttemp.rate * 0.8) {
                rT.reason.append(", temp ${currenttemp.rate} ~< req ${round(rate, 2)}U/hr. ")
                return rT
            } else {
                // calculate a long enough zero temp to eventually correct back up to target
                if (rate <= 0) {
                    bgUndershoot = (target_bg - naive_eventualBG)
                    val worstCaseInsulinReq = bgUndershoot / sens
                    var durationReq = round(60 * worstCaseInsulinReq / DynamischBasaal(profile))
                    if (durationReq < 0) {
                        durationReq = 0
                        // don't set a temp longer than 120 minutes
                    } else {
                        durationReq = round(durationReq / 30.0) * 30
                        durationReq = min(120, max(0, durationReq))
                    }
                    //console.error(durationReq);
                    if (durationReq > 0) {
                        rT.reason.append(", setting ${durationReq}m zero temp. ")
                        return setTempBasal(rate, durationReq, profile, rT, currenttemp)
                    }
                } else {
                    rT.reason.append(", setting ${round(rate, 2)}U/hr. ")
                }
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }
        }

        // if eventual BG is above min but BG is falling faster than expected Delta
        if (minDelta < expectedDelta) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                if (glucose_status.delta < minDelta) {
                    rT.reason.append(
                        "Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Delta ${convert_bg(tick.toDouble())} < Exp. Delta ${
                            convert_bg(expectedDelta)
                        }"
                    )
                } else {
                    rT.reason.append("Eventual BG ${convert_bg(eventualBG)} > ${convert_bg(min_bg)} but Min. Delta ${minDelta.toFixed2()} < Exp. Delta ${convert_bg(expectedDelta)}")
                }
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp " + currenttemp.rate + " ~ req " + round(basal, 2).withoutZeros() + "U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }
        // eventualBG or minPredBG is below max_bg
        if (min(eventualBG, minPredBG) < max_bg) {
            // if in SMB mode, don't cancel SMB zero temp
            if (!(microBolusAllowed && enableSMB)) {
                rT.reason.append("${convert_bg(eventualBG)}-${convert_bg(minPredBG)} in range: no temp required")
                if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                    rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                    return rT
                } else {
                    rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                    return setTempBasal(basal, 30, profile, rT, currenttemp)
                }
            }
        }

        // eventual BG is at/above target
        // if iob is over max, just cancel any temps
        if (eventualBG >= max_bg) {
            rT.reason.append("Eventual BG " + convert_bg(eventualBG) + " >= " + convert_bg(max_bg) + ", ")
        }
        if (iob_data.iob > max_iob) {
            rT.reason.append("IOB ${round(iob_data.iob, 2)} > max_iob $max_iob")
            if (currenttemp.duration > 15 && (round_basal(basal) == round_basal(currenttemp.rate))) {
                rT.reason.append(", temp ${currenttemp.rate} ~ req ${round(basal, 2).withoutZeros()}U/hr. ")
                return rT
            } else {
                rT.reason.append("; setting current basal of ${round(basal, 2)} as temp. ")
                return setTempBasal(basal, 30, profile, rT, currenttemp)
            }
        } else { // otherwise, calculate 30m high-temp required to get projected BG down to target
            // insulinReq is the additional insulin required to get minPredBG down to target_bg
            //console.error(minPredBG,eventualBG);
            var insulinReq = round((min(minPredBG, eventualBG) - target_bg) / sens, 2)
            // if that would put us over max_iob, then reduce accordingly
            if (insulinReq > max_iob - iob_data.iob) {
                rT.reason.append("max_iob $max_iob, ")
                insulinReq = max_iob - iob_data.iob
            }

            // rate required to deliver insulinReq more insulin over 30m:
            var rate = basal + (2 * insulinReq)
            rate = round_basal(rate)
            insulinReq = round(insulinReq, 3)



            rT.insulinReq = insulinReq
            //console.error(iob_data.lastBolusTime);
            //console.error(profile.temptargetSet, target_bg, rT.COB);
            // only allow microboluses with COB or low temp targets, or within DIA hours of a bolus
            val maxBolus: Double
            if (microBolusAllowed && enableSMB && bg > threshold) {
                // never bolus more than maxSMBBasalMinutes worth of basal
                val mealInsulinReq = round(meal_data.mealCOB / profile.carb_ratio, 3)
                if (iob_data.iob > mealInsulinReq && iob_data.iob > 0) {
                    consoleError.add("IOB ${iob_data.iob} > COB ${meal_data.mealCOB}; mealInsulinReq = $mealInsulinReq")
                    consoleError.add("profile.maxUAMSMBBasalMinutes: ${profile.maxUAMSMBBasalMinutes} DynamischBasaal(profile): ${DynamischBasaal(profile)}")
                    maxBolus = round(DynamischBasaal(profile) * profile.maxUAMSMBBasalMinutes / 60, 1)
                } else {
                    consoleError.add("profile.maxSMBBasalMinutes: ${profile.maxSMBBasalMinutes} DynamischBasaal(profile): ${DynamischBasaal(profile)}")
                    maxBolus = round(DynamischBasaal(profile) * profile.maxSMBBasalMinutes / 60, 1)
                }
                // bolus 1/2 the insulinReq, up to maxBolus, rounding down to nearest bolus increment
                val roundSMBTo = 1 / profile.bolus_increment
                var microBolus = Math.floor(Math.min(smb_factor * insulinReq / 2, maxBolus) * roundSMBTo) / roundSMBTo


                // calculate a long enough zero temp to eventually correct back up to target
                val smbTarget = target_bg
                val worstCaseInsulinReq = (smbTarget - (naive_eventualBG + minIOBPredBG) / 2.0) / sens
                var durationReq = round(60 * worstCaseInsulinReq / DynamischBasaal(profile))

                // if insulinReq > 0 but not enough for a microBolus, don't set an SMB zero temp
                if (insulinReq > 0 && microBolus < profile.bolus_increment) {
                    durationReq = 0
                }

                var smbLowTempReq = 0.0
                if (durationReq <= 0) {
                    durationReq = 0
                    // don't set an SMB zero temp longer than 60 minutes
                } else if (durationReq >= 30) {
                    durationReq = round(durationReq / 30.0) * 30
                    durationReq = min(60, max(0, durationReq))
                } else {
                    // if SMB durationReq is less than 30m, set a nonzero low temp
                    smbLowTempReq = round(basal * durationReq / 30.0, 2)
                    durationReq = 30
                }
                rT.reason.append(" insulinReq $insulinReq")
                if (microBolus >= maxBolus) {
                    rT.reason.append("; maxBolus $maxBolus")
                }
                if (durationReq > 0) {
                    rT.reason.append("; setting ${durationReq}m low temp of ${smbLowTempReq}U/h")
                }
                rT.reason.append(". ")

                // seconds since last bolus
                val lastBolusAge = (systemTime - iob_data.lastBolusTime) / 1000.0
                //console.error(lastBolusAge);
                // allow SMBIntervals between 1 and 10 minutes
                val SMBInterval = min(10, max(1, profile.SMBInterval)) * 60.0   // in seconds
                //console.error(naive_eventualBG, insulinReq, worstCaseInsulinReq, durationReq);
                consoleError.add("naive_eventualBG $naive_eventualBG,${durationReq}m ${smbLowTempReq}U/h temp needed; last bolus ${round(lastBolusAge / 60.0, 1)}m ago; maxBolus: $maxBolus")
                if (lastBolusAge > SMBInterval - 6.0) {   // 6s tolerance
                    if (microBolus > 0) {
                        rT.units = microBolus
                        rT.reason.append("Microbolusing ${microBolus}U. ")
                    }
                } else {
                    val nextBolusMins = (SMBInterval - lastBolusAge) / 60.0
                    val nextBolusSeconds = (SMBInterval - lastBolusAge) % 60
                    val waitingSeconds = round(nextBolusSeconds, 0) % 60
                    val waitingMins = round(nextBolusMins - waitingSeconds / 60.0, 0)
                    rT.reason.append("Waiting ${waitingMins.withoutZeros()}m ${waitingSeconds.withoutZeros()}s to microbolus again.")
                }
                //rT.reason += ". ";

                // if no zero temp is required, don't return yet; allow later code to set a high temp
                if (durationReq > 0) {
                    rT.rate = smbLowTempReq
                    rT.duration = durationReq
                    return rT
                }

            }

            val maxSafeBasal = getMaxSafeBasal(profile)

            if (rate > maxSafeBasal) {
                rT.reason.append("adj. req. rate: ${round(rate, 2)} to maxSafeBasal: ${maxSafeBasal.withoutZeros()}, ")
                rate = round_basal(maxSafeBasal)
            }

            val insulinScheduled = currenttemp.duration * (currenttemp.rate - basal) / 60
            if (insulinScheduled >= insulinReq * 2) { // if current temp would deliver >2x more than the required insulin, lower the rate
                rT.reason.append("${currenttemp.duration}m@${(currenttemp.rate).toFixed2()} > 2 * insulinReq. Setting temp basal of ${round(rate, 2)}U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration == 0) { // no temp is set
                rT.reason.append("no temp, setting " + round(rate, 2).withoutZeros() + "U/hr. ")
                return setTempBasal(rate, 30, profile, rT, currenttemp)
            }

            if (currenttemp.duration > 5 && (round_basal(rate) <= round_basal(currenttemp.rate))) { // if required temp <~ existing temp basal
                rT.reason.append("temp ${(currenttemp.rate).toFixed2()} >~ req ${round(rate, 2).withoutZeros()}U/hr. ")
                return rT
            }

            // required temp > existing temp basal
            rT.reason.append("temp ${currenttemp.rate.toFixed2()} < ${round(rate, 2).withoutZeros()}U/hr. ")
            return setTempBasal(rate, 30, profile, rT, currenttemp)
        }
    }
}
