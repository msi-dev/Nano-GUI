#!/bin/bash
# Nano wrapper script for Termux.
# If com.nanoeditor.app is installed, we open the file with it using termux-open.
# Otherwise, we fall back to the original nano binary (nano.orig).

PACKAGE_NAME="com.nanoeditor.app"

# Check if the app is installed
if pm path "$PACKAGE_NAME" >/dev/null 2>&1; then
    if [ $# -gt 0 ]; then
        # Open the file(s) with our Android app via termux-open
        termux-open "$1"
    else
        # No file specified, launch our app main activity
        am start -n "$PACKAGE_NAME/com.example.MainActivity" >/dev/null 2>&1
    fi
else
    # Fallback to the original command-line nano
    if [ -f "$PREFIX/bin/nano.orig" ]; then
        exec "$PREFIX/bin/nano.orig" "$@"
    else
        echo "Error: com.nanoeditor.app is not installed, and original nano could not be found."
        exit 1
    fi
fi
