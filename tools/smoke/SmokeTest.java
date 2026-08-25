package com.typebit.engine;

import java.security.MessageDigest;

/**
 * Headless smoke test for the typebit_native JNI bridge.
 *
 * Declares the exact same native methods as NativeBridgeKt (Kotlin) so the
 * JNI symbol names match the Rust cdylib, then exercises the whole engine
 * lifecycle without any UI or Gradle.
 *
 * Build & run:
 *   javac -d smoke-out tools/smoke/SmokeTest.java
 *   java -Djava.library.path=native/target/release -cp smoke-out com.typebit.engine.SmokeTest
 */
final class NativeBridgeKt {
    static {
        System.loadLibrary("typebit_native");
    }

    static native long nativeCreateEngine(String configJson, String saveDir);
    static native void nativeDestroyEngine(long handle);
    static native String nativeAddTorrent(long handle, byte[] data, String saveDir);
    static native String nativeAddMagnet(long handle, String uri, String saveDir);
    static native int nativeStart(long handle, String hash);
    static native int nativePause(long handle, String hash);
    static native int nativeResume(long handle, String hash);
    static native int nativeRemove(long handle, String hash);
    static native double nativeProgress(long handle, String hash);
    static native long nativeDownloaded(long handle, String hash);
    static native boolean nativeIsComplete(long handle, String hash);
    static native String nativeTorrentInfo(long handle, String hash);
    static native String nativeTorrentStates(long handle);
    static native String nativeSnapshot(long handle);
    static native int nativeTorrentCount(long handle);
    static native int nativeDhtNodeCount(long handle);
    static native String nativePeerId(long handle);
    static native String nativeTotals(long handle);
    static native int nativeSetGlobalLimits(long handle, long down, long up);
    static native int nativeSetSessionConfig(long handle, String configJson);
    static native byte[] nativeSaveState(long handle);
    static native int nativeLoadState(long handle, byte[] data);
    static native String nativeTakeEvents(long handle);
    static native String nativeTakeLogs(long handle);
}

public class SmokeTest {

