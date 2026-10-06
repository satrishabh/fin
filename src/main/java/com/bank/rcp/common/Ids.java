package com.bank.rcp.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class Ids {
    private static final AtomicLong SEQ = new AtomicLong(1000);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private Ids() {}

    /** Human-readable, sortable id such as {@code case-20261005-1001}. */
    public static String next(String prefix) {
        return prefix + "-" + DAY.format(Instant.now()) + "-" + SEQ.incrementAndGet();
    }

    public static String uuid() { return UUID.randomUUID().toString(); }

    public static String now() { return Instant.now().toString(); }
}
