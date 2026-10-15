#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${HOME}/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
TOOLS_ZIP="${TMPDIR:-/tmp}/android-commandline-tools-${CMDLINE_TOOLS_VERSION}.zip"

mkdir -p "${SDK_ROOT}/cmdline-tools"
if [[ ! -x "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]]; then
    curl --fail --location --retry 3 "${CMDLINE_TOOLS_URL}" -o "${TOOLS_ZIP}"
    rm -rf "${SDK_ROOT}/cmdline-tools/latest" "${SDK_ROOT}/cmdline-tools/cmdline-tools"
    unzip -q "${TOOLS_ZIP}" -d "${SDK_ROOT}/cmdline-tools"
    mv "${SDK_ROOT}/cmdline-tools/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
fi

export ANDROID_HOME="${SDK_ROOT}"
export ANDROID_SDK_ROOT="${SDK_ROOT}"
export PATH="${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/platform-tools:${PATH}"

yes | sdkmanager --licenses >/dev/null
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

printf 'Android SDK ready at %s\n' "${SDK_ROOT}"