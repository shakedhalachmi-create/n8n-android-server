#!/bin/bash
# =============================================================================
# Logging Utilities for n8n Android Build Scripts
# =============================================================================
# Provides consistent, colored logging with step tracking.
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/logging.sh"
#   log_step "Downloading packages"
#   log_info "Processing file..."
#   log_warn "Optional dependency missing"
#   log_error "Build failed"
# =============================================================================

# Colors (only if terminal supports it)
if [[ -t 1 ]]; then
    readonly _RED='\033[0;31m'
    readonly _GREEN='\033[0;32m'
    readonly _YELLOW='\033[0;33m'
    readonly _BLUE='\033[0;34m'
    readonly _CYAN='\033[0;36m'
    readonly _BOLD='\033[1m'
    readonly _RESET='\033[0m'
else
    readonly _RED='' _GREEN='' _YELLOW='' _BLUE='' _CYAN='' _BOLD='' _RESET=''
fi

# Step counter for progress tracking
_STEP_COUNT=0

# -----------------------------------------------------------------------------
# log_step - Major build phase marker
# Usage: log_step "Phase Name"
# -----------------------------------------------------------------------------
log_step() {
    ((++_STEP_COUNT))
    echo -e "${_BOLD}${_BLUE}>>> [Step $_STEP_COUNT] $1${_RESET}"
}

# -----------------------------------------------------------------------------
# log_info - Informational message
# Usage: log_info "Processing file..."
# -----------------------------------------------------------------------------
log_info() {
    echo -e "${_CYAN}    [INFO]${_RESET} $1"
}

# -----------------------------------------------------------------------------
# log_success - Success marker
# Usage: log_success "Build complete"
# -----------------------------------------------------------------------------
log_success() {
    echo -e "${_GREEN}    [✓]${_RESET} $1"
}

# -----------------------------------------------------------------------------
# log_warn - Warning (non-fatal)
# Usage: log_warn "Optional dependency missing"
# -----------------------------------------------------------------------------
log_warn() {
    echo -e "${_YELLOW}    [WARN]${_RESET} $1" >&2
}

# -----------------------------------------------------------------------------
# log_error - Error message
# Usage: log_error "Build failed"
# -----------------------------------------------------------------------------
log_error() {
    echo -e "${_RED}    [ERROR]${_RESET} $1" >&2
}

# -----------------------------------------------------------------------------
# log_fatal - Fatal error, exits script
# Usage: log_fatal "Cannot continue"
# -----------------------------------------------------------------------------
log_fatal() {
    echo -e "${_BOLD}${_RED}    [FATAL]${_RESET} $1" >&2
    exit 1
}

# -----------------------------------------------------------------------------
# log_detail - Indented detail line
# Usage: log_detail "libssl.so.3 -> libssl.so"
# -----------------------------------------------------------------------------
log_detail() {
    echo "      $1"
}

# -----------------------------------------------------------------------------
# log_header - Script header banner
# Usage: log_header "n8n Runtime Builder"
# -----------------------------------------------------------------------------
log_header() {
    echo -e "${_BOLD}"
    echo "============================================================================="
    echo " $1"
    echo "============================================================================="
    echo -e "${_RESET}"
}

# -----------------------------------------------------------------------------
# log_footer - Script completion banner
# Usage: log_footer
# -----------------------------------------------------------------------------
log_footer() {
    echo -e "${_BOLD}"
    echo "============================================================================="
    echo " Build Complete ($_STEP_COUNT steps)"
    echo "============================================================================="
    echo -e "${_RESET}"
}
