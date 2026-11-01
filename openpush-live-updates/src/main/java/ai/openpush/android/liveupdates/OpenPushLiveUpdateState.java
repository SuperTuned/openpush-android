package ai.openpush.android.liveupdates;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** Private, durable lifecycle metadata for active Android Live Updates. */
public final class OpenPushLiveUpdateState {
    static final String PREFERENCES_NAME = "ai.openpush.android.live_updates";
    /** An id reserved but never displayed is abandoned after this long. */
    static final long RESERVATION_TIMEOUT_MILLIS = 10L * 60L * 1000L;
    private static final String RECORD_PREFIX = "record.";

    /**
     * One process-wide monitor. Callers build short-lived instances (a messaging service, a
     * broadcast receiver, the host), so guarding on {@code this} would let two threads reserve
     * the same notification id. Every mutation and every read holds this lock instead.
     */
    private static final Object LOCK = new Object();

    /** Monitor for one complete admission/render/commit transaction in the process. */
    static Object transactionLock() {
        return LOCK;
    }

    public enum Decision {
        PROCEED,
        DISMISSED,
        STALE
    }

    interface IdHasher {
        int hash(String packageName, String instanceId);
    }

    public static final class Record {
        private final String instanceId;
        private final String key;
        private final int notificationId;
        private final boolean dismissed;
        private final Long lastSequence;
        private final long timeoutAtMillis;
        private final long reservedAtMillis;

        Record(
                String instanceId,
                String key,
                int notificationId,
                boolean dismissed,
                Long lastSequence,
                long timeoutAtMillis,
                long reservedAtMillis) {
            this.instanceId = instanceId;
            this.key = key;
            this.notificationId = notificationId;
            this.dismissed = dismissed;
            this.lastSequence = lastSequence;
            this.timeoutAtMillis = timeoutAtMillis;
            this.reservedAtMillis = reservedAtMillis;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public String getKey() {
            return key;
        }

        public int getNotificationId() {
            return notificationId;
        }

        public boolean isDismissed() {
            return dismissed;
        }

        public Long getLastSequence() {
            return lastSequence;
        }

        public long getTimeoutAtMillis() {
            return timeoutAtMillis;
        }

        /** When the id was reserved. Only meaningful while {@code timeoutAtMillis} is 0. */
        public long getReservedAtMillis() {
            return reservedAtMillis;
        }
    }

    private final String packageName;
    private final SharedPreferences preferences;
    private final IdHasher idHasher;

    public OpenPushLiveUpdateState(Context context) {
        this(
                context.getApplicationContext().getPackageName(),
                context.getApplicationContext().getSharedPreferences(
                        PREFERENCES_NAME, Context.MODE_PRIVATE),
                OpenPushLiveUpdateState::stableNotificationId);
    }

    OpenPushLiveUpdateState(String packageName, SharedPreferences preferences, IdHasher idHasher) {
        this.packageName = packageName;
        this.preferences = preferences;
        this.idHasher = idHasher;
    }

    /** Returns and reserves a stable ID, probing only when an active instance owns a collision. */
    public int notificationId(String instanceId) {
        return notificationId(instanceId, System.currentTimeMillis());
    }

    /** Reserves a stable ID, stamping the reservation so an abandoned one can be pruned. */
    int notificationId(String instanceId, long nowMillis) {
        synchronized (LOCK) {
            Record existing = get(instanceId);
            if (existing != null) {
                return existing.notificationId;
            }

            int candidate = normalizeId(idHasher.hash(packageName, instanceId));
            while (isNotificationIdInUse(candidate, instanceId)) {
                candidate = candidate == Integer.MAX_VALUE ? 1 : candidate + 1;
            }
            write(new Record(instanceId, "", candidate, false, null, 0, nowMillis));
            return candidate;
        }
    }

    /** Prunes expired entries and applies dismissal/sequence gates for the next event. */
    public Decision prepare(OpenPushLiveUpdatePayload payload, long nowMillis) {
        synchronized (LOCK) {
            pruneExpired(nowMillis);
            Record record = get(payload.getInstanceId());

            if (payload.getEvent() == OpenPushLiveUpdatePayload.Event.START) {
                // A start replaces prior lifecycle state only after the notification posts. Keeping
                // prepare side-effect free preserves dismissal/sequence state when rendering fails.
                return Decision.PROCEED;
            }

            if (record != null && record.dismissed
                    && payload.getEvent() == OpenPushLiveUpdatePayload.Event.UPDATE) {
                return Decision.DISMISSED;
            }

            // End is always idempotent and must clear the instance even after dismissal/reordering.
            if (payload.getEvent() == OpenPushLiveUpdatePayload.Event.END) {
                return Decision.PROCEED;
            }

            Long sequence = payload.getSequence();
            if (record != null && sequence != null && record.lastSequence != null
                    && sequence <= record.lastSequence) {
                return Decision.STALE;
            }
            return Decision.PROCEED;
        }
    }

