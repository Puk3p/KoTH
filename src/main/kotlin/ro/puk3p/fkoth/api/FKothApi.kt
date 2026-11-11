package ro.puk3p.fkoth.api

// Public contract carried over unchanged from the FKothWins addon (package, names and signatures),
// so plugins compiled against it (e.g. DinoFtop) keep working. Registered in Bukkit's ServicesManager
// by com.puk3p.koth.factionwins.FactionWinsModule. Don't change these declarations.

interface FKothApi {
    fun getWinsForFaction(faction: String): Int

    fun getWinsForPlayerFaction(playerName: String): Int

    fun getFactionForPlayer(playerName: String): String?

    fun addWinsForFaction(
        faction: String,
        amount: Int,
    ): Int

    fun setWinsForFaction(
        faction: String,
        amount: Int,
    ): Int

    fun removeWinsForFaction(
        faction: String,
        amount: Int,
    ): Int

    fun top(limit: Int): List<FactionWinsSnapshot>
}

data class FactionWinsSnapshot(
    val faction: String,
    val wins: Int,
)
