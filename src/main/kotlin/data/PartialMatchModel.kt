package data

import org.bukkit.Material

data class PartialMatchModel(val input: String, val exact: Material?, val fuzzy: Collection<Material>)
