package dev.doona.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/** The core looks these members up by name and JNI descriptor, so a changed type fails only on a device. */
class JniDescriptorsTest {
    @Test
    fun nativeResponseIsBuiltFromAnIntAndAString() {
        // "(ILjava/lang/String;)V"
        NativeResponse::class.java.getConstructor(Int::class.javaPrimitiveType, String::class.java)
    }

    @Test
    fun protectTakesAnIntAndReturnsABoolean() {
        // "(I)Z"
        val protect = SocketProtector::class.java.getMethod("protect", Int::class.javaPrimitiveType)
        assertEquals(Boolean::class.javaPrimitiveType, protect.returnType)
    }
}
