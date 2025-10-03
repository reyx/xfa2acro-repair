#!/usr/bin/env bash
set -euo pipefail

JAR_URL="https://github.com/reyx/xfa2acro-repair/releases/download/v1.0.0/xfa2acro-repair.jar"

APP_NAME="xfa2acro-repair"
APP_DIR="/usr/local/lib/${APP_NAME}"
BIN_PATH="/usr/local/bin/${APP_NAME}"
TMP_JAR="$(mktemp -t ${APP_NAME}.jar.XXXXXXXX)"

echo "==> Checking prerequisites…"
if ! command -v java >/dev/null 2>&1; then
  echo "Java not found."
  if command -v brew >/dev/null 2>&1; then
    read -p "Install Temurin (OpenJDK) via Homebrew now? [Y/n] " yn
    yn=${yn:-Y}
    if [[ "$yn" =~ ^[Yy]$ ]]; then
      brew install --cask temurin
    else
      echo "Please install a JRE (e.g., 'brew install --cask temurin') and rerun."
      exit 1
    fi
  else
    echo "Homebrew not found. Install Homebrew first: https://brew.sh/"
    exit 1
  fi
fi

echo "==> Downloading ${APP_NAME}…"
curl -fL "$JAR_URL" -o "$TMP_JAR"

echo "==> Installing to ${APP_DIR}…"
sudo mkdir -p "$APP_DIR"
sudo mv "$TMP_JAR" "${APP_DIR}/app.jar"
sudo chmod 0755 "${APP_DIR}"
sudo chmod 0644 "${APP_DIR}/app.jar"

echo "==> Installing launcher to ${BIN_PATH}…"
LAUNCHER="$(mktemp -t ${APP_NAME}.sh.XXXXXXXX)"
cat > "$LAUNCHER" <<"EOF"
#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/usr/local/lib/xfa2acro-repair"
JAR="${APP_DIR}/app.jar"

usage() {
  cat <<USAGE
Usage:
  xfa2acro-repair <input.pdf> [output.pdf]
  xfa2acro-repair --list-fields <pdf>

Notes:
  - If only <input.pdf> is provided, output defaults to <input>.safe.pdf
  - If ASPOSE_CLIENT_ID and ASPOSE_CLIENT_SECRET are set, the tool will use Aspose Cloud to convert XFA → AcroForm first, then repair locally.
USAGE
}

if [[ $# -lt 1 ]]; then
  usage; exit 2
fi

if [[ "$1" == "--list-fields" ]]; then
  if [[ $# -ne 2 ]]; then usage; exit 2; fi
  exec java -jar "$JAR" --list-fields "$2"
fi

in="$1"
if [[ ! -f "$in" ]]; then
  echo "Input not found: $in" >&2
  exit 3
fi

out="${2:-}"
if [[ -z "${out}" ]]; then
  # Default to *.safe.pdf beside the input
  base="${in%.[Pp][Dd][Ff]}"
  out="${base}.safe.pdf"
fi

exec java -jar "$JAR" "$in" "$out"
EOF

chmod +x "$LAUNCHER"
sudo mv "$LAUNCHER" "$BIN_PATH"

echo "==> Verifying…"
"$BIN_PATH" --list-fields "/System/Library/Fonts/Supplemental/Arial Unicode.ttf" >/dev/null 2>&1 || true
echo "✅ Installed. Try:"
echo "   xfa2acro-repair ~/Downloads/g-1450.pdf"
echo "   # → ~/Downloads/g-1450.safe.pdf"
