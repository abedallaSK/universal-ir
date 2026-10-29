package com.universalir.data.catalog

import com.universalir.ir.protocol.DeviceCategory
import com.universalir.ir.protocol.VerificationLevel
import com.universalir.ir.protocol.ac.CoolixProtocol
import com.universalir.ir.protocol.ac.Electra34Protocol
import com.universalir.ir.protocol.ac.ElectraAcProtocol
import com.universalir.ir.protocol.ac.GreeProtocol
import com.universalir.ir.protocol.ac.Kelon168Protocol
import com.universalir.ir.protocol.ac.TadiranProtocol

/** Localised brand names. Identifiers stay stable; aliases only widen search. */
object KnownBrands {
    data class Alias(val text: String, val language: String)

    val aliases: Map<String, List<Alias>> = mapOf(
        "Electra" to listOf(Alias("אלקטרה", "he"), Alias("إلكترا", "ar"), Alias("الكترا", "ar")),
        "Tadiran" to listOf(Alias("תדיראן", "he"), Alias("تاديران", "ar")),
        "Fujicom" to listOf(Alias("פוג'יקום", "he"), Alias("פוג׳יקום", "he"), Alias("فوجيكوم", "ar")),
        "Tornado" to listOf(Alias("טורנדו", "he"), Alias("تورنادو", "ar")),
        "Kelon" to listOf(Alias("科龙", "zh"), Alias("קלון", "he"), Alias("كيلون", "ar")),
        "Gree" to listOf(Alias("格力", "zh"), Alias("גרי", "he"), Alias("جري", "ar"), Alias("غري", "ar")),
        "Tosot" to listOf(Alias("大松", "zh")),
        "Midea" to listOf(Alias("美的", "zh"), Alias("מידאה", "he"), Alias("ميديا", "ar")),
        "Haier" to listOf(Alias("海尔", "zh"), Alias("הייר", "he"), Alias("هاير", "ar")),
        "Hisense" to listOf(Alias("海信", "zh"), Alias("הייסנס", "he"), Alias("هايسنس", "ar")),
        "AUX" to listOf(Alias("奥克斯", "zh"), Alias("אוקס", "he"), Alias("أوكس", "ar")),
        "TCL" to listOf(Alias("تي سي ال", "ar")),
        "Chigo" to listOf(Alias("志高", "zh"), Alias("צ'יגו", "he"), Alias("تشيجو", "ar")),
        "Samsung" to listOf(Alias("三星", "zh"), Alias("סמסונג", "he"), Alias("سامسونج", "ar")),
        "LG" to listOf(Alias("אל ג'י", "he"), Alias("إل جي", "ar")),
        "Sony" to listOf(Alias("索尼", "zh"), Alias("סוני", "he"), Alias("سوني", "ar")),
        "Daikin" to listOf(Alias("大金", "zh"), Alias("דייקין", "he"), Alias("دايكن", "ar")),
        "Mitsubishi Electric" to listOf(Alias("三菱电机", "zh"), Alias("מיצובישי", "he"), Alias("ميتسوبيشي", "ar")),
        "Xiaomi" to listOf(Alias("小米", "zh"), Alias("שיאומי", "he"), Alias("شاومي", "ar")),
        "Yamaha" to listOf(Alias("雅马哈", "zh"), Alias("ימאהה", "he"), Alias("ياماها", "ar")),
        "Toshiba" to listOf(Alias("东芝", "zh"), Alias("טושיבה", "he"), Alias("توشيبا", "ar")),
        "Pioneer" to listOf(Alias("先锋", "zh"), Alias("פיוניר", "he"), Alias("بايونير", "ar")),
        "Hisense Kelon" to listOf(Alias("海信科龙", "zh")),
        "Beko" to listOf(Alias("בקו", "he"), Alias("بيكو", "ar")),
        "Electrolux" to listOf(Alias("伊莱克斯", "zh"), Alias("אלקטרולוקס", "he"), Alias("إلكترولوكس", "ar")),
    )

    val popular = setOf("Electra", "Tadiran", "Tornado", "Gree", "Midea", "Samsung", "LG", "Sony", "Haier", "Hisense")

