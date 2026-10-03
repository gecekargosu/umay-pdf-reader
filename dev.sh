#!/bin/bash
# ╔══════════════════════════════════════════════════════════╗
# ║  JARVISMOBILE — Development Workflow Script              ║
# ║  MiMo/Freebuff ile mobil geliştirme döngüsü             ║
# ╚══════════════════════════════════════════════════════════╝

set -e

PROJECT_DIR="/storage/emulated/0/FreebuffProject/JarvisMobile"
SCREENSHOT_DIR="/storage/emulated/0/FreebuffProject/screenshots"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"

# Colors
CYAN='\033[0;36m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${CYAN}[JARVIS]${NC} $1"; }
ok()  { echo -e "${GREEN}[OK]${NC} $1"; }
fail(){ echo -e "${RED}[FAIL]${NC} $1"; }
warn(){ echo -e "${YELLOW}[WARN]${NC} $1"; }

# ─────────────────────────────────────────────
# STEP 1: Environment Check
# ─────────────────────────────────────────────
cmd_check() {
    log "Environment kontrolü..."
    echo "  Java:     $(java -version 2>&1 | head -1)"
    echo "  javac:    $(javac -version 2>&1 | head -1)"
    echo "  Gradle:   $(bash $PROJECT_DIR/gradlew --version 2>&1 | grep 'Gradle ' | head -1)"
    echo "  ADB:      $(adb version 2>&1 | head -1)"
    echo "  Python:   $(python3 --version)"
    echo "  Node:     $(node --version)"
    echo "  Git:      $(git --version)"
    echo ""
    echo "  JAVA_HOME=$JAVA_HOME"
    echo "  ANDROID_HOME=$ANDROID_HOME"

    # Check GitHub Actions
    if command -v gh &>/dev/null; then
        echo "  gh CLI:   $(gh --version | head -1)"
        if gh auth status &>/dev/null 2>&1; then
            ok "GitHub CLI authenticated"
        else
            warn "GitHub CLI not authenticated. Run: gh auth login"
        fi
    else
        warn "gh CLI not installed. Install: apt install gh"
    fi
}

# ─────────────────────────────────────────────
# STEP 2: Trigger GitHub Actions Build
# ─────────────────────────────────────────────
cmd_build() {
    log "GitHub Actions build tetikleniyor..."

    cd "$PROJECT_DIR"

    if ! git remote get-url origin &>/dev/null; then
        fail "Git remote origin ayarlanmamış."
        echo ""
        echo "  Önce GitHub'da repo oluştur ve push et:"
        echo "    1. github.com'da 'JarvisMobile' repo oluştur"
        echo "    2. cd $PROJECT_DIR"
        echo "    3. git init && git add -A && git commit -m 'Initial commit'"
        echo "    4. git remote add origin https://github.com/YOUR_USER/JarvisMobile.git"
        echo "    5. git push -u origin main"
        echo ""
        return 1
    fi

    if ! command -v gh &>/dev/null; then
        fail "gh CLI kurulu değil. Install: apt install gh"
        return 1
    fi

    if ! gh auth status &>/dev/null 2>&1; then
        fail "GitHub CLI giriş yapılmamış. Run: gh auth login"
        return 1
    fi

    log "Değişikliklerstag ediliyor..."
    git add -A
    git commit -m "Build: $(date +%Y-%m-%d_%H:%M)" || true
    echo ""
    warn "PUSH İÇİN ONAY GEREKLİ"
    echo "  Aşağıdaki komutu çalıştırarak push yap:"
    echo ""
    echo "    git push -u origin main"
    echo ""
    echo "  VEYA dev.sh ile:"
    echo "    bash dev.sh push"
    echo ""
    log "Commit hazır. Manuel push gerekiyor."
    echo ""
    echo "  Build durumunu kontrol et (push sonrası):"
    echo "    gh run list --workflow=android-build.yml"
    echo ""
    echo "  Build bittikten sonra APK indir:"
    echo "    bash dev.sh download"
}

# ─────────────────────────────────────────────
# STEP 2.5: Explicit Push (sadece manuel)
# ─────────────────────────────────────────────
cmd_push() {
    log "═══ GITHUB'A PUSH ═══"
    echo ""
    warn "DİKKAT: Bu komut kodu GitHub'a gönderir!"
    echo ""
    read -p "Devam etmek istiyor musun? (y/N): " CONFIRM
    if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
        fail "İptal edildi."
        return 1
    fi
    echo ""
    log "Push ediliyor..."
    git push -u origin main 2>/dev/null || git push -u origin master 2>/dev/null
    ok "Push tamamlandı!"
    echo ""
    echo "  GitHub Actions build tetiklendi."
    echo "  Durum: gh run list --workflow=android-build.yml"
}

