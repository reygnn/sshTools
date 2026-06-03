#!/bin/bash

# Farben für bessere Lesbarkeit
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Ziel-Gerät: Hardware-Serial des Pixel 9a (stabil über USB UND WiFi-TLS).
# Bei WiFi-TLS wickelt ADB die Hardware-Serial in einen String wie
# `adb-54191JEBF11618-XXX._adb-tls-connect._tcp` — der Hardware-Anteil
# bleibt aber als Substring erhalten, daher matchen wir per `grep`/awk
# auf das Substring statt auf die volle ADB-Serial.
TARGET_DEVICE_SERIAL_PATTERN="54191JEBF11618"

echo "============================================"
echo "AAB zu APK Installer"
echo "============================================"
echo

# Argumente prüfen
if [ $# -eq 0 ]; then
    echo -e "${RED}FEHLER: Keine AAB-Datei angegeben!${NC}"
    echo "Verwendung: $0 <datei.aab> [uninstall] [--keystore=<pfad>] [--ks-pass=<passwort>] [--key-alias=<alias>] [--key-pass=<passwort>]"
    echo "  - <datei.aab>: Pfad zur AAB-Datei (erforderlich)"
    echo "  - uninstall: Optional - deinstalliert alte Version vor Installation"
    echo "  - --keystore=<pfad>: Pfad zum Release-Keystore (optional)"
    echo "  - --ks-pass=<passwort>: Keystore-Passwort (optional)"
    echo "  - --key-alias=<alias>: Key-Alias (optional)"
    echo "  - --key-pass=<passwort>: Key-Passwort (optional)"
    echo ""
    echo "Hinweis: Keystore-Informationen können auch aus '../keystore.properties' geladen werden"
    exit 1
fi

AAB_FILE="$1"
UNINSTALL_FLAG=""
KEYSTORE=""
KS_PASS=""
KEY_ALIAS=""
KEY_PASS=""
APKS_FILE="app.apks"
USE_RELEASE=false
KEYSTORE_SOURCE="Debug-Keystore (Standard)"

# Aufräum-Funktion für temporäre Dateien (wird auch bei Fehlern ausgeführt)
cleanup() {
    if [ -f "$APKS_FILE" ]; then
        rm -f "$APKS_FILE"
    fi
    if [ -f "universal.apk" ]; then
        rm -f "universal.apk"
    fi
}
trap cleanup EXIT

# Argumente parsen
shift
while [[ $# -gt 0 ]]; do
    case $1 in
        uninstall)
            UNINSTALL_FLAG="uninstall"
            shift
            ;;
        --keystore=*)
            KEYSTORE="${1#*=}"
            shift
            ;;
        --ks-pass=*)
            KS_PASS="${1#*=}"
            shift
            ;;
        --key-alias=*)
            KEY_ALIAS="${1#*=}"
            shift
            ;;
        --key-pass=*)
            KEY_PASS="${1#*=}"
            shift
            ;;
        *)
            echo -e "${RED}Unbekannter Parameter: $1${NC}"
            exit 1
            ;;
    esac
done

# Keystore.properties laden, falls keine Command-Line-Parameter angegeben wurden
KEYSTORE_PROPS="/home/user/.familyKey/keystore.properties"
if [ -z "$KEYSTORE" ] && [ -f "$KEYSTORE_PROPS" ]; then
    echo -e "${BLUE}Lade Keystore-Informationen aus $KEYSTORE_PROPS...${NC}"
    
    # Properties einlesen (mit Unterstützung für Sonderzeichen und Windows-Zeilenumbrüche)
    while IFS='=' read -r key value || [ -n "$key" ]; do
        # Windows-Zeilenumbrüche entfernen
        key=$(echo "$key" | tr -d '\r')
        value=$(echo "$value" | tr -d '\r')
        
        # Kommentare und leere Zeilen überspringen
        [[ "$key" =~ ^#.*$ ]] && continue
        [[ -z "$key" ]] && continue
        
        # Nur führende/nachfolgende Whitespace entfernen, Sonderzeichen behalten
        key=$(echo "$key" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
        value=$(echo "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
        
        case "$key" in
            storePassword)
                KS_PASS="$value"
                ;;
            keyAlias)
                KEY_ALIAS="$value"
                ;;
            keyPassword)
                KEY_PASS="$value"
                ;;
            storeFile)
                KEYSTORE="$value"
                ;;
        esac
    done < "$KEYSTORE_PROPS"
    
    if [ -n "$KEYSTORE" ]; then
        KEYSTORE_SOURCE="keystore.properties"
        USE_RELEASE=true
        echo -e "${GREEN}✓ Keystore-Informationen geladen${NC}"
        echo -e "${GREEN}  - Keystore: $KEYSTORE${NC}"
        echo -e "${GREEN}  - Alias: $KEY_ALIAS${NC}"
    else
        echo -e "${YELLOW}⚠ Konnte keine Keystore-Informationen aus $KEYSTORE_PROPS laden${NC}"
    fi
