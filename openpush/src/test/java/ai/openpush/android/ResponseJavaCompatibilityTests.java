package ai.openpush.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

/** Compile- and descriptor-level proof for the two-argument Response surface. */
public class ResponseJavaCompatibilityTests {
    @Test
    public void theTwoArgumentJavaConstructorAndCopyStillCompile() {
        OpenPush.Response response = new OpenPush.Response(200, "{}");
        OpenPush.Response copied = response.copy(202, "accepted");

        assertEquals(200, response.getStatus());
        assertEquals("{}", response.getBody());
        assertTrue(response.getHeaders().isEmpty());
        assertEquals(202, copied.getStatus());
        assertEquals("accepted", copied.getBody());
    }

    @Test
    public void theTwoArgumentJvmDescriptorsRemainPresentAlongsideHeaders() throws Exception {
        Constructor<OpenPush.Response> legacyConstructor =
                OpenPush.Response.class.getConstructor(int.class, String.class);
        Method legacyCopy = OpenPush.Response.class.getMethod(
                "copy", int.class, String.class);
        Method legacyCopyDefault = OpenPush.Response.class.getDeclaredMethod(
                "copy$default",
                OpenPush.Response.class,
                int.class,
                String.class,
                int.class,
                Object.class);

        Object legacy = legacyConstructor.newInstance(200, "{}");
        assertTrue(legacy instanceof OpenPush.Response);
        assertEquals(OpenPush.Response.class, legacyCopy.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isStatic(legacyCopyDefault.getModifiers()));

        Map<String, String> headers = Collections.singletonMap("retry-after", "5");
        OpenPush.Response withHeaders = new OpenPush.Response(429, "", headers);
        assertEquals(headers, withHeaders.getHeaders());
    }
}
