#!/bin/bash

# 检查 Java 环境
if ! command -v java &> /dev/null
then
    echo "错误: 未找到 Java 环境。请安装 JDK 17 或更高版本。"
    exit 1
fi

# 检查 Android SDK
if [ -z "$ANDROID_HOME" ]; then
    echo "警告: 未设置 ANDROID_HOME 环境变量。编译可能会失败。"
fi

# 确保 gradlew 存在且可执行
if [ ! -f "gradlew" ]; then
    echo "正在尝试初始化 Gradle Wrapper..."
    # 如果系统安装了 gradle，则使用系统 gradle 生成 wrapper
    if command -v gradle &> /dev/null; then
        gradle wrapper
    else
        echo "错误: 未找到 gradle 命令。请先安装 gradle 或手动下载 gradle wrapper 文件。"
        echo "建议：直接在 Android Studio 中打开此文件夹，它会自动配置 Gradle。"
        exit 1
    fi
fi

chmod +x gradlew

echo "开始编译调试版 APK..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo "------------------------------------------------"
    echo "编译成功！"
    echo "APK 位置: app/build/outputs/apk/debug/app-debug.apk"
    echo "------------------------------------------------"
else
    echo "编译失败，请检查错误输出。"
fi
