#!/bin/bash
# =============================================================================
# Native Tooling Utilities (patchelf / ELF Patching)
# =============================================================================
# Functions for Android library compatibility patching.
#
# CRITICAL: These functions implement the library flattening strategy required
# for Android compatibility. DO NOT modify the patching semantics.
#
# Strategy:
#   1. Rename versioned libs (libssl.so.3 → libssl.so)
#   2. Update SONAME in each library
#   3. Patch DT_NEEDED entries in all ELF binaries
#
# Usage:
#   source "$(dirname "${BASH_SOURCE[0]}")/native_tooling.sh"
#   flatten_libraries "$LIB_DIR" "$MAPPING_FILE"
#   patch_all_elfs "$RUNTIME_DIR" "$MAPPING_FILE"
# =============================================================================

# -----------------------------------------------------------------------------
# check_patchelf - Verify patchelf is available
# -----------------------------------------------------------------------------
check_patchelf() {
    if ! command -v patchelf &>/dev/null; then
        log_fatal "patchelf is required but not installed"
    fi
    log_detail "patchelf: $(command -v patchelf)"
}

# -----------------------------------------------------------------------------
# flatten_libraries - Rename versioned .so files to clean names
# Usage: flatten_libraries <lib_dir> <mapping_file>
#
# Example: libssl.so.3.0.1 → libssl.so
# Also updates SONAME in each library.
#
# Writes mappings to file: "old_name|new_name" per line
# -----------------------------------------------------------------------------
flatten_libraries() {
    local lib_dir="$1"
    local mapping_file="$2"
    
    log_step "Flattening Libraries"
    
    cd "$lib_dir" || log_fatal "Cannot enter lib directory"
    
    # Clear previous mapping
    : > "$mapping_file"
    
    # Remove symlinks first (we'll use real files)
    find . -maxdepth 1 -type l -name "lib*.so*" -delete
    
    # Process each ELF shared library
    local count=0
    while IFS= read -r -d '' file; do
        local basename
        basename=$(basename "$file")
        
        # Skip non-ELF files
        if ! readelf -h "$file" &>/dev/null; then
            continue
        fi
        
        # Compute clean name: strip version suffixes
        # libssl.so.3.0.1 → libssl.so
        # libz.so.1 → libz.so
        local clean_name
        clean_name=$(echo "$basename" | sed -E 's/(\.so)(\.[0-9]+)+$/\1/')
        
        if [[ "$basename" != "$clean_name" ]]; then
            log_detail "[Rename] $basename → $clean_name"
            mv "$basename" "$clean_name"
            echo "${basename}|${clean_name}" >> "$mapping_file"
            
            # Also map major version (e.g., libz.so.1 → libz.so)
            local major_name
            major_name=$(echo "$basename" | grep -oE '^lib.*\.so\.[0-9]+' | head -n1)
            if [[ -n "$major_name" && "$major_name" != "$basename" && "$major_name" != "$clean_name" ]]; then
                log_detail "[Map]    $major_name → $clean_name"
                echo "${major_name}|${clean_name}" >> "$mapping_file"
            fi
        else
            # Already clean, but still record for SONAME patching
            echo "${basename}|${basename}" >> "$mapping_file"
        fi
        
        # Update SONAME to clean name
        patchelf --set-soname "$clean_name" "$clean_name" 2>/dev/null || true
        ((++count))
    done < <(find . -maxdepth 1 -type f -name "lib*.so*" -print0)
    
    log_success "Processed $count libraries"
}

# -----------------------------------------------------------------------------
# build_patch_args - Generate patchelf --replace-needed arguments
# Usage: args=$(build_patch_args <mapping_file>)
# Returns: String of patchelf arguments
# -----------------------------------------------------------------------------
build_patch_args() {
    local mapping_file="$1"
    local args=""
    
    while IFS='|' read -r old_name clean_name; do
        if [[ "$old_name" != "$clean_name" ]]; then
            args="$args --replace-needed $old_name $clean_name"
        fi
    done < "$mapping_file"
    
    echo "$args"
}

# -----------------------------------------------------------------------------
# patch_elf_file - Apply library replacements to a single ELF file
# Usage: patch_elf_file <file> <patch_args>
# -----------------------------------------------------------------------------
patch_elf_file() {
    local target="$1"
    local patch_args="$2"
    
    # Apply replacements
    # shellcheck disable=SC2086
    patchelf $patch_args "$target" 2>/dev/null || {
        log_warn "Patch incomplete: $(basename "$target")"
    }
}

# -----------------------------------------------------------------------------
# patch_node_binary - Special handling for Node.js executable
# Usage: patch_node_binary <node_path> <patch_args>
# Sets interpreter to Android linker and applies library patches
# -----------------------------------------------------------------------------
patch_node_binary() {
    local node_bin="$1"
    local patch_args="$2"
    
    log_info "Patching Node.js binary..."
    
    [[ ! -f "$node_bin" ]] && log_fatal "Node binary not found: $node_bin"
    
    # Set interpreter to Android's linker64
    patchelf --set-interpreter /system/bin/linker64 "$node_bin" || {
        log_error "Failed to set interpreter"
        return 1
    }
    
    # Apply library replacements
    patch_elf_file "$node_bin" "$patch_args"
    
    log_success "Node binary patched"
}

# -----------------------------------------------------------------------------
# patch_native_modules - Patch all .node native modules
# Usage: patch_native_modules <runtime_dir> <patch_args>
# -----------------------------------------------------------------------------
patch_native_modules() {
    local runtime_dir="$1"
    local patch_args="$2"
    
    log_info "Patching native modules (.node)..."
    
    local count=0
    while IFS= read -r -d '' module; do
        log_detail "Patching: $(basename "$module")"
        patch_elf_file "$module" "$patch_args"
        ((++count))
    done < <(find "$runtime_dir" -name "*.node" -print0)
    
    log_success "Patched $count native modules"
}

# -----------------------------------------------------------------------------
# patch_all_elfs - Master function to patch all ELF files
# Usage: patch_all_elfs <runtime_dir> <mapping_file>
# Patches: libraries, node binary, native modules
# -----------------------------------------------------------------------------
patch_all_elfs() {
    local runtime_dir="$1"
    local mapping_file="$2"
    
    log_step "Patching ELF Binaries"
    
    check_patchelf
    
    local patch_args
    patch_args=$(build_patch_args "$mapping_file")
    
    if [[ -z "$patch_args" ]]; then
        log_warn "No library mappings to apply"
        return 0
    fi
    
    log_detail "Mappings: $(wc -l < "$mapping_file") entries"
    
    # 1. Patch shared libraries
    log_info "Patching shared libraries..."
    while IFS= read -r -d '' lib; do
        patch_elf_file "$lib" "$patch_args"
    done < <(find "$runtime_dir/lib" -maxdepth 1 -type f -name "*.so" -print0)
    
    # 2. Patch Node binary
    patch_node_binary "$runtime_dir/bin/node" "$patch_args"
    
    # 3. Patch native modules
    patch_native_modules "$runtime_dir" "$patch_args"
}
