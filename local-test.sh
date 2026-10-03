#!/bin/bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  JarvisMobile — Local Test Script                           ║
# ║  APK install → launch → screenshot → log → test             ║
# ╚══════════════════════════════════════════════════════════════╝
#
# Bu script GitHub Actions'dan indirilen APK ilelocal test yapar.
#
# Kullanım:
#   bash local-test.sh <komut>
#
# Komutlar:
#   install    — APK'yı telefona kur
#   launch     — Uygulamayı başlat
#   screenshot — Ekran görüntüsü al
#   logs       — Logcat topla
#   test       — Basit UI testleri çalıştır
#   all        — Tüm adımları sırasıyla yap

set -e

# ─── AYARLAR ───
PACKAGE="com.jarvis.mobile"
MAIN_ACTIVITY="$PACKAGE/.MainActivity"
LOCK_ACTIVITY="$PACKAGE/.LockScreenActivity"
APK_PATH="/storage/emulated/0/FreebuffProject/app-debug.apk"
SCREENSHOT_DIR="/storage/emulated/0/FreebuffProject/screenshots"
TERMUX_BIN="/data/data/com.termux/files/usr/bin"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# ─── RENKLER ───
CYAN='\033[0;36m'; GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${CYAN}[JARVIS]${NC} $1"; }
ok()   { echo -e "${GREEN}[  OK ]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

# ─── ADB KONTROL ───
check_adb() {
    if ! command -v adb &>/dev/null; then
        fail "adb bulunamadı. apt install adb"
        return 1
    fi
    # ADB server başlat
    adb start-server 2>/dev/null || true
}

# ─── APK KONTROL ───
check_apk() {
    if [ ! -f "$APK_PATH" ]; then
        fail "APK bulunamadı: $APK_PATH"
        echo "  GitHub Actions'dan APK indir: gh run download <RUN_ID> -n jarvismobile-debug-apk -D /storage/emulated/0/FreebuffProject"
        return 1
    fi
    ok "APK mevcut ($(ls -lh "$APK_PATH" | awk '{print $5}'))"
}

# ═══════════════════════════════════════════════════
#  INSTALL
# ═══════════════════════════════════════════════════
cmd_install() {
    log "═══ APK KURULUYOR ═══"
    check_apk || return 1

    # Yöntem 1: termux-open (en basit)
    log "termux-open ile Android paket yükleyicisi açılıyor..."
    PATH="$PATH:$TERMUX_BIN" termux-open "$APK_PATH" 2>/dev/null && {
        ok "Paket yükleyicisi açıldı."
        echo "  → 'Yükle' butonuna bas"
        echo "  → Kurulum bitince 'Bitti'ye bas"
        return 0
    }

    # Yöntem 2: am start ile intent
    log "am start ile install deneniyor..."
    PATH="$PATH:$TERMUX_BIN" am start \
        -a android.intent.action.VIEW \
        -d "file://$APK_PATH" \
        -t "application/vnd.android.package-archive" 2>/dev/null && {
        ok "Install intent gönderildi."
        return 0
    }

    fail "Otomatik kurulum başarısız."
    echo "  Manuel kurulum:"
    echo "  1. Dosya yöneticisine git"
    echo "  2. $APK_PATH konumuna git"
    echo "  3. APK'ya tıkla"
    echo "  4. 'Yükle'ye bas"
}

# ═══════════════════════════════════════════════════
#  LAUNCH
# ═══════════════════════════════════════════════════
cmd_launch() {
    log "═══ UYGULAMA BAŞLATILIYOR ═══"

    # Yöntem 1: am start
    PATH="$PATH:$TERMUX_BIN" am start -n "$MAIN_ACTIVITY" 2>/dev/null && {
        ok "MainActivity başlatıldı."
        return 0
    }

    # Yöntem 2: monkey (launcher tetikleme)
    PATH="$PATH:$TERMUX_BIN" am start \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        -n "$PACKAGE/.MainActivity" 2>/dev/null && {
        ok "Uygulama başlatıldı (MAIN/LAUNCHER)."
        return 0
    }

    warn "Otomatik başlatma başarısız."
    echo "  Uygulamayı manuel olarak aç:"
    echo "  Uygulama adı: J.A.R.V.I.S"
}

# ═══════════════════════════════════════════════════
#  SCREENSHOT
# ═══════════════════════════════════════════════════
cmd_screenshot() {
    log "═══ SCREENSHOT ALINIYOR ═══"
    mkdir -p "$SCREENSHOT_DIR"

    local FILENAME="screenshot_${TIMESTAMP}.png"
    local FILEPATH="$SCREENSHOT_DIR/$FILENAME"

    # Yöntem 1: ADB screencap
    if check_adb; then
        local DEVICE=$(adb devices 2>/dev/null | grep -w "device" | head -1 | awk '{print $1}')
        if [ -n "$DEVICE" ]; then
            log "ADB cihaz bulundu: $DEVICE"
            adb shell screencap -p "/sdcard/$FILENAME" 2>/dev/null
            adb pull "/sdcard/$FILENAME" "$FILEPATH" 2>/dev/null
            adb shell rm "/sdcard/$FILENAME" 2>/dev/null

            if [ -f "$FILEPATH" ]; then
                ok "Screenshot: $FILEPATH ($(ls -lh "$FILEPATH" | awk '{print $5}'))"
                return 0
            fi
        fi
    fi

    fail "Screenshot alınamadı."
    echo "  Manuel: Power + Volume Down"
}

# ═══════════════════════════════════════════════════
#  LOGS
# ═══════════════════════════════════════════════════
cmd_logs() {
    log "═══ LOGCAT TOPLANIYOR ═══"
    mkdir -p "$SCREENSHOT_DIR"

    local LOGFILE="$SCREENSHOT_DIR/logcat_${TIMESTAMP}.txt"

    if check_adb; then
        local DEVICE=$(adb devices 2>/dev/null | grep -w "device" | head -1 | awk '{print $1}')
        if [ -n "$DEVICE" ]; then
            # JarvisMobile logları
            adb logcat -d -v time > "$LOGFILE" 2>/dev/null
            ok "Full logcat: $LOGFILE"

            # Sadece uygulama logları
            local APPLOG="$SCREENSHOT_DIR/app_log_${TIMESTAMP}.txt"
            adb logcat -d -v time | grep -iE "$PACKAGE|Jarvis|AndroidRuntime|FATAL" > "$APPLOG" 2>/dev/null
            ok "App logs: $APPLOG"

            # Crash kontrolü
            local CRASHES=$(adb logcat -d | grep -c "FATAL EXCEPTION" 2>/dev/null || echo "0")
            if [ "$CRASHES" -gt 0 ]; then
                fail "CRASH TESPİT EDİLDİ! ($CRASHES adet)"
                adb logcat -d | grep -A 20 "FATAL EXCEPTION" | tail -30
            else
                ok "Crash bulunamadı."
            fi

            return 0
        fi
    fi

    fail "Log toplanamadı. ADB bağlı değil."
}

# ═══════════════════════════════════════════════════
#  UI TEST
# ═══════════════════════════════════════════════════
cmd_test() {
    log "═══ UI TEST BAŞLIYOR ═══"
    local PASS=0
    local FAIL=0

    test_check() {
        local name="$1"
        local result="$2"
        if [ "$result" = "pass" ]; then
            ok "TEST: $name"
            ((PASS++))
        else
            fail "TEST: $name"
            ((FAIL++))
        fi
    }

    # Test 1: Uygulama yüklü mü?
    log "Test 1: Uygulama yüklü mü?"
    PATH="$PATH:$TERMUX_BIN"
    local INSTALLED=$(pm list packages 2>/dev/null | grep "$PACKAGE" || echo "")
    if [ -n "$INSTALLED" ]; then
        test_check "Uygulama yüklü" "pass"
    else
        test_check "Uygulama yüklü" "fail"
    fi

    # Test 2: MainActivity çalışıyor mu?
    log "Test 2: MainActivity çalışıyor mu?"
    PATH="$PATH:$TERMUX_BIN"
    local RUNNING=$(dumpsys activity activities 2>/dev/null | grep "$PACKAGE" | head -1 || echo "")
    if [ -n "$RUNNING" ]; then
        test_check "MainActivity çalışıyor" "pass"
    else
        test_check "MainActivity çalışıyor" "fail"
    fi

    # Test 3: View var mı? (uiautomatordump)
    log "Test 3: UI hiyerarşisi dump ediliyor..."
    if check_adb; then
        local DEVICE=$(adb devices 2>/dev/null | grep -w "device" | head -1 | awk '{print $1}')
        if [ -n "$DEVICE" ]; then
            adb shell uiautomator dump /sdcard/ui_dump.xml 2>/dev/null
            adb pull /sdcard/ui_dump.xml "$SCREENSHOT_DIR/ui_dump_${TIMESTAMP}.xml" 2>/dev/null
            adb shell rm /sdcard/ui_dump.xml 2>/dev/null

            local DUMPFILE="$SCREENSHOT_DIR/ui_dump_${TIMESTAMP}.xml"
            if [ -f "$DUMPFILE" ]; then
                # TextView sayısını kontrol et (saat, batarya vb.)
                local TEXTVIEWS=$(grep -c "TextView" "$DUMPFILE" 2>/dev/null || echo "0")
                if [ "$TEXTVIEWS" -gt 0 ]; then
                    test_check "UI hiyerarşisi mevcut ($TEXTVIEWS TextView)" "pass"
                else
                    test_check "UI hiyerarşisi mevcut" "fail"
                fi
            fi
        fi
    fi

    # Test 4: Logcat crash kontrolü
    log "Test 4: Crash kontrolü..."
    if check_adb; then
        local CRASHES=$(adb logcat -d -t 100 | grep -c "FATAL EXCEPTION" 2>/dev/null || echo "0")
        if [ "$CRASHES" = "0" ]; then
            test_check "Son 100 satırda crash yok" "pass"
        else
            test_check "Son 100 satırda crash var ($CRASHES)" "fail"
        fi
    fi

    # SONUÇ
    echo ""
    echo "═══════════════════════════════════"
    echo "  TEST SONUCU: $PASS başarılı, $FAIL başarısız"
    echo "═══════════════════════════════════"
}

# ═══════════════════════════════════════════════════
#  ALL — TÜM ADIMLAR
# ═══════════════════════════════════════════════════
cmd_all() {
    log "═══ TAM DÖNGÜ ═══"
    echo ""

    cmd_install
    echo ""
    warn "Kurulumdan sonra uygulamayı aç ve 3 saniye bekle."
    echo ""
    cmd_launch
    echo ""
    log "3 saniye bekle..."
    sleep 3
    cmd_screenshot
    echo ""
    cmd_logs
    echo ""
    cmd_test
}

# ═══════════════════════════════════════════════════
#  YARDIM
# ═══════════════════════════════════════════════════
cmd_help() {
    echo ""
    echo "╔══════════════════════════════════════════════╗"
    echo "║  JarvisMobile Local Test Script              ║"
    echo "╚══════════════════════════════════════════════╝"
    echo ""
    echo "  Kullanım: bash local-test.sh <komut>"
    echo ""
    echo "  Komutlar:"
    echo "    install     APK'yı telefona kur"
    echo "    launch      Uygulamayı başlat"
    echo "    screenshot  Ekran görüntüsü al"
    echo "    logs        Logcat topla + crash kontrol"
    echo "    test        Basit UI testleri çalıştır"
    echo "    all         Tüm adımları sırasıyla yap"
    echo ""
    echo "  Örnek:"
    echo "    bash local-test.sh all"
    echo ""
    echo "  APK Konumu: $APK_PATH"
    echo "  Screenshot: $SCREENSHOT_DIR/"
    echo ""
}

# ═══════════════════════════════════════════════════
#  ANA PROGRAM
# ═══════════════════════════════════════════════════
case "${1:-help}" in
    install)    cmd_install ;;
    launch)     cmd_launch ;;
    screenshot) cmd_screenshot ;;
    logs)       cmd_logs ;;
    test)       cmd_test ;;
    all)        cmd_all ;;
    *)          cmd_help ;;
esac
