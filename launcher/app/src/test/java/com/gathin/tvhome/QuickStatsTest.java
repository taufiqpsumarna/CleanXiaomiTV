package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QuickStatsTest {
    @Test
    public void megabytesBelowOneGigabyte() {
        assertEquals("870 MB", QuickStats.size(870L * 1024 * 1024));
    }

    @Test
    public void gigabytesWithOneDecimal() {
        assertEquals("1.9 GB", QuickStats.size(1946L * 1024 * 1024));
        assertEquals("21.8 GB", QuickStats.size((long) (21.8 * 1024 * 1024 * 1024)));
    }
}
