package ai.openpush.android.liveupdates;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated, dependency-free representation of an Android Live Update data payload. */
public final class OpenPushLiveUpdatePayload {
    public static final String DATA_KEY = "live_notification";

    private static final int MAX_WIRE_LENGTH = 16 * 1024;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_STRING_LENGTH = 1024;
    private static final int MAX_COLLECTION_SIZE = 64;
    private static final int MAX_DEPTH = 4;
    private static final double MAX_ABSOLUTE_NUMBER = 1_000_000_000_000d;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Set<String> TOP_LEVEL_KEYS = unmodifiableSet(
            "key", "instance_id", "event", "sequence", "event_attributes", "event_updates");
    private static final Set<String> TEMPLATE_KEYS = unmodifiableSet("progress");
    private static final Set<String> ATTRIBUTE_KEYS = unmodifiableSet(
            "title", "body", "short_text", "unit", "content_description");
    private static final Set<String> UPDATE_KEYS = unmodifiableSet(
            "current_progress", "max_progress", "short_text", "body", "status");

    public enum Event {
        START,
        UPDATE,
        END
    }

    /** Checked validation failure suitable for bounded diagnostic logging. */
    public static final class InvalidPayloadException extends Exception {
        InvalidPayloadException(String message) {
            super(message);
        }

        InvalidPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String key;
    private final String instanceId;
    private final Event event;
    private final Long sequence;
    private final JSONObject eventAttributes;
    private final JSONObject eventUpdates;

    private OpenPushLiveUpdatePayload(
            String key,
            String instanceId,
            Event event,
            Long sequence,
            JSONObject eventAttributes,
            JSONObject eventUpdates) {
        this.key = key;
        this.instanceId = instanceId;
        this.event = event;
        this.sequence = sequence;
        this.eventAttributes = eventAttributes;
        this.eventUpdates = eventUpdates;
    }

    /**
     * Parses {@link #DATA_KEY}. Callers should check map membership first to distinguish an
     * ordinary notification from an invalid Live Update.
     */
    public static OpenPushLiveUpdatePayload parse(Map<String, String> data, String collapseKey)
            throws InvalidPayloadException {
        if (data == null || !data.containsKey(DATA_KEY)) {
            throw new InvalidPayloadException("live_notification is absent");
        }
        String wire = data.get(DATA_KEY);
        if (wire == null || wire.length() == 0 || wire.length() > MAX_WIRE_LENGTH) {
            throw new InvalidPayloadException("live_notification has an invalid size");
        }

        final JSONObject object;
        try {
            object = new JSONObject(wire);
        } catch (JSONException exception) {
            throw new InvalidPayloadException("live_notification is not a JSON object", exception);
        }
        rejectUnknownKeys(object, TOP_LEVEL_KEYS, "live_notification");

        String key = requiredIdentifier(object, "key", 64);
        if (!TEMPLATE_KEYS.contains(key)) {
            throw new InvalidPayloadException("unsupported live_notification key");
        }

        String eventValue = requiredString(object, "event", 16);
        final Event event;
        switch (eventValue) {
            case "start":
                event = Event.START;
                break;
            case "update":
                event = Event.UPDATE;
                break;
            case "end":
            case "dismiss":
                event = Event.END;
                break;
            default:
                throw new InvalidPayloadException("unsupported live_notification event");
        }

        String instanceId;
        if (object.has("instance_id")) {
            instanceId = requiredIdentifier(object, "instance_id", MAX_IDENTIFIER_LENGTH);
        } else if (collapseKey != null && !collapseKey.isEmpty()) {
            instanceId = validateIdentifier(collapseKey, "collapse key", MAX_IDENTIFIER_LENGTH);
        } else {
            instanceId = key;
        }

        Long sequence = optionalSequence(object);
        JSONObject attributes = optionalObject(object, "event_attributes");
        JSONObject updates = optionalObject(object, "event_updates");
        rejectUnknownKeys(attributes, ATTRIBUTE_KEYS, "event_attributes");
        rejectUnknownKeys(updates, UPDATE_KEYS, "event_updates");
        validateTree(attributes, 1);
        validateTree(updates, 1);

        if (event == Event.UPDATE && updates.length() == 0) {
            throw new InvalidPayloadException("update requires event_updates");
        }

        return new OpenPushLiveUpdatePayload(
                key, instanceId, event, sequence, attributes, updates);
    }

