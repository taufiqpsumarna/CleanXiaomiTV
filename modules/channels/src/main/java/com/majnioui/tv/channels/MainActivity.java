package com.majnioui.tv.channels;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live channels from free streams the broadcasters publish (see ChannelList), laid out like a streaming
 * app's Live TV page: a hero at the top with the focused channel's name and a live preview, and below it
 * one row of channel cards per group. Moving between cards updates the hero at once and switches the
 * preview after a short pause; OK goes full screen. Full screen: Up/Down and CH+/CH- switch channels,
 * OK or Back return to the cards, Back on the cards leaves.
 *
 * One SurfaceView serves both the preview and full screen: it punches a hole through the dark window
 * background, so nothing in the cards layer covers the video. No blur, one player, cards are plain views
 * (a few dozen channels at most).
 */
public class MainActivity extends Activity {
    private static final String TAG = "Channels";
    private static final long BAR_MS = 4000;
    private static final long PREVIEW_DELAY_MS = 700;
    private static final int BG = 0xFF0E0E12;
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    // hero preview, in dp on a 960x540 dp screen
    private static final int PV_W = 448, PV_H = 252, PV_TOP = 28, PV_RIGHT = 48, PV_RADIUS = 14;
    private static final int CARD_W = 168, CARD_H = 95;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> logos = new LruCache<>(80);
    /** Logo URL to "mostly light pixels", so a white logo gets a dark card and a dark logo a light one. */
    private final Map<String, Boolean> lightLogo = new HashMap<>();
    private Typeface font;
    private int accent;
    private SharedPreferences prefs;

    private List<ChannelList.Channel> channels = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    /** Cards per row, top to bottom, for D-pad moves the app makes itself. */
    private final List<List<Card>> grid = new ArrayList<>();
    private final List<View> rowTitles = new ArrayList<>();
    private int current = -1;   // playing
    private int focused = -1;   // card with focus
    private boolean fullscreen;