    /** Persists metadata only after a notification has rendered successfully. */
    public void recordDisplayed(
            OpenPushLiveUpdatePayload payload, int notificationId, long timeoutAtMillis) {
        synchronized (LOCK) {
            Record previous = get(payload.getInstanceId());
            Long sequence = payload.getEvent() == OpenPushLiveUpdatePayload.Event.START
                    ? payload.getSequence()
                    : payload.getSequence() != null
                            ? payload.getSequence()
                            : previous == null ? null : previous.lastSequence;
            write(new Record(
                    payload.getInstanceId(),
                    payload.getKey(),
                    notificationId,
                    false,
                    sequence,
                    Math.max(0, timeoutAtMillis),
                    previous == null ? 0 : previous.reservedAtMillis));
        }
    }

    public void markDismissed(String instanceId) {
        synchronized (LOCK) {
            Record record = get(instanceId);
            if (record == null) {
                return;
            }
            write(new Record(
                    record.instanceId,
                    record.key,
                    record.notificationId,
                    true,
                    record.lastSequence,
                    record.timeoutAtMillis,
                    record.reservedAtMillis));
        }
    }

    public Record get(String instanceId) {
        synchronized (LOCK) {
            String recordKey = storageKey(instanceId);
            final String value;
            try {
                value = preferences.getString(recordKey, null);
            } catch (ClassCastException invalidState) {
                preferences.edit().remove(recordKey).apply();
                return null;
            }
            if (value == null) {
                return null;
            }
            try {
                JSONObject object = new JSONObject(value);
                if (!instanceId.equals(object.getString("instance_id"))) {
                    preferences.edit().remove(recordKey).apply();
                    return null;
                }
                return new Record(
                        instanceId,
                        object.optString("key", ""),
                        object.getInt("notification_id"),
                        object.optBoolean("dismissed", false),
                        object.has("last_sequence") ? object.getLong("last_sequence") : null,
                        object.optLong("timeout_at_millis", 0),
                        object.optLong("reserved_at_millis", 0));
            } catch (JSONException | RuntimeException invalidState) {
                preferences.edit().remove(recordKey).apply();
                return null;
            }
        }
    }

    public void remove(String instanceId) {
        synchronized (LOCK) {
            preferences.edit().remove(storageKey(instanceId)).apply();
        }
    }

    /** Removes expired, abandoned or corrupt records and returns the number removed. */
    public int pruneExpired(long nowMillis) {
        synchronized (LOCK) {
            int removed = 0;
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(RECORD_PREFIX)) {
                    continue;
                }
                if (!(entry.getValue() instanceof String)) {
                    editor.remove(entry.getKey());
                    removed++;
                    continue;
                }
                boolean shouldRemove;
                try {
                    JSONObject object = new JSONObject((String) entry.getValue());
                    long timeout = object.optLong("timeout_at_millis", 0);
                    shouldRemove = timeout > 0
                            ? timeout <= nowMillis
                            // A record with no timeout only ever reserved an id. If nothing was
                            // displayed within the reservation window the reservation is stale and
                            // would otherwise hold the id for the lifetime of the install.
                            : nowMillis - object.optLong("reserved_at_millis", 0)
                                    >= RESERVATION_TIMEOUT_MILLIS;
                } catch (JSONException | RuntimeException invalidState) {
                    shouldRemove = true;
                }
                if (shouldRemove) {
                    editor.remove(entry.getKey());
                    removed++;
                }
            }
            if (removed > 0) {
                editor.apply();
            }
            return removed;
        }
    }

    /** Stable FNV-1a hash of package and instance, normalized by the state allocator. */
    public static int stableNotificationId(String packageName, String instanceId) {
        byte[] bytes = (packageName + "\u0000" + instanceId).getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte value : bytes) {
            hash ^= value & 0xff;
            hash *= 0x01000193;
        }
        return normalizeId(hash);
    }

    private boolean isNotificationIdInUse(int notificationId, String instanceId) {
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(RECORD_PREFIX)
                    || !(entry.getValue() instanceof String)) {
                continue;
            }
            try {
                JSONObject object = new JSONObject((String) entry.getValue());
                if (object.optInt("notification_id") == notificationId
                        && !instanceId.equals(object.optString("instance_id"))) {
                    return true;
                }
            } catch (JSONException | RuntimeException ignored) {
                // Corrupt records are removed on the next prepare/prune pass.
            }
        }
        return false;
    }

    private void write(Record record) {
        JSONObject object = new JSONObject();
        try {
            object.put("instance_id", record.instanceId);
            object.put("key", record.key);
            object.put("notification_id", record.notificationId);
            object.put("dismissed", record.dismissed);
            if (record.lastSequence != null) {
                object.put("last_sequence", record.lastSequence);
            }
            object.put("timeout_at_millis", record.timeoutAtMillis);
            object.put("reserved_at_millis", record.reservedAtMillis);
        } catch (JSONException impossible) {
            throw new IllegalStateException("live update state could not be encoded", impossible);
        }
        preferences.edit().putString(storageKey(record.instanceId), object.toString()).apply();
    }

    private static int normalizeId(int value) {
        if (value == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
        }
        int normalized = Math.abs(value);
        return normalized == 0 ? 1 : normalized;
    }

    private static String storageKey(String instanceId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(instanceId.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(RECORD_PREFIX);
            for (int index = 0; index < 16; index++) {
                result.append(String.format(java.util.Locale.US, "%02x", bytes[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
