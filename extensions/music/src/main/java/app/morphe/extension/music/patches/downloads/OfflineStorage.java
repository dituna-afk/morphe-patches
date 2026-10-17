/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;

/**
 * File access shared by the offline catalogue and local auto-downloading playlists.
 */
final class OfflineStorage {

    record Playlist(String name, List<String> videoIds, boolean autoDownload, String remoteId) {
        Playlist(String name, List<String> videoIds) {
            this(name, videoIds, true, "");
        }
    }

    private static final String PLAYLISTS_FILE = "playlists.json";

    private OfflineStorage() {
    }

    static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            //noinspection CharsetObjectCanBeUsed
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void writeText(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void writeJpeg(File file, Bitmap bitmap) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
        }
    }

    @Nullable
    static Bitmap decodeArtwork(File file, int maxSize) {
        if (!file.isFile()) return null;
        String path = file.getAbsolutePath();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / maxSize);
        return BitmapFactory.decodeFile(path, options);
    }

    static void delete(File file) {
        if (file.exists() && !file.delete()) {
            Logger.printException(() -> "Could not delete: " + file);
        }
    }

    static boolean isTrackDownloaded(File musicRoot, String videoId) {
        return new File(musicRoot, videoId + ".webm").isFile()
                || new File(musicRoot, videoId + ".m4a").isFile();
    }

    static List<Playlist> loadPlaylists(File musicRoot) {
        List<Playlist> result = new ArrayList<>();
        File file = new File(musicRoot, PLAYLISTS_FILE);
        if (!file.isFile()) return result;

        try {
            JSONArray array = new JSONArray(readText(file));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "").trim();
                if (name.isEmpty()) continue;

                boolean autoDownload = obj.optBoolean("autoDownload", true);
                String remoteId = obj.optString("remoteId", "");

                List<String> ids = new ArrayList<>();
                JSONArray tracks = obj.optJSONArray("tracks");
                if (tracks != null) {
                    for (int j = 0; j < tracks.length(); j++) {
                        String id = tracks.optString(j, "").trim();
                        if (!id.isEmpty() && !ids.contains(id)) {
                            ids.add(id);
                        }
                    }
                }
                result.add(new Playlist(name, ids, autoDownload, remoteId));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read playlists: " + file, ex);
        }
        return result;
    }

    static synchronized void savePlaylists(File musicRoot, List<Playlist> playlists) {
        try {
            if (!musicRoot.isDirectory() && !musicRoot.mkdirs()) {
                return;
            }
            JSONArray array = new JSONArray();
            for (Playlist playlist : playlists) {
                JSONArray tracks = new JSONArray();
                for (String id : playlist.videoIds()) {
                    tracks.put(id);
                }
                JSONObject obj = new JSONObject()
                        .put("name", playlist.name())
                        .put("autoDownload", playlist.autoDownload())
                        .put("remoteId", playlist.remoteId() == null ? "" : playlist.remoteId())
                        .put("tracks", tracks);
                array.put(obj);
            }
            writeText(new File(musicRoot, PLAYLISTS_FILE), array.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save playlists", ex);
        }
    }

    static synchronized boolean createPlaylist(File musicRoot, String rawName) {
        return createPlaylist(musicRoot, rawName, "");
    }

    static synchronized boolean createPlaylist(File musicRoot, String rawName, String remoteId) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) return false;

        List<Playlist> playlists = loadPlaylists(musicRoot);
        for (Playlist p : playlists) {
            if (p.name().equalsIgnoreCase(name)) return false;
        }
        playlists.add(new Playlist(name, new ArrayList<>(), true, remoteId == null ? "" : remoteId.trim()));
        savePlaylists(musicRoot, playlists);
        return true;
    }

    static synchronized void deletePlaylist(File musicRoot, String name) {
        List<Playlist> playlists = loadPlaylists(musicRoot);
        playlists.removeIf(p -> p.name().equalsIgnoreCase(name));
        savePlaylists(musicRoot, playlists);
    }

    static synchronized boolean addTrackToPlaylist(File musicRoot, String playlistName, String videoId) {
        List<Playlist> playlists = loadPlaylists(musicRoot);
        for (Playlist p : playlists) {
            if (p.name().equalsIgnoreCase(playlistName)) {
                if (p.videoIds().contains(videoId)) return false;
                p.videoIds().add(videoId);
                savePlaylists(musicRoot, playlists);
                return true;
            }
        }
        return false;
    }

    static synchronized int addTracksToPlaylist(File musicRoot, String playlistName, List<String> videoIds) {
        List<Playlist> playlists = loadPlaylists(musicRoot);
        int added = 0;
        for (Playlist p : playlists) {
            if (p.name().equalsIgnoreCase(playlistName)) {
                for (String id : videoIds) {
                    if (id != null && !id.isBlank() && !p.videoIds().contains(id)) {
                        p.videoIds().add(id);
                        added++;
                    }
                }
                if (added > 0) savePlaylists(musicRoot, playlists);
                return added;
            }
        }
        return 0;
    }

    static synchronized List<String> getMissingVideoIds(File musicRoot, @Nullable String playlistName) {
        Set<String> missing = new LinkedHashSet<>();
        List<Playlist> playlists = loadPlaylists(musicRoot);
        for (Playlist p : playlists) {
            if (playlistName != null && !p.name().equalsIgnoreCase(playlistName)) continue;
            if (playlistName == null && !p.autoDownload()) continue;
            for (String videoId : p.videoIds()) {
                if (!isTrackDownloaded(musicRoot, videoId)) {
                    missing.add(videoId);
                }
            }
        }
        return new ArrayList<>(missing);
    }

    static synchronized void removeTrackFromPlaylist(File musicRoot, String playlistName, String videoId) {
        List<Playlist> playlists = loadPlaylists(musicRoot);
        for (Playlist p : playlists) {
            if (p.name().equalsIgnoreCase(playlistName)) {
                p.videoIds().remove(videoId);
                savePlaylists(musicRoot, playlists);
                return;
            }
        }
    }

    static synchronized void removeTrackFromAllPlaylists(File musicRoot, String videoId) {
        List<Playlist> playlists = loadPlaylists(musicRoot);
        boolean modified = false;
        for (Playlist p : playlists) {
            if (p.videoIds().remove(videoId)) {
                modified = true;
            }
        }
        if (modified) savePlaylists(musicRoot, playlists);
    }
}
