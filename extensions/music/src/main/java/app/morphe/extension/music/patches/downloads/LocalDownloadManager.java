/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.scrobbling.ScrobbleManager;
import app.morphe.extension.music.patches.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailEntry;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/** Downloads resolved InnerTube audio streams and auto-downloads entire playlists (including Liked Music). */
public final class LocalDownloadManager {

    public interface DownloadListener {
        void onDownloadStateChanged(String videoId, boolean completed);
    }

    private static final Set<DownloadListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final ExecutorService PLAYLIST_QUEUE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService SEGMENT_POOL = Executors.newFixedThreadPool(8);

    private static final String DOWNLOAD_CHANNEL = "morphe_audio_downloads";
    private static final String USER_AGENT =
            "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip";
    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int SEGMENT_COUNT = 4;
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final long NOTIFICATION_INTERVAL_MILLISECONDS = 900;
    private static final AtomicLong lastNotificationTime = new AtomicLong();

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 15_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 30_000;
    private static final int MAX_STALLED_ATTEMPTS = 4;
    private static final int MAX_CONTINUATION_PAGES = 15;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final Pattern PLAYLIST_PARAM = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern BROWSE_PLAYLIST = Pattern.compile("(?:/playlist/|/browse/)(VL[A-Za-z0-9_-]+|PL[A-Za-z0-9_-]+|OLAK[A-Za-z0-9_-]+|LM|LL)");
    private static final Pattern JSON_VIDEO_ID = Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern CONTINUATION_TOKEN = Pattern.compile("\"continuation\"\\s*:\\s*\"([A-Za-z0-9_%=-]{16,})\"");
    private static final Pattern DIRECT_VIDEO_ID = Pattern.compile("(?:v=|youtu\\.be/|/watch\\?v=|/shorts/)([A-Za-z0-9_-]{11})");
    private static final Pattern SAPISID_PATTERN = Pattern.compile("(?:^|;\\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)");

    private static final String THUMBNAIL_URL = "https://i.ytimg.com/vi/";

    private static final Set<String> ACTIVE_DOWNLOADS = ConcurrentHashMap.newKeySet();

    private LocalDownloadManager() {
    }

