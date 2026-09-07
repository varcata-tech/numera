package app.numera.calculator.data

import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.ExprCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One saved calculation.
 *
 * @property expression the calculation itself, decoded from its stored token stream.
 * @property formula how it looked on screen, e.g. `1÷3`. Stored rather than re-derived so a
 *   long list scrolls without decoding every row.
 * @property result the answer as it was rendered, cached purely so scrolling the list does
 *   not re-evaluate an arbitrarily expensive calculation on the frame thread. The
 *   [expression] remains the source of truth: tapping a row inserts *it*, not this string.
 * @property angleMode the unit the trigonometry in [expression] was evaluated in. Part of
 *   the row, not context: `sin(30` is 0.5 in degrees and −0.988… in radians, so a row
 *   without its unit shows an answer the app would no longer produce for that formula.
 * @property timestamp epoch millis, used only for ordering and grouping.
 */
data class HistoryEntry(
    val id: Long,
    val expression: CalculatorExpr,
    val formula: String,
    val result: String,
    val angleMode: AngleMode,
    val timestamp: Long,
)

/**
 * Persistent calculation history.
 *
 * The design rests on one decision: **the expression is what the row *is*; the result is only
 * a picture of it.** Writing down `0.333333…` as the row's identity would throw away the
 * exactness the whole engine exists to provide, and tapping that row later would seed a new
 * calculation with a rounded number. The token stream is stored instead — a few dozen bytes,
 * survives a reboot, and re-evaluates exactly on demand, which is why tapping `1÷3` and
 * multiplying by three still gives exactly one.
 *
 * The rendered answer is cached beside it purely so that scrolling a long list does not
 * re-evaluate an arbitrarily expensive calculation once per visible row. It is never the
 * thing that gets inserted.
 *
 * It lives in its **own database file**, deliberately not in `calculator_settings`. The
 * backup rules are include-only and name that preferences file alone, so history is excluded
 * from cloud backup by omission. Storing it alongside settings would silently start uploading
 * users' calculations to Google and contradict the app's own privacy policy.
 *
 * Framework [SQLiteOpenHelper] rather than Room: one table does not justify an annotation
 * processor, and the app ships no third-party dependencies.
 *
 * Every method that touches the database is `suspend` and dispatches to [Dispatchers.IO]
 * itself. Leaving that to callers is how a disk read ends up on the frame thread: the
 * drawer's first load and the clear-history confirmation both run from ordinary UI
 * coroutines, which default to the main dispatcher.
 */
class HistoryStore(context: Context) {

    private val helper = Helper(context.applicationContext)

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /**
     * Serialises everything that reads [_entries] and writes it back.
     *
     * The drawer's first load and the calculator's inserts arrive from different coroutines,
     * and every one of these operations is a read-modify-write of the same list. Interleaved,
     * the loser's rows simply disappear from the drawer until the next refresh.
     */
    private val lock = Mutex()

    /** False until the list has been read from disk once; see [insert]'s duplicate test. */
    private var loaded: Boolean = false

    /** Newest first. Empty until [refresh] has run at least once. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    /**
     * Records a completed calculation.
     *
     * Consecutive duplicates are dropped: pressing equals twice, or rotating the device,
     * should not fill the list with the same row.
     *
     * @param angleMode the unit the calculation was evaluated in, stored with the row. See
     *   [HistoryEntry.angleMode] for why it is part of the row rather than of the app's
     *   current state.
     */
    suspend fun insert(expression: CalculatorExpr, result: String, angleMode: AngleMode) {
        if (expression.isEmpty()) return
        val formula = expression.display()

        withContext(Dispatchers.IO) {
            lock.withLock { insertLocked(expression, formula, result, angleMode) }
        }
    }

