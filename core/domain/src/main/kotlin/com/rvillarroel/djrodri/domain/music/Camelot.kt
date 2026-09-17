package com.rvillarroel.djrodri.domain.music

/** A key on the Camelot wheel, the notation DJs use to know which songs sound good back to back. */
data class CamelotKey(val number: Int, val isMinor: Boolean) {
    override fun toString() = "$number${if (isMinor) "A" else "B"}"

    /** Same slot, one step around the wheel, or the relative major/minor. */
    fun isCompatibleWith(other: CamelotKey): Boolean {
        if (number == other.number) return true
        if (isMinor != other.isMinor) return false
        val distance = Math.floorMod(number - other.number, 12)
        return distance == 1 || distance == 11
    }

    companion object {
        /**
         * [pitchClass] 0 = C … 11 = B and [mode] 1 = major, 0 = minor, the encoding ReccoBeats and AcousticBrainz use.
         * Returns null when the source has no key (-1).
         */
        fun from(pitchClass: Int, mode: Int): CamelotKey? {
            if (pitchClass !in 0..11 || mode !in 0..1) return null
            val isMinor = mode == 0
            // A minor key sits on the same number as its relative major, three semitones up.
            val majorPitch = if (isMinor) (pitchClass + 3) % 12 else pitchClass
            val number = (majorPitch * 7 + 7) % 12 + 1
            return CamelotKey(number, isMinor)
        }
    }
}