    public String getKey() {
        return key;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public Event getEvent() {
        return event;
    }

    public Long getSequence() {
        return sequence;
    }

    /** Returns a defensive JSON copy so callers cannot mutate validated payload state. */
    public JSONObject getEventAttributes() {
        return copy(eventAttributes);
    }

    /** Returns a defensive JSON copy so callers cannot mutate validated payload state. */
    public JSONObject getEventUpdates() {
        return copy(eventUpdates);
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("validated JSON could not be copied", impossible);
        }
    }

    private static String requiredIdentifier(JSONObject object, String name, int maxLength)
            throws InvalidPayloadException {
        return validateIdentifier(requiredString(object, name, maxLength), name, maxLength);
    }

    private static String validateIdentifier(String value, String name, int maxLength)
            throws InvalidPayloadException {
        if (value == null || value.length() == 0 || value.length() > maxLength
                || !IDENTIFIER.matcher(value).matches()) {
            throw new InvalidPayloadException(name + " is not a valid identifier");
        }
        return value;
    }

    private static String requiredString(JSONObject object, String name, int maxLength)
            throws InvalidPayloadException {
        Object value = object.opt(name);
        if (!(value instanceof String)) {
            throw new InvalidPayloadException(name + " must be a string");
        }
        String string = (String) value;
        if (string.length() == 0 || string.length() > maxLength) {
            throw new InvalidPayloadException(name + " has an invalid size");
        }
        return string;
    }

    private static Long optionalSequence(JSONObject object) throws InvalidPayloadException {
        if (!object.has("sequence")) {
            return null;
        }
        Object value = object.opt("sequence");
        if (!(value instanceof Number)) {
            throw new InvalidPayloadException("sequence must be a non-negative integer");
        }
        Number number = (Number) value;
        double asDouble = number.doubleValue();
        long asLong = number.longValue();
        if (!Double.isFinite(asDouble) || asDouble < 0 || asDouble != asLong) {
            throw new InvalidPayloadException("sequence must be a non-negative integer");
        }
        return asLong;
    }

    private static JSONObject optionalObject(JSONObject object, String name)
            throws InvalidPayloadException {
        if (!object.has(name)) {
            return new JSONObject();
        }
        Object value = object.opt(name);
        if (!(value instanceof JSONObject)) {
            throw new InvalidPayloadException(name + " must be a JSON object");
        }
        return (JSONObject) value;
    }

    private static void rejectUnknownKeys(JSONObject object, Set<String> allowed, String name)
            throws InvalidPayloadException {
        if (object.length() > MAX_COLLECTION_SIZE) {
            throw new InvalidPayloadException(name + " contains too many fields");
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            if (!allowed.contains(keys.next())) {
                throw new InvalidPayloadException(name + " contains an unsupported field");
            }
        }
    }

    private static void validateTree(Object value, int depth) throws InvalidPayloadException {
        if (depth > MAX_DEPTH) {
            throw new InvalidPayloadException("live_notification nesting is too deep");
        }
        if (value == null || value == JSONObject.NULL || value instanceof Boolean) {
            return;
        }
        if (value instanceof String) {
            if (((String) value).length() > MAX_STRING_LENGTH) {
                throw new InvalidPayloadException("live_notification string is too long");
            }
            return;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || Math.abs(number) > MAX_ABSOLUTE_NUMBER) {
                throw new InvalidPayloadException("live_notification number is out of range");
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.length() > MAX_COLLECTION_SIZE) {
                throw new InvalidPayloadException("live_notification object is too large");
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.length() == 0 || key.length() > 64) {
                    throw new InvalidPayloadException("live_notification field name is invalid");
                }
                validateTree(object.opt(key), depth + 1);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > MAX_COLLECTION_SIZE) {
                throw new InvalidPayloadException("live_notification array is too large");
            }
            for (int index = 0; index < array.length(); index++) {
                validateTree(array.opt(index), depth + 1);
            }
            return;
        }
        throw new InvalidPayloadException("live_notification contains an unsupported value");
    }

    private static Set<String> unmodifiableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
