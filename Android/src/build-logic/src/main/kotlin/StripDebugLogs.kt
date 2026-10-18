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

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Build types whose APKs must not write debug logs (privacy contract, docs/NETWORK.md). */
val LOG_STRIPPED_BUILD_TYPES = setOf("release", "ci")

/**
 * Removes `Log.v`, `Log.d` and `Log.i` calls from the app and all its libraries in the release
 * and ci builds, so prompts, model output and screen text never reach logcat. Warnings and errors
 * stay. The arguments are still evaluated; only the call is replaced by `0`, its return value.
 *
 * The release build does not run R8, so `-assumenosideeffects` is not an option.
 */
internal fun registerDebugLogStripping(project: Project) {
  project.plugins.withId("com.android.application") {
    val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
    components.onVariants(components.selector().all()) { variant ->
      if (variant.buildType in LOG_STRIPPED_BUILD_TYPES) {
        variant.instrumentation.transformClassesWith(
          StripDebugLogsFactory::class.java,
          InstrumentationScope.ALL,
        ) {}
        // The stack is the same before and after each replaced call, so frames stay valid.
        variant.instrumentation.setAsmFramesComputationMode(
          FramesComputationMode.COPY_FRAMES
        )
      }
    }
  }
}

private const val LOG_CLASS = "android/util/Log"
private val STRIPPED_LEVELS = setOf("v", "d", "i")
private val ARG_COUNT_BY_DESCRIPTOR =
  mapOf(
    "(Ljava/lang/String;Ljava/lang/String;)I" to 2,
    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I" to 3,
  )

abstract class StripDebugLogsFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
  override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
    object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
      override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
      ): MethodVisitor {
        val next = super.visitMethod(access, name, descriptor, signature, exceptions)
        return StripDebugLogsMethodVisitor(next)
      }
    }

  override fun isInstrumentable(classData: ClassData): Boolean = classData.className != "android.util.Log"
}

internal class StripDebugLogsMethodVisitor(next: MethodVisitor?) : MethodVisitor(Opcodes.ASM9, next) {
  override fun visitMethodInsn(
    opcode: Int,
    owner: String?,
    name: String?,
    descriptor: String?,
    isInterface: Boolean,
  ) {
    val argCount = ARG_COUNT_BY_DESCRIPTOR[descriptor]
    if (opcode == Opcodes.INVOKESTATIC && owner == LOG_CLASS && name in STRIPPED_LEVELS && argCount != null) {
      repeat(argCount) { super.visitInsn(Opcodes.POP) }
      super.visitInsn(Opcodes.ICONST_0)
      return
    }
    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
  }
}
