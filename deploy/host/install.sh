#!/usr/bin/env bash
# Install one qod release side by side with the others, and cache what it runs.
#
#   install.sh VERSION
#
# Creates the service user and /etc/qod when missing, installs the qod CLI of VERSION into its own
# virtualenv, /opt/qod/versions/VERSION, then runs that CLI's
# `qod start --version VERSION --download-only` as the service user, so the manager jar, a Java
# runtime when the host has none, DuckDB and the DuckDB extensions nodes install are in that
# user's cache. After it, starting VERSION needs no network. Writes /etc/qod/version.env only
# when there is none (the first install); switch.sh moves the pin afterwards.
#
# Idempotent: an installed version is not reinstalled, only its cache is checked again (which
# downloads nothing when it is complete). Never touches another version's folder. A version PyPI
# or GitHub does not have is refused, and the folder this run created is removed.
#
# Needs root (it creates a user and writes /opt/qod and /etc/qod), and the network.
#
# Environment, for tests and other layouts (defaults in brackets):
#   QOD_HOST_PREFIX [/opt/qod]  QOD_HOST_ETC [/etc/qod]  QOD_HOST_USER [qod]
#   QOD_HOST_HOME   [the user's home]  PYTHON [python3]

set -eu

PREFIX="${QOD_HOST_PREFIX:-/opt/qod}"
ETC="${QOD_HOST_ETC:-/etc/qod}"
SERVICE_USER="${QOD_HOST_USER:-qod}"
PYTHON="${PYTHON:-python3}"
# What a systemd service gets as PATH, so the download-only run sees the Java the unit will see.
SERVICE_PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
# The variables of qod.env that change what a start downloads, or where it looks for it.
FETCH_KEYS="JAVA_BIN JAVA_HOME JAR_CACHE_DIR DUCKDB_VERSION DUCKDB_CACHE_DIR XDG_CACHE_HOME
HTTP_PROXY HTTPS_PROXY http_proxy https_proxy NO_PROXY no_proxy"

die() {
  echo "install.sh: $*" >&2
  exit 1
}

usage() {
  echo "usage: install.sh VERSION   (a qod release, for example 0.13.1)" >&2
  exit 2
}

[ "$#" -eq 1 ] || usage
VERSION="$1"
case "$VERSION" in
  "" | *[!0-9.]* | .* | *. | *..*) usage ;;
esac

# The value of KEY in qod.env (systemd EnvironmentFile syntax: KEY=value, optionally quoted),
# the last one when repeated; nothing when absent.
qod_env_value() {
  [ -r "$ETC/qod.env" ] || return 0
  sed -n "s/^[[:space:]]*$1=//p" "$ETC/qod.env" | tail -n 1 |
    sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

user_home() {
  if [ -n "${QOD_HOST_HOME:-}" ]; then
    echo "$QOD_HOST_HOME"
  elif command -v getent >/dev/null 2>&1; then
    getent passwd "$SERVICE_USER" | cut -d: -f6
  else
    eval "echo ~$SERVICE_USER"
  fi
}

# Run "$@" as the service user in the environment its unit gives it: HOME, the service PATH and
# the download-relevant keys of qod.env, nothing else of this shell.
as_service_user() {
  local key value
  local cmd=(env -i "HOME=$(user_home)" "PATH=$SERVICE_PATH")
  for key in $FETCH_KEYS; do
    value="$(qod_env_value "$key")"
    if [ -n "$value" ]; then
      cmd+=("$key=$value")
    fi
  done
  cmd+=("$@")
  if [ "$(id -un)" = "$SERVICE_USER" ]; then
    "${cmd[@]}"
  elif command -v runuser >/dev/null 2>&1; then
    runuser -u "$SERVICE_USER" -- "${cmd[@]}"
  elif command -v sudo >/dev/null 2>&1; then
    sudo -u "$SERVICE_USER" -- "${cmd[@]}"
  else
    die "neither runuser nor sudo is installed, so nothing can run as $SERVICE_USER"
  fi
}

ensure_user() {
  if id "$SERVICE_USER" >/dev/null 2>&1; then
    return 0
  fi
  [ "$(id -u)" -eq 0 ] || die "user $SERVICE_USER does not exist and only root can create it"
  nologin="$(command -v nologin || echo /usr/sbin/nologin)"
  useradd --system --user-group --create-home --home-dir /var/lib/qod --shell "$nologin" \
    "$SERVICE_USER"
  echo "created the system user $SERVICE_USER (home /var/lib/qod)"
}

ensure_user
mkdir -p "$ETC" "$PREFIX/versions"
[ -w "$ETC" ] && [ -w "$PREFIX/versions" ] || die "cannot write $ETC and $PREFIX/versions (run as root)"
# Two runs at once (an agent retrying) wait for each other rather than share a folder.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$PREFIX/.install.lock"
  flock 9
fi

dir="$PREFIX/versions/$VERSION"
marker="$dir/.qod-installed"
created=""
cleanup() {
  if [ -n "$created" ]; then
    rm -rf "$dir"
  fi
}
trap cleanup EXIT

if [ -f "$marker" ]; then
  echo "qod $VERSION is installed in $dir; checking its cache"
else
  # A folder without the marker is what an interrupted run of this script left.
  rm -rf "$dir"
  created=1
  echo "installing qod $VERSION into $dir"
  "$PYTHON" -m venv "$dir" || die "could not create a virtualenv with $PYTHON (install python3-venv?)"
  "$dir/bin/pip" install --quiet --disable-pip-version-check --no-input "qod==$VERSION" ||
    die "pip could not install qod==$VERSION (is it a published release?); nothing was kept"
  reported="$("$dir/bin/qod" --version)"
  [ "$reported" = "qod $VERSION" ] || die "the installed CLI reports '$reported', not 'qod $VERSION'"
fi

echo "caching what qod $VERSION runs, as $SERVICE_USER"
as_service_user "$dir/bin/qod" start --version "$VERSION" --download-only ||
  die "qod $VERSION could not cache what it runs (see above)${created:+; nothing was kept}"

if [ -n "$created" ]; then
  : >"$marker"
  created=""
fi

if [ ! -e "$ETC/version.env" ]; then
  tmp="$(mktemp "$ETC/.version.env.XXXXXX")"
  echo "QOD_VERSION=$VERSION" >"$tmp"
  chmod 0644 "$tmp"
  mv -f "$tmp" "$ETC/version.env"
  echo "pinned $VERSION in $ETC/version.env (there was no pin)"
else
  echo "installed qod $VERSION; switch to it with: switch.sh $VERSION"
fi
