#!/usr/bin/env bash
#
# company-insight development environment setup (macOS, Apple Silicon / Intel)
#
# Run once on a new Mac (Terminal):
#   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/ysj13/company-insight/main/scripts/setup-mac.sh)"
#
# Or from an existing clone:
#   ./scripts/setup-mac.sh
#
# Safe to run again: installed tools and existing files are skipped.
#
# Environment variables (optional):
#   CI_SETUP_DIR         clone location when run outside the repo (default ~/workspace/company-insight)
#   CI_SETUP_SKIP_IDE    set to 1 to skip IntelliJ IDEA
#   CI_SETUP_SKIP_BUILD  set to 1 to skip the Gradle build

set -euo pipefail

REPO_URL="https://github.com/ysj13/company-insight.git"
DEFAULT_DIR="${CI_SETUP_DIR:-$HOME/workspace/company-insight}"

step() { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
info() { printf '    %s\n' "$1"; }
warn() { printf '    \033[33m! %s\033[0m\n' "$1"; }

# 1. Homebrew ---------------------------------------------------------------
step "Homebrew"
if ! command -v brew >/dev/null 2>&1; then
  info "installing Homebrew (asks for your Mac password)"
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi
if [ -x /opt/homebrew/bin/brew ]; then
  eval "$(/opt/homebrew/bin/brew shellenv)"
elif [ -x /usr/local/bin/brew ]; then
  eval "$(/usr/local/bin/brew shellenv)"
fi
info "$(brew --version | head -1)"

install_formula() {
  if brew list --formula "$1" >/dev/null 2>&1; then info "$1: already installed"; else info "$1: installing"; brew install "$1"; fi
}
install_cask() {
  local cask="$1" app="$2"
  if brew list --cask "$cask" >/dev/null 2>&1 || [ -d "/Applications/$app" ]; then
    info "$cask: already installed"
  else
    info "$cask: installing"
    brew install --cask "$cask"
  fi
}

# 2. Tools ------------------------------------------------------------------
step "Install tools"
install_formula git
install_formula gh
if /usr/libexec/java_home -v 25 >/dev/null 2>&1; then
  info "JDK 25: already installed"
else
  info "JDK 25 (Temurin): installing"
  brew install --cask temurin@25
fi
install_cask docker-desktop "Docker.app"
if [ "${CI_SETUP_SKIP_IDE:-0}" != "1" ]; then
  install_cask intellij-idea "IntelliJ IDEA.app"
fi
JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export JAVA_HOME
info "JAVA_HOME = $JAVA_HOME"

# 3. GitHub login -----------------------------------------------------------
step "GitHub login"
if gh auth status >/dev/null 2>&1; then
  info "already logged in"
else
  info "A browser will open. Enter the one-time code shown here."
  gh auth login --hostname github.com --git-protocol https --web
fi

# 4. Repository -------------------------------------------------------------
step "Repository"
if [ -f settings.gradle.kts ] && grep -q company-insight settings.gradle.kts; then
  REPO_DIR="$(pwd)"
  info "using current folder: $REPO_DIR"
elif [ -d "$DEFAULT_DIR/.git" ]; then
  REPO_DIR="$DEFAULT_DIR"
  info "already cloned: $REPO_DIR"
else
  mkdir -p "$(dirname "$DEFAULT_DIR")"
  git clone "$REPO_URL" "$DEFAULT_DIR"
  REPO_DIR="$DEFAULT_DIR"
fi
cd "$REPO_DIR"

if [ -z "$(git config user.email || true)" ]; then
  login="$(gh api user --jq .login)"
  uid="$(gh api user --jq .id)"
  git config user.name "$login"
  git config user.email "$uid+$login@users.noreply.github.com"
  info "git author: $login <$uid+$login@users.noreply.github.com>"
fi

# 5. .env -------------------------------------------------------------------
step "Local .env"
if [ -f .env ]; then
  info ".env exists, not changed"
else
  # tr </dev/urandom | head 는 pipefail에서 SIGPIPE로 실패하므로 쓰지 않는다.
  pw="$(openssl rand -hex 16)"
  sed -e "s/^POSTGRES_PASSWORD=\$/POSTGRES_PASSWORD=$pw/" \
      -e "s/^DB_PASSWORD=\$/DB_PASSWORD=$pw/" .env.example > .env
  chmod 600 .env
  info ".env created with a random local DB password"
fi

# 6. Docker + DB ------------------------------------------------------------
step "Docker engine"
docker_ok=0
if docker info >/dev/null 2>&1; then
  docker_ok=1
else
  open -a Docker || true
  info "waiting for Docker Desktop (accept its terms on first run)..."
  for _ in $(seq 1 60); do
    sleep 5
    if docker info >/dev/null 2>&1; then docker_ok=1; break; fi
  done
fi
if [ "$docker_ok" = "1" ]; then
  info "running"
  step "Local PostgreSQL"
  docker compose up -d
else
  warn "Docker engine is not running. Start Docker Desktop, then run this script again."
fi

# 7. Build ------------------------------------------------------------------
if [ "$docker_ok" = "1" ] && [ "${CI_SETUP_SKIP_BUILD:-0}" != "1" ]; then
  step "Build and test"
  ./gradlew build --no-daemon
fi

# 8. Next -------------------------------------------------------------------
step "Done"
info "Folder: $REPO_DIR"
info "Fill in DART_API_KEY and GEMINI_API_KEY in .env when you have them."
info "Run the app: ./gradlew bootRun  ->  http://localhost:8080/actuator/health"
