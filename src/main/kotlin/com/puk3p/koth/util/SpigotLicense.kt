package com.puk3p.koth.util

import java.util.logging.Logger

/**
 * The buyer data SpigotMC injects into a premium resource's jar at download time.
 *
 * In the source these are the literal tokens `%%__USER__%%` and `%%__RESOURCE__%%`; when a buyer
 * downloads the resource from Spigot's premium system, Spigot rewrites those bytes in the jar with
 * the buyer's numeric user id and the resource id. A jar built locally (`mvn package`) or by anyone
 * who never bought it keeps the tokens unchanged.
 *
 * We never hard-disable on a missing licence: that would also stop the author's own builds, and a
 * leaked copy carries a valid buyer id anyway, so the check would pass for it regardless. What this
 * gives is a **watermark** — every start logs which buyer the copy belongs to, so a leaked jar
 * points straight back to the account that shared it — plus a clear warning for a build that never
 * came from Spigot. Obfuscation complements this; on its own the check can be edited out.
 */
object SpigotLicense {
    // Each token must appear exactly once in the whole plugin: Spigot replaces every occurrence of
    // the bytes, so a second copy kept for comparison would be rewritten too and break the check.
    private const val USER = "%%__USER__%%"
    private const val RESOURCE = "%%__RESOURCE__%%"

    // Unreplaced tokens still contain the "%%" markers (compared as a separate literal Spigot leaves
    // alone); after a real download they become plain digits.
    private fun valueOrNull(token: String): String? = token.takeUnless { it.contains("%%") }

    /** Logs the watermark for an official download, or a warning for an unofficial build. */
    fun logStatus(logger: Logger) {
        val user = valueOrNull(USER)
        if (user == null) {
            logger.warning("This copy was not downloaded from SpigotMC (development or unofficial build).")
            return
        }
        val resource = valueOrNull(RESOURCE)
        logger.info("Licensed to SpigotMC user ID $user${resource?.let { " (resource $it)" }.orEmpty()}.")
    }
}
