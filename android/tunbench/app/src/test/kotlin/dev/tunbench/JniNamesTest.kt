package dev.tunbench

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The names native code binds to. A rename here compiles and fails only on the phone, so the
 * classes are read by their full names, without running the System.loadLibrary in their init.
 */
class JniNamesTest {
    @Test
    fun rustExportsResolveByName() {
        // crates/honk-android exports Java_dev_tunbench_<class>_<method>, resolved by name alone.
        val expected = mapOf(
            "dev.tunbench.HonkEngine" to setOf(
                "start", "stop", "isRunning", "stats", "workerTid", "workerAffinity",
                "prepareThread", "gettid", "findThread", "telemetry",
            ),
            "dev.tunbench.Bench" to setOf("run", "stop"),
        )
        for ((name, methods) in expected) {
            assertEquals(name, methods, natives(name).keys)
        }
    }

    @Test
    fun hevRegistersByNameAndDescriptor() {
        // hev's JNI_OnLoad (hev-jni.c, PKGNAME and CLSNAME in Application.mk) calls RegisterNatives.
        val expected = mapOf(
            "TProxyStartService" to "(Ljava/lang/String;I)Z",
            "TProxyStopService" to "()Z",
            "TProxyIsRunning" to "()Z",
            "TProxyGetStats" to "()[J",
        )
        assertEquals(expected, natives("dev.tunbench.TProxyService"))
    }

    @Test
    fun benchHostKeepsTheMethodsRustCalls() {
        // crates/honk-android/src/harness/java.rs calls these on the host by name and descriptor;
        // it never looks up the host's class name.
        val expected = mapOf(
            "openTun" to "(I)I",
            "closeTun" to "()V",
            "startHev" to "(Ljava/lang/String;I)Z",
            "stopHev" to "()Z",
            "hevStats" to "()[J",
            "platform" to "()Ljava/lang/String;",
            "progress" to "(II)V",
        )
        val actual = BenchHost::class.java.methods.associate { it.name to descriptor(it) }
        assertEquals(expected, expected.keys.associateWith { actual[it] })
    }

    private fun natives(className: String): Map<String, String> =
        Class.forName(className, false, javaClass.classLoader).declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .associate { it.name to descriptor(it) }

    private fun descriptor(m: Method): String =
        MethodType.methodType(m.returnType, m.parameterTypes).toMethodDescriptorString()
}
