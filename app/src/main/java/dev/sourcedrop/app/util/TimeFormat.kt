package dev.sourcedrop.app.util

import java.text.DateFormat
import java.util.Date

/** A time of day in the user's format, e.g. "10:42" or "10:42 AM". */
fun formatTime(epochMillis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMillis))
