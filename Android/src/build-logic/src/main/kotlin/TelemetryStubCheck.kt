/*
 * Copyright 2026 The PhoneAgent Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Verifies that the no-op `:telemetry-stub` module covers everything other libraries (MediaPipe)
 * reference in the Firebase datatransport and Firebase packages.
 *
 * Bytecode references are resolved by the VM only when they run, so a missing class or method
 * would crash a tile at runtime instead of failing the build. This check moves that failure to
 * build time: every class and member referenced from outside the watched packages must be defined
 * on the runtime classpath.
 */
internal class TelemetryStubCheck(
  /** Class jars on the runtime classpath, paired with "project" or "module" origin labels. */
  private val jars: List<Pair<File, String>>,
) {
  data class Result(val referenceCount: Int, val consumers: Set<String>, val problems: List<String>)

  private class ClassDef(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val members: MutableSet<String> = mutableSetOf(),
    val origin: String,
  )

  private data class MemberRef(val owner: String, val name: String, val desc: String, val from: String)

  fun run(): Result {
    val defs = mutableMapOf<String, ClassDef>()
    val classRefs = mutableMapOf<String, String>() // referenced class -> first referencing class
    val memberRefs = mutableListOf<MemberRef>()
    val consumers = sortedSetOf<String>()

    for ((jar, origin) in jars) {
      if (!jar.isFile || !jar.name.endsWith(".jar")) continue
      ZipFile(jar).use { zip ->
        for (entry in zip.entries()) {
          if (entry.isDirectory || !entry.name.endsWith(".class")) continue
          val bytes = zip.getInputStream(entry).use { it.readBytes() }
          val internalName = entry.name.removeSuffix(".class")
          if (isWatched(internalName)) {
            readDefinition(bytes, origin)?.let { defs[it.name] = it }
          } else if (mentionsWatchedPackage(bytes)) {
            consumers += internalName.substringBeforeLast('/').replace('/', '.')
            readReferences(bytes, internalName, classRefs, memberRefs)
          }
        }
      }
    }

    val problems = mutableListOf<String>()
    for ((ref, from) in classRefs.toSortedMap()) {
      if (ref !in defs) problems += "class $ref is missing (referenced by $from)"
    }
    for (ref in memberRefs.distinctBy { Triple(it.owner, it.name, it.desc) }) {
      if (ref.owner !in defs) continue // already reported as a missing class
      if (!resolves(ref.owner, "${ref.name}${ref.desc}", defs)) {
        problems += "member ${ref.owner}.${ref.name}${ref.desc} is missing (referenced by ${ref.from})"
      }
    }
    // The real uploader must not come back through some other dependency.
    for (def in defs.values) {
      val uploaderPackage =
        def.name.startsWith("com/google/android/datatransport/runtime/") ||
          def.name.startsWith("com/google/android/datatransport/cct/")
      if (uploaderPackage && def.origin != "project") {
        problems += "${def.name} comes from ${def.origin}, not from :telemetry-stub"
      }
    }
    return Result(
      referenceCount = classRefs.size + memberRefs.distinctBy { Triple(it.owner, it.name, it.desc) }.size,
      consumers = consumers,
      problems = problems,
    )
  }

  private fun resolves(owner: String, member: String, defs: Map<String, ClassDef>): Boolean {
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(owner))
    while (queue.isNotEmpty()) {
      val name = queue.removeFirst()
      if (!seen.add(name)) continue
      val def = defs[name]
      if (def == null) {
        // Supertypes outside the watched packages (java.lang.Object, ...) only provide the
        // Object methods; everything else must be defined in the watched classes themselves.
        if (member.substringBefore('(') in OBJECT_METHODS) return true
        continue
      }
      if (member in def.members) return true
      def.superName?.let { queue += it }
      queue += def.interfaces
    }
    return false
  }

  private fun readDefinition(bytes: ByteArray, origin: String): ClassDef? {
    var def: ClassDef? = null
    ClassReader(bytes)
      .accept(
        object : ClassVisitor(Opcodes.ASM9) {
          override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
          ) {
            def = ClassDef(name, superName, interfaces?.toList().orEmpty(), origin = origin)
          }

          override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
          ): FieldVisitor? {
            def?.members?.add("$name$descriptor")
            return null
          }

          override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
          ): MethodVisitor? {
            def?.members?.add("$name$descriptor")
            return null
          }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
      )
    return def
  }

  private fun readReferences(
    bytes: ByteArray,
    from: String,
    classRefs: MutableMap<String, String>,
    memberRefs: MutableList<MemberRef>,
  ) {
    fun addType(type: Type) {
      when (type.sort) {
        Type.ARRAY -> addType(type.elementType)
        Type.OBJECT -> if (isWatched(type.internalName)) classRefs.putIfAbsent(type.internalName, from)
        Type.METHOD -> {
          type.argumentTypes.forEach(::addType)
          addType(type.returnType)
        }
      }
    }

    fun addMember(owner: String, name: String, desc: String) {
      if (owner.startsWith("[")) return addType(Type.getType(owner))
      if (isWatched(owner)) {
        classRefs.putIfAbsent(owner, from)
        memberRefs += MemberRef(owner, name, desc, from)
      }
      addType(Type.getType(desc))
    }

    fun addConstant(value: Any?) {
      when (value) {
        is Type -> addType(value)
        is Handle -> addMember(value.owner, value.name, value.desc)
      }
    }

    val methodVisitor =
      object : MethodVisitor(Opcodes.ASM9) {
        override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) =
          addMember(owner, name, desc)

        override fun visitFieldInsn(op: Int, owner: String, name: String, desc: String) =
          addMember(owner, name, desc)

        override fun visitTypeInsn(op: Int, type: String) = addType(Type.getObjectType(type))

        override fun visitLdcInsn(value: Any?) = addConstant(value)

        override fun visitMultiANewArrayInsn(desc: String, dims: Int) = addType(Type.getType(desc))

        override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, vararg args: Any?) {
          addType(Type.getMethodType(desc))
          addConstant(bsm)
          args.forEach(::addConstant)
        }
      }

    ClassReader(bytes)
      .accept(
        object : ClassVisitor(Opcodes.ASM9) {
          override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
          ) {
            superName?.let { addType(Type.getObjectType(it)) }
            interfaces?.forEach { addType(Type.getObjectType(it)) }
          }

          override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
          ): FieldVisitor? {
            addType(Type.getType(descriptor))
            return null
          }

          override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
          ): MethodVisitor {
            addType(Type.getMethodType(descriptor))
            return methodVisitor
          }
        },
        ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
      )
  }

  companion object {
    private val WATCHED = listOf("com/google/android/datatransport/", "com/google/firebase/")
    private val WATCHED_BYTES = WATCHED.map { it.toByteArray(Charsets.UTF_8) }
    private val OBJECT_METHODS =
      setOf("equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait", "clone", "finalize")

    fun isWatched(internalName: String) = WATCHED.any { internalName.startsWith(it) }

    /** Cheap pre-filter: the constant pool stores class names as plain UTF-8. */
    private fun mentionsWatchedPackage(bytes: ByteArray) = WATCHED_BYTES.any { indexOf(bytes, it) >= 0 }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
      outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
      }
      return -1
    }
  }
}
