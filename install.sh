#!/usr/bin/env bash
# Установка приложений screen-node на проектор через adb — например, после сброса настроек
# проектора к заводским. Скачивает оба APK указанного релиза GitHub и делает на проекторе то же,
# что делалось при первой настройке (кроме удаления ненужных приложений):
#   1. ставит экран akiba (space.akiba.screen_node) и службу пульта (space.akiba.remote);
#   2. выдаёт службе пульта право переключать клавиатуру (печать на любом языке, подтверждение
#      обновлений) и право ставить приложения (обновления с сервера);
#   3. пробует сделать службу пульта владельцем устройства (тихие обновления; на Wanbo X2 Max
#      невозможно — в прошивке нет device_admin, это не ошибка);
#   4. если заданы --server/--token — прописывает их обоим приложениям;
#   5. запускает службу пульта и экран akiba.
#
# Использование:
#   ./install.sh --projector <ip:порт adb> [--adb <путь к adb>] [--server <ip:порт>] [--token <токен>] <ссылка на релиз>
#
#   --projector ip:порт   обязательно: адрес adb проектора, например 192.168.8.103:5555
#   --adb путь            необязательно: бинарь adb, если его нет в PATH
#   --server ip:порт      необязательно: адрес HTTP сервера трансляций (например 192.168.8.115:8080)
#   --token токен         необязательно: токен проектора (секрет сервера)
#   ссылка на релиз       https://github.com/akiba-hs/screen-node/releases/tag/<тег> (слеш в конце — можно)
#
# Пример:
#   ./install.sh --projector 192.168.8.103:5555 --server 192.168.8.115:8080 \
#       https://github.com/akiba-hs/screen-node/releases/tag/v1.0.0
set -euo pipefail

readonly REPO_URL="https://github.com/akiba-hs/screen-node"
readonly AKIBA_PKG="space.akiba.screen_node"
readonly REMOTE_PKG="space.akiba.remote"
# Файлы APK в релизе (их так называет .github/workflows/release.yml).
readonly AKIBA_ASSET="akiba.apk"
readonly REMOTE_ASSET="akiba-remote.apk"

ADB=""
PROJECTOR=""
SERVER=""
TOKEN=""
RELEASE_URL=""
TAG=""
WORKDIR=""

log() { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33mВНИМАНИЕ:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mОШИБКА:\033[0m %s\n' "$*" >&2; exit 1; }

# Справка — комментарий в начале файла.
usage() {
  sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'
}

parse_args() {
  if [[ $# -eq 0 ]]; then
    usage
    exit 0
  fi
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --projector) PROJECTOR="${2:?после --projector нужен ip:порт}"; shift 2 ;;
      --adb) ADB="${2:?после --adb нужен путь к adb}"; shift 2 ;;
      --server) SERVER="${2:?после --server нужен ip:порт}"; shift 2 ;;
      --token) TOKEN="${2:?после --token нужен токен}"; shift 2 ;;
      -h|--help) usage; exit 0 ;;
      -*) die "неизвестный ключ $1 (справка — запуск без аргументов)" ;;
      *)
        [[ -z "$RELEASE_URL" ]] || die "ссылка на релиз передана дважды: $RELEASE_URL и $1"
        RELEASE_URL="$1"; shift ;;
    esac
  done
}

validate_args() {
  [[ -n "$PROJECTOR" ]] || die "не указан адрес проектора: --projector <ip:порт adb>"
  [[ "$PROJECTOR" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]] || die "адрес проектора — в виде ip:порт, например 192.168.8.103:5555"
  [[ -n "$RELEASE_URL" ]] || die "не указана ссылка на релиз: $REPO_URL/releases/tag/<тег>"
  TAG="$(release_tag "$RELEASE_URL")" || die "ссылка должна вести на релиз репозитория $REPO_URL: $REPO_URL/releases/tag/<тег>, получено: $RELEASE_URL"
  if [[ -n "$SERVER" && ! "$SERVER" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]]; then
    die "адрес сервера — в виде ip:порт, например 192.168.8.115:8080"
  fi
  if [[ -n "$TOKEN" && ! "$TOKEN" =~ ^[A-Za-z0-9._~-]{1,128}$ ]]; then
    die "токен: только латиница, цифры и ._~-"
  fi
}

# Тег из ссылки на релиз нужного репозитория (слеш в конце не обязателен); иначе — код 1.
release_tag() {
  local re="^https://github\.com/akiba-hs/screen-node/releases/tag/([^/?#[:space:]]+)/?$"
  [[ "$1" =~ $re ]] || return 1
  printf '%s\n' "${BASH_REMATCH[1]}"
}

find_adb() {
  if [[ -n "$ADB" ]]; then
    [[ -x "$ADB" ]] || die "по пути $ADB нет исполняемого adb"
    return
  fi
  if command -v adb >/dev/null; then
    ADB="$(command -v adb)"
    return
  fi
  local c
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$c" && -x "$c/platform-tools/adb" ]]; then
      ADB="$c/platform-tools/adb"
      return
    fi
  done
  die "не найден adb: установите platform-tools или укажите путь ключом --adb"
}

adb_() { "$ADB" -s "$PROJECTOR" "$@"; }