    private FrameLayout root;
    private LinearLayout browse;
    private LinearLayout rowsBox;
    private ScrollView rowsScroll;
    private TextView heroName, heroMeta, heroCount;
    private SurfaceView surface;
    private View mask;
    private TextView previewLive;
    private TextView message;
    private LinearLayout bar;
    private ImageView barLogo;
    private TextView barName, barGroup;
    private ExoPlayer player;
    private boolean rendered;
    private int retries;
    /** Channels that failed on every link this session; their cards are dimmed and say Offline. */
    private final java.util.Set<String> offline = new java.util.HashSet<>();
    private final Runnable hideBar = () -> bar.animate().alpha(0f).setDuration(400).start();
    private final Runnable previewFocused = () -> {
        if (!fullscreen && focused >= 0 && focused != current) play(focused);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        font = getResources().getFont(R.font.outfit);
        accent = getColor(R.color.accent);
        prefs = getSharedPreferences("channels", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        buildBrowse();
        surface = new SurfaceView(this);
        root.addView(surface, previewParams());
        mask = new View(this);
        mask.setBackground(new CornerMask(BG, dp(PV_RADIUS)));
        root.addView(mask, previewParams());
        previewLive = livePill(12);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.setMargins(0, dp(PV_TOP + 12), dp(PV_RIGHT + PV_W - 64), 0);
        root.addView(previewLive, plp);
        message = text(17, 400, 0xCCFFFFFF);
        message.setGravity(Gravity.CENTER);
        root.addView(message, previewParams());
        buildBar();
        setContentView(root);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setChannels(ChannelList.load(this));
        if (ChannelList.refreshDue(this)) {
            io.execute(() -> {
                List<ChannelList.Channel> fresh = ChannelList.refresh(this);
                if (fresh != null) main.post(() -> setChannels(fresh));
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (channels.isEmpty()) return;
        int last = indexOf(prefs.getString("last", ""));
        showBrowse(last >= 0 ? last : 0);
        play(last >= 0 ? last : 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(previewFocused);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** New list (bundled, saved or refreshed): rebuild the rows, keep the channel that is playing. */
    private void setChannels(List<ChannelList.Channel> fresh) {
        String playingId = current >= 0 && current < channels.size() ? channels.get(current).id : null;
        String focusedId = focused >= 0 && focused < channels.size() ? channels.get(focused).id : null;
        channels = fresh;
        current = playingId == null ? -1 : indexOf(playingId);
        focused = focusedId == null ? -1 : indexOf(focusedId);
        buildRows();
        heroCount.setText(channels.size() + " channel live");
        if (!fullscreen && focused >= 0) cards.get(focused).view.requestFocus();
    }

    private int indexOf(String id) {
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(id)) return i;
        return -1;
    }

    // ---- cards page -----------------------------------------------------------------------------

    private void buildBrowse() {
        browse = new LinearLayout(this);
        browse.setOrientation(LinearLayout.VERTICAL);

        // hero: brand line, then the focused channel's details on the left; the preview sits on the right
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(48), dp(32), dp(PV_RIGHT + PV_W + 32), 0);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(accent);
        dot.setBackground(d);
        brand.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView brandText = text(17, 700, 0xFFFFFFFF);
        brandText.setText("Live TV");
        brandText.setPadding(dp(10), 0, 0, 0);
        brand.addView(brandText);
        heroCount = text(14, 400, 0x80FFFFFF);
        heroCount.setPadding(dp(14), 0, 0, 0);
        brand.addView(heroCount);
        hero.addView(brand);

        heroName = text(34, 700, 0xFFFFFFFF);
        heroName.setSingleLine(true);
        heroName.setEllipsize(TextUtils.TruncateAt.END);
        heroName.setPadding(0, dp(72), 0, 0);
        hero.addView(heroName);

        LinearLayout metaLine = new LinearLayout(this);
        metaLine.setGravity(Gravity.CENTER_VERTICAL);
        metaLine.setPadding(0, dp(12), 0, 0);
        metaLine.addView(livePill(12));
        heroMeta = text(15, 400, 0xA6FFFFFF);
        heroMeta.setPadding(dp(12), 0, 0, 0);
        metaLine.addView(heroMeta);
        hero.addView(metaLine);

        TextView hint = text(13, 400, 0x66FFFFFF);
        hint.setText("OK  Tonton layar penuh     ◀ ▶ ▲ ▼  Pilih channel");
        hint.setPadding(0, dp(18), 0, 0);
        hero.addView(hint);

        browse.addView(hero, new LinearLayout.LayoutParams(-1, dp(PV_TOP + PV_H + 6)));

        rowsScroll = new ScrollView(this);
        rowsScroll.setVerticalScrollBarEnabled(false);
        rowsScroll.setSmoothScrollingEnabled(true);
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setPadding(0, 0, 0, dp(120));
        rowsScroll.addView(rowsBox, new FrameLayout.LayoutParams(-1, -2));
        browse.addView(rowsScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(browse, new FrameLayout.LayoutParams(-1, -1));
    }

    /** One row per group: its title, then the group's cards side by side. */
    private void buildRows() {
        rowsBox.removeAllViews();
        cards.clear();
        grid.clear();
        rowTitles.clear();
        LinearLayout strip = null;
        String group = null;
        for (int i = 0; i < channels.size(); i++) {
            ChannelList.Channel c = channels.get(i);
            if (!c.group.equals(group)) {
                group = c.group;
                TextView title = text(17, 600, 0xFFFFFFFF);
                title.setText(group);
                title.setPadding(dp(48), dp(cards.isEmpty() ? 6 : 12), 0, dp(4));
                rowsBox.addView(title);
                rowTitles.add(title);
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                hs.setClipChildren(false);
                hs.setClipToPadding(false);
                hs.setSmoothScrollingEnabled(true);
                strip = new LinearLayout(this);
                strip.setClipChildren(false);
                strip.setPadding(dp(48), dp(4), dp(48), dp(6));
                hs.addView(strip, new FrameLayout.LayoutParams(-2, -2));
                rowsBox.addView(hs, new LinearLayout.LayoutParams(-1, -2));
                grid.add(new ArrayList<>());
            }
            Card card = new Card(i, grid.size() - 1, grid.get(grid.size() - 1).size());
            grid.get(grid.size() - 1).add(card);
            cards.add(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), -2);
            lp.rightMargin = dp(18);
            strip.addView(card.view, lp);
        }
        for (Card card : cards) card.bindPlaying();
    }

    /** Logo on a rounded tile with the name under it; a red ring when focused. */
    private final class Card {
        final int index, row, col;
        final LinearLayout view;
        final FrameLayout tile;
        final GradientDrawable tileBg;
        final ImageView logo;
        final TextView name;
        final TextView live;
        final TextView off;

        Card(int index, int row, int col) {
            this.index = index;
            this.row = row;
            this.col = col;
            ChannelList.Channel c = channels.get(index);
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setFocusable(true);
            view.setClipChildren(false);

            tile = new FrameLayout(MainActivity.this);
            tileBg = new GradientDrawable();
            tileBg.setCornerRadius(dp(10));
            tileBg.setColor(0xFF24242B);
            tile.setBackground(tileBg);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(10));
            ring.setStroke(dp(4), accent);
            StateListDrawable fg = new StateListDrawable();
            fg.addState(new int[] {android.R.attr.state_focused}, ring);
            tile.setForeground(fg);
            tile.setDuplicateParentStateEnabled(true);
            logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -1);
            llp.setMargins(dp(22), dp(16), dp(22), dp(16));
            tile.addView(logo, llp);
            live = livePill(10);
            FrameLayout.LayoutParams lv = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            lv.setMargins(dp(7), dp(7), 0, 0);
            tile.addView(live, lv);
            off = text(10, 600, 0xFFFFFFFF);
            off.setText("OFFLINE");
            off.setLetterSpacing(0.08f);
            off.setPadding(dp(7), dp(3), dp(7), dp(3));
            GradientDrawable ob = new GradientDrawable();
            ob.setCornerRadius(dp(4));
            ob.setColor(0xCC3A3A42);
            off.setBackground(ob);
            FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            ol.setMargins(0, 0, dp(7), dp(7));
            tile.addView(off, ol);
            view.addView(tile, new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)));

            name = text(14, 500, 0x99FFFFFF);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(2), dp(9), dp(2), 0);
            name.setText(c.name);
            view.addView(name);

            view.setOnFocusChangeListener((v, has) -> {
                name.setTextColor(has ? 0xFFFFFFFF : 0x99FFFFFF);
                if (has) {
                    onCardFocused(index);
                    // the focused row's title sits at the top of the list
                    View title = rowTitles.get(row);
                    rowsScroll.post(() -> rowsScroll.smoothScrollTo(0, title.getTop()));
                }
            });
            view.setOnClickListener(v -> {
                if (index != current) play(index);
                showFullscreen();
            });

            final String want = c.logo;
            loadLogo(want, bmp -> {
                logo.setImageBitmap(bmp);
                Boolean light = lightLogo.get(want);
                tileBg.setColor(light != null && !light ? 0xFFF1F1F4 : 0xFF24242B);
            });
        }

