package com.rvillarroel.djrodri.party

import com.rvillarroel.djrodri.domain.dj.DjMode

fun DjMode.label(): String = when (this) {
    DjMode.NATURAL -> "Natural"
    DjMode.FIESTA -> "Fiesta"
    DjMode.HORA_LOCA -> "Hora loca"
}

fun DjMode.description(): String = when (this) {
    DjMode.NATURAL -> "Casi todas completas, cortes muy de vez en cuando"
    DjMode.FIESTA -> "Cortes en buenos momentos y alguna entrada al drop"
    DjMode.HORA_LOCA -> "Cambios seguidos y muchas entradas al drop"
}
