#!/bin/bash
# =============================================================================
# Termux Package Utilities
# =============================================================================
# Functions for fetching and extracting Termux packages.
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/termux_packages.sh"
#   init_termux_repo "$WORK_DIR" "aarch64"
#   download_package "nodejs-lts"
#   extract_package "nodejs-lts" "$DEST_DIR"
# =============================================================================

# Repository configuration
readonly TERMUX_REPO_BASE="https://packages.termux.dev/apt/termux-main"

# Module state
_TERMUX_WORK_DIR=""
_TERMUX_ARCH=""
_TERMUX_INDEX_LOADED=false

# -----------------------------------------------------------------------------
# init_termux_repo - Initialize package index
# Usage: init_termux_repo <work_dir> <arch>
# Args:
#   work_dir - Directory for downloads and temp files
#   arch     - Target architecture (aarch64, arm, x86_64)
# -----------------------------------------------------------------------------
init_termux_repo() {
    local work_dir="$1"
    local arch="${2:-aarch64}"
    
    _TERMUX_WORK_DIR="$work_dir"
    _TERMUX_ARCH="$arch"
    
    log_step "Fetching Termux Package Index"
    
    mkdir -p "$_TERMUX_WORK_DIR"
    cd "$_TERMUX_WORK_DIR" || log_fatal "Cannot enter work directory"
    
    # Download and decompress package index
    local index_url="$TERMUX_REPO_BASE/dists/stable/main/binary-$_TERMUX_ARCH/Packages.gz"
    wget -q "$index_url" -O Packages.gz || log_fatal "Failed to fetch package index"
    gunzip -f Packages.gz || log_fatal "Failed to decompress index"
    
    _TERMUX_INDEX_LOADED=true
    log_success "Package index loaded"
}

# -----------------------------------------------------------------------------
# resolve_package_url - Get download URL for a package
# Usage: url=$(resolve_package_url "nodejs-lts")
# Returns: Package filename relative to repo root
# -----------------------------------------------------------------------------
resolve_package_url() {
    local pkg_name="$1"
    
    [[ "$_TERMUX_INDEX_LOADED" != true ]] && log_fatal "Package index not loaded. Call init_termux_repo first."
    
    awk -v pkg="$pkg_name" '
        /^Package: / { if ($2 == pkg) { inside=1 } else { inside=0 } }
        inside && /^Filename: / { print $2; exit }
    ' "$_TERMUX_WORK_DIR/Packages"
}

# -----------------------------------------------------------------------------
# download_package - Download a package .deb file
# Usage: download_package "nodejs-lts"
# -----------------------------------------------------------------------------
download_package() {
    local pkg_name="$1"
    
    log_info "Resolving $pkg_name..."
    
    local filename
    filename=$(resolve_package_url "$pkg_name")
    
    if [[ -z "$filename" ]]; then
        log_error "Package not found: $pkg_name"
        return 1
    fi
    
    wget -q "$TERMUX_REPO_BASE/$filename" -O "$_TERMUX_WORK_DIR/${pkg_name}.deb" || {
        log_error "Download failed: $pkg_name"
        return 1
    }
    
    log_detail "Downloaded: $pkg_name"
}

# -----------------------------------------------------------------------------
# download_packages - Download multiple packages
# Usage: download_packages "nodejs-lts" "openssl" "zlib"
# -----------------------------------------------------------------------------
download_packages() {
    log_step "Downloading Termux Packages"
    
    local failed=0
    for pkg in "$@"; do
        download_package "$pkg" || ((++failed))
    done
    
    if ((failed > 0)); then
        log_error "$failed package(s) failed to download"
        return 1
    fi
    
    log_success "All packages downloaded"
}

# -----------------------------------------------------------------------------
# extract_package - Extract a .deb package to destination
# Usage: extract_package "nodejs-lts" "/dest/runtime"
# Extracts Termux layout to: dest/bin, dest/lib, dest/etc
# -----------------------------------------------------------------------------
extract_package() {
    local pkg_name="$1"
    local dest_dir="$2"
    
    local deb_file="$_TERMUX_WORK_DIR/${pkg_name}.deb"
    [[ ! -f "$deb_file" ]] && log_fatal "Package not downloaded: $pkg_name"
    
    local temp_dir
    temp_dir=$(mktemp -d)
    
    # Extract .deb structure
    (cd "$temp_dir" && ar x "$deb_file" data.tar.xz) || {
        rm -rf "$temp_dir"
        log_error "Failed to unpack $pkg_name"
        return 1
    }
    
    # Extract data tarball
    tar -xf "$temp_dir/data.tar.xz" -C "$temp_dir" || {
        rm -rf "$temp_dir"
        log_error "Failed to extract $pkg_name data"
        return 1
    }
    
    # Map Termux layout to our runtime structure
    # Termux: data/data/com.termux/files/usr/{bin,lib,etc}
    local termux_usr="$temp_dir/data/data/com.termux/files/usr"
    
    [[ -d "$termux_usr/bin" ]] && cp -r "$termux_usr/bin/"* "$dest_dir/bin/" 2>/dev/null || true
    [[ -d "$termux_usr/lib" ]] && cp -r "$termux_usr/lib/"* "$dest_dir/lib/" 2>/dev/null || true
    [[ -d "$termux_usr/etc" ]] && cp -r "$termux_usr/etc/"* "$dest_dir/etc/" 2>/dev/null || true
    
    rm -rf "$temp_dir"
    log_detail "Extracted: $pkg_name"
}

# -----------------------------------------------------------------------------
# extract_packages - Extract multiple packages
# Usage: extract_packages "/dest/runtime" "nodejs-lts" "openssl"
# -----------------------------------------------------------------------------
extract_packages() {
    local dest_dir="$1"
    shift
    
    log_step "Extracting Termux Binaries"
    
    # Ensure destination structure exists
    mkdir -p "$dest_dir/bin" "$dest_dir/lib" "$dest_dir/etc"
    
    for pkg in "$@"; do
        extract_package "$pkg" "$dest_dir"
    done
    
    log_success "All packages extracted"
}
