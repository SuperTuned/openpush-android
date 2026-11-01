package ai.openpush.android.liveupdates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OpenPushLiveUpdatePayloadTests {
    @Test
    public void parsesStartAndUsesExplicitInstance() throws Exception {
        OpenPushLiveUpdatePayload payload = OpenPushLiveUpdatePayload.parse(data(
                "{\"key\":\"progress\",\"instance_id\":\"match-42\","
                        + "\"event\":\"start\",\"sequence\":1,"
                        + "\"event_attributes\":{\"title\":\"Final\"},"
                        + "\"event_updates\":{\"current_progress\":10}}"), "ignored");

        assertEquals("progress", payload.getKey());
        assertEquals("match-42", payload.getInstanceId());
        assertEquals(OpenPushLiveUpdatePayload.Event.START, payload.getEvent());
        assertEquals(Long.valueOf(1), payload.getSequence());
        assertEquals("Final", payload.getEventAttributes().getString("title"));
    }

    @Test
    public void fallsBackToCollapseKeyThenTemplateKey() throws Exception {
        String wire = "{\"key\":\"progress\",\"event\":\"start\"}";
        assertEquals("collapse-1", OpenPushLiveUpdatePayload.parse(data(wire), "collapse-1")
                .getInstanceId());
        assertEquals("progress", OpenPushLiveUpdatePayload.parse(data(wire), null)
                .getInstanceId());
    }

    @Test
    public void rejectsMissingMalformedUnknownAndUnsafeFields() {
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(Collections.emptyMap(), null));
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data("not-json"), null));
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data(
                        "{\"key\":\"other\",\"event\":\"start\"}"), null));
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data(
                        "{\"key\":\"progress\",\"event\":\"pause\"}"), null));
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data(
                        "{\"key\":\"progress\",\"event\":\"start\","
                                + "\"intent\":\"com.example.SecretActivity\"}"), null));
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data(
                        "{\"key\":\"progress\",\"event\":\"start\","
                                + "\"event_attributes\":{\"url\":\"https://example.test\"}}"), null));
    }

    @Test
    public void validatesUpdateAndSequence() {
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data(
                        "{\"key\":\"progress\",\"event\":\"update\"}"), null));
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data(
                        "{\"key\":\"progress\",\"event\":\"start\","
                                + "\"sequence\":1.5}"), null));
        assertThrows(OpenPushLiveUpdatePayload.InvalidPayloadException.class,
                () -> OpenPushLiveUpdatePayload.parse(data(
                        "{\"key\":\"progress\",\"event\":\"start\","
                                + "\"sequence\":-1}"), null));
    }

    @Test
    public void sequenceIsOptional() throws Exception {
        OpenPushLiveUpdatePayload payload = OpenPushLiveUpdatePayload.parse(data(
                "{\"key\":\"progress\",\"event\":\"end\"}"), null);
        assertNull(payload.getSequence());
        assertEquals(OpenPushLiveUpdatePayload.Event.END, OpenPushLiveUpdatePayload.parse(data(
                "{\"key\":\"progress\",\"event\":\"dismiss\"}"), null).getEvent());
    }

    private static Map<String, String> data(String wire) {
        Map<String, String> result = new HashMap<>();
        result.put(OpenPushLiveUpdatePayload.DATA_KEY, wire);
        return result;
    }
}