    public static void main(String[] args) throws Exception {
        new java.io.File("./smoke-data").mkdirs();
        byte[] torrent = makeTorrent();
        java.nio.file.Files.write(java.nio.file.Paths.get("./smoke-torrent.bin"), torrent);
        System.out.println("torrent bytes: " + bytesToHex(torrent));

        long h = NativeBridgeKt.nativeCreateEngine(
            "{\"listen_port\":6899,\"cache_bytes\":1048576,\"dht_enabled\":false," +
            "\"max_peers\":20,\"request_pipeline\":16}",
            "./smoke-data");
        check(h != 0, "engine create");
        System.out.println("[1] engine handle = " + h);

        String hash = NativeBridgeKt.nativeAddTorrent(h, torrent, "./smoke-data");
        if (hash == null) {
            System.out.println("[2] FAILED logs: " + NativeBridgeKt.nativeTakeLogs(h));
        }
        check(hash != null && hash.length() == 40, "add torrent -> " + hash);
        System.out.println("[2] added torrent " + hash);

        String magnetHash = NativeBridgeKt.nativeAddMagnet(
            h, "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=smoke-magnet", "./smoke-data");
        check(magnetHash != null && magnetHash.length() == 40, "add magnet -> " + magnetHash);
        System.out.println("[3] added magnet " + magnetHash);

        check(NativeBridgeKt.nativeStart(h, hash) == 0, "start");
        System.out.println("[4] started");

        check(NativeBridgeKt.nativeTorrentCount(h) == 2, "torrent count == 2");
        System.out.println("[5] torrent count = " + NativeBridgeKt.nativeTorrentCount(h));

        String info = NativeBridgeKt.nativeTorrentInfo(h, hash);
        check(info != null && info.contains("\"name\":\"smoke.bin\""), "torrent info");
        System.out.println("[6] info = " + info);

        String magnetInfo = NativeBridgeKt.nativeTorrentInfo(h, magnetHash);
        check(magnetInfo != null && magnetInfo.contains("\"metadata_ready\":false"), "magnet placeholder info");
        System.out.println("[7] magnet info = " + magnetInfo);

        System.out.println("[8] peer id = " + NativeBridgeKt.nativePeerId(h));
        System.out.println("[9] dht nodes = " + NativeBridgeKt.nativeDhtNodeCount(h));

        NativeBridgeKt.nativeSetGlobalLimits(h, 1024 * 1024, 512 * 1024);
        System.out.println("[10] limits set");

        for (int i = 0; i < 5; i++) {
            Thread.sleep(150);
            String ev = NativeBridgeKt.nativeTakeEvents(h);
            if (!"[]".equals(ev)) {
                System.out.println("[11] events: " + ev);
            }
            String logs = NativeBridgeKt.nativeTakeLogs(h);
            if (!"[]".equals(logs)) {
                System.out.println("[11] logs: " + logs);
            }
        }

        System.out.println("[12] progress = " + NativeBridgeKt.nativeProgress(h, hash));
        System.out.println("[13] downloaded = " + NativeBridgeKt.nativeDownloaded(h, hash));
        System.out.println("[14] isComplete = " + NativeBridgeKt.nativeIsComplete(h, hash));
        System.out.println("[15] states = " + NativeBridgeKt.nativeTorrentStates(h));
        System.out.println("[16] totals = " + NativeBridgeKt.nativeTotals(h));

        String snap = NativeBridgeKt.nativeSnapshot(h);
        check(snap != null && snap.contains("\"torrents\":[") && snap.contains("\"dht\""), "batched snapshot");
        System.out.println("[16b] snapshot = " + snap);

        byte[] state = NativeBridgeKt.nativeSaveState(h);
        check(state != null && state.length > 0, "save state");
        System.out.println("[17] resume state bytes = " + (state == null ? -1 : state.length));

        check(NativeBridgeKt.nativeLoadState(h, state) == 0, "load state");
        System.out.println("[18] state restored");

        check(NativeBridgeKt.nativeRemove(h, hash) == 0, "remove");
        check(NativeBridgeKt.nativeTorrentCount(h) == 1, "count after remove == 1");
        System.out.println("[19] removed, remaining = " + NativeBridgeKt.nativeTorrentCount(h));

        check(NativeBridgeKt.nativeSetSessionConfig(h,
            "{\"max_peers\":50,\"use_default_trackers\":true,\"seeding_slots\":4}") == 0, "session config");
        System.out.println("[20] session config applied");

        NativeBridgeKt.nativeDestroyEngine(h);
        System.out.println("SMOKE_OK");
    }

    static void check(boolean cond, String what) {
        if (!cond) {
            System.err.println("FAILED: " + what);
            System.exit(1);
        }
    }

    /** Hand-builds a tiny single-file .torrent (one 16 KiB piece). */
    static byte[] makeTorrent() throws Exception {
        byte[] piece = new byte[16 * 1024];
        for (int i = 0; i < piece.length; i++) piece[i] = (byte) (i % 251);

        byte[] pieces = MessageDigest.getInstance("SHA-1").digest(piece);
        byte[] info = bencDict(
            bencBytes(b("name")), bencBytes(b("smoke.bin")),
            bencBytes(b("piece length")), bencInt(16 * 1024),
            bencBytes(b("length")), bencInt(piece.length),
            bencBytes(b("pieces")), bencBytes(pieces)
        );
        return bencDict(bencBytes(b("info")), info);
    }

    /** Bencode integer: `i<decimal>e`. */
    static byte[] bencInt(long v) throws Exception {
        return b("i" + v + "e");
    }

    /** Bencode string: `<len>:<bytes>`. */
    static byte[] bencBytes(byte[] raw) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(b(String.valueOf(raw.length)));
        out.write(':');
        out.write(raw, 0, raw.length);
        return out.toByteArray();
    }

    /** Bencode dictionary: `d` + key/value pairs + `e`. */
    static byte[] bencDict(byte[]... kv) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write('d');
        for (byte[] part : kv) out.write(part, 0, part.length);
        out.write('e');
        return out.toByteArray();
    }

    static byte[] b(String s) { return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII); }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
