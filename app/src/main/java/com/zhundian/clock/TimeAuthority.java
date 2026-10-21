package com.zhundian.clock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class TimeAuthority {
    private static final long NTP_EPOCH_SECONDS = 2208988800L;
    private static final long MAX_ANCHOR_AGE_MS = 60 * 60 * 1000L;
    private static final String[] SERVERS = {"time.cloudflare.com", "ntp.aliyun.com"};
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "clock-calibration");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Anchor anchor;
    private static volatile boolean syncing;
    private static volatile String failure = "尚未校准";
    private static long lastAttemptElapsed;
    private static boolean networkListenerRegistered;
    private static boolean initialized;

    private TimeAuthority() {}

    static synchronized void initialize(Context context) {
        if (initialized) return;
        initialized = true;
        SharedPreferences prefs = context.getSharedPreferences("clock", Context.MODE_PRIVATE);
        long mono = prefs.getLong("calibrationMonoNanos", 0);
        long savedBoot = prefs.getLong("calibrationBootEpoch", 0);
        long age = SystemClock.elapsedRealtimeNanos() - mono;
        long currentBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        if (mono <= 0 || age < 0 || age / 1_000_000L >= MAX_ANCHOR_AGE_MS
                || Math.abs(currentBoot - savedBoot) > 300_000) return;
        anchor = new Anchor(prefs.getLong("calibrationEpochMillis", 0), mono,
                prefs.getLong("calibrationDelayMillis", 0),
                prefs.getInt("calibrationUncertaintyMillis", Integer.MAX_VALUE),
                prefs.getInt("calibrationSources", 1), prefs.getBoolean("calibrationNetwork", false));
    }

    static synchronized void clearAfterBoot(Context context) {
        anchor = null;
        initialized = true;
        context.getSharedPreferences("clock", Context.MODE_PRIVATE).edit()
                .remove("calibrationMonoNanos").remove("calibrationEpochMillis")
                .remove("calibrationBootEpoch").commit();
    }

    static long nowMillis() {
        Anchor current = anchor;
        long elapsed = SystemClock.elapsedRealtimeNanos();
        if (current != null && (elapsed - current.monotonicNanos) / 1_000_000L < MAX_ANCHOR_AGE_MS) {
            return current.epochMillis + (elapsed - current.monotonicNanos) / 1_000_000L;
        }
        return System.currentTimeMillis();
    }

    static String status() {
        Anchor current = anchor;
        if (current == null || ageMillis(current) >= MAX_ANCHOR_AGE_MS) {
            String reason = current == null ? failure : "校准已过期";
            return syncing ? "网络校准中" : reason + " · 使用手机时间";
        }
        long ageMinutes = ageMillis(current) / 60_000L;
        String source = current.network ? "网络校准" : "系统网络时间";
        String quality = current.network && current.sources == 2 && current.uncertaintyMillis <= 50
                && ageMinutes < 5 ? "较稳定" : "仅供参考";
        String warning = "尚未校准".equals(failure) ? "" : " · 更新失败";
        return source + " · " + quality + " · " + ageMinutes + "分钟前"
                + (syncing ? " · 更新中" : warning);
    }

    static String shortStatus() {
        Anchor current = anchor;
        if (current == null || ageMillis(current) >= MAX_ANCHOR_AGE_MS) return "手机时间";
        return current.network ? "网络校准" : "系统网络时间";
    }

    static String details() {
        Anchor current = anchor;
        if (current == null || ageMillis(current) >= MAX_ANCHOR_AGE_MS) return status();
        long offset = nowMillis() - System.currentTimeMillis();
        String direction = offset >= 0 ? "手机慢约 " : "手机快约 ";
        return direction + Math.abs(offset) + " ms · 测量延迟 " + current.delayMillis + " ms · "
                + current.sources + " 个来源\n" + status();
    }

    static synchronized void syncIfDue(Context context, boolean force) {
        Context app = context.getApplicationContext();
        initialize(app);
        registerNetworkListener(app);
        long now = SystemClock.elapsedRealtime();
        if (syncing || (lastAttemptElapsed != 0 && now - lastAttemptElapsed < (force ? 10_000 : interval(context)))) return;
        long target = context.getSharedPreferences("clock", Context.MODE_PRIVATE).getLong("targetMillis", 0);
        long remaining = target - nowMillis();
        if (remaining > 0 && remaining <= 10_000 && anchor != null) return;
        lastAttemptElapsed = now;
        syncing = true;
        WORKER.execute(() -> {
            try {
                calibrate(app);
            } finally {
                syncing = false;
                ReminderScheduler.schedule(app);
            }
        });
    }

    private static void registerNetworkListener(Context app) {
        if (networkListenerRegistered) return;
        ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        try {
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    syncIfDue(app, true);
                }
            });
            networkListenerRegistered = true;
        } catch (RuntimeException ignored) {
            // Time display remains available when network monitoring is restricted.
        }
    }

    private static long interval(Context context) {
        long target = context.getSharedPreferences("clock", Context.MODE_PRIVATE).getLong("targetMillis", 0);
        long remaining = target - nowMillis();
        if (remaining > 10_000 && remaining <= 30_000) return 20_000;
        if (remaining > 30_000 && remaining <= 120_000) return 60_000;
        return 10 * 60_000L;
    }

    private static void calibrate(Context context) {
        List<Sample> best = new ArrayList<>();
        for (String server : SERVERS) {
            List<Sample> samples = new ArrayList<>();
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Sample sample = query(server);
                    if (sample != null) samples.add(sample);
                } catch (Exception ignored) {
                    break;
                }
            }
            samples.sort(Comparator.comparingLong(sample -> sample.delayMillis));
            if (!samples.isEmpty()) best.add(samples.get(0));
        }
        if (best.size() == 2 && Math.abs(best.get(0).epochAt(SystemClock.elapsedRealtimeNanos())
                - best.get(1).epochAt(SystemClock.elapsedRealtimeNanos())) > 100) {
            failure = "时间源不一致";
            fallbackNetworkClock(context);
            return;
        }
        if (best.isEmpty()) {
            failure = "网络校准不可用";
            fallbackNetworkClock(context);
            return;
        }
        long mono = SystemClock.elapsedRealtimeNanos();
        long epoch = 0;
        long delay = 0;
        for (Sample sample : best) {
            epoch += sample.epochAt(mono);
            delay += sample.delayMillis;
        }
        epoch /= best.size();
        delay /= best.size();
        int uncertainty = (int) Math.max(delay / 2, best.size() == 2
                ? Math.abs(best.get(0).epochAt(mono) - best.get(1).epochAt(mono)) / 2 : 100);
        publish(context, new Anchor(epoch, mono, delay, uncertainty, best.size(), true));
        failure = "尚未校准";
    }

    private static void fallbackNetworkClock(Context context) {
        if (anchor != null && ageMillis(anchor) < MAX_ANCHOR_AGE_MS) return;
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            long mono = SystemClock.elapsedRealtimeNanos();
            long epoch = SystemClock.currentNetworkTimeClock().millis();
            publish(context, new Anchor(epoch, mono, 0, Integer.MAX_VALUE, 1, false));
        } catch (DateTimeException ignored) {
            anchor = null;
        }
    }

    private static void publish(Context context, Anchor value) {
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        context.getSharedPreferences("clock", Context.MODE_PRIVATE).edit()
                .putLong("calibrationEpochMillis", value.epochMillis)
                .putLong("calibrationMonoNanos", value.monotonicNanos)
                .putLong("calibrationBootEpoch", boot)
                .putLong("calibrationDelayMillis", value.delayMillis)
                .putInt("calibrationUncertaintyMillis", value.uncertaintyMillis)
                .putInt("calibrationSources", value.sources)
                .putBoolean("calibrationNetwork", value.network).commit();
        anchor = value;
    }

    private static long ageMillis(Anchor value) {
        return Math.max(0, (SystemClock.elapsedRealtimeNanos() - value.monotonicNanos) / 1_000_000L);
    }

    private static Sample query(String hostname) throws Exception {
        InetAddress address = InetAddress.getByName(hostname);
        byte[] request = new byte[48];
        request[0] = 0x23;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(address, 123);
            socket.setSoTimeout(850);
            long sentWall = System.currentTimeMillis();
            long sentMono = SystemClock.elapsedRealtimeNanos();
            writeTimestamp(request, 40, sentWall);
            socket.send(new DatagramPacket(request, request.length));
            byte[] response = new byte[48];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            long receivedMono = SystemClock.elapsedRealtimeNanos();
            if (packet.getLength() < 48 || (response[0] & 7) != 4 || (response[0] >>> 6 & 3) == 3
                    || (response[1] & 255) == 0 || (response[1] & 255) > 15
                    || !Arrays.equals(Arrays.copyOfRange(request, 40, 48), Arrays.copyOfRange(response, 24, 32))) {
                return null;
            }
            long receivedWall = sentWall + (receivedMono - sentMono) / 1_000_000L;
            long serverReceive = readTimestamp(response, 32);
            long serverSend = readTimestamp(response, 40);
            long delay = receivedWall - sentWall - (serverSend - serverReceive);
            if (serverReceive <= 0 || serverSend < serverReceive || delay < 0 || delay > 500) return null;
            long offset = ((serverReceive - sentWall) + (serverSend - receivedWall)) / 2;
            return new Sample(receivedWall + offset, receivedMono, delay);
        }
    }

    private static void writeTimestamp(byte[] data, int position, long unixMillis) {
        long seconds = unixMillis / 1000 + NTP_EPOCH_SECONDS;
        long fraction = (unixMillis % 1000) * 0x100000000L / 1000;
        for (int i = 0; i < 4; i++) {
            data[position + i] = (byte) (seconds >>> (24 - 8 * i));
            data[position + 4 + i] = (byte) (fraction >>> (24 - 8 * i));
        }
    }

    private static long readTimestamp(byte[] data, int position) {
        long seconds = 0;
        long fraction = 0;
        for (int i = 0; i < 4; i++) {
            seconds = seconds << 8 | data[position + i] & 255L;
            fraction = fraction << 8 | data[position + 4 + i] & 255L;
        }
        return (seconds - NTP_EPOCH_SECONDS) * 1000 + (fraction * 1000 >>> 32);
    }

    private static final class Sample {
        final long epochMillis;
        final long monotonicNanos;
        final long delayMillis;

        Sample(long epochMillis, long monotonicNanos, long delayMillis) {
            this.epochMillis = epochMillis;
            this.monotonicNanos = monotonicNanos;
            this.delayMillis = delayMillis;
        }

        long epochAt(long mono) { return epochMillis + (mono - monotonicNanos) / 1_000_000L; }
    }

    private static final class Anchor {
        final long epochMillis;
        final long monotonicNanos;
        final long delayMillis;
        final int uncertaintyMillis;
        final int sources;
        final boolean network;

        Anchor(long epochMillis, long monotonicNanos, long delayMillis, int uncertaintyMillis,
                int sources, boolean network) {
            this.epochMillis = epochMillis;
            this.monotonicNanos = monotonicNanos;
            this.delayMillis = delayMillis;
            this.uncertaintyMillis = uncertaintyMillis;
            this.sources = sources;
            this.network = network;
        }
    }
}