download_release() {
  command -v curl >/dev/null || die "нужен curl"
  WORKDIR="$(mktemp -d)"
  trap 'rm -rf "$WORKDIR"' EXIT
  local asset
  for asset in "$AKIBA_ASSET" "$REMOTE_ASSET"; do
    log "Скачиваю $asset из релиза $TAG"
    curl -fL --retry 3 -o "$WORKDIR/$asset" "$REPO_URL/releases/download/$TAG/$asset" \
      || die "не удалось скачать $asset: есть ли релиз $TAG и файл $asset в нём?"
  done
}

connect_projector() {
  log "Подключаюсь к проектору $PROJECTOR по adb"
  local out
  out="$("$ADB" connect "$PROJECTOR" 2>&1 || true)"
  # macOS: adb-сервер, запущенный не из терминала, может не иметь разрешения «Локальная сеть».
  if [[ "$out" == *"No route to host"* ]]; then
    warn "adb-сервер не видит локальную сеть — перезапускаю его"
    "$ADB" kill-server >/dev/null 2>&1 || true
    out="$("$ADB" connect "$PROJECTOR" 2>&1 || true)"
  fi
  [[ "$out" == *"connected to"* ]] || die "не удалось подключиться к $PROJECTOR: $out"
  local _
  for _ in 1 2 3 4 5; do
    [[ "$(adb_ get-state 2>/dev/null)" == device ]] && break
    sleep 1
  done
  [[ "$(adb_ get-state 2>/dev/null)" == device ]] || die "проектор $PROJECTOR не готов (не разрешил отладку?)"
  log "Проектор: $(adb_ shell getprop ro.product.model | tr -d '\r'), Android $(adb_ shell getprop ro.build.version.release | tr -d '\r')"
}

install_apk() {
  local apk="$1" name="$2" out
  log "Установка: $name"
  # --no-streaming: сначала push, потом pm install — переживает просадки Wi-Fi.
  # -r — обновить с сохранением данных, -d — разрешить и откат версии.
  out="$(adb_ install --no-streaming -r -d "$apk" 2>&1)" || {
    if [[ "$out" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
      die "$name: на проекторе стоит сборка с другой подписью — удалите её и повторите: $ADB -s $PROJECTOR uninstall <пакет>"
    fi
    die "$name не установлен: $out"
  }
}

install_apps() {
  install_apk "$WORKDIR/$AKIBA_ASSET" "экран akiba ($AKIBA_PKG)"
  install_apk "$WORKDIR/$REMOTE_ASSET" "служба пульта ($REMOTE_PKG)"
}

grant_permissions() {
  log "Право переключать клавиатуру (печать на любом языке, подтверждение обновлений)"
  adb_ shell pm grant "$REMOTE_PKG" android.permission.WRITE_SECURE_SETTINGS \
    || warn "не выдано: пульт будет печатать только латиницу, обновления придётся подтверждать на экране"
  log "Право ставить приложения (обновления с сервера)"
  adb_ shell appops set "$REMOTE_PKG" REQUEST_INSTALL_PACKAGES allow \
    || warn "не выдано: обновления с сервера не установятся"
}

set_device_owner() {
  if adb_ shell dumpsys device_policy 2>/dev/null | tr -d '\r' | grep -q "admin=ComponentInfo{$REMOTE_PKG/"; then
    log "Служба пульта уже владелец устройства"
    return
  fi
  log "Пробую сделать службу пульта владельцем устройства (тихая установка обновлений)"
  local out
  out="$(adb_ shell dpm set-device-owner "$REMOTE_PKG/.AdminReceiver" 2>&1 | tr -d '\r' || true)"
  if [[ "$out" == *Success* ]]; then
    log "Готово: обновления будут ставиться без окна подтверждения"
  else
    warn "не получилось (на Wanbo X2 Max это ожидаемо — в прошивке нет device_admin): обновления"
    warn "будет подтверждать сама служба пульта. Ответ системы: ${out%%$'\n'*}"
  fi
}

# Адрес сервера и токен: приёмник настройки принимает их только от оболочки adb.
configure_server() {
  if [[ -z "$SERVER" && -z "$TOKEN" ]]; then
    warn "--server и --token не заданы: приложения останутся с адресом сервера из сборки (projector.lan:8080)"
    return
  fi
  local pkg args out
  for pkg in "$AKIBA_PKG" "$REMOTE_PKG"; do
    args=()
    [[ -z "$SERVER" ]] || args+=(--es server "$SERVER")
    [[ -z "$TOKEN" ]] || args+=(--es token "$TOKEN")
    out="$(adb_ shell am broadcast -f 0x20 -a space.akiba.SET_SERVER \
      -n "$pkg/space.akiba.screen_node.ConfigReceiver" "${args[@]}" 2>&1 | tr -d '\r')"
    [[ "$out" == *'data="ok'* ]] || die "$pkg не принял настройку сервера: $out"
    log "$pkg: настройка сервера сохранена${SERVER:+ ($SERVER)}"
  done
}

start_apps() {
  log "Запуск службы пульта и экрана akiba"
  adb_ shell am start-foreground-service -n "$REMOTE_PKG/.RemoteService" >/dev/null \
    || warn "служба пульта не запустилась"
  adb_ shell am start -n "$AKIBA_PKG/.MainActivity" >/dev/null \
    || warn "экран akiba не открылся"
}

main() {
  parse_args "$@"
  validate_args
  find_adb
  download_release
  connect_projector
  install_apps
  grant_permissions
  set_device_owner
  configure_server
  start_apps
  log "Готово: на проекторе установлен релиз $TAG"
}

main "$@"
