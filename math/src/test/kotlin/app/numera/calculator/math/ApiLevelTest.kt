package app.numera.calculator.math

import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine must not reference any `java.math.BigInteger` member newer than the app's
 * minimum SDK, and nothing else in the build can tell.
 *
 * `:math` is a plain JVM module on purpose — its tests run in milliseconds with no device —
 * but the price is that it compiles against a desktop JDK where `BigInteger.TWO` and
 * `BigInteger.sqrt()` exist, no Android lint ever sees it, and these very tests pass on a
 * JDK that has both. On a phone running Android 12 (API 31, inside the declared
 * `minSdk`) the first calculation ran `BoundedRational`'s companion initialiser, which
 * read `BigInteger.TWO` — added in API 33 — and died with a `NoSuchFieldError` wrapped in
 * an `ExceptionInInitializerError`. That is an `Error`, so no catch in the engine or the
 * view model saw it, and the process ended on the first keypress. D8 cannot backport a
 * static *field*, and `desugar_jdk_libs` does not carry either member.
 *
 * So the check has to be made here, against what was actually compiled: every class in
 * the module's output is opened, its constant pool walked, and any field or method
 * reference into `java/math/BigInteger` whose name is on the API-33 list fails the build.
 */
class ApiLevelTest {

    private companion object {
        const val BIG_INTEGER = "java/math/BigInteger"

        /** `java.math.BigInteger` members that Android added at API 33. */
        val ADDED_IN_API_33: Set<String> = setOf("TWO", "sqrt", "sqrtAndRemainder")
    }

    @Test
    fun `the compiled engine references no BigInteger member newer than API 31`() {
        val offenders = ArrayList<String>()
        var scanned = 0
        forEachCompiledClass { name, bytes ->
            scanned++
            for (member in bigIntegerMembersOf(bytes)) {
                if (member in ADDED_IN_API_33) offenders.add("$name -> BigInteger.$member")
            }
        }
        assertTrue("no compiled classes were found to scan", scanned > 0)
        assertTrue(
            "API 33 members of java.math.BigInteger referenced on a minSdk 31 app: $offenders",
            offenders.isEmpty(),
        )
    }

    /** Visits every `.class` in the location the engine's own classes were loaded from. */
    private fun forEachCompiledClass(action: (String, ByteArray) -> Unit) {
        val location = File(ConstructiveReal::class.java.protectionDomain.codeSource.location.toURI())
        if (location.isDirectory) {
            for (file in location.walkTopDown()) {
                if (file.isFile && file.name.endsWith(".class")) {
                    action(file.relativeTo(location).path, file.readBytes())
                }
            }
        } else {
            JarFile(location).use { jar ->
                for (entry in jar.entries()) {
                    if (entry.name.endsWith(".class")) {
                        val bytes = jar.getInputStream(entry).use { it.readBytes() }
                        action(entry.name, bytes)
                    }
                }
            }
        }
    }

    /**
     * Names of every field and method of `java.math.BigInteger` that [classBytes]
     * references, read from the class file's constant pool.
     *
     * A byte search for the name would not do: this module declares its own `TWO` and
     * `sqrt`, so their names appear in the pool of the classes that define them, and only
     * a Fieldref or Methodref whose owner is `java/math/BigInteger` is a call into the
     * platform.
     */
    private fun bigIntegerMembersOf(classBytes: ByteArray): List<String> {
        val input = DataInputStream(classBytes.inputStream())
        val magic = input.readInt()
        assertTrue("not a class file: magic 0x${Integer.toHexString(magic)}", magic == 0xCAFEBABE.toInt())
        input.readUnsignedShort() // minor version
        input.readUnsignedShort() // major version
        val count = input.readUnsignedShort()

        val utf8 = HashMap<Int, String>()
        val classNameIndex = HashMap<Int, Int>()
        val nameAndTypeNameIndex = HashMap<Int, Int>()
        val memberRefs = ArrayList<Pair<Int, Int>>()
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte()
            when (tag) {
                1 -> utf8[index] = input.readUTF()
                3, 4 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    // Long and Double occupy two constant pool slots.
                    index++
                }
                7 -> classNameIndex[index] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipBytes(2)
                9, 10, 11 -> {
                    val ownerIndex = input.readUnsignedShort()
                    val nameAndTypeIndex = input.readUnsignedShort()
                    memberRefs.add(ownerIndex to nameAndTypeIndex)
                }
                12 -> {
                    nameAndTypeNameIndex[index] = input.readUnsignedShort()
                    input.skipBytes(2) // descriptor
                }
                15 -> input.skipBytes(3)
                17, 18 -> input.skipBytes(4)
                else -> throw AssertionError("unknown constant pool tag $tag at index $index")
            }
            index++
        }

        val members = ArrayList<String>()
        for ((ownerIndex, nameAndTypeIndex) in memberRefs) {
            val owner: String? = classNameIndex[ownerIndex]?.let { utf8[it] }
            val name: String? = nameAndTypeNameIndex[nameAndTypeIndex]?.let { utf8[it] }
            if (owner == BIG_INTEGER && name != null) members.add(name)
        }
        return members
    }
}