    /**
     * Brand ↔ protocol links. Each entry exists because a named source says so; [level] is the
     * strongest evidence that applies to *this brand*, never just to the protocol in general.
     */
    data class ProtocolLink(
        val brand: String,
        val protocolId: String,
        val modelLabel: String,
        val remoteModels: String?,
        val level: VerificationLevel,
        val evidence: String,
        val sourceRepository: String,
        val sourceUrl: String,
        val license: String,
    )

    private const val ESP = "IRremoteESP8266"
    private const val ESP_URL = "https://github.com/crankyoldgit/IRremoteESP8266/blob/master/SupportedProtocols.md"
    private const val SMARTIR_URL = "https://github.com/zephyrx47/SmartIR"

    val protocolLinks: List<ProtocolLink> = listOf(
        ProtocolLink("Electra", ElectraAcProtocol.ID, "Electra Classic / Electra Smart (0xC3 remotes)", "YKR-M/003E",
            VerificationLevel.WAVEFORM_VALIDATED,
            "IRremoteESP8266 lists Electra Classic INV 17 / AXW12DCS (YKR-M/003E). SmartIR 1942 'Electra Classic' captures decode as this protocol with valid checksums; Flipper 'Electra Smart' frames start with 0xC3.",
            ESP, ESP_URL, "LGPL-2.1"),
        ProtocolLink("Electra", Electra34Protocol.ID, "Electra 34-bit Manchester remotes (split and central)", null,
            VerificationLevel.WAVEFORM_VALIDATED,
            "Electra-AC-Remote (split unit) and electra-central-ac-ir-codes (ducted unit, captures verified on hardware by its author) agree; SmartIR 1940 'Electra' captures use the same 3000 us Manchester frame.",
            "electra-central-ac-ir-codes", "https://github.com/danielrosehill/electra-central-ac-ir-codes", "MIT"),
        ProtocolLink("Electra", CoolixProtocol.ID, "Electra Platinum Plus Inverter", null,
            VerificationLevel.WAVEFORM_VALIDATED,
            "SmartIR 1944 'Electra Platinum Plus Inverter' captures equal the Coolix encoder output for every correctly labelled state.",
            "SmartIR", SMARTIR_URL, "MIT"),
        ProtocolLink("Electra", Kelon168Protocol.ID, "Kelon-made Electra units (KELON168 remotes)", null,
            VerificationLevel.WAVEFORM_VALIDATED,
            "kelon168-ir-homeassistant reverse-engineered a Kelon OEM unit sold as Electra/Tadiran; encoder reproduces its captured byte vectors.",
            "kelon168-ir-homeassistant", "https://github.com/Aikidyst/kelon168-ir-homeassistant", "MIT"),
        ProtocolLink("Tadiran", TadiranProtocol.ID, "Tadiran 64-bit remotes (incl. TAC490, WIND 3P)", "TAC490",
            VerificationLevel.WAVEFORM_VALIDATED,
            "IRTadiran encoder; checksum and bytes match SmartIR 1340, 1341 (TAC490) and 1560 (WIND 3P) captures.",
            "IRTadiran", "https://github.com/arikfe/IRTadiran", "MIT"),
        ProtocolLink("Tadiran", GreeProtocol.ID, "Tadiran 10i / 15i / inv220a / Alpha ('Sakal' remotes)", null,
            VerificationLevel.WAVEFORM_VALIDATED,
            "IRTadiranSakal is the Gree frame; SmartIR 1342 (10i/15i/inv220a) captures are reproduced byte-exactly, 1343 (Alpha) uses the same frame.",
            "IRTadiran", "https://github.com/arikfe/IRTadiran", "MIT"),
        ProtocolLink("Tadiran", Kelon168Protocol.ID, "Kelon-made Tadiran units (KELON168 remotes)", null,
            VerificationLevel.WAVEFORM_VALIDATED,
            "kelon168-ir-homeassistant: Kelon OEM unit sold as Electra/Tadiran.",
            "kelon168-ir-homeassistant", "https://github.com/Aikidyst/kelon168-ir-homeassistant", "MIT"),
        ProtocolLink("Tadiran", Electra34Protocol.ID, "Electra/Tadiran central (ducted) units", null,
            VerificationLevel.WAVEFORM_VALIDATED,
            "electra-central-ac-ir-codes describes an Electra/Tadiran-family ducted unit; one unit only.",
            "electra-central-ac-ir-codes", "https://github.com/danielrosehill/electra-central-ac-ir-codes", "MIT"),
        ProtocolLink("Tornado", CoolixProtocol.ID, "Tornado Super Inverter A / i, Super Design, Plasma, Gold i", null,
            VerificationLevel.WAVEFORM_VALIDATED,
            "SmartIR 1620 Tornado captures equal Coolix encoder output (its 'low' entries hold the AUTO fan code).",
            "SmartIR", SMARTIR_URL, "MIT"),
        ProtocolLink("Tornado", Kelon168Protocol.ID, "Tornado Super Legend 40 (KELON168 framing)", null,
            VerificationLevel.ENCODER_IMPLEMENTED,
            "SmartIR 1621 frames have KELON168 framing and checksums, but bytes 6/7 differ from the reverse-engineered tables; prefer the captured SmartIR set for this model.",
            "SmartIR", SMARTIR_URL, "MIT"),
        ProtocolLink("Kelon", Kelon168Protocol.ID, "Kelon DG11R2-01 / RCH-R0Y3 remotes", "DG11R2-01",
            VerificationLevel.WAVEFORM_VALIDATED, "IRremoteESP8266 lists Kelon DG11R2-01 and RCH-R0Y3 (KELON168).", ESP, ESP_URL, "LGPL-2.1"),
        ProtocolLink("Hisense", Kelon168Protocol.ID, "Hisense AST-09UW4RVETG00A", "DGR11R2",
            VerificationLevel.ENCODER_IMPLEMENTED,
            "IRremoteESP8266 lists Hisense AST-09UW4RVETG00A as KELON168; SmartIR 1521/1522 captures fingerprint as KELON168 framing.",
            ESP, ESP_URL, "LGPL-2.1"),
        ProtocolLink("Gree", GreeProtocol.ID, "Gree YAW1F / YBOFB / YB1F2 remotes", "YAW1F",
            VerificationLevel.ENCODER_IMPLEMENTED, "IRremoteESP8266 lists Gree VIR09HP115V1AH and YAA1FBF/YAN1F1/YB1F2F remotes.", ESP, ESP_URL, "LGPL-2.1"),
        ProtocolLink("Tosot", GreeProtocol.ID, "Tosot (Gree-made)", null,
            VerificationLevel.SOURCE_DISCOVERED, "SmartIR 1200 Tosot T09H-SJ captures fingerprint as the Gree frame.", "SmartIR", SMARTIR_URL, "MIT"),
        ProtocolLink("Midea", CoolixProtocol.ID, "Midea RG52D/BGE remotes", "RG52D/BGE",
            VerificationLevel.ENCODER_IMPLEMENTED,
            "IRremoteESP8266 lists Midea MS12FU-10HRDN1 / MSABAU-07HRFN1 (RG52D/BGE); SmartIR 1380–1385 fingerprint as Coolix.",
            ESP, ESP_URL, "LGPL-2.1"),
        ProtocolLink("Beko", CoolixProtocol.ID, "Beko BINR 070/071 (RG57K7(B)/BGEF)", "RG57K7(B)/BGEF",
            VerificationLevel.ENCODER_IMPLEMENTED, "IRremoteESP8266 lists Beko BINR 070/071.", ESP, ESP_URL, "LGPL-2.1"),
        ProtocolLink("AUX", ElectraAcProtocol.ID, "AUX KFR-35GW/BpNFW=3 (YKR-T/011)", "YKR-T/011",
            VerificationLevel.ENCODER_IMPLEMENTED, "IRremoteESP8266 lists AUX KFR-35GW/BpNFW=3; SmartIR 1960 fingerprints as the 0xC3 frame.", ESP, ESP_URL, "LGPL-2.1"),
        ProtocolLink("Electrolux", ElectraAcProtocol.ID, "Electrolux YKR-H/531E", "YKR-H/531E",
            VerificationLevel.ENCODER_IMPLEMENTED, "IRremoteESP8266 lists Electrolux YKR-H/531E.", ESP, ESP_URL, "LGPL-2.1"),
    )

    fun categoriesFor(brand: String): Set<DeviceCategory> =
        if (protocolLinks.any { it.brand == brand }) setOf(DeviceCategory.AIR_CONDITIONER) else emptySet()
}