# ─────────────────────────────────────────────
# STEP 3: Download APK from GitHub Actions
# ─────────────────────────────────────────────
cmd_download() {
    log "GitHub Actions'dan APK indiriliyor..."

    if ! command -v gh &>/dev/null; then
        fail "gh CLI kurulu değil."
        return 1
    fi

    if ! gh auth status &>/dev/null 2>&1; then
        fail "GitHub CLI giriş yapılmamış."
        return 1
    fi

    RUN_ID=$(gh run list --workflow=build.yml --status=success --limit=1 --json databaseId --jq '.[0].databaseId')

    if [ -z "$RUN_ID" ]; then
        fail "Başarılı build bulunamadı. Önce 'bash dev.sh build' çalıştır."
        return 1
    fi

    log "Run ID: $RUN_ID — APK indiriliyor..."
    mkdir -p "$SCREENSHOT_DIR"
    gh run download "$RUN_ID" -n jarvismobile-debug -D /storage/emulated/0/FreebuffProject

    APK="/storage/emulated/0/FreebuffProject/app-debug.apk"
    if [ -f "$APK" ]; then
        ok "APK indirildi: $APK ($(ls -lh "$APK" | awk '{print $5}'))"
    else
        fail "APK bulunamadı."
        return 1
    fi
}

# ─────────────────────────────────────────────
# STEP 4: Install APK
# ─────────────────────────────────────────────
cmd_install() {
    APK="/storage/emulated/0/FreebuffProject/app-debug.apk"

    if [ ! -f "$APK" ]; then
        fail "APK bulunamadı: $APK"
        echo "  Önce 'bash dev.sh download' çalıştır."
        return 1
    fi

    log "APK kuruluyor..."
    log "Android paket yükleyicisi açılacak — 'Yükle' butonuna bas."

    PATH="$PATH:$TERMUX_BIN" termux-open "$APK" 2>/dev/null || {
        warn "termux-open başarısız. Manuel kurulum gerekli."
        echo "  APK konumu: $APK"
        echo "  Dosya yöneticisinden APK'ya tıkla."
    }
}

# ─────────────────────────────────────────────
# STEP 5: Launch App
# ─────────────────────────────────────────────
cmd_launch() {
    log "JarvisMobile başlatılıyor..."

    # Try termux am start
    PATH="$PATH:$TERMUX_BIN" am start -n com.jarvis.mobile/.MainActivity 2>/dev/null && {
        ok "MainActivity başlatıldı"
        return 0
    }

    warn "am start başarısız. Uygulamayı manuel aç."
    echo "  Uygulama adı: J.A.R.V.I.S"
    echo "  Ana ekrandan bul ve aç."
}

# ─────────────────────────────────────────────
# STEP 6: Screenshot
# ─────────────────────────────────────────────
cmd_screenshot() {
    mkdir -p "$SCREENSHOT_DIR"
    TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    FILENAME="screenshot_${TIMESTAMP}.png"
    FILEPATH="$SCREENSHOT_DIR/$FILENAME"

    log "Screenshot alınıyor..."

    # Method 1: ADB screencap
    if adb devices 2>/dev/null | grep -q "device$"; then
        adb shell screencap -p "/sdcard/$FILENAME"
        adb pull "/sdcard/$FILENAME" "$FILEPATH" 2>/dev/null
        adb shell rm "/sdcard/$FILENAME" 2>/dev/null

        if [ -f "$FILEPATH" ]; then
            ok "Screenshot: $FILEPATH"
            return 0
        fi
    fi

    # Method 2: termux-screenshot (if available)
    PATH="$PATH:$TERMUX_BIN"
    if command -v termux-screenshot &>/dev/null; then
        termux-screenshot "$FILEPATH" 2>/dev/null
        if [ -f "$FILEPATH" ]; then
            ok "Screenshot: $FILEPATH"
            return 0
        fi
    fi

    fail "Screenshot alınamadı."
    echo "  ADB: $(adb devices 2>&1 | head -3)"
    echo "  Alternatif: Telefondan manuel screenshot al (power + volume_down)"
}

