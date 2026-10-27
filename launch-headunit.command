#!/bin/zsh
# Double-click to start AARE's desktop head unit for the phone on USB.
export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/Library/Android/sdk/platform-tools:$PATH"
cd "$(dirname "$0")"
./headunit.sh