fi

# Bundletool automatisch finden
BUNDLETOOL=$(ls bundletool*.jar 2>/dev/null | head -n 1)
if [ -z "$BUNDLETOOL" ]; then
    echo -e "${RED}FEHLER: Keine bundletool*.jar Datei gefunden!${NC}"
    echo "Bitte bundletool-all-*.jar in das gleiche Verzeichnis legen."
    exit 1
fi
echo -e "${GREEN}Verwende: $BUNDLETOOL${NC}"

# Keystore Info anzeigen
if [ -n "$KEYSTORE" ]; then
    echo -e "${GREEN}Verwende Release-Keystore: $KEYSTORE${NC}"
else
    echo -e "${YELLOW}Verwende Debug-Keystore (für Release bitte --keystore angeben oder keystore.properties anlegen)${NC}"
fi
echo

# Prüfen ob AAB-Datei existiert
if [ ! -f "$AAB_FILE" ]; then
    echo -e "${RED}FEHLER: AAB-Datei '$AAB_FILE' nicht gefunden!${NC}"
    exit 1
fi

# Prüfen ob Keystore existiert
if [ -n "$KEYSTORE" ] && [ ! -f "$KEYSTORE" ]; then
    echo -e "${RED}FEHLER: Keystore '$KEYSTORE' nicht gefunden!${NC}"
    exit 1
fi

echo -e "${YELLOW}[1/6] Prüfen ob Gerät verbunden ist...${NC}"
adb devices -l
if [ $? -ne 0 ]; then
    echo -e "${RED}FEHLER: adb nicht gefunden oder Gerät nicht verbunden!${NC}"
    exit 1
fi

# Find the Pixel 9a — robust over USB, WiFi-TLS (mDNS) AND plain TCP/Tailscale.
#
# Over a plain TCP connection the adb serial is just the IP (e.g.
# 100.70.9.96:5555), so the hardware serial never appears in `adb devices`
# output. We therefore: (1) best-effort connect the endpoints from
# install-aab.conf, (2) try the fast hardware-serial substring match, and
# (3) fall back to querying ro.serialno on each authorized device and
# matching that — which still keys on the real hardware serial, so the
# emulator can never be selected by accident.

# Load PHONE_ENDPOINTS (and the serial pattern) from the local conf, if present.
if [ -f "install-aab.conf" ]; then
    # shellcheck disable=SC1091
    source "install-aab.conf"
fi

# Best-effort: bring up the known endpoints (Tailscale IP first, then
# LAN/wireless debugging). Each connect is capped with a short timeout so an
# unreachable endpoint (e.g. Tailscale when off-VPN) can't block — the next
# endpoint still gets its chance to become the live connection.
if [ -n "${PHONE_ENDPOINTS:-}" ]; then
    for endpoint in "${PHONE_ENDPOINTS[@]}"; do
        timeout 2 adb connect "$endpoint" >/dev/null 2>&1
    done
fi

# (1) Fast path: the serial pattern appears directly in `adb devices` output.
TARGET_SERIAL=$(adb devices -l | awk -v p="$TARGET_DEVICE_SERIAL_PATTERN" '$0 ~ p && $2 == "device" {print $1; exit}')

