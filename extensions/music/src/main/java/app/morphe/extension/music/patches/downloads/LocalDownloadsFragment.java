/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;

/** Enhanced local catalogue with playlist management and full media controls. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener {

    private static final int SEEK_BAR_HEIGHT_DP = 4;
    private static final int SEEK_BAR_TOUCH_DP = 22;
    private static final int SEEK_THUMB_DP = 14;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;

    private static final float SECONDARY_DIM = 0.72f;
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;

    private static final int ROW_CORNER_DP = 8;
    private static final int ROW_ARTWORK_PIXELS = 256;
    private static final int ARTWORK_CORNER_DP = 6;

    private static final int MENU_WIDTH_DP = 230;
    private static final int MENU_CORNER_DP = 12;
    private static final int ICON_BUTTON_DP = 44;

    private File musicRoot;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private TextView miniTime;
    private ImageButton miniPlay;
    private ImageButton miniPrevious;
    private ImageButton miniNext;
    private TextView miniRewind;
    private TextView miniFastForward;
    private TextView miniRepeat;
    private TextView miniShuffle;
    private SeekBar miniSeek;
    private LinearLayout miniPlayer;
    private LinearLayout songsList;
    private LinearLayout playlistChipsContainer;

    private boolean showingPause;

    private List<OfflineTrack> tracks = new ArrayList<>();
    private List<OfflineStorage.Playlist> playlists = new ArrayList<>();
    @Nullable
    private String selectedPlaylist = null;

    private final List<View> rowViews = new ArrayList<>();
    private List<String> queuePaths = new ArrayList<>();
    private String playingPath = "";
    private String query = "";
    private TextView summary;
    private EditText searchBar;
    private boolean userSeeking;

    private enum SortOrder {
        ARTIST, TITLE, SIZE;

        static SortOrder saved() {
            try {
                return valueOf(Settings.DOWNLOADS_SORT.get());
            } catch (Exception ignored) {
                return ARTIST;
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        root.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(dp(8), 0, dp(8), dp(12));
        scroll.addView(songsList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = createEnhancedMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        showTracks();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        super.onStop();
    }

    private void loadTracks() {
        tracks = new ArrayList<>();
        File[] files = musicRoot.listFiles(file -> file.isFile()
                && (file.getName().endsWith(".webm") || file.getName().endsWith(".m4a")));
        if (files != null) {
            for (File file : files) {
                tracks.add(OfflineTrack.load(file));
            }
            tracks.sort(comparator());
        }
        playlists = OfflineStorage.loadPlaylists(musicRoot);
        if (selectedPlaylist != null && findPlaylist(selectedPlaylist) == null) {
            selectedPlaylist = null;
        }
    }

    @Nullable
    private OfflineStorage.Playlist findPlaylist(@Nullable String name) {
        if (name == null) return null;
        for (OfflineStorage.Playlist p : playlists) {
            if (p.name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private Comparator<OfflineTrack> comparator() {
        Comparator<OfflineTrack> byTitle =
                Comparator.comparing(track -> track.displayTitle().toLowerCase(Locale.ROOT));

        return switch (SortOrder.saved()) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong(
                    (OfflineTrack track) -> track.audioFile().length()).reversed();
            default -> Comparator.comparing(
                    (OfflineTrack track) -> track.displayArtist().toLowerCase(Locale.ROOT))
                    .thenComparing(byTitle);
        };
    }

    private boolean matchesQuery(OfflineTrack track) {
        if (query.isEmpty()) return true;
        return track.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || track.displayArtist().toLowerCase(Locale.ROOT).contains(query);
    }

    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(6));

        searchBar = CustomDialog.createSearchBar(getActivity(),
                str("morphe_music_downloads_search_hint"), text -> {
                    query = text.trim().toLowerCase(Locale.ROOT);
                    showTracks();
                });
        searchBar.setVisibility(View.GONE);
        searchBar.setAlpha(0f);
        header.addView(searchBar, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout line = new LinearLayout(getActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(4));

        summary = text("", 13, secondary());
        line.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton search = icon(BaseSearchViewController.getSearchIconDrawable(),
                str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton sort = icon("yt_outline_experimental_sort_vd_theme_24",
                str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton deleteAll = icon("yt_outline_experimental_trash_can_vd_theme_24",
                str("morphe_music_downloads_delete_all"));
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        line.addView(deleteAll, new LinearLayout.LayoutParams(dp(40), dp(40)));

        header.addView(line, new LinearLayout.LayoutParams(-1, -2));

        // Yatay Oynatma Listesi (Playlist) Sekme Çubuğu
        HorizontalScrollView chipScroll = new HorizontalScrollView(getActivity());
        chipScroll.setHorizontalScrollBarEnabled(false);
        playlistChipsContainer = new LinearLayout(getActivity());
        playlistChipsContainer.setOrientation(LinearLayout.HORIZONTAL);
        playlistChipsContainer.setGravity(Gravity.CENTER_VERTICAL);
        playlistChipsContainer.setPadding(0, dp(6), 0, dp(4));
        chipScroll.addView(playlistChipsContainer);
        header.addView(chipScroll, new LinearLayout.LayoutParams(-1, -2));

        return header;
    }

    private void rebuildPlaylistChips() {
        if (playlistChipsContainer == null) return;
        playlistChipsContainer.removeAllViews();

        // 1. Tüm Şarkılar Sekmesi
        playlistChipsContainer.addView(createChip(
                "Tüm Şarkılar (" + tracks.size() + ")",
                selectedPlaylist == null,
                v -> {
                    selectedPlaylist = null;
                    showTracks();
                }));

        // 2. Yeni Liste Oluştur Butonu
        playlistChipsContainer.addView(createChip(
                "+ Yeni Liste",
                false,
                v -> showCreatePlaylistDialog(null)));

        // 3. Kullanıcının Oluşturduğu Oynatma Listeleri
        for (OfflineStorage.Playlist playlist : playlists) {
            boolean active = playlist.name().equalsIgnoreCase(selectedPlaylist);
            View chip = createChip(
                    playlist.name() + " (" + playlist.videoIds().size() + ")",
                    active,
                    v -> {
                        selectedPlaylist = playlist.name();
                        showTracks();
                    });
            chip.setOnLongClickListener(v -> {
                confirmDeletePlaylist(playlist.name());
                return true;
            });
            playlistChipsContainer.addView(chip);
        }

        // 4. Seçili Oynatma Listesini Silme Butonu (Bir liste seçiliyse görünür)
        if (selectedPlaylist != null) {
            playlistChipsContainer.addView(createChip(
                    "Listeyi Sil",
                    false,
                    v -> confirmDeletePlaylist(selectedPlaylist)));
        }
    }

    private View createChip(String label, boolean selected, View.OnClickListener onClick) {
        TextView chip = text(label, 13, selected ? background() : foreground());
        chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(18));
        if (selected) {
            shape.setColor(foreground());
        } else {
            shape.setColor(Utils.adjustColorBrightness(background(), 1.25f));
            shape.setStroke(dp(1), rippleColor());
        }
        chip.setBackground(new RippleDrawable(
                ColorStateList.valueOf(rippleColor()), shape, shape));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(params);
        chip.setOnClickListener(onClick);
        return chip;
    }

    private void showCreatePlaylistDialog(@Nullable OfflineTrack trackToAdd) {
        LinearLayout container = new LinearLayout(getActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(22), dp(18), dp(22), dp(14));
        container.setBackground(menuBackground());

        TextView titleView = text("Yeni Oynatma Listesi", 17, foreground());
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        EditText input = new EditText(getActivity());
        input.setHint("Liste adı girin...");
        input.setTextColor(foreground());
        input.setHintTextColor(secondary());
        input.setSingleLine(true);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, -2);
        inputParams.setMargins(0, dp(12), 0, dp(16));
        container.addView(input, inputParams);

        LinearLayout actions = new LinearLayout(getActivity());
        actions.setGravity(Gravity.END);

        TextView cancelBtn = createControlPill("İptal", false);
        TextView createBtn = createControlPill("Oluştur", true);
        actions.addView(cancelBtn);
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(-2, -2);
        createParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(createBtn, createParams);
        container.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setView(container)
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        createBtn.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Utils.showToastShort("Lütfen bir liste adı girin");
                return;
            }
            if (!OfflineStorage.createPlaylist(musicRoot, name)) {
                Utils.showToastShort("Bu isimde bir liste zaten var");
                return;
            }
            if (trackToAdd != null) {
                OfflineStorage.addTrackToPlaylist(musicRoot, name, trackToAdd.videoId());
                Utils.showToastShort(name + " listesine eklendi");
            } else {
                selectedPlaylist = name;
                Utils.showToastShort(name + " listesi oluşturuldu");
            }
            dialog.dismiss();
            showTracks();
        });

        dialog.show();
    }

    private void confirmDeletePlaylist(String playlistName) {
        confirm("Listeyi Sil",
                "\"" + playlistName + "\" oynatma listesi silinsin mi? (İndirilen şarkılar silinmez)",
                () -> {
                    OfflineStorage.deletePlaylist(musicRoot, playlistName);
                    if (playlistName.equalsIgnoreCase(selectedPlaylist)) {
                        selectedPlaylist = null;
                    }
                    showTracks();
                    Utils.showToastShort("Liste silindi");
                });
    }

    private void toggleSearch() {
        final boolean opening = searchBar.getVisibility() != View.VISIBLE;
        InputMethodManager keyboard = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (opening) {
            searchBar.setVisibility(View.VISIBLE);
            slideSearchBar(dp(SEARCH_BAR_HEIGHT_DP));
            searchBar.animate().alpha(1f).setDuration(SEARCH_ANIMATION_MILLISECONDS).start();

            searchBar.requestFocus();
            if (keyboard != null) keyboard.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT);
            return;
        }

        if (keyboard != null) keyboard.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        searchBar.setText("");
        searchBar.clearFocus();

        slideSearchBar(0);
        searchBar.animate().alpha(0f).setDuration(SEARCH_ANIMATION_MILLISECONDS)
                .withEndAction(() -> searchBar.setVisibility(View.GONE)).start();
    }

    private void slideSearchBar(int target) {
        ValueAnimator animator = ValueAnimator.ofInt(searchBar.getHeight(), target);
        animator.setDuration(SEARCH_ANIMATION_MILLISECONDS);
        animator.addUpdateListener(value -> {
            searchBar.getLayoutParams().height = (int) value.getAnimatedValue();
            searchBar.requestLayout();
        });
        animator.start();
    }

    private void showSortMenu(View anchor) {
        String[] items = {
                str("morphe_music_downloads_sort_artist"),
                str("morphe_music_downloads_sort_title"),
                str("morphe_music_downloads_sort_size"),
        };
        showMenu(anchor, items, SortOrder.saved().ordinal(), position -> {
            Settings.DOWNLOADS_SORT.save(SortOrder.values()[position].name());
            showTracks();
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path.isEmpty()) return null;
        for (OfflineTrack track : tracks) {
            if (track.audioFile().getAbsolutePath().equals(path)) return track;
        }
        return null;
    }

    private void showTracks() {
        loadTracks();
        rebuildPlaylistChips();
        songsList.removeAllViews();
        rowViews.clear();
        queuePaths = new ArrayList<>();

        List<OfflineTrack> displayedTracks = new ArrayList<>();
        OfflineStorage.Playlist activePlaylist = findPlaylist(selectedPlaylist);

        if (activePlaylist != null) {
            Map<String, OfflineTrack> byId = new HashMap<>();
            for (OfflineTrack t : tracks) {
                byId.put(t.videoId(), t);
            }
            for (String id : activePlaylist.videoIds()) {
                OfflineTrack t = byId.get(id);
                if (t != null) displayedTracks.add(t);
            }
        } else {
            displayedTracks.addAll(tracks);
        }

        long totalBytes = 0;
        for (OfflineTrack track : displayedTracks) {
            totalBytes += track.audioFile().length();
        }
        summary.setText(str("morphe_music_downloads_summary",
                displayedTracks.size(), formatSize(totalBytes)));

        for (OfflineTrack track : displayedTracks) {
            if (!matchesQuery(track)) continue;
            queuePaths.add(track.audioFile().getAbsolutePath());
            songsList.addView(songRow(track));
        }

        if (queuePaths.isEmpty()) {
            String emptyMsg = activePlaylist != null
                    ? "Bu oynatma listesinde henüz şarkı yok.\nŞarkıların yanındaki 3 noktadan ekleyebilirsin."
                    : str(tracks.isEmpty()
                            ? "morphe_music_downloads_empty"
                            : "morphe_music_downloads_nothing_found");
            TextView empty = text(emptyMsg, 15, secondary());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(70), dp(20), 0);
            songsList.addView(empty);
            return;
        }
        markPlayingRow();
    }

    private View songRow(OfflineTrack track) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        rowViews.add(row);
        row.addView(artworkView(track.artwork(ROW_ARTWORK_PIXELS)),
                new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(track.displayTitle(), 16, foreground());
        name.setSingleLine(true);

        TextView detail = text(str("morphe_music_downloads_track_subtitle",
                track.displayArtist(), formatDuration(track.durationSeconds()),
                formatSize(track.audioFile().length())), 13, secondary());
        detail.setSingleLine(true);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track));
        row.addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> play(track));
        return row;
    }

    private void markPlayingRow() {
        int playingIndex = queuePaths.indexOf(playingPath);
        for (int i = 0; i < rowViews.size(); i++) {
            rowViews.get(i).setBackground(rowBackground(i == playingIndex));
        }
    }

    private Drawable rowBackground(boolean playing) {
        Drawable card = playing
                ? CustomDialog.createRoundedBackground(ROW_CORNER_DP, Utils.adjustColorBrightness(
                        ThemeUtils.getAppBackgroundColor(), PLAYING_ROW_BRIGHTNESS))
                : null;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    private static int rippleColor() {
        final int foreground = foreground();
        return Color.argb(60, Color.red(foreground),
                Color.green(foreground), Color.blue(foreground));
    }

    private LinearLayout createEnhancedMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ThemeUtils.getDialogBackgroundColor());

        View divider = new View(getActivity());
        divider.setBackgroundColor(rippleColor());
        outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        outer.setPadding(0, dp(8), 0, dp(12));

        // 1. Üst Satır: Kapak + Şarkı/Sanatçı + Süre (01:20 / 03:45)
        LinearLayout infoRow = new LinearLayout(getActivity());
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setPadding(dp(16), 0, dp(16), dp(2));

        miniArtwork = artworkView(null);
        infoRow.addView(miniArtwork, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);

        miniTitle = text("", 14, foreground());
        miniTitle.setSingleLine(true);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = text("", 12, secondary());
        miniArtist.setSingleLine(true);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        infoRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        miniTime = text("0:00 / 0:00", 12, secondary());
        infoRow.addView(miniTime, new LinearLayout.LayoutParams(-2, -2));

        outer.addView(infoRow, new LinearLayout.LayoutParams(-1, -2));

        // 2. Orta Satır: İlerleme Çubuğu (SeekBar)
        outer.addView(createSeekBar(), new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));

        // 3. Alt Satır: Tam Medya Kontrol Tuşları (Tekrar | Önceki | -10s | Oynat | +10s | Sonraki | Karıştır)
        LinearLayout controlsRow = new LinearLayout(getActivity());
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        controlsRow.setPadding(dp(8), dp(2), dp(8), 0);

        miniRepeat = createControlPill(repeatLabel(OfflinePlaybackService.getRepeatMode()),
                OfflinePlaybackService.getRepeatMode() != OfflinePlaybackService.REPEAT_OFF);
        miniRepeat.setOnClickListener(v -> {
            int mode = OfflinePlaybackService.cycleRepeatMode();
            updateRepeatButton(mode);
        });
        controlsRow.addView(miniRepeat, controlMarginParams());

        miniPrevious = icon("yt_fill_experimental_skip_previous_vd_theme_24",
                str("morphe_music_downloads_previous"));
        miniPrevious.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(getActivity()));
        controlsRow.addView(miniPrevious, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniRewind = createControlPill("-10s", false);
        miniRewind.setOnClickListener(v -> OfflinePlaybackService.seekBy(-10_000));
        controlsRow.addView(miniRewind, controlMarginParams());

        miniPlay = icon("yt_fill_experimental_play_vd_theme_24",
                str("morphe_music_downloads_play_pause"));
        miniPlay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        miniPlay.setPadding(dp(10), dp(10), dp(10), dp(10));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        controlsRow.addView(miniPlay, new LinearLayout.LayoutParams(dp(48), dp(48)));

        miniFastForward = createControlPill("+10s", false);
        miniFastForward.setOnClickListener(v -> OfflinePlaybackService.seekBy(10_000));
        controlsRow.addView(miniFastForward, controlMarginParams());

        miniNext = icon("yt_fill_experimental_skip_next_vd_theme_24",
                str("morphe_music_downloads_next"));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        controlsRow.addView(miniNext, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        miniShuffle = createControlPill("Karıştır", false);
        miniShuffle.setOnClickListener(v -> shuffleAndPlay());
        controlsRow.addView(miniShuffle, controlMarginParams());

        outer.addView(controlsRow, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    private LinearLayout.LayoutParams controlMarginParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private TextView createControlPill(String label, boolean active) {
        TextView view = text(label, 12, active ? background() : foreground());
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        applyPillStyle(view, active);
        return view;
    }

    private void applyPillStyle(TextView view, boolean active) {
        view.setTextColor(active ? background() : foreground());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (active) {
            bg.setColor(foreground());
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), rippleColor());
        }
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), bg, bg));
    }

    private String repeatLabel(int mode) {
        return switch (mode) {
            case OfflinePlaybackService.REPEAT_ALL -> "Tekrar: Tümü";
            case OfflinePlaybackService.REPEAT_ONE -> "Tekrar: 1";
            default -> "Tekrar: Kapalı";
        };
    }

    private void updateRepeatButton(int mode) {
        if (miniRepeat == null) return;
        miniRepeat.setText(repeatLabel(mode));
        applyPillStyle(miniRepeat, mode != OfflinePlaybackService.REPEAT_OFF);
    }

    private void shuffleAndPlay() {
        if (queuePaths.isEmpty()) return;
        ArrayList<String> shuffled = new ArrayList<>(queuePaths);
        Collections.shuffle(shuffled);
        OfflineTrack first = findByPath(shuffled.get(0));
        if (first != null) {
            playWithCustomQueue(first, shuffled, 0);
            Utils.showToastShort("Karışık çalma başlatıldı");
        }
    }

    private SeekBar createSeekBar() {
        miniSeek = new SeekBar(getActivity());
        miniSeek.setPadding(dp(16), 0, dp(16), 0);
        miniSeek.setProgressDrawable(createSeekBarDrawable());
        miniSeek.setThumb(null);
        miniSeek.setThumbOffset(0);
        miniSeek.setBackground(null);
        miniSeek.setSplitTrack(false);
        miniSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                bar.setThumb(createThumb());
                bar.setThumbOffset(dp(SEEK_THUMB_DP) / 2);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                bar.setThumb(null);
                OfflinePlaybackService.seekTo(bar.getProgress());
            }

            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && miniTime != null) {
                    miniTime.setText(formatDuration(value / 1000) + " / "
                            + formatDuration(bar.getMax() / 1000));
                }
            }
        });
        return miniSeek;
    }

    private Drawable createThumb() {
        ShapeDrawable thumb = new ShapeDrawable(new OvalShape());
        thumb.setIntrinsicWidth(dp(SEEK_THUMB_DP));
        thumb.setIntrinsicHeight(dp(SEEK_THUMB_DP));
        thumb.getPaint().setColor(ThemeUtils.getAppForegroundColor());
        return thumb;
    }

    private Drawable createSeekBarDrawable() {
        final int foreground = ThemeUtils.getAppForegroundColor();
        Drawable track = roundedBar(Utils.adjustColorBrightness(foreground, 0.3f));
        Drawable played = roundedBar(foreground);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(played, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);

        final int inset = (dp(SEEK_BAR_TOUCH_DP) - dp(SEEK_BAR_HEIGHT_DP)) / 2;
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        return layers;
    }

    private Drawable roundedBar(int color) {
        return CustomDialog.createRoundedBackground(SEEK_BAR_HEIGHT_DP, color);
    }

    private void showTrackMenu(View anchor, OfflineTrack track) {
        if (selectedPlaylist != null) {
            String[] items = {
                    str("morphe_music_downloads_play"),
                    "Oynatma Listesine Ekle",
                    "Listeden Çıkar",
                    str("morphe_music_downloads_delete_track"),
            };
            showMenu(anchor, items, -1, position -> {
                switch (position) {
                    case 0 -> play(track);
                    case 1 -> showAddToPlaylistMenu(anchor, track);
                    case 2 -> {
                        OfflineStorage.removeTrackFromPlaylist(musicRoot, selectedPlaylist, track.videoId());
                        showTracks();
                        Utils.showToastShort("Listeden çıkarıldı");
                    }
                    case 3 -> confirmDelete(track);
                }
            });
        } else {
            String[] items = {
                    str("morphe_music_downloads_play"),
                    "Oynatma Listesine Ekle",
                    str("morphe_music_downloads_delete_track"),
            };
            showMenu(anchor, items, -1, position -> {
                switch (position) {
                    case 0 -> play(track);
                    case 1 -> showAddToPlaylistMenu(anchor, track);
                    case 2 -> confirmDelete(track);
                }
            });
        }
    }

    private void showAddToPlaylistMenu(View anchor, OfflineTrack track) {
        String[] items = new String[playlists.size() + 1];
        items[0] = "+ Yeni Liste Oluştur";
        for (int i = 0; i < playlists.size(); i++) {
            items[i + 1] = playlists.get(i).name();
        }
        showMenu(anchor, items, -1, position -> {
            if (position == 0) {
                showCreatePlaylistDialog(track);
            } else {
                String targetName = playlists.get(position - 1).name();
                if (OfflineStorage.addTrackToPlaylist(musicRoot, targetName, track.videoId())) {
                    showTracks();
                    Utils.showToastShort(targetName + " listesine eklendi");
                } else {
                    Utils.showToastShort("Şarkı zaten bu listede var");
                }
            }
        });
    }

    private void showMenu(View anchor, String[] items, int selected, IntConsumer onPick) {
        ListPopupWindow popup = new ListPopupWindow(getActivity());
        popup.setAnchorView(anchor);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(MENU_WIDTH_DP));
        popup.setModal(true);
        popup.setBackgroundDrawable(menuBackground());
        popup.setListSelector(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null,
                CustomDialog.createRoundedBackground(MENU_CORNER_DP, Color.WHITE)));
        popup.setAdapter(new ArrayAdapter<>(getActivity(), 0, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = text(getItem(position), 15,
                        selected < 0 || position == selected ? foreground() : secondary());
                view.setPadding(dp(20), dp(14), dp(20), dp(14));
                return view;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            onPick.accept(position);
        });
        popup.show();
    }

    private Drawable menuBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(ThemeUtils.getDialogBackgroundColor());
        background.setCornerRadius(dp(MENU_CORNER_DP));
        background.setStroke(dp(1), rippleColor());
        return background;
    }

    private void confirmDelete(OfflineTrack track) {
        confirm(str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()),
                () -> deleteTrack(track));
    }

    private void confirmDeleteAll() {
        if (tracks.isEmpty()) return;
        confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"), this::deleteAll);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null,
                str("morphe_music_downloads_delete"), onConfirm, () -> {
                }, null, null, false).first.show();
    }

    private void deleteAll() {
        for (OfflineTrack track : new ArrayList<>(tracks)) {
            OfflineStorage.delete(track.audioFile());
            deleteSidecars(track.videoId());
            OfflineStorage.removeTrackFromAllPlaylists(musicRoot, track.videoId());
        }
        showTracks();
        Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
    }

    private void deleteSidecars(String videoId) {
        OfflineStorage.delete(new File(musicRoot, videoId + ".json"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".jpg"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".webm.part"));
        OfflineStorage.delete(new File(musicRoot, videoId + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack track) {
        final boolean deleted = track.audioFile().delete();
        deleteSidecars(track.videoId());
        OfflineStorage.removeTrackFromAllPlaylists(musicRoot, track.videoId());

        if (!deleted) {
            Utils.showToastShort(str("morphe_music_downloads_track_delete_failed"));
            return;
        }
        showTracks();
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    private void play(OfflineTrack track) {
        ArrayList<String> queue = new ArrayList<>(queuePaths);
        final int queueIndex = Math.max(0, queue.indexOf(track.audioFile().getAbsolutePath()));
        playWithCustomQueue(track, queue, queueIndex);
    }

    private void playWithCustomQueue(OfflineTrack track, ArrayList<String> queue, int queueIndex) {
        try {
            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, track.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, track.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, track.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH,
                            track.artworkFile().getAbsolutePath())
                    .putStringArrayListExtra(OfflinePlaybackService.EXTRA_QUEUE, queue)
                    .putExtra(OfflinePlaybackService.EXTRA_QUEUE_INDEX, queueIndex);
            getActivity().startForegroundService(intent);

            applyTrackVisuals(track);
        } catch (Exception ex) {
            Logger.printException(() -> "Offline playback failed: " + track.audioFile(), ex);
            Utils.showToastShort(str("morphe_music_downloads_play_failed"));
        }
    }

    @Override
    public void onPlaybackChanged(String path, String title, boolean playing,
                                  int position, int duration) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            final boolean wasVisible = miniPlayer.getVisibility() == View.VISIBLE;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

            if (!path.equals(playingPath)) {
                playingPath = path;
                OfflineTrack track = findByPath(path);
                if (track != null) applyTrackVisuals(track);
                else miniTitle.setText(title);
                markPlayingRow();
            }

            int repeatMode = OfflinePlaybackService.getRepeatMode();
            updateRepeatButton(repeatMode);

            int index = queuePaths.indexOf(path);
            boolean loopAll = repeatMode == OfflinePlaybackService.REPEAT_ALL && !queuePaths.isEmpty();
            setButtonEnabled(miniPrevious, loopAll || index > 0 || position > 3000);
            setButtonEnabled(miniNext, loopAll || (index >= 0 && index + 1 < queuePaths.size()));

            updatePlayIcon(playing, wasVisible && playing != showingPause);
            miniSeek.setMax(Math.max(1, duration));
            if (!userSeeking) {
                miniSeek.setProgress(position, true);
                if (miniTime != null) {
                    miniTime.setText(formatDuration(position / 1000) + " / "
                            + formatDuration(duration / 1000));
                }
            }
        });
    }

    private void updatePlayIcon(boolean playing, boolean morph) {
        showingPause = playing;
        if (morph && startIconMorph(playing)) return;

        miniPlay.setImageDrawable(ResourceUtils.getDrawable(playing
                ? "yt_fill_experimental_pause_vd_theme_24"
                : "yt_fill_experimental_play_vd_theme_24"));
    }

    private boolean startIconMorph(boolean playing) {
        String name = playing
                ? "player_play_pause_vector_transition"
                : "player_pause_play_vector_transition";

        int identifier = ResourceUtils.getDrawableIdentifier(name + "_delhi");
        if (identifier == 0) identifier = ResourceUtils.getDrawableIdentifier(name);

        Activity activity = getActivity();
        if (identifier == 0 || activity == null) return false;

        Drawable drawable = activity.getDrawable(identifier);
        if (!(drawable instanceof AnimatedVectorDrawable morph)) return false;

        miniPlay.setImageDrawable(morph);
        morph.start();
        return true;
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle());
        miniArtist.setText(track.displayArtist());

        Bitmap bitmap = track.artwork(ROW_ARTWORK_PIXELS);
        if (bitmap == null) return;

        miniArtwork.clearColorFilter();
        miniArtwork.setPadding(0, 0, 0, 0);
        miniArtwork.setImageBitmap(bitmap);
        miniArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    private void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .35f);
    }

    private ImageView artworkView(@Nullable Bitmap bitmap) {
        ImageView view = new ImageView(getActivity());
        view.setBackgroundColor(artworkPlaceholder());
        roundCorners(view);

        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            view.setImageDrawable(
                    ResourceUtils.getDrawable("yt_fill_experimental_play_circle_vd_theme_24"));
            view.setColorFilter(foreground());
            view.setPadding(dp(14), dp(14), dp(14), dp(14));
        }
        return view;
    }

    private void roundCorners(View view) {
        final float radius = dp(ARTWORK_CORNER_DP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View outlined, Outline outline) {
                outline.setRoundRect(0, 0, outlined.getWidth(), outlined.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getActivity());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private ImageButton icon(String drawable, String description) {
        ImageButton button = icon(ResourceUtils.getDrawable(drawable), description);
        button.setColorFilter(foreground());
        return button;
    }

    private ImageButton icon(Drawable drawable, String description) {
        ImageButton button = new ImageButton(getActivity());
        button.setImageDrawable(drawable);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));

        button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()),
                null, new ShapeDrawable(new OvalShape())));
        return button;
    }

    private static int foreground() {
        return ThemeUtils.getAppForegroundColor();
    }

    private static int secondary() {
        return Utils.adjustColorBrightness(foreground(), SECONDARY_DIM);
    }

    private static int background() {
        return ThemeUtils.getAppBackgroundColor();
    }

    private static int artworkPlaceholder() {
        return Utils.adjustColorBrightness(foreground(), 0.2f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "0:00";
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }
}
