package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("deprecation")
public final class LocalDownloadsFragment extends PreferenceFragment
        implements OfflinePlaybackService.PlaybackListener, LocalDownloadManager.DownloadListener {

    private static final int SEEK_BAR_HEIGHT_DP = 4;
    private static final int SEEK_BAR_TOUCH_DP = 32;
    private static final int SEEK_THUMB_DP = 14;
    private static final int SEARCH_BAR_HEIGHT_DP = 46;
    private static final long SEARCH_ANIMATION_MILLISECONDS = 180;
    private static final int ROW_CORNER_DP = 10;
    private static final int ROW_ARTWORK_PIXELS = 192;
    private static final int FULL_ARTWORK_PIXELS = 720;
    private static final int ARTWORK_CORNER_DP = 8;
    private static final int FULL_ARTWORK_CORNER_DP = 14;
    private static final int MENU_WIDTH_DP = 240;
    private static final int MENU_CORNER_DP = 14;
    private static final int ICON_BUTTON_DP = 44;

    private static final int ICON_CHEVRON_DOWN = 1;
    private static final int ICON_SHUFFLE = 2;
    private static final int ICON_REPEAT = 3;
    private static final int ICON_PLAY = 4;
    private static final int ICON_PAUSE = 5;
    private static final int ICON_SKIP_PREV = 6;
    private static final int ICON_SKIP_NEXT = 7;
    private static final int ICON_PALETTE = 8;

    private static final String PREF_NAME = "morphe_anime_theme_prefs";
    private static final String PREF_THEME_KEY = "selected_anime_theme";

    // Bellek içi yüksek hızlı Bitmap önbelleği (UI takılmalarını önler)
    private static final LruCache<String, Bitmap> ARTWORK_CACHE = new LruCache<>(80);

    public enum AnimeTheme {
        EVANGELION(
                "Evangelion (EVA-01)",
                Color.parseColor("#3D165B"), // EVA-01 Zırh Moru
                Color.parseColor("#160A24"), // Tokyo-3 Gece Moru
                Color.parseColor("#09040E"), // NERV Derin Siyah
                Color.parseColor("#39FF14"), // EVA-01 Siber Neon Yeşil (İlerleme çubuğu & vurgu)
                Color.parseColor("#FF6D00"), // NERV / AT-Field Turuncu (İkincil geçiş)
                Color.parseColor("#0D0814")  // Oynatma Butonu İkon Rengi
        ),
        COWBOY_BEBOP(
                "Cowboy Bebop",
                Color.parseColor("#8E1616"), // Swordfish II Kızılı
                Color.parseColor("#131D36"), // Spike Spiegel Uzay Laciverti
                Color.parseColor("#080C17"), // Bebop Gece Siyahı
                Color.parseColor("#FFB300"), // Caz Kehribar / Altın Sarısı
                Color.parseColor("#EF5350"), // Neon Kızıl Geçiş
                Color.parseColor("#0B0F19")
        ),
        BERSERK(
                "Berserk (Eclipse)",
                Color.parseColor("#6E0711"), // Kurban Damgası Kan Kırmızısı
                Color.parseColor("#1C0608"), // Tutulma Koyu Bordo
                Color.parseColor("#060606"), // Berserker Zırhı Obsidyen Siyahı
                Color.parseColor("#FF1744"), // Kor Kan Kızılı
                Color.parseColor("#B71C1C"), // Derin Kızıl
                Color.parseColor("#080808")
        ),
        ANIME_FUSION(
                "Eva × Bebop × Berserk Geçiş",
                Color.parseColor("#4A154B"), // Evangelion Moru
                Color.parseColor("#7F1D1D"), // Berserk Kan Kırmızısı
                Color.parseColor("#0F172A"), // Cowboy Bebop Gece Mavisi
                Color.parseColor("#39FF14"), // Eva Yeşil -> Bebop Altın -> Berserk Kızıl
                Color.parseColor("#FFB300"),
                Color.parseColor("#09090B")
        );

        final String label;
        final int gradTop;
        final int gradMid;
        final int gradBottom;
        final int accentPrimary;
        final int accentSecondary;
        final int playIconDark;

        AnimeTheme(String label, int gradTop, int gradMid, int gradBottom,
                   int accentPrimary, int accentSecondary, int playIconDark) {
            this.label = label;
            this.gradTop = gradTop;
            this.gradMid = gradMid;
            this.gradBottom = gradBottom;
            this.accentPrimary = accentPrimary;
            this.accentSecondary = accentSecondary;
            this.playIconDark = playIconDark;
        }
    }

    private File musicRoot;
    private LinearLayout rootLayout, miniPlayer, songsList, playlistChipsContainer, fullPlayerRoot;
    private ProgressBar miniProgressBar;
    private ImageView miniArtwork, fullArtwork;
    private TextView miniTitle, miniArtist, fullTitle, fullArtist, fullCurrentTime, fullTotalTime, summary, themePillText;
    private ImageButton miniPlay, miniNext, fullPlayBtn, fullPrevBtn, fullNextBtn, fullShuffleBtn, fullRepeatBtn;
    private SeekBar fullSeek;
    private EditText searchBar;
    @Nullable private Dialog fullPlayerDialog;
    @Nullable private ValueAnimator fusionAnimator;
    private float fusionPhase = 0f;

    private AnimeTheme currentTheme = AnimeTheme.EVANGELION;
    private boolean showingPause, userSeeking, likedCurrentTrack;
    private List<OfflineTrack> tracks = new ArrayList<>();
    private List<OfflineStorage.Playlist> playlists = new ArrayList<>();
    @Nullable private String selectedPlaylist = null;
    private final List<View> rowViews = new ArrayList<>();
    private List<String> queuePaths = new ArrayList<>();
    private String playingPath = "", query = "";

    private enum SortOrder {
        ARTIST, TITLE, SIZE;
        static SortOrder saved() {
            try { return valueOf(Settings.DOWNLOADS_SORT.get()); }
            catch (Exception ignored) { return ARTIST; }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        loadSavedTheme();
        musicRoot = new File(getActivity().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");

        rootLayout = new LinearLayout(getActivity());
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        applyListBackground();
        rootLayout.addView(createHeader(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(getActivity());
        songsList = new LinearLayout(getActivity());
        songsList.setOrientation(LinearLayout.VERTICAL);
        songsList.setPadding(dp(8), 0, dp(8), dp(12));
        scroll.addView(songsList);
        rootLayout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = createOriginalYtMusicMiniPlayer();
        miniPlayer.setVisibility(View.GONE);
        rootLayout.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        showTracks();
        LocalDownloadManager.syncPlaylists(musicRoot, null, false);
        startOrStopFusionAnimator();
        return rootLayout;
    }

    @Override
    public void onStart() {
        super.onStart();
        OfflinePlaybackService.addListener(this);
        LocalDownloadManager.addListener(this);
        startOrStopFusionAnimator();
    }

    @Override
    public void onStop() {
        OfflinePlaybackService.removeListener(this);
        LocalDownloadManager.removeListener(this);
        stopFusionAnimator();
        super.onStop();
    }

    private void loadSavedTheme() {
        try {
            SharedPreferences prefs = getActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String saved = prefs.getString(PREF_THEME_KEY, AnimeTheme.EVANGELION.name());
            currentTheme = AnimeTheme.valueOf(saved);
        } catch (Exception ignored) {
            currentTheme = AnimeTheme.EVANGELION;
        }
    }

    private void cycleAnimeTheme() {
        AnimeTheme[] values = AnimeTheme.values();
        currentTheme = values[(currentTheme.ordinal() + 1) % values.length];
        try {
            getActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(PREF_THEME_KEY, currentTheme.name()).apply();
        } catch (Exception ignored) {
        }
        Utils.showToastShort("Tema: " + currentTheme.label);
        startOrStopFusionAnimator();
        refreshAllThemeVisuals();
    }

    private void startOrStopFusionAnimator() {
        stopFusionAnimator();
        if (currentTheme != AnimeTheme.ANIME_FUSION) return;

        fusionAnimator = ValueAnimator.ofFloat(0f, 1f);
        fusionAnimator.setDuration(7500);
        fusionAnimator.setRepeatCount(ValueAnimator.INFINITE);
        fusionAnimator.setRepeatMode(ValueAnimator.REVERSE);
        fusionAnimator.addUpdateListener(anim -> {
            fusionPhase = (float) anim.getAnimatedValue();
            updateDynamicPlayerColors();
        });
        fusionAnimator.start();
    }

    private void stopFusionAnimator() {
        if (fusionAnimator != null) {
            fusionAnimator.cancel();
            fusionAnimator = null;
        }
    }

    private int activeAccentColor() {
        if (currentTheme == AnimeTheme.ANIME_FUSION) {
            ArgbEvaluator eval = new ArgbEvaluator();
            if (fusionPhase < 0.5f) {
                return (int) eval.evaluate(fusionPhase * 2f,
                        AnimeTheme.EVANGELION.accentPrimary,
                        AnimeTheme.COWBOY_BEBOP.accentPrimary);
            } else {
                return (int) eval.evaluate((fusionPhase - 0.5f) * 2f,
                        AnimeTheme.COWBOY_BEBOP.accentPrimary,
                        AnimeTheme.BERSERK.accentPrimary);
            }
        }
        return currentTheme.accentPrimary;
    }

    private int activeSecondaryAccent() {
        if (currentTheme == AnimeTheme.ANIME_FUSION) {
            ArgbEvaluator eval = new ArgbEvaluator();
            return (int) eval.evaluate(fusionPhase,
                    AnimeTheme.BERSERK.accentPrimary,
                    AnimeTheme.COWBOY_BEBOP.accentPrimary);
        }
        return currentTheme.accentSecondary;
    }

    private GradientDrawable buildAnimeBackgroundGradient(@Nullable OfflineTrack track) {
        if (currentTheme == AnimeTheme.ANIME_FUSION) {
            ArgbEvaluator eval = new ArgbEvaluator();
            int c1 = (int) eval.evaluate(fusionPhase, AnimeTheme.EVANGELION.gradTop, AnimeTheme.COWBOY_BEBOP.gradTop);
            int c2 = (int) eval.evaluate(fusionPhase, AnimeTheme.COWBOY_BEBOP.gradMid, AnimeTheme.BERSERK.gradTop);
            int c3 = (int) eval.evaluate(fusionPhase, AnimeTheme.BERSERK.gradBottom, AnimeTheme.EVANGELION.gradBottom);
            return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2, c3});
        }
        int top = currentTheme.gradTop;
        if (track != null) {
            int dom = track.extractDominantColor(top);
            top = blendColors(currentTheme.gradTop, dom, 0.35f);
        }
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, currentTheme.gradMid, currentTheme.gradBottom});
    }

    private static int blendColors(int c1, int c2, float ratio) {
        float inv = 1f - ratio;
        int r = (int) (Color.red(c1) * inv + Color.red(c2) * ratio);
        int g = (int) (Color.green(c1) * inv + Color.green(c2) * ratio);
        int b = (int) (Color.blue(c1) * inv + Color.blue(c2) * ratio);
        return Color.rgb(r, g, b);
    }

    private void applyListBackground() {
        if (rootLayout == null) return;
        rootLayout.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        blendColors(currentTheme.gradMid, Color.BLACK, 0.35f),
                        currentTheme.gradBottom
                }));
    }

    private void refreshAllThemeVisuals() {
        applyListBackground();
        rebuildPlaylistChips();
        markPlayingRow();
        updateDynamicPlayerColors();
    }

    private void updateDynamicPlayerColors() {
        OfflineTrack cur = findByPath(playingPath);
        if (fullPlayerRoot != null) {
            fullPlayerRoot.setBackground(buildAnimeBackgroundGradient(cur));
        }
        if (themePillText != null) {
            themePillText.setText("🎨 " + currentTheme.label);
        }
        if (fullSeek != null) {
            fullSeek.invalidate();
        }
        if (miniProgressBar != null) {
            miniProgressBar.invalidate();
        }
        if (fullPlayBtn != null) {
            GradientDrawable playCircle = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{activeAccentColor(), activeSecondaryAccent()});
            playCircle.setShape(GradientDrawable.OVAL);
            fullPlayBtn.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), playCircle, playCircle));
            fullPlayBtn.setImageDrawable(createVectorIconDrawable(
                    showingPause ? ICON_PAUSE : ICON_PLAY, currentTheme.playIconDark, 0));
        }
        if (miniPlay != null) {
            miniPlay.setImageDrawable(createVectorIconDrawable(
                    showingPause ? ICON_PAUSE : ICON_PLAY, activeAccentColor(), 0));
        }
        updateFullShuffleRepeatVisuals();
    }

    @Override
    public void onDownloadStateChanged(String videoId, boolean completed) {
        Activity activity = getActivity();
        if (activity != null) activity.runOnUiThread(this::showTracks);
    }

    private void loadTracks() {
        tracks = new ArrayList<>();
        File[] files = musicRoot.listFiles(f -> f.isFile() && (f.getName().endsWith(".webm") || f.getName().endsWith(".m4a")));
        if (files != null) {
            for (File file : files) tracks.add(OfflineTrack.load(file));
            tracks.sort(comparator());
        }
        playlists = OfflineStorage.loadPlaylists(musicRoot);
        if (selectedPlaylist != null && findPlaylist(selectedPlaylist) == null) selectedPlaylist = null;
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
        Comparator<OfflineTrack> byTitle = Comparator.comparing(t -> t.displayTitle().toLowerCase(Locale.ROOT));
        return switch (SortOrder.saved()) {
            case TITLE -> byTitle;
            case SIZE -> Comparator.comparingLong((OfflineTrack t) -> t.audioFile().length()).reversed();
            default -> Comparator.comparing((OfflineTrack t) -> t.displayArtist().toLowerCase(Locale.ROOT)).thenComparing(byTitle);
        };
    }

    private boolean matchesQuery(OfflineTrack t) {
        return query.isEmpty() || t.displayTitle().toLowerCase(Locale.ROOT).contains(query)
                || t.displayArtist().toLowerCase(Locale.ROOT).contains(query);
    }

    private LinearLayout createHeader() {
        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(6));

        searchBar = CustomDialog.createSearchBar(getActivity(), str("morphe_music_downloads_search_hint"), text -> {
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

        ImageButton themeBtn = icon(createVectorIconDrawable(ICON_PALETTE, activeAccentColor(), 0), "Anime Teması Değiştir");
        themeBtn.setOnClickListener(v -> {
            cycleAnimeTheme();
            themeBtn.setImageDrawable(createVectorIconDrawable(ICON_PALETTE, activeAccentColor(), 0));
        });
        line.addView(themeBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton search = icon(BaseSearchViewController.getSearchIconDrawable(), str("morphe_music_downloads_search_hint"));
        search.setOnClickListener(v -> toggleSearch());
        line.addView(search, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton sort = icon("yt_outline_experimental_sort_vd_theme_24", str("morphe_music_downloads_sort"));
        sort.setOnClickListener(v -> showSortMenu(sort));
        line.addView(sort, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton deleteAll = icon("yt_outline_experimental_trash_can_vd_theme_24", str("morphe_music_downloads_delete_all"));
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
        playlistChipsContainer.addView(createChip("Tüm Şarkılar (" + tracks.size() + ")", selectedPlaylist == null, v -> {
            selectedPlaylist = null;
            showTracks();
        }));
        playlistChipsContainer.addView(createChip("+ Playlist / Beğenilenler (LM)", false,
                v -> showCreateOrAutoDownloadPlaylistDialog(null)));
        playlistChipsContainer.addView(createChip("🎨 " + currentTheme.label, false,
                v -> cycleAnimeTheme()));

        for (OfflineStorage.Playlist playlist : playlists) {
            boolean active = playlist.name().equalsIgnoreCase(selectedPlaylist);
            View chip = createChip(playlist.name() + " (" + playlist.videoIds().size() + ")", active, v -> {
                selectedPlaylist = playlist.name();
                showTracks();
                LocalDownloadManager.syncPlaylists(musicRoot, playlist.name(), false);
            });
            chip.setOnLongClickListener(v -> { confirmDeletePlaylist(playlist.name()); return true; });
            playlistChipsContainer.addView(chip);
        }
        if (selectedPlaylist != null) {
            playlistChipsContainer.addView(createChip("Tümünü İndir", false,
                    v -> LocalDownloadManager.syncPlaylists(musicRoot, selectedPlaylist, true)));
            playlistChipsContainer.addView(createChip("Listeyi Sil", false,
                    v -> confirmDeletePlaylist(selectedPlaylist)));
        }
    }

    private View createChip(String label, boolean selected, View.OnClickListener onClick) {
        int accent = activeAccentColor();
        TextView chip = text(label, 13, selected ? currentTheme.playIconDark : Color.WHITE);
        chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(18));
        if (selected) {
            shape.setColors(new int[]{accent, activeSecondaryAccent()});
            shape.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        } else {
            shape.setColor(Color.argb(45, 255, 255, 255));
            shape.setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)));
        }
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), shape, shape));
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

        TextView titleView = text("Oynatma Listesi & Otomatik İndirme", 17, Color.WHITE);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        EditText nameInput = new EditText(getActivity());
        nameInput.setHint("Liste adı girin (Örn: Beğendiklerim)");
        nameInput.setTextColor(Color.WHITE);
        nameInput.setHintTextColor(secondary());
        nameInput.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.setMargins(0, dp(12), 0, dp(8));
        container.addView(nameInput, nameParams);

        EditText urlInput = new EditText(getActivity());
        urlInput.setHint("Playlist Linki, ID veya Beğenilenler için 'LM' / 'LL' yazın");
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(secondary());
        urlInput.setTextSize(13);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, -2);
        urlParams.setMargins(0, 0, 0, dp(10));
        container.addView(urlInput, urlParams);

        TextView quickLikedBtn = createPillButton("❤️ Beğendiğim Şarkıları (LM) Otomatik Doldur", false);
        quickLikedBtn.setOnClickListener(v -> {
            if (nameInput.getText().toString().trim().isEmpty()) {
                nameInput.setText("Beğendiğim Şarkılar");
            }
            urlInput.setText("LM");
        });
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(-2, -2);
        quickParams.setMargins(0, 0, 0, dp(14));
        container.addView(quickLikedBtn, quickParams);

        LinearLayout actions = new LinearLayout(getActivity());
        actions.setGravity(Gravity.END);
        TextView cancelBtn = createPillButton("İptal", false);
        TextView createBtn = createPillButton("Oluştur & İndir", true);
        actions.addView(cancelBtn);
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(-2, -2);
        createParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(createBtn, createParams);
        container.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(getActivity()).setView(container).create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        createBtn.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String remoteInput = urlInput.getText().toString().trim();
            if (name.isEmpty()) { Utils.showToastShort("Lütfen bir liste adı girin"); return; }
            OfflineStorage.createPlaylist(musicRoot, name, remoteInput);
            if (trackToAdd != null) OfflineStorage.addTrackToPlaylist(musicRoot, name, trackToAdd.videoId());
            selectedPlaylist = name;
            if (!remoteInput.isEmpty()) LocalDownloadManager.fetchAndDownloadPlaylist(musicRoot, name, remoteInput);
            else Utils.showToastShort(name + " listesi hazır");
            dialog.dismiss();
            showTracks();
        });
        dialog.show();
    }

    private void confirmDeletePlaylist(String playlistName) {
        confirm("Listeyi Sil", "\"" + playlistName + "\" oynatma listesi silinsin mi?", () -> {
            OfflineStorage.deletePlaylist(musicRoot, playlistName);
            if (playlistName.equalsIgnoreCase(selectedPlaylist)) selectedPlaylist = null;
            showTracks();
            Utils.showToastShort("Liste silindi");
        });
    }

    private void toggleSearch() {
        boolean opening = searchBar.getVisibility() != View.VISIBLE;
        InputMethodManager kb = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (opening) {
            searchBar.setVisibility(View.VISIBLE);
            slideSearchBar(dp(SEARCH_BAR_HEIGHT_DP));
            searchBar.animate().alpha(1f).setDuration(SEARCH_ANIMATION_MILLISECONDS).start();
            searchBar.requestFocus();
            if (kb != null) kb.showSoftInput(searchBar, InputMethodManager.SHOW_IMPLICIT);
            return;
        }
        if (kb != null) kb.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        searchBar.setText("");
        searchBar.clearFocus();
        slideSearchBar(0);
        searchBar.animate().alpha(0f).setDuration(SEARCH_ANIMATION_MILLISECONDS)
                .withEndAction(() -> searchBar.setVisibility(View.GONE)).start();
    }

    private void slideSearchBar(int target) {
        ValueAnimator anim = ValueAnimator.ofInt(searchBar.getHeight(), target);
        anim.setDuration(SEARCH_ANIMATION_MILLISECONDS);
        anim.addUpdateListener(v -> {
            searchBar.getLayoutParams().height = (int) v.getAnimatedValue();
            searchBar.requestLayout();
        });
        anim.start();
    }

    private void showSortMenu(View anchor) {
        String[] items = {
                str("morphe_music_downloads_sort_artist"),
                str("morphe_music_downloads_sort_title"),
                str("morphe_music_downloads_sort_size"),
        };
        showMenu(anchor, items, SortOrder.saved().ordinal(), pos -> {
            Settings.DOWNLOADS_SORT.save(SortOrder.values()[pos].name());
            showTracks();
        });
    }

    @Nullable
    private OfflineTrack findByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        for (OfflineTrack t : tracks) {
            if (t.audioFile().getAbsolutePath().equals(path)) return t;
        }
        return null;
    }

    private void showTracks() {
        loadTracks();
        rebuildPlaylistChips();
        songsList.removeAllViews();
        rowViews.clear();
        queuePaths = new ArrayList<>();

        List<OfflineTrack> displayed = new ArrayList<>();
        OfflineStorage.Playlist activePlaylist = findPlaylist(selectedPlaylist);
        int pending = 0;

        if (activePlaylist != null) {
            Map<String, OfflineTrack> byId = new HashMap<>();
            for (OfflineTrack t : tracks) byId.put(t.videoId(), t);
            for (String id : activePlaylist.videoIds()) {
                OfflineTrack t = byId.get(id);
                if (t != null) displayed.add(t);
                else pending++;
            }
        } else displayed.addAll(tracks);

        long totalBytes = 0;
        for (OfflineTrack t : displayed) totalBytes += t.audioFile().length();
        String sum = str("morphe_music_downloads_summary", displayed.size(), formatSize(totalBytes));
        if (pending > 0) sum += " • " + pending + " şarkı indiriliyor...";
        summary.setText(sum);

        for (OfflineTrack t : displayed) {
            if (!matchesQuery(t)) continue;
            queuePaths.add(t.audioFile().getAbsolutePath());
            songsList.addView(songRow(t));
        }

        if (queuePaths.isEmpty()) {
            String msg = activePlaylist != null
                    ? (pending > 0 ? pending + " şarkı arka planda otomatik indiriliyor..." : "Bu listede henüz şarkı yok.")
                    : str(tracks.isEmpty() ? "morphe_music_downloads_empty" : "morphe_music_downloads_nothing_found");
            TextView empty = text(msg, 15, secondary());
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

        ImageView artView = artworkView(null, ARTWORK_CORNER_DP);
        loadArtworkAsync(track, ROW_ARTWORK_PIXELS, artView);
        row.addView(artView, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, dp(8), 0);

        TextView name = text(track.displayTitle(), 15, Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);

        TextView detail = text(str("morphe_music_downloads_track_subtitle", track.displayArtist(),
                formatDuration(track.durationSeconds()), formatSize(track.audioFile().length())), 13, secondary());
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);

        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton menu = icon("yt_outline_experimental_overflow_vertical_vd_theme_24",
                str("morphe_music_downloads_actions_for", track.displayTitle()));
        menu.setOnClickListener(v -> showTrackMenu(menu, track));
        row.addView(menu, new LinearLayout.LayoutParams(dp(ICON_BUTTON_DP), dp(ICON_BUTTON_DP)));

        row.setOnClickListener(v -> { play(track); showFullScreenPlayer(); });
        return row;
    }

    private void loadArtworkAsync(OfflineTrack track, int maxSize, ImageView target) {
        String cacheKey = track.videoId() + "_" + maxSize;
        target.setTag(cacheKey);
        Bitmap cached = ARTWORK_CACHE.get(cacheKey);
        if (cached != null) {
            target.clearColorFilter();
            target.setPadding(0, 0, 0, 0);
            target.setImageBitmap(cached);
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return;
        }
        Utils.submitOnBackgroundThread(() -> {
            Bitmap decoded = track.artwork(maxSize);
            if (decoded != null) {
                ARTWORK_CACHE.put(cacheKey, decoded);
                Utils.runOnMainThread(() -> {
                    if (cacheKey.equals(target.getTag())) {
                        target.clearColorFilter();
                        target.setPadding(0, 0, 0, 0);
                        target.setImageBitmap(decoded);
                        target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    }
                });
            }
            return null;
        });
    }

    private void markPlayingRow() {
        int idx = queuePaths.indexOf(playingPath);
        for (int i = 0; i < rowViews.size(); i++) rowViews.get(i).setBackground(rowBackground(i == idx));
    }

    private Drawable rowBackground(boolean playing) {
        GradientDrawable card = null;
        if (playing) {
            int accent = activeAccentColor();
            card = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{
                            Color.argb(65, Color.red(accent), Color.green(accent), Color.blue(accent)),
                            Color.argb(20, Color.red(accent), Color.green(accent), Color.blue(accent))
                    });
            card.setCornerRadius(dp(ROW_CORNER_DP));
            card.setStroke(dp(1), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)));
        }
        return new RippleDrawable(ColorStateList.valueOf(rippleColor()), card,
                CustomDialog.createRoundedBackground(ROW_CORNER_DP, Color.WHITE));
    }

    private int rippleColor() {
        int accent = activeAccentColor();
        return Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent));
    }

    private LinearLayout createOriginalYtMusicMiniPlayer() {
        LinearLayout outer = new LinearLayout(getActivity());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.parseColor("#18121E"));
        outer.setClickable(true);
        outer.setFocusable(true);
        outer.setOnClickListener(v -> showFullScreenPlayer());

        miniProgressBar = new ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
        miniProgressBar.setMax(1000);
        miniProgressBar.setProgressDrawable(createAnimeProgressDrawable(dp(3), false));
        outer.addView(miniProgressBar, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout bar = new LinearLayout(getActivity());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(8), dp(8));

        miniArtwork = artworkView(null, 6);
        bar.addView(miniArtwork, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);

        miniTitle = text("", 14, Color.WHITE);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTitle.setTypeface(Typeface.DEFAULT_BOLD);

        miniArtist = text("", 12, Color.argb(185, 255, 255, 255));
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);

        labels.addView(miniTitle);
        labels.addView(miniArtist);
        bar.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        miniPlay = new ImageButton(getActivity());
        miniPlay.setBackground(createCircleRipple());
        miniPlay.setImageDrawable(createVectorIconDrawable(ICON_PLAY, activeAccentColor(), 0));
        miniPlay.setOnClickListener(v -> OfflinePlaybackService.toggle(getActivity()));
        bar.addView(miniPlay, new LinearLayout.LayoutParams(dp(44), dp(44)));

        miniNext = new ImageButton(getActivity());
        miniNext.setBackground(createCircleRipple());
        miniNext.setImageDrawable(createVectorIconDrawable(ICON_SKIP_NEXT, Color.WHITE, 0));
        miniNext.setOnClickListener(v -> OfflinePlaybackService.skipNext(getActivity()));
        bar.addView(miniNext, new LinearLayout.LayoutParams(dp(44), dp(44)));

        outer.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return outer;
    }

    private void showFullScreenPlayer() {
        Activity activity = getActivity();
        if (activity == null || (fullPlayerDialog != null && fullPlayerDialog.isShowing())) return;

        fullPlayerDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        fullPlayerRoot = new LinearLayout(activity);
        fullPlayerRoot.setOrientation(LinearLayout.VERTICAL);
        fullPlayerRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        fullPlayerRoot.setPadding(dp(24), dp(14), dp(24), dp(16));

        // 1. ÜST BAR (Daraltma Butonu + Şarkı | Video/Sıradaki Hapı + 3 Nokta Menü)
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, dp(4), 0, dp(10));

        ImageButton collapseBtn = new ImageButton(activity);
        collapseBtn.setImageDrawable(createVectorIconDrawable(ICON_CHEVRON_DOWN, Color.WHITE, 0));
        collapseBtn.setBackground(createCircleRipple());
        collapseBtn.setOnClickListener(v -> fullPlayerDialog.dismiss());
        topBar.addView(collapseBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        FrameLayout centerToggleWrap = new FrameLayout(activity);
        LinearLayout songVideoPill = new LinearLayout(activity);
        songVideoPill.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(18));
        pillBg.setColor(Color.argb(45, 255, 255, 255));
        songVideoPill.setBackground(pillBg);

        TextView songTab = text("Şarkı", 13, Color.WHITE);
        songTab.setTypeface(Typeface.DEFAULT_BOLD);
        songTab.setPadding(dp(18), dp(6), dp(18), dp(6));
        GradientDrawable activeTabBg = new GradientDrawable();
        activeTabBg.setCornerRadius(dp(18));
        activeTabBg.setColor(Color.argb(85, 255, 255, 255));
        songTab.setBackground(activeTabBg);

        TextView queueTab = text("Sıradaki", 13, Color.argb(195, 255, 255, 255));
        queueTab.setPadding(dp(16), dp(6), dp(16), dp(6));
        queueTab.setOnClickListener(v -> showUpNextQueueDialog());

        songVideoPill.addView(songTab);
        songVideoPill.addView(queueTab);
        centerToggleWrap.addView(songVideoPill, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        topBar.addView(centerToggleWrap, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton moreBtn = icon("yt_outline_experimental_overflow_vertical_vd_theme_24", "Menü");
        moreBtn.setColorFilter(Color.WHITE);
        moreBtn.setOnClickListener(v -> {
            OfflineTrack cur = findByPath(playingPath);
            if (cur != null) showTrackMenu(moreBtn, cur);
        });
        topBar.addView(moreBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        fullPlayerRoot.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        // 2. ALBÜM KAPAĞI
        FrameLayout artWrap = new FrameLayout(activity);
        int artSize = Math.min(dp(320), getResources().getDisplayMetrics().widthPixels - dp(52));
        fullArtwork = artworkView(null, FULL_ARTWORK_CORNER_DP);
        fullArtwork.setElevation(dp(14));
        artWrap.addView(fullArtwork, new FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER));
        fullPlayerRoot.addView(artWrap, new LinearLayout.LayoutParams(-1, 0, 1));

        // 3. ŞARKI ADI VE SANATÇI (Orijinal YT Music Düzeni)
        LinearLayout metaCol = new LinearLayout(activity);
        metaCol.setOrientation(LinearLayout.VERTICAL);
        metaCol.setPadding(dp(4), dp(12), dp(4), dp(10));

        fullTitle = text("", 22, Color.WHITE);
        fullTitle.setTypeface(Typeface.DEFAULT_BOLD);
        fullTitle.setSingleLine(true);
        fullTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        fullTitle.setMarqueeRepeatLimit(-1);
        fullTitle.setSelected(true);

        fullArtist = text("", 15, Color.argb(190, 255, 255, 255));
        fullArtist.setSingleLine(true);
        fullArtist.setEllipsize(TextUtils.TruncateAt.END);
        fullArtist.setPadding(0, dp(3), 0, 0);

        metaCol.addView(fullTitle);
        metaCol.addView(fullArtist);
        fullPlayerRoot.addView(metaCol, new LinearLayout.LayoutParams(-1, -2));

        // 4. ORİJİNAL YOUTUBE MUSIC AKSİYON HAPLARI SATIRI (Beğen | Tema | Kaydet | Bilgi)
        HorizontalScrollView actionsScroll = new HorizontalScrollView(activity);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setPadding(0, dp(2), 0, dp(12));

        LinearLayout actionsRow = new LinearLayout(activity);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView likePill = createYtActionPill("👍 Beğen  |  👎");
        likePill.setOnClickListener(v -> {
            likedCurrentTrack = !likedCurrentTrack;
            likePill.setText(likedCurrentTrack ? "❤️ Beğenildi  |  👎" : "👍 Beğen  |  👎");
            OfflineTrack cur = findByPath(playingPath);
            if (cur != null && likedCurrentTrack) {
                OfflineStorage.createPlaylist(musicRoot, "Beğendiğim Şarkılar", "LM");
                OfflineStorage.addTrackToPlaylist(musicRoot, "Beğendiğim Şarkılar", cur.videoId());
                Utils.showToastShort("Beğendiğim Şarkılar listesine eklendi");
            }
        });
        actionsRow.addView(likePill);

        themePillText = createYtActionPill("🎨 " + currentTheme.label);
        themePillText.setOnClickListener(v -> cycleAnimeTheme());
        actionsRow.addView(themePillText);

        TextView savePill = createYtActionPill("➕ Listeye Kaydet");
        savePill.setOnClickListener(v -> {
            OfflineTrack cur = findByPath(playingPath);
            if (cur != null) showAddToPlaylistMenu(savePill, cur);
        });
        actionsRow.addView(savePill);

        TextView infoPill = createYtActionPill("💬 Şarkı Bilgisi");
        infoPill.setOnClickListener(v -> showCurrentTrackDetailsDialog());
        actionsRow.addView(infoPill);

        actionsScroll.addView(actionsRow);
        fullPlayerRoot.addView(actionsScroll, new LinearLayout.LayoutParams(-1, -2));

        // 5. İLERLEME ÇUBUĞU (GARANTİLİ GÖRÜNÜR SEEKBAR VE SÜRELER)
        fullSeek = createFullPlayerSeekBar();
        fullPlayerRoot.addView(fullSeek, new LinearLayout.LayoutParams(-1, dp(SEEK_BAR_TOUCH_DP)));

        LinearLayout timeRow = new LinearLayout(activity);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setPadding(dp(4), 0, dp(4), dp(12));
        fullCurrentTime = text("0:00", 12, Color.argb(195, 255, 255, 255));
        fullTotalTime = text("0:00", 12, Color.argb(195, 255, 255, 255));
        fullTotalTime.setGravity(Gravity.END);
        timeRow.addView(fullCurrentTime, new LinearLayout.LayoutParams(0, -2, 1));
        timeRow.addView(fullTotalTime, new LinearLayout.LayoutParams(0, -2, 1));
        fullPlayerRoot.addView(timeRow, new LinearLayout.LayoutParams(-1, -2));

        // 6. KONTROL BUTONLARI (Karıştır, Önceki, Oynat/Duraklat, Sonraki, Tekrarla)
        LinearLayout controlsRow = new LinearLayout(activity);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setPadding(0, dp(4), 0, dp(18));

        fullShuffleBtn = new ImageButton(activity);
        fullShuffleBtn.setBackground(createCircleRipple());
        fullShuffleBtn.setOnClickListener(v -> {
            boolean en = OfflinePlaybackService.toggleShuffle();
            updateFullShuffleRepeatVisuals();
            Utils.showToastShort(en ? "Karışık çalma açık" : "Karışık çalma kapalı");
        });
        controlsRow.addView(fullShuffleBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        controlsRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));

        fullPrevBtn = new ImageButton(activity);
        fullPrevBtn.setBackground(createCircleRipple());
        fullPrevBtn.setImageDrawable(createVectorIconDrawable(ICON_SKIP_PREV, Color.WHITE, 0));
        fullPrevBtn.setOnClickListener(v -> OfflinePlaybackService.skipPrevious(activity));
        controlsRow.addView(fullPrevBtn, new LinearLayout.LayoutParams(dp(54), dp(54)));
        controlsRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));

        fullPlayBtn = new ImageButton(activity);
        fullPlayBtn.setElevation(dp(8));
        fullPlayBtn.setOnClickListener(v -> OfflinePlaybackService.toggle(activity));
        controlsRow.addView(fullPlayBtn, new LinearLayout.LayoutParams(dp(72), dp(72)));
        controlsRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));

        fullNextBtn = new ImageButton(activity);
        fullNextBtn.setBackground(createCircleRipple());
        fullNextBtn.setImageDrawable(createVectorIconDrawable(ICON_SKIP_NEXT, Color.WHITE, 0));
        fullNextBtn.setOnClickListener(v -> OfflinePlaybackService.skipNext(activity));
        controlsRow.addView(fullNextBtn, new LinearLayout.LayoutParams(dp(54), dp(54)));
        controlsRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));

        fullRepeatBtn = new ImageButton(activity);
        fullRepeatBtn.setBackground(createCircleRipple());
        fullRepeatBtn.setOnClickListener(v -> {
            OfflinePlaybackService.cycleRepeatMode();
            updateFullShuffleRepeatVisuals();
        });
        controlsRow.addView(fullRepeatBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        fullPlayerRoot.addView(controlsRow, new LinearLayout.LayoutParams(-1, -2));

        // 7. ALT SEKMELER (SIRADAKİ | TEMA GEÇİŞİ | İLGİLİ BİLGİ)
        LinearLayout bottomTabs = new LinearLayout(activity);
        bottomTabs.setOrientation(LinearLayout.HORIZONTAL);
        bottomTabs.setGravity(Gravity.CENTER);
        bottomTabs.setPadding(0, dp(6), 0, dp(4));

        TextView upNextTab = text("SIRADAKİ", 12, Color.WHITE);
        upNextTab.setTypeface(Typeface.DEFAULT_BOLD);
        upNextTab.setGravity(Gravity.CENTER);
        upNextTab.setPadding(dp(12), dp(10), dp(12), dp(10));
        upNextTab.setOnClickListener(v -> showUpNextQueueDialog());

        TextView lyricsThemeTab = text("ANİME TEMA", 12, Color.argb(185, 255, 255, 255));
        lyricsThemeTab.setTypeface(Typeface.DEFAULT_BOLD);
        lyricsThemeTab.setGravity(Gravity.CENTER);
        lyricsThemeTab.setPadding(dp(12), dp(10), dp(12), dp(10));
        lyricsThemeTab.setOnClickListener(v -> cycleAnimeTheme());

        TextView detailsTab = text("İLGİLİ", 12, Color.argb(185, 255, 255, 255));
        detailsTab.setTypeface(Typeface.DEFAULT_BOLD);
        detailsTab.setGravity(Gravity.CENTER);
        detailsTab.setPadding(dp(12), dp(10), dp(12), dp(10));
        detailsTab.setOnClickListener(v -> showCurrentTrackDetailsDialog());

        bottomTabs.addView(upNextTab, new LinearLayout.LayoutParams(0, -2, 1));
        bottomTabs.addView(lyricsThemeTab, new LinearLayout.LayoutParams(0, -2, 1));
        bottomTabs.addView(detailsTab, new LinearLayout.LayoutParams(0, -2, 1));
        fullPlayerRoot.addView(bottomTabs, new LinearLayout.LayoutParams(-1, -2));

        fullPlayerDialog.setContentView(fullPlayerRoot);
        if (fullPlayerDialog.getWindow() != null) {
            fullPlayerDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        OfflineTrack current = findByPath(playingPath);
        if (current != null) applyTrackVisuals(current);
        updateDynamicPlayerColors();
        updatePlayIcon(showingPause);
        fullPlayerDialog.show();
    }

    private TextView createYtActionPill(String label) {
        TextView pill = text(label, 13, Color.WHITE);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(Color.argb(38, 255, 255, 255));
        bg.setStroke(dp(1), Color.argb(65, 255, 255, 255));
        pill.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), bg, bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(10), 0);
        pill.setLayoutParams(lp);
        return pill;
    }

    /**
     * Özel Canvas Çizimli İlerleme Çubuğu: Her Android sürümünde arka izi (track)
     * ve anime temalı renk geçişli (gradient) doluluk çubuğunu %100 görünür çizer.
     */
    private Drawable createAnimeProgressDrawable(final int barHeightPx, final boolean rounded) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected boolean onLevelChange(int level) {
                invalidateSelf();
                return true;
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) return;

                float cy = b.exactCenterY();
                float halfH = barHeightPx / 2f;
                float radius = rounded ? halfH : 0f;

                // 1. Arka Plan İzi (Her zaman net görünür yarı saydam beyaz/gri iz)
                paint.setShader(null);
                paint.setColor(Color.argb(85, 255, 255, 255));
                RectF trackRect = new RectF(b.left, cy - halfH, b.right, cy + halfH);
                canvas.drawRoundRect(trackRect, radius, radius, paint);

                // 2. Oynatılan Kısım (Evangelion / Cowboy Bebop / Berserk Geçişli)
                float ratio = Math.max(0f, Math.min(1f, getLevel() / 10000f));
                float playedRight = b.left + b.width() * ratio;
                if (playedRight > b.left) {
                    RectF playedRect = new RectF(b.left, cy - halfH, playedRight, cy + halfH);
                    paint.setShader(new LinearGradient(
                            b.left, cy, b.right, cy,
                            new int[]{activeAccentColor(), activeSecondaryAccent()},
                            null, Shader.TileMode.CLAMP));
                    canvas.drawRoundRect(playedRect, radius, radius, paint);
                }
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private SeekBar createFullPlayerSeekBar() {
        SeekBar seek = new SeekBar(getActivity());
        seek.setPadding(dp(10), 0, dp(10), 0);
        seek.setProgressDrawable(createAnimeProgressDrawable(dp(SEEK_BAR_HEIGHT_DP), true));

        Drawable thumb = new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            public void draw(@NonNull Canvas canvas) {
                float cx = getBounds().exactCenterX();
                float cy = getBounds().exactCenterY();
                p.setColor(activeAccentColor());
                canvas.drawCircle(cx, cy, dp(SEEK_THUMB_DP) / 2f, p);
                p.setColor(Color.WHITE);
                canvas.drawCircle(cx, cy, dp(SEEK_THUMB_DP) / 4f, p);
            }
            @Override public int getIntrinsicWidth() { return dp(SEEK_THUMB_DP); }
            @Override public int getIntrinsicHeight() { return dp(SEEK_THUMB_DP); }
            @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
            @Override public void setColorFilter(@Nullable ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        seek.setThumb(thumb);
        seek.setThumbOffset(dp(SEEK_THUMB_DP) / 2);
        seek.setBackground(null);
        seek.setSplitTrack(false);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                OfflinePlaybackService.seekTo(bar.getProgress());
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && fullCurrentTime != null) fullCurrentTime.setText(formatDuration(value / 1000));
            }
        });
        return seek;
    }

    private void showUpNextQueueDialog() {
        List<String> queue = OfflinePlaybackService.getCurrentQueue();
        if (queue.isEmpty()) { Utils.showToastShort("Sırada şarkı yok"); return; }

        LinearLayout sheet = new LinearLayout(getActivity());
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(16), dp(18), dp(16), dp(16));
        sheet.setBackground(menuBackground());

        TextView header = text("Sıradaki Şarkılar (" + queue.size() + ")", 17, Color.WHITE);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(8), 0, dp(8), dp(12));
        sheet.addView(header);

        ScrollView scroll = new ScrollView(getActivity());
        LinearLayout list = new LinearLayout(getActivity());
        list.setOrientation(LinearLayout.VERTICAL);
        AlertDialog dialog = new AlertDialog.Builder(getActivity()).setView(sheet).create();

        int activeIdx = OfflinePlaybackService.getCurrentQueueIndex();
        for (int i = 0; i < queue.size(); i++) {
            final int index = i;
            OfflineTrack t = findByPath(queue.get(i));
            if (t == null) t = OfflineTrack.load(new File(queue.get(i)));

            LinearLayout item = new LinearLayout(getActivity());
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8), dp(8), dp(8), dp(8));
            item.setBackground(rowBackground(i == activeIdx));

            ImageView art = artworkView(null, 4);
            loadArtworkAsync(t, 128, art);
            item.addView(art, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout col = new LinearLayout(getActivity());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            TextView title = text(t.displayTitle(), 14, Color.WHITE);
            if (i == activeIdx) title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            TextView sub = text(t.displayArtist() + " • " + formatDuration(t.durationSeconds()), 12, secondary());
            sub.setSingleLine(true);
            col.addView(title);
            col.addView(sub);
            item.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            item.setOnClickListener(v -> { OfflinePlaybackService.playAtQueueIndex(index); dialog.dismiss(); });
            list.addView(item);
        }
        scroll.addView(list);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, dp(360)));
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.show();
    }

    private void showCurrentTrackDetailsDialog() {
        OfflineTrack cur = findByPath(playingPath);
        if (cur == null) return;
        String info = "Başlık: " + cur.displayTitle() + "\nSanatçı: " + cur.displayArtist()
                + "\nAlbüm: " + cur.displayAlbum() + "\nSüre: " + formatDuration(cur.durationSeconds())
                + "\nBoyut: " + formatSize(cur.audioFile().length()) + " MB\nVideo ID: " + cur.videoId()
                + "\nAktif Tema: " + currentTheme.label;
        CustomDialog.create(getActivity(), "Şarkı Bilgisi", info, null, "Tamam", () -> {}, null, null, null, false).first.show();
    }

    private void updateFullShuffleRepeatVisuals() {
        int accent = activeAccentColor();
        if (fullShuffleBtn != null) {
            int c = OfflinePlaybackService.isShuffleEnabled() ? accent : Color.argb(135, 255, 255, 255);
            fullShuffleBtn.setImageDrawable(createVectorIconDrawable(ICON_SHUFFLE, c,
                    OfflinePlaybackService.isShuffleEnabled() ? 1 : 0));
        }
        if (fullRepeatBtn != null) {
            int r = OfflinePlaybackService.getRepeatMode();
            int c = r != OfflinePlaybackService.REPEAT_OFF ? accent : Color.argb(135, 255, 255, 255);
            fullRepeatBtn.setImageDrawable(createVectorIconDrawable(ICON_REPEAT, c, r));
        }
    }

    private Drawable createVectorIconDrawable(int type, int color, int mode) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            public void draw(@NonNull Canvas canvas) {
                float cx = getBounds().width() / 2f, cy = getBounds().height() / 2f;
                paint.setColor(color);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);

                if (type == ICON_PLAY) {
                    paint.setStyle(Paint.Style.FILL);
                    float s = dp(10);
                    Path p = new Path();
                    p.moveTo(cx - s * 0.65f, cy - s);
                    p.lineTo(cx + s * 0.95f, cy);
                    p.lineTo(cx - s * 0.65f, cy + s);
                    p.close();
                    canvas.drawPath(p, paint);
                } else if (type == ICON_PAUSE) {
                    paint.setStyle(Paint.Style.FILL);
                    float w = dp(3.5f), h = dp(10), gap = dp(4.5f);
                    canvas.drawRoundRect(new RectF(cx - gap - w, cy - h, cx - gap + w, cy + h), dp(2), dp(2), paint);
                    canvas.drawRoundRect(new RectF(cx + gap - w, cy - h, cx + gap + w, cy + h), dp(2), dp(2), paint);
                } else if (type == ICON_SKIP_PREV) {
                    paint.setStyle(Paint.Style.FILL);
                    float s = dp(8);
                    canvas.drawRoundRect(new RectF(cx - s, cy - s, cx - s + dp(2.5f), cy + s), dp(1), dp(1), paint);
                    Path p = new Path();
                    p.moveTo(cx + s, cy - s);
                    p.lineTo(cx - s + dp(3), cy);
                    p.lineTo(cx + s, cy + s);
                    p.close();
                    canvas.drawPath(p, paint);
                } else if (type == ICON_SKIP_NEXT) {
                    paint.setStyle(Paint.Style.FILL);
                    float s = dp(8);
                    canvas.drawRoundRect(new RectF(cx + s - dp(2.5f), cy - s, cx + s, cy + s), dp(1), dp(1), paint);
                    Path p = new Path();
                    p.moveTo(cx - s, cy - s);
                    p.lineTo(cx + s - dp(3), cy);
                    p.lineTo(cx - s, cy + s);
                    p.close();
                    canvas.drawPath(p, paint);
                } else if (type == ICON_PALETTE) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    canvas.drawCircle(cx, cy, dp(8), paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx - dp(3), cy - dp(2), dp(1.6f), paint);
                    canvas.drawCircle(cx + dp(3), cy - dp(2), dp(1.6f), paint);
                    canvas.drawCircle(cx, cy + dp(3), dp(1.6f), paint);
                } else if (type == ICON_CHEVRON_DOWN) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2.2f));
                    float s = dp(7);
                    Path p = new Path();
                    p.moveTo(cx - s, cy - s / 2f);
                    p.lineTo(cx, cy + s / 2f);
                    p.lineTo(cx + s, cy - s / 2f);
                    canvas.drawPath(p, paint);
                } else if (type == ICON_SHUFFLE) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    float s = dp(7);
                    Path p = new Path();
                    p.moveTo(cx - s, cy - s * 0.6f);
                    p.lineTo(cx + s, cy + s * 0.6f);
                    p.moveTo(cx - s, cy + s * 0.6f);
                    p.lineTo(cx + s, cy - s * 0.6f);
                    canvas.drawPath(p, paint);
                    if (mode != 0) {
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy + dp(11), dp(2.2f), paint);
                    }
                } else if (type == ICON_REPEAT) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    float r = dp(7);
                    canvas.drawRoundRect(new RectF(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f), dp(4), dp(4), paint);
                    if (mode == OfflinePlaybackService.REPEAT_ONE) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setTextSize(dp(9));
                        paint.setTypeface(Typeface.DEFAULT_BOLD);
                        paint.setTextAlign(Paint.Align.CENTER);
                        canvas.drawText("1", cx, cy + dp(3), paint);
                    }
                    if (mode != OfflinePlaybackService.REPEAT_OFF) {
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy + dp(11), dp(2.2f), paint);
                    }
                }
            }
            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private Drawable createCircleRipple() {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(65, 255, 255, 255)), null, new ShapeDrawable(new OvalShape()));
    }

    private TextView createPillButton(String label, boolean active) {
        int accent = activeAccentColor();
        TextView view = text(label, 12, active ? currentTheme.playIconDark : Color.WHITE);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (active) {
            bg.setColors(new int[]{accent, activeSecondaryAccent()});
            bg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        } else {
            bg.setColor(Color.argb(40, 255, 255, 255));
            bg.setStroke(dp(1), Color.argb(95, 255, 255, 255));
        }
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), bg, bg));
        return view;
    }

    private void showTrackMenu(View anchor, OfflineTrack track) {
        if (selectedPlaylist != null) {
            String[] items = {str("morphe_music_downloads_play"), "Başka Oynatma Listesine Ekle", "Bu Listeden Çıkar", str("morphe_music_downloads_delete_track")};
            showMenu(anchor, items, -1, pos -> {
                switch (pos) {
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
            String[] items = {str("morphe_music_downloads_play"), "Oynatma Listesine Ekle", str("morphe_music_downloads_delete_track")};
            showMenu(anchor, items, -1, pos -> {
                switch (pos) {
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
        for (int i = 0; i < playlists.size(); i++) items[i + 1] = playlists.get(i).name();
        showMenu(anchor, items, -1, pos -> {
            if (pos == 0) showCreateOrAutoDownloadPlaylistDialog(track);
            else {
                String target = playlists.get(pos - 1).name();
                if (OfflineStorage.addTrackToPlaylist(musicRoot, target, track.videoId())) {
                    showTracks();
                    Utils.showToastShort(target + " listesine eklendi");
                } else Utils.showToastShort("Şarkı zaten bu listede var");
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
            @NonNull @Override
            public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView v = text(getItem(pos), 15, selected < 0 || pos == selected ? Color.WHITE : secondary());
                v.setPadding(dp(20), dp(14), dp(20), dp(14));
                return v;
            }
        });
        popup.setOnItemClickListener((p, v, pos, id) -> { popup.dismiss(); onPick.accept(pos); });
        popup.show();
    }

    private Drawable menuBackground() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{currentTheme.gradMid, currentTheme.gradBottom});
        bg.setCornerRadius(dp(MENU_CORNER_DP));
        bg.setStroke(dp(1), activeAccentColor());
        return bg;
    }

    private void confirmDelete(OfflineTrack track) {
        confirm(str("morphe_music_downloads_delete_track_title"),
                str("morphe_music_downloads_delete_track_message", track.displayTitle()), () -> deleteTrack(track));
    }

    private void confirmDeleteAll() {
        if (!tracks.isEmpty()) confirm(str("morphe_music_downloads_delete_all_title"),
                str("morphe_music_downloads_delete_all_message"), this::deleteAll);
    }

    private void confirm(String title, String message, Runnable onConfirm) {
        CustomDialog.create(getActivity(), title, message, null, str("morphe_music_downloads_delete"),
                onConfirm, () -> {}, null, null, false).first.show();
    }

    private void deleteAll() {
        for (OfflineTrack t : new ArrayList<>(tracks)) {
            OfflineStorage.delete(t.audioFile());
            deleteSidecars(t.videoId());
            OfflineStorage.removeTrackFromAllPlaylists(musicRoot, t.videoId());
        }
        ARTWORK_CACHE.evictAll();
        showTracks();
        Utils.showToastShort(str("morphe_music_downloads_all_deleted"));
    }

    private void deleteSidecars(String id) {
        OfflineStorage.delete(new File(musicRoot, id + ".json"));
        OfflineStorage.delete(new File(musicRoot, id + ".jpg"));
        OfflineStorage.delete(new File(musicRoot, id + ".webm.part"));
        OfflineStorage.delete(new File(musicRoot, id + ".m4a.part"));
    }

    private void deleteTrack(OfflineTrack t) {
        boolean deleted = t.audioFile().delete();
        deleteSidecars(t.videoId());
        OfflineStorage.removeTrackFromAllPlaylists(musicRoot, t.videoId());
        if (!deleted) { Utils.showToastShort(str("morphe_music_downloads_track_delete_failed")); return; }
        showTracks();
        Utils.showToastShort(str("morphe_music_downloads_track_deleted"));
    }

    private void play(OfflineTrack track) {
        ArrayList<String> queue = new ArrayList<>(queuePaths);
        playWithCustomQueue(track, queue, Math.max(0, queue.indexOf(track.audioFile().getAbsolutePath())));
    }

    private void playWithCustomQueue(OfflineTrack track, ArrayList<String> queue, int queueIndex) {
        try {
            Intent intent = new Intent(getActivity(), OfflinePlaybackService.class)
                    .setAction(OfflinePlaybackService.ACTION_PLAY_FILE)
                    .putExtra(OfflinePlaybackService.EXTRA_PATH, track.audioFile().getAbsolutePath())
                    .putExtra(OfflinePlaybackService.EXTRA_TITLE, track.displayTitle())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTIST, track.displayArtist())
                    .putExtra(OfflinePlaybackService.EXTRA_ARTWORK_PATH, track.artworkFile().getAbsolutePath())
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
    public void onPlaybackChanged(String path, String title, boolean playing, int position, int duration) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (miniPlayer == null) return;
            miniPlayer.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

            if (!path.equals(playingPath)) {
                playingPath = path;
                likedCurrentTrack = false;
                OfflineTrack t = findByPath(path);
                if (t != null) applyTrackVisuals(t);
                else {
                    miniTitle.setText(title);
                    if (fullTitle != null) fullTitle.setText(title);
                }
                markPlayingRow();
            }
            updateFullShuffleRepeatVisuals();
            updatePlayIcon(playing);

            int safeDur = Math.max(1, duration);
            if (miniProgressBar != null) {
                miniProgressBar.setMax(safeDur);
                miniProgressBar.setProgress(position);
            }
            if (fullSeek != null && !userSeeking) {
                fullSeek.setMax(safeDur);
                fullSeek.setProgress(position, true);
                if (fullCurrentTime != null) fullCurrentTime.setText(formatDuration(position / 1000));
                if (fullTotalTime != null) fullTotalTime.setText(formatDuration(duration / 1000));
            }
        });
    }

    private void updatePlayIcon(boolean playing) {
        showingPause = playing;
        int iconType = playing ? ICON_PAUSE : ICON_PLAY;
        if (fullPlayBtn != null) {
            GradientDrawable playCircle = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{activeAccentColor(), activeSecondaryAccent()});
            playCircle.setShape(GradientDrawable.OVAL);
            fullPlayBtn.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), playCircle, playCircle));
            fullPlayBtn.setImageDrawable(createVectorIconDrawable(iconType, currentTheme.playIconDark, 0));
        }
        if (miniPlay != null) {
            miniPlay.setImageDrawable(createVectorIconDrawable(iconType, activeAccentColor(), 0));
        }
    }

    private void applyTrackVisuals(OfflineTrack track) {
        miniTitle.setText(track.displayTitle());
        miniArtist.setText(track.displayArtist());
        loadArtworkAsync(track, ROW_ARTWORK_PIXELS, miniArtwork);

        if (fullTitle != null) {
            fullTitle.setText(track.displayTitle());
            fullArtist.setText(track.displayArtist());
        }
        if (fullArtwork != null) {
            loadArtworkAsync(track, FULL_ARTWORK_PIXELS, fullArtwork);
        }
        if (fullPlayerRoot != null) {
            fullPlayerRoot.setBackground(buildAnimeBackgroundGradient(track));
        }
    }

    private ImageView artworkView(@Nullable Bitmap bmp, int cornerDp) {
        ImageView v = new ImageView(getActivity());
        v.setBackgroundColor(Color.argb(45, 255, 255, 255));
        roundCorners(v, cornerDp);
        if (bmp != null) {
            v.setImageBitmap(bmp);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            v.setImageDrawable(createVectorIconDrawable(ICON_PLAY, activeAccentColor(), 0));
            v.setPadding(dp(12), dp(12), dp(12), dp(12));
        }
        return v;
    }

    private void roundCorners(View v, int cornerDp) {
        final float r = dp(cornerDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private TextView text(String val, int sp, int color) {
        TextView v = new TextView(getActivity());
        v.setText(val);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private ImageButton icon(String drawable, String desc) {
        ImageButton b = icon(ResourceUtils.getDrawable(drawable), desc);
        b.setColorFilter(Color.WHITE);
        return b;
    }

    private ImageButton icon(Drawable drawable, String desc) {
        ImageButton b = new ImageButton(getActivity());
        b.setImageDrawable(drawable);
        b.setContentDescription(desc);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor()), null, new ShapeDrawable(new OvalShape())));
        return b;
    }

    private static int secondary() { return Color.argb(180, 230, 230, 240); }
    private int dp(int val) { return (int) (val * getResources().getDisplayMetrics().density + 0.5f); }
    private float dp(float val) { return val * getResources().getDisplayMetrics().density; }

    private static String formatDuration(int s) {
        if (s <= 0) return "0:00";
        return s < 3600 ? String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static String formatSize(long b) { return String.format(Locale.ROOT, "%.1f", b / 1048576.0); }
}