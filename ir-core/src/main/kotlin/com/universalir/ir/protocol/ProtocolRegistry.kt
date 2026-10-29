package com.universalir.ir.protocol

import com.universalir.ir.protocol.ac.AcProtocol
import com.universalir.ir.protocol.ac.CoolixProtocol
import com.universalir.ir.protocol.ac.Electra34Protocol
import com.universalir.ir.protocol.ac.ElectraAcProtocol
import com.universalir.ir.protocol.ac.GreeProtocol
import com.universalir.ir.protocol.ac.Kelon168Protocol
import com.universalir.ir.protocol.ac.TadiranProtocol
import com.universalir.ir.protocol.consumer.ConsumerProtocols

/** Every protocol the app can encode. Anything not listed here is reported as unsupported. */
object ProtocolRegistry {
    val acProtocols: List<AcProtocol> = listOf(
        Kelon168Protocol(),
        TadiranProtocol(),
        GreeProtocol(),
        ElectraAcProtocol(),
        Electra34Protocol(),
        CoolixProtocol(),
    )

    val all: List<IrProtocol> = acProtocols + ConsumerProtocols.all

    fun ac(id: String): AcProtocol? = acProtocols.firstOrNull { it.info.id == id }

    fun info(id: String): ProtocolInfo? = all.firstOrNull { it.info.id.equals(id, ignoreCase = true) }?.info
}
