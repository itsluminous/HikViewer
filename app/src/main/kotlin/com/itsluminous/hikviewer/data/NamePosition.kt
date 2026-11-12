package com.itsluminous.hikviewer.data

/**
 * Where a tile draws its camera name (Settings: Camera name position). The top-right corner is
 * not offered: the live dot and the frame-rate badge live there.
 *
 * The names are stored in preferences and written into backup files, so renaming one breaks
 * what users already have.
 */
enum class NamePosition {
    TOP_LEFT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    /** Position constants. */
    companion object {
        /** Today's placement, and the one every build before this setting used. */
        val DEFAULT: NamePosition = BOTTOM_LEFT

        /** The position named [name], or [DEFAULT] for a name this build does not know (an older or newer file). */
        fun fromName(name: String?): NamePosition = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
