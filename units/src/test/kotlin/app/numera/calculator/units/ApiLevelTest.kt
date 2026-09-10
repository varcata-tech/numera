package app.numera.calculator.units

import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit catalogue must not reference any `java.math.BigInteger` member newer than the
 * app's minimum SDK, and nothing else in the build can tell.
 *
 * A copy of the engine's test of the same name, and deliberately so: `:units` is its own
 * plain JVM module with no way to share a test class with `:math` short of test fixtures,
 * and the reason the check exists is that a module nobody's lint sees is exactly where the
 * reference survives. It did here. The engine's `BigInteger.TWO` was found and fixed while
 * `UnitCatalog`'s binary-storage ladder still read the same API-33 field, so on Android 12
 * the calculator opened fine and the converter died on its first open with a
 * `NoSuchFieldError` from the catalogue's initialiser. Every class in this module's output is
 * opened, its constant pool walked, and any reference into `java/math/BigInteger` whose name
 * is on the API-33 list fails the build.
 */
class ApiLevelTest {

    private companion object {
        const val BIG_INTEGER = "java/math/BigInteger"

        /** `java.math.BigInteger` members that Android added at API 33. */
        val ADDED_IN_API_33: Set<String> = setOf("TWO", "sqrt", "sqrtAndRemainder")
    }

    @Test
    fun `the compiled catalogue references no BigInteger member newer than API 31`() {
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

    /** Visits every `.class` in the location the catalogue's own classes were loaded from. */
    private fun forEachCompiledClass(action: (String, ByteArray) -> Unit) {
        val location = File(UnitCatalog::class.java.protectionDomain.codeSource.location.toURI())
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
