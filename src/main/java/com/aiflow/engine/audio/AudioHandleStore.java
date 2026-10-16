package com.aiflow.engine.audio;

import com.aiflow.engine.run.RunResourceLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-process, run-scoped storage for short-lived audio bytes.
 *
 * <p>The store intentionally has no file, database, Redis, URL or base64
 * representation.  A handle is an opaque UUID and is useful only while the
 * owning execution segment is open.  All mutating and reading operations fence
 * through the execution guard before touching the bytes.</p>
 */
public final class AudioHandleStore implements RunResourceLifecycle {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_ENTRIES = 64;
    public static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;

    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final long maxBytes;
    private final Map<UUID, RunState> runs = new HashMap<>();
    private int entryCount;
    private long byteCount;

    public AudioHandleStore() {
        this(Clock.systemUTC(), DEFAULT_TTL, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
    }

    public AudioHandleStore(Clock clock, Duration ttl, int maxEntries, long maxBytes) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (ttl == null || ttl.isZero() || ttl.isNegative()
                || maxEntries <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Invalid audio handle limits");
        }
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    @Override
    public synchronized void begin(UUID runId) {
        if (runId == null) {
            throw new AudioHandleException(AudioHandleException.Reason.RUN_NOT_OPEN);
        }
        if (runs.containsKey(runId)) {
            throw new AudioHandleException(AudioHandleException.Reason.RUN_NOT_OPEN);
        }
        runs.put(runId, new RunState());
    }

    @Override
    public synchronized void end(UUID runId) {
        if (runId != null) {
            RunState state = runs.remove(runId);
            if (state != null) {
                entryCount -= state.entries.size();
                byteCount -= state.totalBytes;
            }
        }
    }

    /** Captures this execution segment before a provider is allowed to start. */
    public synchronized Access access(UUID runId, Runnable guard) {
        guard.run();
        RunState state = runs.get(runId);
        if (state == null) {
            throw new AudioHandleException(AudioHandleException.Reason.RUN_NOT_OPEN);
        }
        return new Access(runId, state);
    }

    public synchronized String put(Access access, AudioInput input, Runnable guard) {
        RunState state = checkedState(access, guard);
        if (input == null) {
            throw new AudioHandleException(AudioHandleException.Reason.HANDLE_INVALID);
        }
        sweepExpired();
        byte[] bytes = input.bytes();
        if (bytes.length > maxBytes || entryCount >= maxEntries
                || byteCount > maxBytes - bytes.length) {
            throw new AudioHandleException(AudioHandleException.Reason.CAPACITY);
        }
        String handle = UUID.randomUUID().toString();
        state.entries.put(handle, new Entry(bytes, input.mimeType(), Instant.now(clock).plus(ttl)));
        state.totalBytes += bytes.length;
        byteCount += bytes.length;
        entryCount++;
        try {
            guard.run();
        } catch (RuntimeException | Error failure) {
            remove(access, handle);
            throw failure;
        }
        return handle;
    }

    public String put(UUID runId, AudioInput input) {
        Access access = access(runId, () -> { });
        return put(access, input, () -> { });
    }

    public synchronized AudioInput get(Access access, String handle, Runnable guard) {
        RunState state = checkedState(access, guard);
        if (handle == null || handle.isBlank()) {
            throw new AudioHandleException(AudioHandleException.Reason.HANDLE_INVALID);
        }
        // Handles are UUIDs by contract.  Parsing also keeps arbitrary input
        // out of error messages and prevents path/URL-like values being used.
        try {
            if (!UUID.fromString(handle).toString().equals(handle)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ignored) {
            throw new AudioHandleException(AudioHandleException.Reason.HANDLE_INVALID);
        }
        Entry entry = state.entries.get(handle);
        if (entry == null) {
            throw new AudioHandleException(AudioHandleException.Reason.HANDLE_INVALID);
        }
        if (!Instant.now(clock).isBefore(entry.expiresAt)) {
            remove(access, handle);
            throw new AudioHandleException(AudioHandleException.Reason.HANDLE_EXPIRED);
        }
        guard.run();
        return new AudioInput(entry.bytes, entry.mimeType);
    }

    public AudioInput get(UUID runId, String handle) {
        Access access = access(runId, () -> { });
        return get(access, handle, () -> { });
    }

    /** Cleanup is safe even after cancellation or after this segment has closed. */
    public synchronized void remove(Access access, String handle) {
        RunState state = access == null ? null : runs.get(access.runId);
        if (state == null || state != access.state) {
            return;
        }
        Entry entry = state.entries.remove(handle);
        if (entry != null) {
            state.totalBytes -= entry.bytes.length;
            byteCount -= entry.bytes.length;
            entryCount--;
        }
    }

    /** Removes expired entries and returns the number of entries removed. */
    public synchronized int sweepExpired() {
        Instant now = Instant.now(clock);
        int removed = 0;
        for (RunState state : runs.values()) {
            var iterator = state.entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next().getValue();
                if (!now.isBefore(entry.expiresAt)) {
                    state.totalBytes -= entry.bytes.length;
                    byteCount -= entry.bytes.length;
                    entryCount--;
                    iterator.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public synchronized int size() {
        return entryCount;
    }

    public synchronized long totalBytes() {
        return byteCount;
    }

    private RunState checkedState(Access access, Runnable guard) {
        guard.run();
        RunState state = access == null ? null : runs.get(access.runId);
        if (state == null || state != access.state) {
            throw new AudioHandleException(AudioHandleException.Reason.RUN_NOT_OPEN);
        }
        return state;
    }

    /** Opaque segment identity; never put this object in workflow variables. */
    public static final class Access {
        private final UUID runId;
        private final RunState state;

        private Access(UUID runId, RunState state) {
            this.runId = runId;
            this.state = state;
        }

        @Override
        public String toString() {
            return "AudioHandleAccess[run-local]";
        }
    }

    private static final class RunState {
        private final Map<String, Entry> entries = new HashMap<>();
        private long totalBytes;
    }

    private record Entry(byte[] bytes, String mimeType, Instant expiresAt) {
    }
}
