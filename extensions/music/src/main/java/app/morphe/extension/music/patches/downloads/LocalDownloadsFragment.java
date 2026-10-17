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
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
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

/** Original YouTube Music style offline player and automatic playlist downloader. */
@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener, LocalDownloadManager.DownloadListener {

    private static final int SEEK_BAR_HEIGHT_DP = 3;
    private static final int SEEK_BAR_TOUCH_DP = 28;
    private static final int SEEK_THUMB_DP = 14;

    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;

    private static final float SECONDARY_DIM = 0.70f;
    private static final float PLAYING_ROW_BRIGHTNESS = 1.35f;

    private static final int ROW_CORNER_DP = 8;
    private static final int ROW_ARTWORK_PIXELS = 256;
    private static final int FULL_ARTWORK_PIXELS = 720;
    private static final int ARTWORK_CORNER_DP = 6;
    private static final int FULL_ARTWORK_CORNER_DP = 12;

    private static final int MENU_WIDTH_DP = 230;
    private static final int MENU_CORNER_DP = 12;
    private static final int ICON_BUTTON_DP = 44;

    private File musicRoot;

    // Collapsed YT Music MiniPlayer views
    private LinearLayout miniPlayer;
    private ProgressBar miniProgressBar;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ImageButton miniNext;

    // Full-Screen Original YT Music Player Dialog & views
    @Nullable
    private Dialog fullPlayerDialog;
    private LinearLayout fullPlayerRoot;
    private ImageView fullArtwork;
    private TextView fullTitle;
    private TextView fullArtist;
    private SeekBar fullSeek;
    private TextView fullCurrentTime;
    private TextView fullTotalTime;
    private ImageButton fullPlayBtn;
    private ImageButton fullPrevBtn;
    private ImageButton fullNextBtn;
    private ImageButton fullShuffleBtn;
    private ImageButton fullRepeatBtn;

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

        miniPlayer = createOriginalYtMusicMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        showTracks();
        // Otomatik olarak tüm playlistlerdeki eksik şarkıları arka planda indir
        LocalDownloadManager.syncPlaylists(musicRoot, null, false);
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
        LocalDownloadManager.addListener(this);
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        LocalDownloadManager.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDownloadStateChanged(String videoId, boolean completed) {
        Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(this::showTracks);
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

        playlistChipsContainer.addView(createChip(
                "Tüm Şarkılar (" + tracks.size() + ")",
                selectedPlaylist == null,
                v -> {
                    selectedPlaylist = null;
                    showTracks();
                }));

        playlistChipsContainer.addView(createChip(
                "+ Playlist Oluştur / Otomatik İndir",
                false,
                v -> showCreateOrAutoDownloadPlaylistDialog(null)));

        for (OfflineStorage.Playlist playlist : playlists) {
            boolean active = playlist.name().equalsIgnoreCase(selectedPlaylist);
            View chip = createChip(
                    playlist.name() + " (" + playlist.videoIds().size() + ")",
                    active,
                    v -> {
                        selectedPlaylist = playlist.name();
                        showTracks();
                        // Seçilen playlistte indirilmemiş şarkı varsa otomatik indir
                        LocalDownloadManager.syncPlaylists(musicRoot, playlist.name(), false);
                    });
            chip.setOnLongClickListener(v -> {
                confirmDeletePlaylist(playlist.name());
                return true;
            });
            playlistChipsContainer.addView(chip);
        }

        if (selectedPlaylist != null) {
            playlistChipsContainer.addView(createChip(
                    "Tümünü İndir",
                    false,
                    v -> LocalDownloadManager.syncPlaylists(musicRoot, selectedPlaylist, true)));

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
        shape.setCornerRadius(dp(8));
        if (selected) {
            shape.setColor(foreground());
        } else {
            shape.setColor(Utils.adjustColorBrightness(background(), 1.22f));
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

    private void showCreateOrAutoDownloadPlaylistDialog(@Nullable OfflineTrack trackToAdd) {
        LinearLayout container = new LinearLayout(getActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(22), dp(18), dp(22), dp(14));
        container.setBackground(menuBackground());

        TextView titleView = text("Oynatma Listesi & Otomatik İndirme", 17, foreground());
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        EditText nameInput = new EditText(getActivity());
        nameInput.setHint("Liste adı girin (Örn: Favorilerim)");
        nameInput.setTextColor(foreground());
        nameInput.setHintTextColor(secondary());
        nameInput.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.setMargins(0, dp(12), 0, dp(8));
        container.addView(nameInput, nameParams);

        EditText urlInput = new EditText(getActivity());
        urlInput.setHint("İsteğe bağlı: YT Music Playlist Linki / ID yapıştırın (Tümünü otomatik indirir)");
        urlInput.setTextColor(foreground());
        urlInput.setHintTextColor(secondary());
        urlInput.setTextSize(13);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, -2);
        urlParams.setMargins(0, 0, 0, dp(16));
        container.addView(urlInput, urlParams);

        LinearLayout actions = new LinearLayout(getActivity());
        actions.setGravity(Gravity.END);

        TextView cancelBtn = createPillButton("İptal", false);
        TextView createBtn = createPillButton("Oluştur & İndir", true);
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
            String name = nameInput.getText().toString().trim();
            String remoteInput = urlInput.getText().toString().trim();
            if (name.isEmpty()) {
                Utils.showToastShort("Lütfen bir liste adı girin");
                return;
            }
            OfflineStorage.createPlaylist(musicRoot, name, remoteInput);
            if (trackToAdd != null) {
                OfflineStorage.addTrackToPlaylist(musicRoot, name, trackToAdd.videoId());
            }
            if (!remoteInput.isEmpty()) {
                selectedPlaylist = name;
                LocalDownloadManager.fetchAndDownloadPlaylist(musicRoot, name, remoteInput);
            } else {
                selectedPlaylist = name;
                Utils.showToastShort(name + " listesi hazır");
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
            Settings.DOWNLOADS_SORT