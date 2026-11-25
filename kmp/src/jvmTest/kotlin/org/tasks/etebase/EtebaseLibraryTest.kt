package org.tasks.etebase

import com.etebase.client.Client
import com.etebase.client.FetchOptions
import com.etebase.client.exceptions.EtebaseException
import org.junit.Assert.assertTrue
import org.junit.Test

/** The patched etebase library (etebase/build.sh); the live test covers content with repeated chunks. */
class EtebaseLibraryTest {
    @Test
    fun panicInTheLibraryIsAnExceptionNotAnAbort() {
        Client.getDefaultServerUrl() // loads the native library
        val options = FetchOptions()
        // a released native object: the library panics on the null pointer
        FetchOptions::class.java.getDeclaredField("mNativeObj").apply { isAccessible = true }.setLong(options, 0L)
        val error = runCatching { options.limit(1) }.exceptionOrNull()
        assertTrue("$error", error is EtebaseException && error.message!!.startsWith("etebase panicked"))
    }
}