# (2) Fallback: ask each authorized device for its real hardware serial.
if [ -z "$TARGET_SERIAL" ]; then
    while read -r serial state; do
        [ "$state" = "device" ] || continue
        hw=$(timeout 2 adb -s "$serial" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')
        if echo "$hw" | grep -q "$TARGET_DEVICE_SERIAL_PATTERN"; then
            TARGET_SERIAL="$serial"
            break
        fi
    done < <(adb devices | awk 'NR>1 && NF>=2 {print $1, $2}')
fi

if [ -z "$TARGET_SERIAL" ]; then
    echo -e "${RED}FEHLER: Pixel 9a (Serial-Pattern: $TARGET_DEVICE_SERIAL_PATTERN) nicht verbunden / nicht autorisiert!${NC}"
    echo -e "${YELLOW}Aktuell verbundene Geräte siehe oben. Bei WiFi-Debugging: Gerät entsperren + Pairing prüfen.${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Ziel-Gerät: $TARGET_SERIAL${NC}"
echo

# Package Name aus AAB extrahieren
echo -e "${YELLOW}[2/6] Package Name ermitteln...${NC}"
MANIFEST_OUTPUT=$(java -jar "$BUNDLETOOL" dump manifest --bundle="$AAB_FILE" | head -1)
PACKAGE_NAME=$(echo "$MANIFEST_OUTPUT" | sed -n 's/.*package="\([^"]*\)".*/\1/p')

if [ -z "$PACKAGE_NAME" ]; then
    echo -e "${RED}FEHLER: Konnte Package Name nicht ermitteln!${NC}"
    echo "Manifest Ausgabe:"
    echo "$MANIFEST_OUTPUT"
    exit 1
fi
echo -e "${GREEN}Package Name: $PACKAGE_NAME${NC}"
echo

# Deinstallieren falls gewünscht
if [ "$UNINSTALL_FLAG" == "uninstall" ]; then
    echo -e "${YELLOW}[3/6] Alte Version deinstallieren...${NC}"
    adb -s "$TARGET_SERIAL" uninstall "$PACKAGE_NAME"
    echo
else
    echo -e "${YELLOW}[3/6] Überspringe Deinstallation (nicht angefordert)${NC}"
    echo
fi

echo -e "${YELLOW}[4/6] APK-Set aus AAB erstellen...${NC}"

# Bundletool-Befehl mit oder ohne Keystore
if [ -n "$KEYSTORE" ]; then
    # Mit Release-Keystore
    BUILD_CMD="java -jar \"$BUNDLETOOL\" build-apks --bundle=\"$AAB_FILE\" --output=\"$APKS_FILE\" --mode=universal --ks=\"$KEYSTORE\""
    
    if [ -n "$KS_PASS" ]; then
        BUILD_CMD="$BUILD_CMD --ks-pass=pass:$KS_PASS"
    fi
    
    if [ -n "$KEY_ALIAS" ]; then
        BUILD_CMD="$BUILD_CMD --ks-key-alias=$KEY_ALIAS"
    fi
    
    if [ -n "$KEY_PASS" ]; then
        BUILD_CMD="$BUILD_CMD --key-pass=pass:$KEY_PASS"
    fi
    
    eval $BUILD_CMD
    BUILD_SUCCESS=$?
else
    # Mit Debug-Keystore (Standard)
    java -jar "$BUNDLETOOL" build-apks --bundle="$AAB_FILE" --output="$APKS_FILE" --mode=universal
    BUILD_SUCCESS=$?
fi

if [ $BUILD_SUCCESS -ne 0 ]; then
    echo -e "${RED}FEHLER: Konnte APK-Set nicht erstellen!${NC}"
    exit 1
fi

# Bestätigung über verwendete Signatur - NUR bei erfolgreichem Build
if [ "$USE_RELEASE" = true ]; then
    echo -e "${GREEN}✓ APK erfolgreich mit Release-Keys signiert!${NC}"
else
    echo -e "${BLUE}ℹ APK mit Debug-Keys signiert (für Produktion Release-Keys verwenden)${NC}"
fi
echo

echo -e "${YELLOW}[5/6] APK extrahieren...${NC}"
rm -f universal.apk
unzip -o "$APKS_FILE" universal.apk
if [ $? -ne 0 ]; then
    echo -e "${RED}FEHLER: Konnte APK nicht extrahieren!${NC}"
    exit 1
fi
echo

echo -e "${YELLOW}[6/6] App installieren auf $TARGET_SERIAL...${NC}"
adb -s "$TARGET_SERIAL" install universal.apk
if [ $? -ne 0 ]; then
    echo -e "${RED}FEHLER: Installation fehlgeschlagen!${NC}"
    exit 1
fi
echo

# APK umbenennen basierend auf AAB-Dateinamen
echo -e "${YELLOW}APK für GitHub vorbereiten...${NC}"
AAB_BASENAME=$(basename "$AAB_FILE" .aab)
FINAL_APK="${AAB_BASENAME}.apk"

mv universal.apk "$FINAL_APK"
# bundletool stamps fixed DOS-epoch timestamps (1981-01-01) into the
# .apks zip entries for reproducible builds; `unzip -o` propagates that
# to the extracted file's mtime. Reset to "now" — content and signature
# untouched, only the mtime is rewritten.
touch "$FINAL_APK"
echo -e "${GREEN}✓ APK gespeichert als: $FINAL_APK${NC}"
echo

echo
echo "============================================"
echo -e "${GREEN}Installation erfolgreich abgeschlossen!${NC}"
echo ""
echo -e "${BLUE}Verwendete Signatur:${NC} $KEYSTORE_SOURCE"
if [ "$USE_RELEASE" = true ]; then
    echo -e "${GREEN}✓ App wurde mit Release-Signatur installiert${NC}"
    echo -e "${GREEN}✓ Release-APK bereit für GitHub: $FINAL_APK${NC}"
else
    echo -e "${YELLOW}⚠ App wurde mit Debug-Signatur installiert${NC}"
    echo -e "${YELLOW}⚠ Für GitHub-Release bitte Release-Keys verwenden!${NC}"
fi
echo "============================================"