    public static void addListener(DownloadListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(DownloadListener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean isDownloading(String videoId) {
        return ACTIVE_DOWNLOADS.contains(videoId);
    }

    public static int getActiveDownloadCount() {
        return ACTIVE_DOWNLOADS.size();
    }

    private static void notifyListeners(String videoId, boolean completed) {
        for (DownloadListener listener : LISTENERS) {
            listener.onDownloadStateChanged(videoId, completed);
        }
    }

    public static void enqueue(String videoId) {
        enqueue(videoId, null, true);
    }

    public static void enqueue(String videoId, @Nullable String targetPlaylistName, boolean showToasts) {
        if (!VIDEO_ID.matcher(videoId).matches()) {
            Logger.printDebug(() -> "Refusing to download an unusable id: " + videoId);
            if (showToasts) Utils.showToastShort(str("morphe_music_downloads_unknown_track"));
            return;
        }

        final boolean isPlayingTrack = videoId.equals(VideoInformation.getVideoId());
        ScrobbleManager metadata = ScrobbleManager.getInstance();

        String title = isPlayingTrack ? metadata.getCurrentTitle() : null;
        String artist = isPlayingTrack ? metadata.getCurrentArtist() : null;
        String album = isPlayingTrack ? metadata.getCurrentAlbum() : null;
        int duration = isPlayingTrack ? metadata.getCurrentDurationSeconds() : 0;
        Bitmap artwork = isPlayingTrack ? metadata.getCurrentArtwork() : null;

        Utils.submitOnBackgroundThread(() -> {
            downloadBlocking(videoId, title, artist, album, duration, artwork, targetPlaylistName, showToasts);
            return null;
        });
    }

    public static void syncPlaylists(File musicRoot, @Nullable String playlistName, boolean notifyStart) {
        PLAYLIST_QUEUE_EXECUTOR.submit(() -> {
            List<String> missing = OfflineStorage.getMissingVideoIds(musicRoot, playlistName);
            if (missing.isEmpty()) {
                if (notifyStart) {
                    Utils.runOnMainThread(() -> Utils.showToastShort("Listedeki tüm şarkılar zaten indirilmiş"));
                }
                return;
            }
            if (notifyStart) {
                Utils.runOnMainThread(() -> Utils.showToastShort(
                        missing.size() + " şarkı otomatik indirme kuyruğuna alındı"));
            }
            for (String videoId : missing) {
                downloadBlocking(videoId, null, null, null, 0, null, playlistName, false);
            }
            if (notifyStart) {
                Utils.runOnMainThread(() -> Utils.showToastShort("Playlist otomatik indirmesi tamamlandı"));
            }
        });
    }

    public static void fetchAndDownloadPlaylist(File musicRoot, String playlistName, String rawInput) {
        PLAYLIST_QUEUE_EXECUTOR.submit(() -> {
            try {
                List<String> resolvedIds = resolvePlaylistOrTrackIds(rawInput);
                if (resolvedIds.isEmpty()) {
                    Utils.runOnMainThread(() -> Utils.showToastShort(
                            "Şarkı bulunamadı. Beğenilenler (LM) veya gizli listeler için listeyi 'Liste Dışı' yapın ya da şarkı linklerini yapıştırın."));
                    return;
                }

                OfflineStorage.createPlaylist(musicRoot, playlistName, rawInput);
                OfflineStorage.addTracksToPlaylist(musicRoot, playlistName, resolvedIds);
                notifyListeners("", false);

                Utils.runOnMainThread(() -> Utils.showToastShort(
                        "\"" + playlistName + "\" listesinde " + resolvedIds.size() + " şarkı bulundu, indiriliyor..."));

                int count = 0;
                for (String id : resolvedIds) {
                    count++;
                    final int index = count;
                    showDownloadNotification("playlist_sync_" + playlistName,
                            playlistName + " (" + index + "/" + resolvedIds.size() + ")",
                            (index * 100) / resolvedIds.size(),
                            true,
                            "Oynatma listesi otomatik indiriliyor...");
                    downloadBlocking(id, null, null, null, 0, null, playlistName, false);
                }

                showDownloadNotification("playlist_sync_" + playlistName,
                        playlistName,
                        100,
                        false,
                        resolvedIds.size() + " şarkı başarıyla indirildi");
                Utils.runOnMainThread(() -> Utils.showToastShort(
                        "\"" + playlistName + "\" listesindeki tüm şarkılar indirildi!"));
            } catch (Exception ex) {
                Logger.printException(() -> "Playlist auto-download failed: " + rawInput, ex);
                Utils.runOnMainThread(() -> Utils.showToastShort("Playlist indirilirken hata oluştu"));
            }
        });
    }

    private static List<String> resolvePlaylistOrTrackIds(String input) {
        Set<String> ids = new LinkedHashSet<>();
        if (input == null || input.isBlank()) return new ArrayList<>();

        String trimmed = input.trim();

        // 1. Doğrudan video linklerini öncelikle ayıkla (watch?v=...&list=LM gibi durumlarda videoyu kaçırmamak için)
        Matcher directMatcher = DIRECT_VIDEO_ID.matcher(trimmed);
        while (directMatcher.find()) {
            ids.add(directMatcher.group(1));
        }

        // 2. Playlist ID tespiti (LM, VLLM, LL, VLLL, FEmusic_liked_videos, PL..., OLAK..., RD...)
        String listId = extractPlaylistId(trimmed);

        if (listId != null) {
            boolean isLikedList = listId.equalsIgnoreCase("LM")
                    || listId.equalsIgnoreCase("VLLM")
                    || listId.equalsIgnoreCase("LL")
                    || listId.equalsIgnoreCase("VLLL")
                    || listId.equalsIgnoreCase("FEmusic_liked_videos");

            // A) InnerTube Browse & Next API üzerinden çözümle (sayfalama ve oturum çerezi destekli)
            if (isLikedList) {
                fetchInnerTubeBrowseIds("VLLM", ids);
                fetchInnerTubeBrowseIds("FEmusic_liked_videos", ids);
                fetchInnerTubeBrowseIds("VLLL", ids);
                fetchRemotePlaylistWebPage("LL", true, ids);
                fetchRemotePlaylistWebPage("LM", true, ids);
            } else {
                String browseId = listId.startsWith("VL") ? listId : "VL" + listId;
                fetchInnerTubeBrowseIds(browseId, ids);
                if (ids.isEmpty()) {
                    fetchRemotePlaylistWebPage(listId, false, ids);
                }
            }

            // Eğer o an çalan şarkı varsa ve LM boş döndüyse en azından aktif parçayı yakala
            if (ids.isEmpty() && isLikedList) {
                String activeId = VideoInformation.getVideoId();
                if (activeId != null && activeId.matches("[A-Za-z0-9_-]{11}")) {
                    ids.add(activeId);
                }
            }
        }

        // 3. Boşluk, virgül veya satır sonu ile ayrılmış 11 karakterli ID'ler
        if (ids.isEmpty()) {
            String[] tokens = trimmed.split("[\\s,;]+");
            for (String token : tokens) {
                if (token.matches("[A-Za-z0-9_-]{11}")) {
                    ids.add(token);
                }
            }
        }

        return new ArrayList<>(ids);
    }

    @Nullable
    private static String extractPlaylistId(String trimmed) {
        Matcher listMatcher = PLAYLIST_PARAM.matcher(trimmed);
        if (listMatcher.find()) {
            String raw = listMatcher.group(1);
            return raw != null && raw.startsWith("VL") && raw.length() > 4 ? raw.substring(2) : raw;
        }
        Matcher browseMatcher = BROWSE_PLAYLIST.matcher(trimmed);
        if (browseMatcher.find()) {
            String raw = browseMatcher.group(1);
            return raw != null && raw.startsWith("VL") && raw.length() > 4 ? raw.substring(2) : raw;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.equals("LM") || upper.equals("VLLM") || upper.equals("LL")
                || upper.equals("VLLL") || upper.equals("FEMUSIC_LIKED_VIDEOS")) {
            return upper;
        }
        if (trimmed.startsWith("VL") && trimmed.length() > 4 && !trimmed.contains(" ")) {
            return trimmed.substring(2);
        }
        if ((trimmed.startsWith("PL") || trimmed.startsWith("OLAK") || trimmed.startsWith("RD") || trimmed.startsWith("UU") || trimmed.startsWith("FL"))
                && !trimmed.contains(" ") && trimmed.length() >= 6) {
            return trimmed;
        }
        return null;
    }

    private static void fetchInnerTubeBrowseIds(String browseId, Set<String> outIds) {
        try {
            String continuation = null;
            for (int page = 0; page < MAX_CONTINUATION_PAGES; page++) {
                String endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
                String payload;
                if (page == 0) {
                    payload = "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20260101.01.00\",\"hl\":\"tr\",\"gl\":\"TR\"}},\"browseId\":\"" + browseId + "\"}";
                } else {
                    if (continuation == null || continuation.isEmpty()) break;
                    payload = "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20260101.01.00\",\"hl\":\"tr\",\"gl\":\"TR\"}},\"continuation\":\"" + continuation + "\"}";
                }

                String response = postInnerTubeJson(endpoint, payload);
                if (response == null || response.isEmpty()) break;

                int beforeCount = outIds.size();
                Matcher m = JSON_VIDEO_ID.matcher(response);
                while (m.find()) {
                    outIds.add(m.group(1));
                }

                Matcher contMatcher = CONTINUATION_TOKEN.matcher(response);
                String nextToken = null;
                while (contMatcher.find()) {
                    String candidate = contMatcher.group(1);
                    if (!candidate.equals(continuation)) {
                        nextToken = candidate;
                        break;
                    }
                }
                if (nextToken == null || outIds.size() == beforeCount) break;
                continuation = nextToken;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "InnerTube browse resolution fallback: " + browseId, ex);
        }
    }

    @Nullable
    private static String postInnerTubeJson(String url, String jsonPayload) {
        HttpURLConnection conn = null;
        try {
            conn = Requester.openConnection(url);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            conn.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", WEB_USER_AGENT);
            conn.setRequestProperty("Origin", "https://music.youtube.com");
            conn.setRequestProperty("Referer", "https://music.youtube.com/");
            conn.setRequestProperty("X-YouTube-Client-Name", "67");
            conn.setRequestProperty("X-YouTube-Client-Version", "1.20260101.01.00");

            applySessionCookiesAndAuth(conn, "https://music.youtube.com");

            byte[] body = jsonPayload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            return readStreamToString(conn.getInputStream());
        } catch (Exception ex) {
            Logger.printDebug(() -> "postInnerTubeJson failed", ex);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void fetchRemotePlaylistWebPage(String listId, boolean musicDomain, Set<String> ids) {
        HttpURLConnection conn = null;
        try {
            String base = musicDomain ? "https://music.youtube.com/playlist?list=" : "https://www.youtube.com/playlist?list=";
            conn = Requester.openConnection(base + listId + "&hl=en");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            conn.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            conn.setRequestProperty("User-Agent", WEB_USER_AGENT);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            applySessionCookiesAndAuth(conn, musicDomain ? "https://music.youtube.com" : "https://www.youtube.com");

            String html = readStreamToString(conn.getInputStream());
            Matcher m = JSON_VIDEO_ID.matcher(html);
            while (m.find()) {
                ids.add(m.group(1));
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch remote playlist page: " + listId, ex);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void applySessionCookiesAndAuth(HttpURLConnection conn, String origin) {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) return;
            String cookies = cm.getCookie(origin);
            if (cookies == null || cookies.isBlank()) {
                cookies = cm.getCookie("https://www.youtube.com");
            }
            if (cookies != null && !cookies.isBlank()) {
                conn.setRequestProperty("Cookie", cookies);
                Matcher sapisid = SAPISID_PATTERN.matcher(cookies);
                if (sapisid.find()) {
                    long timestamp = System.currentTimeMillis() / 1000L;
                    String raw = timestamp + " " + sapisid.group(1) + " " + origin;
                    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                    byte[] digest = sha1.digest(raw.getBytes(StandardCharsets.UTF_8));
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
                    conn.setRequestProperty("Authorization", "SAPISIDHASH " + timestamp + "_" + hex);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readStreamToString(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream buffer = new ByteArrayOutputStream(32768)) {
            byte[] chunk = new byte[16384];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void downloadBlocking(String videoId, @Nullable String title, @Nullable String artist,
                                         @Nullable String album, int duration, @Nullable Bitmap artwork,
                                         @Nullable String targetPlaylistName, boolean showToasts) {
        Context context = Utils.getContext();
        File directory = new File(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");

        if (targetPlaylistName != null && !targetPlaylistName.isBlank()) {
            OfflineStorage.addTrackToPlaylist(directory, targetPlaylistName, videoId);
        }

        if (OfflineStorage.isTrackDownloaded(directory, videoId)) {
            Logger.printDebug(() -> "Already downloaded: " + videoId);
            if (showToasts) Utils.showToastShort(str("morphe_music_downloads_already_saved"));
            notifyListeners(videoId, true);
            return;
        }

        if (!ACTIVE_DOWNLOADS.add(videoId)) {
            if (showToasts) Utils.showToastShort(str("morphe_music_downloads_already_running"));
            return;
        }

        notifyListeners(videoId, false);

        try {
            ResolvedStream resolved = resolveStream(videoId);
            if (resolved == null || resolved.format().getUrl().isBlank()) {
                throw new IllegalStateException("Audio stream unavailable");
            }

            Format format = resolved.format();
            VideoDetails details = resolved.details();

            title = firstNotBlank(title, details.getTitle(), videoId);
            artist = firstNotBlank(artist, stripTopic(details.getOwnerChannelName()), "");
            if (duration <= 0) duration = details.getLengthSeconds();
            if (artwork == null) artwork = resolveArtwork(videoId, details);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create download directory");
            }

            String extension = format.getMimeType().contains("mp4") ? ".m4a" : ".webm";
            File destination = new File(directory, videoId + extension);
            File temporary = new File(directory, destination.getName() + ".part");

            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("Could not reset partial audio file");
            }

            downloadWithResume(videoId, title, format.getUrl(), temporary,
                    format.getContentLength(), showToasts);

            if (destination.exists() && !destination.delete()) {
                throw new IllegalStateException("Could not replace audio file");
            }
            if (!temporary.renameTo(destination)) {
                throw new IllegalStateException("Could not finish audio file");
            }

            OfflineTrack.save(directory, videoId, title, artist, album, duration, artwork);
            if (showToasts) Utils.showToastShort(str("morphe_music_downloads_complete"));
        } catch (Exception ex) {
            Logger.printException(() -> "Local audio download failed: " + videoId, ex);
            awaitNotificationSlot();
            showDownloadNotification(videoId, title, 0, false, str("morphe_music_downloads_failed"));
            if (showToasts) Utils.showToastShort(str("morphe_music_downloads_failed"));
        } finally {
            ACTIVE_DOWNLOADS.remove(videoId);
            notifyListeners(videoId, true);
        }
    }

    private record ResolvedStream(Format format, VideoDetails details) {
    }

    private static String firstNotBlank(@Nullable String value, String fallback, String lastResort) {
        if (value != null && !value.isBlank()) return value;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return lastResort;
    }

    private static String stripTopic(String channelName) {
        if (channelName == null) return "";
        return channelName.endsWith(" - Topic")
                ? channelName.substring(0, channelName.length() - " - Topic".length())
                : channelName;
    }

    private static boolean canPostProgress() {
        long now = System.currentTimeMillis();
        long previous = lastNotificationTime.get();
        return now - previous >= NOTIFICATION_INTERVAL_MILLISECONDS
                && lastNotificationTime.compareAndSet(previous, now);
    }

    private static void awaitNotificationSlot() {
        while (true) {
            long now = System.currentTimeMillis();
            long previous = lastNotificationTime.get();
            long remaining = NOTIFICATION_INTERVAL_MILLISECONDS - (now - previous);

            if (remaining <= 0) {
                if (lastNotificationTime.compareAndSet(previous, now)) return;
                continue;
            }

            try {
                //noinspection BusyWait
                Thread.sleep(remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Nullable
    private static Bitmap resolveArtwork(String videoId, VideoDetails details) {
        Bitmap artwork = downloadArtwork(largestThumbnail(details));
        if (artwork != null) return artwork;

        artwork = downloadArtwork(THUMBNAIL_URL + videoId + "/maxresdefault.jpg");
        return artwork != null ? artwork : downloadArtwork(THUMBNAIL_URL + videoId + "/hqdefault.jpg");
    }

    @Nullable
    private static String largestThumbnail(VideoDetails details) {
        String url = null;
        int widest = 0;
        for (ThumbnailEntry entry : details.getThumbnail().getThumbnailsList()) {
            if (entry.getWidth() >= widest && !entry.getUrl().isBlank()) {
                widest = entry.getWidth();
                url = entry.getUrl();
            }
        }
        return url;
    }

    @Nullable
    private static Bitmap downloadArtwork(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        try (InputStream input = Requester.openConnection(url).getInputStream()) {
            return BitmapFactory.decodeStream(input);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch artwork: " + url, ex);
            return null;
        }
    }

    private static void downloadWithResume(String videoId, String title, String url,
                                           File temporary, long expectedLength, boolean showToasts) throws Exception {
        if (showToasts) Utils.showToastShort(str("morphe_music_downloads_started"));
        awaitNotificationSlot();
        showDownloadNotification(videoId, title, 0, true, str("morphe_music_downloads_starting"));

        if (expectedLength <= 0) expectedLength = probeContentLength(url);
        final long totalLength = expectedLength;

        if (totalLength <= 0) {
            downloadUnknownLength(url, temporary);
        } else {
            downloadInParallel(videoId, title, url, temporary, totalLength);
        }

        if (totalLength > 0 && temporary.length() != totalLength) {
            throw new IllegalStateException(
                    "Incomplete audio file: " + temporary.length() + "/" + totalLength);
        }
        awaitNotificationSlot();
        showDownloadNotification(videoId, title, 100, false, str("morphe_music_downloads_complete"));
    }

    private static void downloadInParallel(String videoId, String title, String url,
                                           File temporary, long totalLength) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(temporary, "rw")) {
            file.setLength(totalLength);
        }

        AtomicLong downloaded = new AtomicLong();
        long segmentSize = (totalLength + SEGMENT_COUNT - 1) / SEGMENT_COUNT;

        List<Future<?>> futures = new ArrayList<>(SEGMENT_COUNT);
        for (int index = 0; index < SEGMENT_COUNT; index++) {
            long start = index * segmentSize;
            long end = Math.min(totalLength - 1, start + segmentSize - 1);
            if (start > end) continue;

            futures.add(SEGMENT_POOL.submit(() -> {
                downloadRange(url, temporary, start, end, downloaded, bytes -> {
                    if (!canPostProgress()) return;
                    int progress = (int) Math.min(99, bytes * 100 / totalLength);
                    showDownloadNotification(videoId, title, progress, true,
                            str("morphe_music_downloads_progress", progress));
                });
                return null;
            }));
        }

        for (Future<?> future : futures) {
            future.get();
        }
    }

    private interface ProgressCallback {
        void update(long bytes);
    }

    private static void downloadRange(String url, File destination, long start, long end,
                                      AtomicLong downloaded, ProgressCallback progress) throws Exception {
        long position = start;
        int failures = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        try (RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
            while (position <= end) {
                long before = position;
                HttpURLConnection connection = openAudioConnection(url, position, end);

                try {
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IllegalStateException(
                                "Range request returned HTTP " + connection.getResponseCode());
                    }

                    try (InputStream input = connection.getInputStream()) {
                        output.seek(position);
                        while (position <= end) {
                            int read = input.read(buffer, 0,
                                    (int) Math.min(buffer.length, end - position + 1));
                            if (read < 0) break;

                            output.write(buffer, 0, read);
                            position += read;
                            progress.update(downloaded.addAndGet(read));
                        }
                    }
                } catch (IOException ex) {
                    Logger.printDebug(() -> "Audio range interrupted; retrying", ex);
                } finally {
                    connection.disconnect();
                }

                failures = position > before ? 0 : failures + 1;
                if (failures >= MAX_STALLED_ATTEMPTS) {
                    throw new IllegalStateException("Download range stalled at " + position);
                }
            }
        }
    }

    private static long probeContentLength(String url) {
        HttpURLConnection connection = null;
        try {
            connection = openAudioConnection(url, 0, 0);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;

            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange == null) return -1;

            int slash = contentRange.lastIndexOf('/');
            return slash < 0 ? -1 : Long.parseLong(contentRange.substring(slash + 1));
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not probe audio length", ex);
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openAudioConnection(String url, long start, long end) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://music.youtube.com/");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        return connection;
    }

    private static void downloadUnknownLength(String url, File destination) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    static void showDownloadNotification(String key, String title, int progress,
                                         boolean ongoing, String text) {
        Utils.runOnMainThread(() -> postDownloadNotification(key, title, progress, ongoing, text));
    }

    @SuppressLint("NotificationPermission")
    private static void postDownloadNotification(String key, String title, int progress,
                                                 boolean ongoing, String text) {
        Context context = Utils.getContext();
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        manager.createNotificationChannel(new NotificationChannel(DOWNLOAD_CHANNEL,
                str("morphe_music_downloads_notification_channel"),
                NotificationManager.IMPORTANCE_LOW));

        Notification.Builder builder = new Notification.Builder(context, DOWNLOAD_CHANNEL);
        builder.setSmallIcon(ongoing
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title == null || title.isBlank() ? "YouTube Music" : title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);

        if (ongoing) builder.setProgress(100, Math.max(0, progress), progress < 0);
        manager.notify(key.hashCode(), builder.build());
    }

    @Nullable
    private static ResolvedStream resolveStream(String videoId) {
        StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
        if (request == null) {
            Logger.printDebug(() -> "No cached streaming request for download, resolving: " + videoId);
            request = StreamingDataRequest.fetchRequestForDownload(videoId,
                    SpoofVideoStreamsPatch.getAvailableClients(),
                    Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
        }

        StreamingDataRequest.StreamData stream = request.getStream();
        if (stream == null) {
            Logger.printDebug(() -> "No stream data resolved for " + videoId);
            return null;
        }

        try {
            PlayerResponse response = PlayerResponse.parseFrom(stream.streamingData());
            Format best = response.getStreamingData()
                    .getAdaptiveFormatsList()
                    .stream()
                    .filter(format -> format.getMimeType().startsWith("audio/")
                            && !format.getUrl().isBlank())
                    .max(Comparator.comparingInt(Format::getBitrate))
                    .orElse(null);
            return best == null ? null : new ResolvedStream(best, response.getVideoDetails());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not parse audio formats: " + videoId, ex);
            return null;
        }
    }
}