    /**
     * The body of [insert], holding the lock and off the main thread.
     *
     * A function of its own so that the early exits are plain `return`s: as a lambda they
     * were `return@withLock`, which stops compiling the moment the block's last expression is
     * something other than `Unit`.
     */
    private fun insertLocked(
        expression: CalculatorExpr,
        formula: String,
        result: String,
        angleMode: AngleMode,
    ) {
        try {
            // The list has to be on hand before the duplicate test means anything. On a cold
            // start the drawer's first refresh may not have landed yet, and an empty list
            // would let an exact duplicate of the newest stored row through.
            loadIfNeeded()
            if (isConsecutiveDuplicate(_entries.value.firstOrNull(), formula, angleMode)) return

            val timestamp = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(COLUMN_EXPR, ExprCodec.encode(expression))
                put(COLUMN_FORMULA, formula)
                put(COLUMN_RESULT, result)
                // By name, never by ordinal: reordering the enum later must not silently
                // re-label every stored row.
                put(COLUMN_ANGLE, angleMode.name)
                put(COLUMN_TIMESTAMP, timestamp)
            }
            val id = helper.writableDatabase.insert(TABLE, null, values)
            if (id == -1L) {
                // Silent until this said so. A schema the insert no longer matches fails
                // here on every equals, and the only symptom is a drawer that has quietly
                // stopped recording.
                Log.w(TAG, "History row rejected by the database; not recorded.")
                return
            }

            // A full list means the table was already at the cap, so this row is the one that
            // pushed it over. Running the delete on every equals instead costs a full-table
            // scan for a calculation the user is waiting on.
            if (_entries.value.size >= MAX_ENTRIES) trim()

            // Prepended rather than re-queried: re-reading five hundred rows and decoding
            // every blob to learn one new row is work the frame after equals cannot afford.
            val entry = HistoryEntry(id, expression, formula, result, angleMode, timestamp)
            _entries.value = prependCapped(entry, _entries.value, MAX_ENTRIES)
        } catch (e: SQLException) {
            // A full disk or a corrupt database file must not end the process. This is
            // reached from an unsupervised `viewModelScope.launch`, where an escaping
            // exception goes to the thread's default handler; losing a history row is
            // survivable, losing the answer the user is looking at is not.
            Log.w(TAG, "History insert failed", e)
        }
    }

    /** Reloads [entries] from disk, leaving the list alone if the database cannot be read. */
    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    _entries.value = query()
                    loaded = true
                } catch (e: SQLException) {
                    // Reached from a LaunchedEffect on the calculator screen, so a corrupt
                    // database would otherwise be a crash loop at launch that no user action
                    // clears. `loaded` stays false, so a later insert tries the read again.
                    Log.w(TAG, "History could not be read", e)
                }
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    helper.writableDatabase.delete(TABLE, null, null)
                    _entries.value = emptyList()
                    loaded = true
                } catch (e: SQLException) {
                    Log.w(TAG, "History could not be cleared", e)
                }
            }
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        _entries.value = query()
        loaded = true
    }

    private fun query(): List<HistoryEntry> {
        val result = ArrayList<HistoryEntry>()
        helper.readableDatabase.query(
            TABLE,
            arrayOf(
                COLUMN_ID, COLUMN_EXPR, COLUMN_FORMULA, COLUMN_RESULT,
                COLUMN_ANGLE, COLUMN_TIMESTAMP,
            ),
            null, null, null, null,
            "$COLUMN_ID DESC",
            MAX_ENTRIES.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val blob = cursor.getBlob(1) ?: continue
                // A row whose blob no longer decodes is skipped rather than crashing the
                // list. That happens if the format version moves on, and losing one row is
                // a far better outcome than a startup crash the user cannot get past.
                val expression = ExprCodec.decode(blob) ?: continue
                result += HistoryEntry(
                    id = cursor.getLong(0),
                    expression = expression,
                    formula = cursor.getString(2) ?: expression.display(),
                    result = cursor.getString(3).orEmpty(),
                    angleMode = angleModeNamed(cursor.getString(4)),
                    timestamp = cursor.getLong(5),
                )
            }
        }
        return result
    }

    /** Keeps the table bounded, so a heavy user's history cannot grow without limit. */
    private fun trim() {
        helper.writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE $COLUMN_ID NOT IN " +
                "(SELECT $COLUMN_ID FROM $TABLE ORDER BY $COLUMN_ID DESC LIMIT $MAX_ENTRIES)",
        )
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COLUMN_EXPR BLOB NOT NULL, " +
                    "$COLUMN_FORMULA TEXT NOT NULL, " +
                    "$COLUMN_RESULT TEXT NOT NULL, " +
                    "$COLUMN_ANGLE TEXT NOT NULL DEFAULT '$DEFAULT_ANGLE', " +
                    "$COLUMN_TIMESTAMP INTEGER NOT NULL)",
            )
        }

        /**
         * Migrates, never drops.
         *
         * An empty body here is not neutral once a column is added: the old table survives,
         * every insert then fails against a `ContentValues` naming a column that is not
         * there, and history stops recording with nothing on screen to say so. The added
         * column carries a default precisely so the existing rows stay readable — rows
         * written before version 2 have no recorded unit, and degrees is both the app's
         * default and the unit those rows were overwhelmingly computed in.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    "ALTER TABLE $TABLE ADD COLUMN $COLUMN_ANGLE " +
                        "TEXT NOT NULL DEFAULT '$DEFAULT_ANGLE'",
                )
            }
        }

        /**
         * Recreates the table on downgrade.
         *
         * The default implementation throws, which would make the app uninstallable-but-for-
         * data on any downgrade. History is regenerable by definition, so dropping it is the
         * proportionate response.
         */
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private companion object {
        const val TAG = "HistoryStore"
        const val DATABASE_NAME = "numera_history.db"

        /** 2 added [COLUMN_ANGLE]; see the helper's `onUpgrade`. */
        const val DATABASE_VERSION = 2
        const val TABLE = "history"
        const val COLUMN_ID = "_id"
        const val COLUMN_EXPR = "expr"
        const val COLUMN_FORMULA = "formula"
        const val COLUMN_RESULT = "result"
        const val COLUMN_ANGLE = "angle"
        const val COLUMN_TIMESTAMP = "ts"

        /**
         * What a row with no recorded unit is read as.
         *
         * Written into the schema as a literal rather than interpolated from
         * `AngleMode.DEGREES.name`, because a `CREATE TABLE` already on disk cannot change
         * when the constant is renamed — the value has to stay stable independently of the
         * enum, and [angleModeNamed] is what reconciles the two.
         */
        const val DEFAULT_ANGLE = "DEGREES"

        /** Bounded so the table cannot grow without limit on a heavily used device. */
        const val MAX_ENTRIES = 500
    }
}

