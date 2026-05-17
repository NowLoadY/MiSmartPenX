#!/usr/bin/env bash
# MiSmartPenX Setup Tool
# Designed and Developed by NowLoadY

set -euo pipefail

INSTALL_DIR="${ANDROID_DEV_HOME:-$HOME/android_dev}"
JAVA_HOME_DIR="$INSTALL_DIR/jdk"
ANDROID_HOME_DIR="$INSTALL_DIR/sdk"
CMDLINE_TOOLS_DIR="$ANDROID_HOME_DIR/cmdline-tools/latest"
TMP_DIR="$INSTALL_DIR/tmp"

JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.11_9.tar.gz"
SDK_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

SDK_PACKAGES=(
    "platform-tools"
    "platforms;android-34"
    "build-tools;34.0.0"
)

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "错误: 未找到 $1，请先安装后重试。"
        exit 1
    fi
}

download() {
    local url="$1"
    local output="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -L --fail --progress-bar "$url" -o "$output"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress "$url" -O "$output"
    else
        echo "错误: 未找到 curl 或 wget，请先安装其中之一。"
        exit 1
    fi
}

install_jdk() {
    if [ -x "$JAVA_HOME_DIR/bin/java" ]; then
        echo "JDK 已存在: $JAVA_HOME_DIR"
        return
    fi

    echo "正在安装 JDK 17..."
    rm -rf "$JAVA_HOME_DIR"
    mkdir -p "$JAVA_HOME_DIR" "$TMP_DIR"
    download "$JDK_URL" "$TMP_DIR/jdk.tar.gz"
    tar -xzf "$TMP_DIR/jdk.tar.gz" -C "$JAVA_HOME_DIR" --strip-components=1
    rm -f "$TMP_DIR/jdk.tar.gz"
}

install_cmdline_tools() {
    if [ -x "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
        echo "Android Command-line Tools 已存在: $CMDLINE_TOOLS_DIR"
        return
    fi

    echo "正在安装 Android Command-line Tools..."
    rm -rf "$ANDROID_HOME_DIR/cmdline-tools"
    mkdir -p "$ANDROID_HOME_DIR/cmdline-tools" "$TMP_DIR"
    download "$SDK_URL" "$TMP_DIR/commandlinetools.zip"
    unzip -q "$TMP_DIR/commandlinetools.zip" -d "$ANDROID_HOME_DIR/cmdline-tools"
    mv "$ANDROID_HOME_DIR/cmdline-tools/cmdline-tools" "$CMDLINE_TOOLS_DIR"
    rm -f "$TMP_DIR/commandlinetools.zip"
}

update_shell_profile() {
    local bashrc="$HOME/.bashrc"
    local start_marker="# >>> MiSmartPenX Android Dev Environment >>>"
    local end_marker="# <<< MiSmartPenX Android Dev Environment <<<"
    local block

    block=$(cat <<EOF
$start_marker
export JAVA_HOME="$JAVA_HOME_DIR"
export ANDROID_HOME="$ANDROID_HOME_DIR"
export ANDROID_SDK_ROOT="$ANDROID_HOME_DIR"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"
$end_marker
EOF
)

    touch "$bashrc"
    if grep -q "$start_marker" "$bashrc"; then
        sed -i "/$start_marker/,/$end_marker/c\\$block" "$bashrc"
    else
        {
            echo ""
            echo "$block"
        } >> "$bashrc"
    fi
}

install_sdk_packages() {
    export JAVA_HOME="$JAVA_HOME_DIR"
    export ANDROID_HOME="$ANDROID_HOME_DIR"
    export ANDROID_SDK_ROOT="$ANDROID_HOME_DIR"
    export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

    echo "正在接受 Android SDK 协议..."
    yes | sdkmanager --licenses >/dev/null

    echo "正在安装 Android SDK 组件: ${SDK_PACKAGES[*]}"
    sdkmanager "${SDK_PACKAGES[@]}"
}

main() {
    require_command tar
    require_command unzip
    mkdir -p "$INSTALL_DIR" "$TMP_DIR"

    install_jdk
    install_cmdline_tools
    update_shell_profile
    install_sdk_packages

    echo "------------------------------------------------"
    echo "安装完成。"
    echo "JDK 路径: $JAVA_HOME_DIR"
    echo "SDK 路径: $ANDROID_HOME_DIR"
    echo "已安装 SDK: Android 34 / Build-tools 34.0.0"
    echo "请执行 'source ~/.bashrc' 或重新打开终端以生效。"
    echo "------------------------------------------------"
}

main "$@"
