#!/bin/bash
# MiSmartPenX Setup Tool
# Designed and Developed by NowLoadY

# 定义安装目录
INSTALL_DIR="$HOME/android_dev"
mkdir -p "$INSTALL_DIR"

# 1. 下载并安装 JDK 17 (Temurin)
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.11_9.tar.gz"
echo "正在下载 JDK 17..."
wget -q --show-progress "$JDK_URL" -O "$INSTALL_DIR/jdk.tar.gz"
mkdir -p "$INSTALL_DIR/jdk"
tar -xzf "$INSTALL_DIR/jdk.tar.gz" -C "$INSTALL_DIR/jdk" --strip-components=1
rm "$INSTALL_DIR/jdk.tar.gz"

# 2. 下载并安装 Android SDK Command-line Tools
SDK_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
echo "正在下载 Android Command-line Tools..."
wget -q --show-progress "$SDK_URL" -O "$INSTALL_DIR/commandlinetools.zip"
mkdir -p "$INSTALL_DIR/sdk/cmdline-tools"
unzip -q "$INSTALL_DIR/commandlinetools.zip" -d "$INSTALL_DIR/sdk/cmdline-tools"
# Android SDK 要求目录结构必须是 cmdline-tools/latest/...
mv "$INSTALL_DIR/sdk/cmdline-tools/cmdline-tools" "$INSTALL_DIR/sdk/cmdline-tools/latest"
rm "$INSTALL_DIR/commandlinetools.zip"

# 3. 设置环境变量并写入 .bashrc
export JAVA_HOME="$INSTALL_DIR/jdk"
export ANDROID_HOME="$INSTALL_DIR/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 写入配置文件
BASHRC="$HOME/.bashrc"
if ! grep -q "JAVA_HOME=\"$INSTALL_DIR/jdk\"" "$BASHRC"; then
    echo "" >> "$BASHRC"
    echo "# Android Dev Environment" >> "$BASHRC"
    echo "export JAVA_HOME=\"$INSTALL_DIR/jdk\"" >> "$BASHRC"
    echo "export ANDROID_HOME=\"$INSTALL_DIR/sdk\"" >> "$BASHRC"
    echo "export PATH=\"\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\"" >> "$BASHRC"
fi

# 4. 使用 sdkmanager 安装必要组件
echo "正在接受 SDK 协议并安装必要组件 (Platform 33, Build-tools)..."
# 自动接受协议
yes | sdkmanager --licenses > /dev/null
sdkmanager "platform-tools" "platforms;android-33" "build-tools;33.0.2"

echo "------------------------------------------------"
echo "安装完成！"
echo "JDK 路径: $JAVA_HOME"
echo "SDK 路径: $ANDROID_HOME"
echo "请执行 'source ~/.bashrc' 或重新打开终端以生效。"
echo "------------------------------------------------"