        void bindPlaying() {
            boolean down = offline.contains(channels.get(index).id);
            live.setVisibility(index == current && !down ? View.VISIBLE : View.GONE);
            off.setVisibility(down ? View.VISIBLE : View.GONE);
            logo.setAlpha(down ? 0.35f : 1f);
        }
    }

    private void onCardFocused(int index) {
        focused = index;
        ChannelList.Channel c = channels.get(index);
        heroName.setText(c.name);
        heroMeta.setText(c.group + "   ·   " + (index + 1));
        main.removeCallbacks(previewFocused);
        main.postDelayed(previewFocused, PREVIEW_DELAY_MS);
    }

    private void showBrowse(int focus) {
        fullscreen = false;
        if (player != null) player.setVolume(0f);
        main.removeCallbacks(hideBar);
        bar.animate().cancel();
        bar.setAlpha(0f);
        browse.setVisibility(View.VISIBLE);
        mask.setVisibility(View.VISIBLE);
        previewLive.setVisibility(View.VISIBLE);
        surface.setLayoutParams(previewParams());
        message.setLayoutParams(previewParams());
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (focus >= 0 && focus < cards.size()) {
            Card card = cards.get(focus);
            card.view.requestFocus();
            onCardFocused(focus);
        }
    }

    private void showFullscreen() {
        if (current < 0) return;
        fullscreen = true;
        if (player != null) player.setVolume(1f);
        main.removeCallbacks(previewFocused);
        browse.setVisibility(View.GONE);
        mask.setVisibility(View.GONE);
        previewLive.setVisibility(View.GONE);
        message.setLayoutParams(new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        if (player != null && player.getVideoSize().width > 0) fitSurface(player.getVideoSize());
        else surface.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        showBar(channels.get(current), current);
    }

    private FrameLayout.LayoutParams previewParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(PV_W), dp(PV_H), Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(PV_TOP), dp(PV_RIGHT), 0);
        return lp;
    }

    // ---- player ---------------------------------------------------------------------------------

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(48), dp(70), dp(48), dp(36));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF00E0E12, 0xB30E0E12, 0x000E0E12}));
        FrameLayout logoBox = new FrameLayout(this);
        GradientDrawable lb = new GradientDrawable();
        lb.setCornerRadius(dp(10));
        lb.setColor(0xFFF1F1F4);
        logoBox.setBackground(lb);
        logoBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        barLogo = new ImageView(this);
        barLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logoBox.addView(barLogo, new FrameLayout.LayoutParams(-1, -1));
        bar.addView(logoBox, new LinearLayout.LayoutParams(dp(112), dp(63)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(20), 0, 0, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(livePill(12));
        barName = text(26, 700, 0xFFFFFFFF);
        barName.setPadding(dp(12), 0, 0, 0);
        top.addView(barName);
        texts.addView(top);
        barGroup = text(15, 400, 0xA6FFFFFF);
        barGroup.setPadding(0, dp(8), 0, 0);
        texts.addView(barGroup);
        bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView hint = text(13, 400, 0x80FFFFFF);
        hint.setText("▲ ▼  Ganti channel     OK  Semua channel");
        bar.addView(hint);
        bar.setAlpha(0f);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private void play(int index) {
        if (channels.isEmpty()) return;
        index = (index % channels.size() + channels.size()) % channels.size();
        int previous = current;
        current = index;
        final ChannelList.Channel c = channels.get(index);
        prefs.edit().putString("last", c.id).apply();
        if (previous >= 0 && previous < cards.size()) cards.get(previous).bindPlaying();
        if (index < cards.size()) cards.get(index).bindPlaying();
        message.setText("Memuat…");
        message.setVisibility(View.VISIBLE);
        if (fullscreen) showBar(c, index);
        playLink(c, index, false);
    }

    /** Plays the channel's link, or its backup (the bundled link) once the refreshed one has failed. */
    private void playLink(ChannelList.Channel c, int index, boolean backup) {
        rendered = false;
        retries = 0;
        final String url = backup ? c.backupUrl : c.url;
        final String ua = backup ? c.backupUserAgent : c.userAgent;
        final String ref = backup ? c.backupReferrer : c.referrer;
        if (player != null) player.release();
        Map<String, String> headers = new HashMap<>();
        if (ref != null) headers.put("Referer", ref);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua != null ? ua : DEFAULT_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        player.setVideoSurfaceView(surface);
        // the preview on the cards page is silent; sound comes on with full screen
        player.setVolume(fullscreen ? 1f : 0f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) message.setVisibility(View.GONE);
                else if (state == Player.STATE_BUFFERING && !rendered) {
                    message.setText("Memuat…");
                    message.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                rendered = true;
                message.setVisibility(View.GONE);
                if (offline.remove(c.id) && index < cards.size()) cards.get(index).bindPlaying();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (fullscreen) fitSurface(size);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, c.name + ": " + e.getErrorCodeName());
                // free relays answer 503 now and then: try again twice before giving up
                if (retries < 2 && player != null) {
                    retries++;
                    final ExoPlayer again = player;
                    main.postDelayed(() -> {
                        if (player == again) again.prepare();
                    }, 2000);
                    return;
                }
                if (!backup && c.hasBackup()) {
                    Log.w(TAG, c.name + ": trying the bundled link");
                    playLink(c, index, true);
                    return;
                }
                offline.add(c.id);
                if (index < cards.size()) cards.get(index).bindPlaying();
                message.setText(c.name + " sedang tidak tersedia.\nCoba channel lain.");
                message.setVisibility(View.VISIBLE);
            }
        });
        final ExoPlayer p = player;
        // Links without .m3u8 or .mpd: look at the first bytes to tell DASH from HLS.
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains(".mpd")) {
            start(p, url, null);
        } else {
            io.execute(() -> {
                String mime = MimeTypes.APPLICATION_M3U8;
                try {
                    // DASH manifests are XML; HLS playlists start with #EXTM3U.
                    String head = new String(ChannelList.fetch(url), StandardCharsets.UTF_8).trim();
                    if (head.startsWith("<")) mime = MimeTypes.APPLICATION_MPD;
                } catch (Exception ignored) {
                }
                final String m = mime;
                main.post(() -> {
                    if (player == p) start(p, url, m);
                });
            });
        }
    }

    private static void start(ExoPlayer p, String url, String mime) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url))
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build());
        if (mime != null) item.setMimeType(mime);
        p.setMediaItem(item.build());
        p.prepare();
        p.setPlayWhenReady(true);
    }

    /** Letterbox the video in the screen at its own aspect ratio. */
    private void fitSurface(VideoSize size) {
        if (size.width == 0 || size.height == 0) return;
        float aspect = size.width * size.pixelWidthHeightRatio / size.height;
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        int vw = w, vh = Math.round(w / aspect);
        if (vh > h) {
            vh = h;
            vw = Math.round(h * aspect);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    private void showBar(ChannelList.Channel c, int index) {
        barName.setText(c.name);
        barGroup.setText(c.group + "   ·   " + (index + 1) + " dari " + channels.size());
        barLogo.setImageDrawable(null);
        loadLogo(c.logo, barLogo::setImageBitmap);
        bar.animate().cancel();
        bar.setAlpha(1f);
        main.removeCallbacks(hideBar);
        main.postDelayed(hideBar, BAR_MS);
    }

    /**
     * D-pad on the cards page, handled before the scroll views see it (ScrollView and
     * HorizontalScrollView would otherwise move focus by their own geometry).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        boolean arrow = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (fullscreen || !arrow || cards.isEmpty()) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) moveFocus(k);
        return true;
    }

    private void moveFocus(int keyCode) {
        if (focused < 0 || focused >= cards.size()) {
            cards.get(0).view.requestFocus();
            return;
        }
        Card at = cards.get(focused);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            // exactly one row, same column or the row's last card
            int r = at.row + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            if (r < 0 || r >= grid.size()) return;
            List<Card> row = grid.get(r);
            row.get(Math.min(at.col, row.size() - 1)).view.requestFocus();
        } else {
            // stay in the row; the ends don't wrap or fall into another row
            List<Card> row = grid.get(at.row);
            int c = at.col + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            if (c >= 0 && c < row.size()) row.get(c).view.requestFocus();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!fullscreen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN: {
                    int next = (focused < 0 ? 0 : focused) + (keyCode == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                    next = (next % cards.size() + cards.size()) % cards.size();
                    cards.get(next).view.requestFocus();
                    return true;
                }
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                showBrowse(current);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                play(current - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                play(current + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                if (current >= 0) showBar(channels.get(current), current);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---- logos ----------------------------------------------------------------------------------

    private interface BitmapCallback {
        void onBitmap(Bitmap bmp);
    }

    /** From memory, then the cache directory, then the network. */
    private void loadLogo(String url, BitmapCallback cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap hit = logos.get(url);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        io.execute(() -> {
            File dir = new File(getCacheDir(), "logos");
            if (!dir.isDirectory()) dir.mkdirs();
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
            try {
                if (!f.isFile()) {
                    byte[] data = ChannelList.fetch(url);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= 320) o.inSampleSize *= 2;
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
                if (bmp == null) {
                    f.delete();
                    return;
                }
                boolean light = isLight(bmp);
                main.post(() -> {
                    logos.put(url, bmp);
                    lightLogo.put(url, light);
                    cb.onBitmap(bmp);
                });
            } catch (Exception e) {
                Log.w(TAG, "logo " + url + ": " + e);
            }
        });
    }

    /** Average brightness of the logo's visible pixels, on a coarse grid. */
    private static boolean isLight(Bitmap b) {
        long sum = 0;
        int n = 0;
        int w = b.getWidth(), h = b.getHeight();
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int px = b.getPixel(x * (w - 1) / 23, y * (h - 1) / 23);
                if ((px >>> 24) < 128) continue;
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, bl = px & 0xFF;
                sum += (r * 299 + g * 587 + bl * 114) / 1000;
                n++;
            }
        }
        // an opaque logo (a picture with its own background) keeps the dark tile
        if (n > 24 * 24 * 0.9) return true;
        return n > 0 && sum / n > 170;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private TextView livePill(float sp) {
        TextView t = text(sp, 700, 0xFFFFFFFF);
        t.setText("LIVE");
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(7), dp(3), dp(7), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(accent);
        t.setBackground(bg);
        return t;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(font, weight, false) : font);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Paints the background colour outside a rounded rectangle: rounded corners over the video. */
    private static final class CornerMask extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float radius;

        CornerMask(int color, float radius) {
            paint.setColor(color);
            this.radius = radius;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            path.reset();
            path.setFillType(Path.FillType.INVERSE_EVEN_ODD);
            path.addRoundRect(new RectF(b), radius, radius, Path.Direction.CW);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.save();
            canvas.clipRect(getBounds());
            canvas.drawPath(path, paint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
