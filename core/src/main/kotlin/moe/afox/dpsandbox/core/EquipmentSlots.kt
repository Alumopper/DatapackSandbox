package moe.afox.dpsandbox.core

internal object EquipmentSlots {
    const val MAINHAND = "weapon.mainhand"
    const val OFFHAND = "weapon.offhand"
    const val FEET = "armor.feet"
    const val LEGS = "armor.legs"
    const val CHEST = "armor.chest"
    const val HEAD = "armor.head"

    val all: Set<String> = setOf(MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD)

    fun canonical(raw: String): String? =
        when (raw) {
            MAINHAND, "hotbar.selected" -> MAINHAND
            OFFHAND -> OFFHAND
            FEET -> FEET
            LEGS -> LEGS
            CHEST -> CHEST
            HEAD -> HEAD
            else -> null
        }

    fun handSlot(index: Int): String? =
        when (index) {
            0 -> MAINHAND
            1 -> OFFHAND
            else -> null
        }

    fun armorSlot(index: Int): String? =
        when (index) {
            0 -> FEET
            1 -> LEGS
            2 -> CHEST
            3 -> HEAD
            else -> null
        }
}
