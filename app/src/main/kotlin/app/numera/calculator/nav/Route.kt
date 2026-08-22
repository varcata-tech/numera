package app.numera.calculator.nav

/**
 * One of the app's seven top-level destinations.
 *
 * There is no back stack: every mode returns to [Calculator] and nothing else, so a route
 * is a single value rather than a list.
 *
 * @property id the stable token used in the launcher-shortcut intent extra. It is written
 *   into `res/xml/shortcuts.xml` and pinned onto users' home screens, so renaming one
 *   breaks an already-placed shortcut — treat these as persisted data, not as labels.
 */
sealed interface Route {
    val id: String

    /** The main calculator. The cold-start destination. */
    data object Calculator : Route {
        override val id: String = "calculator"
    }

    /** Unit conversion. */
    data object Converter : Route {
        override val id: String = "converter"
    }

    /** Programmer mode: bases, bitwise operators, word size. */
    data object Programmer : Route {
        override val id: String = "programmer"
    }

    /** Function plotting. */
    data object Graphing : Route {
        override val id: String = "graphing"
    }

    /** Date arithmetic. */
    data object DateTime : Route {
        override val id: String = "datetime"
    }

    /** Loan, interest and savings calculators. */
    data object Financial : Route {
        override val id: String = "financial"
    }

    /** Appearance, calculation defaults and licences. */
    data object Settings : Route {
        override val id: String = "settings"
    }

    companion object {
        /** Every route, in the order the mode switcher should offer them. */
        val all: List<Route> = listOf(
            Calculator, Converter, Programmer, Graphing, DateTime, Financial, Settings,
        )

        /**
         * Resolves a route from a shortcut extra.
         *
         * @param id a token previously produced by [Route.id], or null.
         * @return the matching route, or null when [id] is absent or unrecognised — which
         *   happens whenever a shortcut pinned by an older version names a mode this build
         *   no longer has. Callers fall back to [Calculator] rather than crashing.
         */
        fun fromId(id: String?): Route? = all.firstOrNull { it.id == id }
    }
}
