package com.puk3p.koth.tournament

import java.util.UUID

data class Participant(
    val uuid: UUID,
    var isEliminated: Boolean = false
)