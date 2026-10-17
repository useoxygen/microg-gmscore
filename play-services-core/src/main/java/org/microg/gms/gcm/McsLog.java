/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.gcm;

import android.util.Log;

import com.squareup.wire.Message;

import org.microg.gms.gcm.mcs.DataMessageStanza;

/**
 * Redacted logging for the MCS transport (useoxygen/cyclon#842). Push messages carry Google sign-in prompt payloads,
 * account names, registration ids and {@code auths} tokens, so transport logs name only the message type, a size
 * bucket and, for data messages, the target category (an app package) and the number of app data entries. Never a
 * value, key, id or exception message.
 */
public final class McsLog {
    /** Where lines go; tests replace it. Android's log by default. */
    public interface Sink {
        void log(int priority, String tag, String line);
    }

    private static final Sink ANDROID = (priority, tag, line) -> Log.println(priority, tag, line);
    private static volatile Sink sink = ANDROID;

    private McsLog() {
    }

    static void setSinkForTest(Sink replacement) {
        sink = replacement == null ? ANDROID : replacement;
    }

    static void d(String tag, String line) {
        sink.log(Log.DEBUG, tag, line);
    }

    static void w(String tag, String line) {
        sink.log(Log.WARN, tag, line);
    }

    /** {@code type=DataMessageStanza size=1024 category=com.example app_data=3}; nothing from the message's values. */
    public static String describe(Object message) {
        if (message == null) return "type=none";
        StringBuilder line = new StringBuilder("type=").append(message.getClass().getSimpleName());
        if (message instanceof Message) {
            int size;
            try {
                size = ((Message) message).encode().length;
            } catch (RuntimeException e) {
                size = -1;
            }
            line.append(" size=").append(size < 0 ? "?" : Integer.toString(sizeBucket(size)));
        }
        if (message instanceof DataMessageStanza) {
            DataMessageStanza data = (DataMessageStanza) message;
            line.append(" category=").append(safeCategory(data.category));
            line.append(" app_data=").append(data.app_data == null ? 0 : data.app_data.size());
        }
        return line.toString();
    }

    /** Only the exception classes of a failure and its causes, never their messages, which may quote content. */
    public static String describe(Throwable error) {
        if (error == null) return "error=none";
        StringBuilder line = new StringBuilder("error=");
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) line.append("<-");
            line.append(current.getClass().getSimpleName());
        }
        return line.toString();
    }

    /** A category is a package name; anything else (it comes from the network) is not echoed. */
    static String safeCategory(String category) {
        if (category == null) return "none";
        if (category.length() > 100 || !category.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")) return "?";
        return category;
    }

    /** Rough size, so logs show a message's shape without its exact length. */
    static int sizeBucket(int length) {
        if (length <= 0) return 0;
        if (length <= 256) return 256;
        if (length <= 1024) return 1024;
        if (length <= 4096) return 4096;
        return 65536;
    }
}