/**
 * Reads a stored angle unit back by name.
 *
 * By name and with a fallback, never `valueOf`: a renamed or removed constant would throw,
 * and a history row is not worth crashing the drawer over.
 */
internal fun angleModeNamed(stored: String?): AngleMode =
    enumValues<AngleMode>().firstOrNull { it.name == stored } ?: AngleMode.DEGREES

/**
 * The newest-first list with [entry] at its head, cut to [max].
 *
 * Pure so the capping can be tested without a device: this is the path a heavy user's five
 * hundredth calculation takes, and getting the cut wrong either grows the in-memory list for
 * ever or drops rows the database still holds.
 */
internal fun prependCapped(
    entry: HistoryEntry,
    existing: List<HistoryEntry>,
    max: Int,
): List<HistoryEntry> {
    val updated = ArrayList<HistoryEntry>(minOf(existing.size + 1, max))
    updated += entry
    for (row in existing) {
        if (updated.size == max) break
        updated += row
    }
    return updated
}

/**
 * Whether a calculation just repeats the newest row.
 *
 * The angle unit is part of the comparison, not only the formula. `sin(30` has two values,
 * and the natural way to see both is to press equals, switch the angle unit and press equals
 * again — at which point matching on the formula alone drops the second calculation and
 * leaves the drawer showing degrees' answer beside a formula the app would now evaluate in
 * radians. Rotating the device or pressing equals twice still matches on both fields and is
 * still dropped, which is what this test is for.
 *
 * The comparison is deliberately made against the *question* rather than against the rendered
 * answer. The answer string is localised — digits, grouping and all — so an Arabic-locale `٢`
 * and an English `2` are different strings for the same calculation, and matching on them
 * wrote the same sum twice, in two numbering systems, after a language change. Formula and
 * unit together determine the value exactly, so nothing is lost by not looking at it.
 */
internal fun isConsecutiveDuplicate(
    newest: HistoryEntry?,
    formula: String,
    angleMode: AngleMode,
): Boolean = newest != null && newest.formula == formula && newest.angleMode == angleMode
