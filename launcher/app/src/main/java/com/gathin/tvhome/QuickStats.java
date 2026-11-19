package com.gathin.tvhome;

import android.os.Environment;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * The numbers for the quick stats pill on Home (home_stats): CPU load, memory in use and free storage.
 * Plain file reads, no permission needed; call {@link #sample} off the main thread, about every 10 s, and
 * only while Home is on screen. Ported from CleanYourTV's show_stats line.
 *
 * Android 8 and later hide /proc/stat from apps on most TVs; the CPU figure then comes from the cores'
 * cpuidle times, and if those are hidden too it is left out rather than shown as a dash.
 */
final class QuickStats {
    /** One reading. cpu is -1 when unknown; memory and storage in bytes, -1 when unreadable. */
    static final class Sample {
        final int cpu;
        final long memUsed, memTotal, storageFree;

        Sample(int cpu, long memUsed, long memTotal, long storageFree) {
            this.cpu = cpu;
            this.memUsed = memUsed;
            this.memTotal = memTotal;
            this.storageFree = storageFree;
        }
    }

    private static final int UNREADABLE = -2;
    private long statTotalPrev = -1, statIdlePrev = -1;
    private boolean statDead;
    private long idlePrev = -1, wallPrev = -1;

    /** Forget the previous CPU sample, e.g. after Home was away for a while. */
    void reset() {
        statTotalPrev = statIdlePrev = idlePrev = wallPrev = -1;
    }

    Sample sample() {
        int cpu = cpu();
        if (cpu == -1) {
            // the first call has nothing to compare with: take a second sample a moment later
            SystemClock.sleep(250);
            cpu = cpu();
        }
        long[] mem = memory();
        long free = -1;
        try {
            free = Environment.getDataDirectory().getUsableSpace();
        } catch (Exception ignored) {
        }
        return new Sample(Math.max(-1, cpu), mem[0], mem[1], free);
    }

    /** 1.2 GB / 870 MB style, one decimal from 1 GB up. */
    static String size(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
        return String.format(Locale.ROOT, "%d MB", Math.round(mb));
    }

    // ---- CPU ----

    private int cpu() {
        if (!statDead) {
            int v = fromProcStat();
            if (v != UNREADABLE) return v;
            statDead = true;
        }
        int v = fromCpuIdle();
        return v == UNREADABLE ? -3 : v;   // -3: no source at all, don't sample twice
    }

    private int fromProcStat() {
        String s = firstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu ")) return UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return UNREADABLE;
        try {
            long total = 0;
            for (int i = 1; i < Math.min(t.length, 8); i++) total += Long.parseLong(t[i]);
            long idle = Long.parseLong(t[4]) + (t.length > 5 ? Long.parseLong(t[5]) : 0);
            if (statTotalPrev < 0) {
                statTotalPrev = total;
                statIdlePrev = idle;
                return -1;
            }
            long dt = total - statTotalPrev, di = idle - statIdlePrev;
            statTotalPrev = total;
            statIdlePrev = idle;
            if (dt <= 0) return -1;
            return clamp((int) Math.round((dt - di) * 100.0 / dt));
        } catch (NumberFormatException e) {
            return UNREADABLE;
        }
    }

    /** Busy share from the time each core spent in its idle states (microseconds). */
    private int fromCpuIdle() {
        long idle = 0;
        int cores = 0;
        for (int c = 0; c < 16; c++) {
            boolean any = false;
            for (int st = 0; st < 8; st++) {
                String v = firstLine("/sys/devices/system/cpu/cpu" + c + "/cpuidle/state" + st + "/time");
                if (v == null) break;
                try {
                    idle += Long.parseLong(v.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return UNREADABLE;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev, dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        return clamp((int) Math.round((dw - di) * 100.0 / dw));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // ---- memory ----

    /** {used, total} in bytes from /proc/meminfo; used = total - available. -1s when unreadable. */
    private static long[] memory() {
        long total = -1, avail = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            for (String l; (l = r.readLine()) != null && (total < 0 || avail < 0); ) {
                if (l.startsWith("MemTotal:")) total = kb(l);
                else if (l.startsWith("MemAvailable:")) avail = kb(l);
            }
        } catch (Exception ignored) {
        }
        if (total <= 0 || avail < 0) return new long[] {-1, -1};
        return new long[] {(total - avail) * 1024, total * 1024};
    }

    private static long kb(String line) {
        String[] t = line.trim().split("\\s+");
        try {
            return Long.parseLong(t[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String firstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
