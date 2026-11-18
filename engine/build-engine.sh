#!/bin/sh
# Сборка встроенного движка YouCode: opencode из исходников на Bun для Android.
# Результат: app/src/main/jniLibs/arm64-v8a/libopencode.so
# (обычный исполняемый файл с именем библиотеки: только из папки библиотек Android разрешает запуск).
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
WORK="$ROOT/.work"
TARGET_DIR="$ROOT/../app/src/main/jniLibs/arm64-v8a"

BUN_VERSION="1.4.4"
OPENCODE_TAG="v2.2.5"
BUN_BASE="https://github.com/oven-sh/bun/releases/download/bun-v${BUN_VERSION}"
ANDROID_ZIP="bun-linux-aarch64-android.zip"
HOST_ZIP="bun-linux-x64.zip"

log() { printf '==> %s\n' "$*"; }

mkdir -p "$WORK/dl" "$TARGET_DIR"
cd "$WORK"

# 1. Bun: версия для Android (она войдёт в итоговый файл) и версия для этой машины (ею собираем)
log "Скачиваю Bun $BUN_VERSION"
curl -fsSL -o dl/SHASUMS256.txt "$BUN_BASE/SHASUMS256.txt"
for zip in "$ANDROID_ZIP" "$HOST_ZIP"; do
    [ -f "dl/$zip" ] || curl -fsSL -o "dl/$zip" "$BUN_BASE/$zip"
done
(cd dl && grep -E "  ($ANDROID_ZIP|$HOST_ZIP)\$" SHASUMS256.txt | sha256sum -c -)

[ -d bun-android ] || unzip -oq "dl/$ANDROID_ZIP" -d bun-android
[ -d bun-host ] || unzip -oq "dl/$HOST_ZIP" -d bun-host
ANDROID_BUN="$WORK/bun-android/${ANDROID_ZIP%.zip}/bun"
HOST_BUN_DIR="$WORK/bun-host/${HOST_ZIP%.zip}"
PATH="$HOST_BUN_DIR:$PATH"
export PATH

# 2. Исходники opencode нужной версии + наш патч
if [ ! -f src/.prepared ]; then
    log "Получаю opencode $OPENCODE_TAG"
    rm -rf src
    git clone -q --depth 1 --branch "$OPENCODE_TAG" https://github.com/anomalyco/opencode.git src
    git -C src apply "$ROOT/patches/android-bun.patch"
    (cd src && bun install)
    # нативные помощники для arm64, как их подтягивает штатная сборка opencode
    (
        cd src/packages/cli
        # версия может быть записана как "catalog:", тогда берём её из каталога корневого package.json
        pkg_version() {
            PKG="$1" bun -e '
                const p = require("./package.json"), r = require("../../package.json");
                let v = p.dependencies[process.env.PKG];
                if (String(v).startsWith("catalog:")) {
                    const c = (r.workspaces && r.workspaces.catalog) || r.catalog || {};
                    v = c[process.env.PKG];
                }
                console.log(v);
            '
        }
        tui_ver=$(pkg_version "@opentui/core")
        pty_ver=$(pkg_version "@opencode/pty")
        bun install --os="*" --cpu="*" --libc="*" "@opentui/core@$tui_ver" "@opencode/pty@$pty_ver"
    )
    touch src/.prepared
fi

# 3. Сборка единого исполняемого файла под linux-arm64 (bionic)
log "Собираю opencode"
(
    cd src/packages/cli
    YOUCODE_BUN_EXECUTABLE="$ANDROID_BUN" \
    OPENCODE_TARGET="linux-arm64" \
    OPENCODE_CHANNEL="latest" \
    OPENCODE_VERSION="${OPENCODE_TAG#v}" \
        bun run script/build.ts --skip-install --skip-web-ui --outdir="$WORK/out"
)

cp "$WORK/out/cli-linux-arm64/bin/opencode" "$TARGET_DIR/libopencode.so"
log "Готово:"
ls -la "$TARGET_DIR/libopencode.so"
