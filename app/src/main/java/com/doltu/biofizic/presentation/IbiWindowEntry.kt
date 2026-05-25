package com.doltu.biofizic.presentation

/** Un interval IBI (ms) cu timestamp senzor — pentru fereastră HRV pe timp. */
data class IbiWindowEntry(
    val ibiMs: Int,
    val ts: Long,
    val tsSource: String = "reconstructed",
)
