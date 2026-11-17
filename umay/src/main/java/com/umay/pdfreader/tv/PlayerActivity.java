package com.umay.pdfreader.tv;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.List;

public class PlayerActivity extends android.app.Activity {

    private static final int ACCENT = Color.parseColor("#FF7A18");
    private static final int TEXT = Color.parseColor("#FFFFFF");
    private static final int MUTED = Color.parseColor("#B9C0CE");

    private List<Channel> list;
    private int index;
    private VideoView video;
    private ProgressBar progress;
    private TextView nameView, errorView, favButton, playButton, counter, recButton, clockBar;
    private LinearLayout top, bottom;
    private HlsRecorder recorder = new HlsRecorder();
    private final Handler ui = new Handler();
    private boolean barsVisible = true;
    private long sleepUntil = 0;
    private AudioManager audio;
    private boolean tracking = false;
    private long openedAt = 0;
    private boolean limitWarned = false;
    private boolean locked = false;
    private boolean screenOffAudio = false;
    private boolean muted = false;
    private android.media.MediaPlayer mp;
    private TextView lockHint;
    private TextView muteBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        list = ChannelRepo.all(this);
        index = getIntent().getIntExtra("index", Prefs.lastIndex(this));
        if (index < 0 || index >= list.size()) index = 0;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        vlp.gravity = Gravity.CENTER;
        video.setLayoutParams(vlp);
        root.addView(video);

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(52), dp(52));
        plp.gravity = Gravity.CENTER;
        progress.setLayoutParams(plp);
        root.addView(progress);

        // saydam saat (barlar gizlense de kalir)
        clockBar = new TextView(this);
        clockBar.setTextColor(Color.parseColor("#CCFFFFFF"));
        clockBar.setTextSize(13);
        clockBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.START;
        clp.setMargins(dp(10), dp(54), 0, 0);
        root.addView(clockBar, clp);

        // yari saydam ses dugmesi: barlar gizlense de ekranda kalir
        muteBtn = new TextView(this);
        muteBtn.setText("🔊");
        muteBtn.setTextSize(17);
        muteBtn.setGravity(Gravity.CENTER);
        GradientDrawable mbg = new GradientDrawable();
        mbg.setColor(Color.parseColor("#66000000"));
        mbg.setCornerRadius(dp(24));
        muteBtn.setBackground(mbg);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(46), dp(46));
        mlp.gravity = Gravity.BOTTOM | Gravity.END;
        mlp.setMargins(0, 0, dp(14), dp(74));
        muteBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMute(); }
        });
        root.addView(muteBtn, mlp);

        // --- ust bar ---
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(8), dp(4), dp(8));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#CC000000"), Color.parseColor("#00000000")}));

        nameView = new TextView(this);
        nameView.setTextColor(TEXT);
        nameView.setTextSize(15);
        nameView.setTypeface(nameView.getTypeface(), android.graphics.Typeface.BOLD);
        nameView.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(nameView);

        recButton = iconButton("⏺");
        recButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleRecording(); }
        });
        top.addView(recButton);

        TextView qualityBtn = iconButton("HD");
        qualityBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseQuality(); }
        });
        top.addView(qualityBtn);

        TextView audioBtn = iconButton("🎧");
        audioBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { listenInBackground(); }
        });
        top.addView(audioBtn);

        TextView lockBtn = iconButton("🔒");
        lockBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setLocked(!locked); }
        });
        top.addView(lockBtn);

        TextView pipBtn = iconButton("⧉");
        pipBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!enterPip()) Toast.makeText(PlayerActivity.this,
                        "Küçük pencere için önce kanal açılmalı", Toast.LENGTH_SHORT).show();
            }
        });
        top.addView(pipBtn);

        TextView multiBtn = iconButton("▦");
        multiBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(PlayerActivity.this, MultiActivity.class);
                i.putExtra("start", index);
                startActivity(i);
            }
        });
        top.addView(multiBtn);

        favButton = iconButton("★");
        favButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFav(); }
        });
        top.addView(favButton);

        TextView close = iconButton("✕");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(close);

        FrameLayout.LayoutParams toplp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toplp.gravity = Gravity.TOP;
        root.addView(top, toplp);

        // --- alt bar ---
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(8), dp(6), dp(8), dp(10));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.parseColor("#CC000000"), Color.parseColor("#00000000")}));

        TextView prev = smallButton("⏮");
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(-1); }
        });
        bottom.addView(prev);

        playButton = smallButton("⏸");
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (video.isPlaying()) { video.pause(); playButton.setText("▶"); }
                else { video.start(); playButton.setText("⏸"); }
            }
        });
        bottom.addView(playButton);

        TextView next = smallButton("⏭");
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(1); }
        });
        bottom.addView(next);

        SeekBar vol = new SeekBar(this);
        vol.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        vol.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        LinearLayout.LayoutParams volp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        volp.setMargins(dp(6), 0, dp(6), 0);
        bottom.addView(vol, volp);

        TextView timerBtn = smallButton("⏰");
        timerBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sleepDialog(); }
        });
        bottom.addView(timerBtn);

        counter = new TextView(this);
        counter.setTextColor(MUTED);
        counter.setTextSize(11);
        counter.setPadding(dp(6), 0, 0, 0);
        bottom.addView(counter);

        FrameLayout.LayoutParams bolp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bolp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bolp);

        errorView = new TextView(this);
        errorView.setTextColor(TEXT);
        errorView.setTextSize(14);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(24), dp(24), dp(24), dp(24));
        errorView.setVisibility(View.GONE);
        errorView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(); }
        });
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        video.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (locked) {
                    Toast.makeText(PlayerActivity.this,
                            "Dokunma kilidi açık · açmak için ekrana uzun bas", Toast.LENGTH_SHORT).show();
                    return;
                }
                toggleBars();
            }
        });
        video.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (locked) setLocked(false);
                return true;
            }
        });

        lockHint = new TextView(this);
        lockHint.setText("🔒 dokunma kilidi · açmak için uzun bas");
        lockHint.setTextColor(Color.parseColor("#CCFFFFFF"));
        lockHint.setTextSize(11);
        lockHint.setPadding(dp(10), dp(4), dp(10), dp(4));
        FrameLayout.LayoutParams lhlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lhlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lhlp.setMargins(0, 0, 0, dp(16));
        lockHint.setVisibility(View.GONE);
        root.addView(lockHint, lhlp);

        setContentView(root);

        video.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(android.media.MediaPlayer m) {
                progress.setVisibility(View.GONE);
                errorView.setVisibility(View.GONE);
                mp = m;
                if (muted) m.setVolume(0f, 0f);
                video.start();
                playButton.setText("⏸");
                Prefs.clearFails(PlayerActivity.this, list.get(index).url);
                if (!tracking) { tracking = true; openedAt = System.currentTimeMillis(); Net.begin(); }
                ui.removeCallbacks(watchdog);
            }
        });
        video.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                progress.setVisibility(View.GONE);
                Prefs.addFail(PlayerActivity.this, list.get(index).url);
                errorView.setText("Bu kanal şu an açılamadı.\n\nDokun → tekrar dene\nveya ⏭ ile sonraki kanal");
                errorView.setVisibility(View.VISIBLE);
                return true;
            }
        });

        show();

        ui.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String saat = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            String s = saat;
            if (sleepUntil > 0) {
                long kalan = sleepUntil - System.currentTimeMillis();
                if (kalan <= 0) {
                    sleepUntil = 0;
                    Toast.makeText(PlayerActivity.this, "Uyku zamanlayıcısı: kapatılıyor", Toast.LENGTH_SHORT).show();
                    AudioService.stop(PlayerActivity.this);
                    finish();
                    return;
                }
                long s2 = kalan / 1000;
                s = saat + "   ⏰ " + String.format(java.util.Locale.getDefault(), "%d:%02d", s2 / 60, s2 % 60);
            }
            clockBar.setText(s + "\n📶 " + Net.mb(Prefs.todayBytes(PlayerActivity.this)
                    + Net.sessionSoFar()) + " bugün");
            checkLimit();
            ui.postDelayed(this, 1000);
        }
    };

    /** Limit dolduysa sorar: devam edilsin mi? */
    private void checkLimit() {
        final int lim = Prefs.limitMb(this);
        if (lim <= 0 || limitWarned) return;
        long used = Prefs.todayBytes(this) + Net.sessionSoFar();
        if (used < lim * 1048576L) return;
        limitWarned = true;
        new AlertDialog.Builder(this)
                .setTitle("Günlük veri limiti doldu")
                .setMessage("Limit: " + lim + " MB\nKullanılan: " + Net.mb(used)
                        + "\n\nDevam edilsin mi? (mobil veridesin)")
                .setCancelable(false)
                .setPositiveButton("Devam et", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { }
                })
                .setNegativeButton("Durdur", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Toast.makeText(PlayerActivity.this, "Limit doldu, yayın durduruldu",
                                Toast.LENGTH_LONG).show();
                        finishUsage();
                        finish();
                    }
                })
                .show();
    }

    // ---------- uyku zamanlayicisi ----------
    private void sleepDialog() {
        final String[] items = {"15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika",
                "120 dakika", "Saat gir (örn 23:30)", "Zamanlayıcıyı iptal et"};
        new AlertDialog.Builder(this)
                .setTitle("Ne zaman kapansın?")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == items.length - 1) { sleepUntil = 0; return; }
                        if (which == 6) { askClockTime(); return; }
                        int[] mins = {15, 30, 45, 60, 90, 120};
                        sleepUntil = System.currentTimeMillis() + mins[which] * 60000L;
                        Toast.makeText(PlayerActivity.this, mins[which] + " dakika sonra kapanacak",
                                Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void askClockTime() {
        final EditText et = new EditText(this);
        et.setHint("23:30");
        et.setInputType(InputType.TYPE_CLASS_DATETIME);
        new AlertDialog.Builder(this).setTitle("Kapanış saati").setView(et)
                .setPositiveButton("Tamam", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            String[] p = et.getText().toString().trim().split(":");
                            int h = Integer.parseInt(p[0].trim());
                            int mi = Integer.parseInt(p[1].trim());
                            java.util.Calendar c = java.util.Calendar.getInstance();
                            c.set(java.util.Calendar.HOUR_OF_DAY, h);
                            c.set(java.util.Calendar.MINUTE, mi);
                            c.set(java.util.Calendar.SECOND, 0);
                            if (c.getTimeInMillis() <= System.currentTimeMillis())
                                c.add(java.util.Calendar.DAY_OF_MONTH, 1);
                            sleepUntil = c.getTimeInMillis();
                            Toast.makeText(PlayerActivity.this, "Saat " + String.format("%02d:%02d", h, mi)
                                    + " de kapanacak", Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(PlayerActivity.this, "Saat formatı: 23:30", Toast.LENGTH_SHORT).show();
                        }
                    }
                }).setNegativeButton("Vazgeç", null).show();
    }

    // ---------- kalite ----------
    private void chooseQuality() {
        final Channel c = list.get(index);
        Toast.makeText(this, "Kaliteler aranıyor...", Toast.LENGTH_SHORT).show();
        Quality.load(c.url, new Quality.Callback() {
            @Override public void onResult(final List<Quality.Variant> variants) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (variants.isEmpty()) {
                            Toast.makeText(PlayerActivity.this, "Bu kanal tek kalitede yayın yapıyor",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] labels = new String[variants.size() + 1];
                        labels[0] = "Otomatik (en iyi)";
                        for (int i = 0; i < variants.size(); i++) labels[i + 1] = variants.get(i).label;
                        new AlertDialog.Builder(PlayerActivity.this)
                                .setTitle("Yayın kalitesi")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int which) {
                                        String url = which == 0 ? null : variants.get(which - 1).url;
                                        Prefs.setQuality(PlayerActivity.this, c.url, url);
                                        play();
                                    }
                                }).show();
                    }
                });
            }
        });
    }

    // ---------- kayit ----------
    private void toggleRecording() {
        if (recorder.isRunning()) {
            recorder.stop();
            recButton.setTextColor(TEXT);
            Toast.makeText(this, "Kayıt durduruluyor...", Toast.LENGTH_SHORT).show();
            return;
        }
        Channel c = list.get(index);
        recorder.start(this, c.url, c.name, new HlsRecorder.Listener() {
            @Override public void onInfo(final String msg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { recButton.setTextColor(Color.parseColor("#FF5252")); }
                });
            }
            @Override public void onError(final String msg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        recButton.setTextColor(TEXT);
                        Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
            @Override public void onStopped(final String path, final long bytes, final boolean sesli) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        recButton.setTextColor(TEXT);
                        Toast.makeText(PlayerActivity.this, "Kayıt bitti: " + (bytes / 1048576) + " MB"
                                + (sesli ? " (ses dahil)" : "") + "\nGaleri → Movies/TV-Kayitlari", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        recButton.setTextColor(Color.parseColor("#FF5252"));
        Toast.makeText(this, "Kayıt başladı → Movies/TV-Kayitlari", Toast.LENGTH_SHORT).show();
    }

    // ---------- arka plan ses ----------
    private void listenInBackground() {
        Channel c = list.get(index);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
        }
        AudioService.start(this, c.url, c.name);
        // ayni anda iki ses olmasin: TV goruntusunu sessize al
        try { if (mp != null) mp.setVolume(0f, 0f); } catch (Exception ignored) { }
        muted = true;
        muteBtn.setText("🔇");
        Toast.makeText(this, c.name + " arka planda çalıyor. Ekranı kapatabilirsin.", Toast.LENGTH_LONG).show();
    }

    private void show() {
        Channel c = list.get(index);
        nameView.setText("№" + c.no + "  " + c.flag() + "  " + c.name);
        counter.setText((index + 1) + "/" + list.size());
        favButton.setTextColor(Prefs.favs(this).contains(c.url) ? Color.parseColor("#FFC107") : MUTED);
        if (recorder.isRunning()) { recorder.stop(); recButton.setTextColor(TEXT); }
        Prefs.addRecent(this, c.url);
        Prefs.setLastIndex(this, index);
        play();
        scheduleHide();
    }

    private void play() {
        final Channel c = list.get(index);
        if (!Net.online(this)) {
            errorView.setText("İnternet bağlantısı yok.\n\nBağlanınca dokun → tekrar dene");
            errorView.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            return;
        }
        if (Net.mobile(this) && !Prefs.mobileOk(this)) { askMobile(c); return; }
        if (Net.mobile(this) && Prefs.audioOnlyMobile(this)) { startAudioOnly(c); return; }
        playNow(c);
    }

    /** Wi-Fi yok: mobil veri onayi + limit kontrolu. */
    private void askMobile(final Channel c) {
        int lim = Prefs.limitMb(this);
        long used = Prefs.todayBytes(this);
        StringBuilder msg = new StringBuilder();
        msg.append("Wi-Fi yok, mobil veridesin.\nYayın internet harcar.\n\n");
        msg.append("Bugün: ").append(Net.mb(used));
        if (lim > 0) {
            msg.append(" / ").append(lim).append(" MB");
            if (used > lim * 1048576L) msg.append("\n⚠ Günlük limitin doldu!");
            else msg.append("\nKalan: ").append(Net.mb(lim * 1048576L - used));
        }
        msg.append("\n\nSadece ses → görüntü inmez, çok daha az veri harcar.");
        new AlertDialog.Builder(this)
                .setTitle("Mobil veri uyarısı")
                .setMessage(msg.toString())
                .setPositiveButton("Aç (mobil veri)", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Prefs.setMobileOk(PlayerActivity.this, true);
                        playNow(c);
                    }
                })
                .setNeutralButton("Sadece ses 🎧", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startAudioOnly(c); }
                })
                .setNegativeButton("Vazgeç", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { finish(); }
                })
                .show();
    }

    private void startAudioOnly(Channel c) {
        AudioService.start(this, c.url, c.name);
        Toast.makeText(this, c.name + ": ses olarak çalıyor (veri tasarrufu).\n"
                + "Ekranı kapatabilirsin.", Toast.LENGTH_LONG).show();
        finish();
    }

    private void playNow(final Channel c) {
        // TV acilinca arkadaki radyo sussun (tek ses kaynagi kalsin)
        AudioService.stop(this);
        screenOffAudio = false;
        String custom = Prefs.quality(this, c.url);
        if (custom != null) { startVideo(custom); return; }
        if (Prefs.dataSaver(this)) {
            progress.setVisibility(View.VISIBLE);
            errorView.setVisibility(View.GONE);
            Quality.load(c.url, new Quality.Callback() {
                @Override public void onResult(final List<Quality.Variant> variants) {
                    final String low = Quality.lowest(variants);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { startVideo(low != null ? low : c.url); }
                    });
                }
            });
            return;
        }
        startVideo(c.url);
    }

    private void startVideo(String url) {
        progress.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        try { video.stopPlayback(); } catch (Exception ignored) { }
        mp = null;
        video.setVideoPath(url);
        video.requestFocus();
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, 13000);
    }

    private void toggleMute() {
        muted = !muted;
        try { if (mp != null) mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); }
        catch (Exception ignored) { }
        muteBtn.setText(muted ? "🔇" : "🔊");
        Toast.makeText(this, muted ? "TV sesi kapalı (radyo/müzik dinleyebilirsin)"
                : "TV sesi açık", Toast.LENGTH_SHORT).show();
    }

    /** 13 sn icinde goruntu gelmezse siyah ekran yerine aciklama goster. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                if (!video.isPlaying()) {
                    progress.setVisibility(View.GONE);
                    errorView.setText("Yayın 13 saniyede açılmadı.\n\nDokun → tekrar dene\nveya ⏭ ile sonraki kanal");
                    errorView.setVisibility(View.VISIBLE);
                    Prefs.addFail(PlayerActivity.this, list.get(index).url);
                }
            } catch (Exception ignored) { }
        }
    };

    private void finishUsage() {
        if (!tracking) return;
        tracking = false;
        long bytes = Net.end();
        int sec = (int) ((System.currentTimeMillis() - openedAt) / 1000);
        if (index >= 0 && index < list.size() && sec > 2)
            Prefs.addUsage(this, list.get(index).url, bytes, sec);
    }

    private boolean enterPip() {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            if (!video.isPlaying()) return false;
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void step(int d) {
        finishUsage();
        ui.removeCallbacks(watchdog);
        index = (index + d + list.size()) % list.size();
        show();
    }

    private void toggleFav() {
        Channel c = list.get(index);
        Prefs.toggleFav(this, c.url);
        favButton.setTextColor(Prefs.favs(this).contains(c.url) ? Color.parseColor("#FFC107") : MUTED);
    }

    // ---------- yatay / dikey ----------
    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            goImmersive(true);
            scheduleHide();
        } else {
            goImmersive(false);
            showBars();
        }
    }

    private void goImmersive(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void toggleBars() {
        if (barsVisible) hideBars(); else { showBars(); scheduleHide(); }
    }

    private void showBars() { barsVisible = true; top.setVisibility(View.VISIBLE); bottom.setVisibility(View.VISIBLE); }

    private void hideBars() { barsVisible = false; top.setVisibility(View.INVISIBLE); bottom.setVisibility(View.INVISIBLE); }

    private void scheduleHide() {
        ui.removeCallbacks(hideRunnable);
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) return;
        ui.postDelayed(hideRunnable, 4000);
    }

    private final Runnable hideRunnable = new Runnable() {
        @Override public void run() { hideBars(); }
    };

    // ---------- PiP ----------
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (inPip) {
            top.setVisibility(View.GONE);
            bottom.setVisibility(View.GONE);
            errorView.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            clockBar.setVisibility(View.GONE);
        } else {
            showBars();
            clockBar.setVisibility(View.VISIBLE);
        }
    }

    private TextView iconButton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        return t;
    }

    private TextView smallButton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(ACCENT);
        d.setCornerRadius(dp(22));
        t.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(38));
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onPause() {
        super.onPause();
        finishUsage();
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode()) return;
        // ekran kapaninca / uygulamadan cikinca ses devam etsin (kilit ekrani dinleme)
        try {
            if (video.isPlaying() && index >= 0 && index < list.size()) {
                Channel c = list.get(index);
                String q = Prefs.quality(this, c.url);
                AudioService.start(this, q != null ? q : c.url, c.name);
                screenOffAudio = true;
            }
        } catch (Exception ignored) { }
        try { video.pause(); } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screenOffAudio) {
            screenOffAudio = false;
            AudioService.stop(this);
            try { video.start(); } catch (Exception ignored) { }
        }
    }

    private void setLocked(boolean v) {
        locked = v;
        lockHint.setVisibility(v ? View.VISIBLE : View.GONE);
        if (v) {
            hideBars();
        } else {
            showBars();
            scheduleHide();
        }
        Toast.makeText(this, v ? "Ekran kilitli: yanlışlıkla dokunmak bir şey bozmaz"
                : "Kilit açıldı", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        finishUsage();
        ui.removeCallbacksAndMessages(null);
        if (recorder.isRunning()) recorder.stop();
        try { video.stopPlayback(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