# ─────────────────────────────────────────────
# STEP 7: Collect Logs
# ─────────────────────────────────────────────
cmd_logs() {
    mkdir -p "$SCREENSHOT_DIR"
    TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    LOGFILE="$SCREENSHOT_DIR/logcat_${TIMESTAMP}.txt"

    log "Logcat toplanıyor..."

    if adb devices 2>/dev/null | grep -q "device$"; then
        adb logcat -d -s "JarvisMobile" > "$LOGFILE" 2>/dev/null
        ok "Loglar: $LOGFILE"
    else
        warn "ADB bağlı değil. Log toplanamadı."
    fi
}

# ─────────────────────────────────────────────
# STEP 8: Full Cycle
# ─────────────────────────────────────────────
cmd_cycle() {
    log "═══ TAM DÖNGÜ BAŞLIyor ═══"
    echo ""

    cmd_build || return 1
    echo ""

    log "Build bittikten sonra devam et:"
    echo "  bash dev.sh download"
    echo "  bash dev.sh install"
    echo "  bash dev.sh launch"
    echo "  bash dev.sh screenshot"
    echo ""
    log "Veya tek seferde:"
    echo "  bash dev.sh download && bash dev.sh install && bash dev.sh launch && sleep 5 && bash dev.sh screenshot"
}

# ─────────────────────────────────────────────
# STEP 9: Status
# ─────────────────────────────────────────────
cmd_status() {
    log "═══ DURUM ═══"
    echo ""

    # Check APK
    APK="/storage/emulated/0/FreebuffProject/app-debug.apk"
    if [ -f "$APK" ]; then
        ok "APK: Mevcut ($(ls -lh "$APK" | awk '{print $5}'))"
    else
        warn "APK: Bulunamadı"
    fi

    # Check screenshots
    if [ -d "$SCREENSHOT_DIR" ]; then
        COUNT=$(ls "$SCREENSHOT_DIR"/*.png 2>/dev/null | wc -l)
        ok "Screenshots: $COUNT adet"
    else
        warn "Screenshot klasörü yok"
    fi

    # Check GitHub Actions
    if command -v gh &>/dev/null && gh auth status &>/dev/null 2>&1; then
        echo ""
        log "Son GitHub Actions workflow:"
        gh run list --workflow=build.yml --limit=3 2>/dev/null || echo "  (bulunamadı)"
    fi
}

# ─────────────────────────────────────────────
# MAIN
# ─────────────────────────────────────────────    case "${1:-help}" in
    check)     cmd_check ;;
    build)     cmd_build ;;
    push)      cmd_push ;;
    download)  cmd_download ;;
    install)   cmd_install ;;
    launch)    cmd_launch ;;
    screenshot)cmd_screenshot ;;
    logs)      cmd_logs ;;
    cycle)     cmd_cycle ;;
    status)    cmd_status ;;
    *)
        echo ""
        echo "╔══════════════════════════════════════════════╗"
        echo "║  JARVISMOBILE Development Workflow           ║"
        echo "╚══════════════════════════════════════════════╝"
        echo ""
        echo "  Kullanım: bash dev.sh <komut>"
        echo ""
        echo "  Komutlar:"
        echo "    check      — Ortam kontrolü"
        echo "    build      — Değişiklikleri commit et (PUSH YAPMAZ)"
        echo "    push       — GitHub'a PUSH ET (onay ister)"
        echo "    download   — APK'yı GitHub'dan indir"
        echo "    install    — APK'yı telefona kur"
        echo "    launch     — Uygulamayı aç"
        echo "    screenshot — Ekran görüntüsü al"
        echo "    logs       — Logcat topla"
        echo "    cycle      — Tam döngü (push → build → download → install → launch → screenshot)"
        echo "    status     — Mevcut durumu göster"
        echo ""
        echo "  Akış:"
        echo "    1. bash dev.sh build      (commit et)"
        echo "    2. bash dev.sh push       (GitHub'a gönder — onay ister)"
        echo "    3. bash dev.sh download   (APK indir)"
        echo "    4. bash dev.sh install    (kur)"
        echo "    5. bash dev.sh launch     (aç)"
        echo "    6. bash dev.sh screenshot (görsel al)"
        echo ""
        echo "  ÖNEMLİ: push komutu otomatik çalışmaz, onay ister!"
        echo ""
        ;;
esac
