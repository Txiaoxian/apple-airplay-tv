#!/bin/bash
set -e

SDK_DIR="$HOME/Library/Android/sdk"
CMDLINE_TOOLS_DIR="$SDK_DIR/cmdline-tools/latest"
GRADLE_DIR="$SDK_DIR/gradle-8.5"
PROJECT_DIR="/Users/wohenhaoqi/.gemini/antigravity/scratch/airplay-tv"

echo "=== 1. 检查并准备 Android SDK 目录 ==="
mkdir -p "$SDK_DIR/cmdline-tools"

if [ ! -f "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
    echo "正在从 Google 官方下载 Android Command Line Tools..."
    TMP_ZIP="/tmp/cmdline-tools.zip"
    curl -L "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip" -o "$TMP_ZIP"
    
    rm -rf /tmp/cmdline-tools-extracted "$CMDLINE_TOOLS_DIR"
    mkdir -p /tmp/cmdline-tools-extracted
    unzip -q "$TMP_ZIP" -d /tmp/cmdline-tools-extracted
    mv /tmp/cmdline-tools-extracted/cmdline-tools "$CMDLINE_TOOLS_DIR"
    rm -f "$TMP_ZIP"
    rm -rf /tmp/cmdline-tools-extracted
    echo "Android Command Line Tools 安装就绪！"
else
    echo "Android Command Line Tools 已存在，跳过下载。"
fi

echo "=== 2. 检查 Android 编译环境 ==="
export ANDROID_HOME="$SDK_DIR"
export PATH="$CMDLINE_TOOLS_DIR/bin:$SDK_DIR/platform-tools:$PATH"

if [ ! -d "$SDK_DIR/platforms/android-34" ]; then
    echo "正在安装 Android 34 核心编译库..."
    sdkmanager "platforms;android-34" "build-tools;34.0.0"
else
    echo "Android 34 编译库已就绪！"
fi

echo "=== 3. 检查并准备 Gradle 构建工具 ==="
if [ ! -f "$GRADLE_DIR/bin/gradle" ]; then
    echo "正在下载 Gradle 8.5 运行时..."
    TMP_GRADLE_ZIP="/tmp/gradle-8.5-bin.zip"
    curl -L "https://services.gradle.org/distributions/gradle-8.5-bin.zip" -o "$TMP_GRADLE_ZIP"
    
    mkdir -p "$SDK_DIR"
    unzip -q "$TMP_GRADLE_ZIP" -d "$SDK_DIR"
    rm -f "$TMP_GRADLE_ZIP"
    echo "Gradle 8.5 安装就绪！"
else
    echo "Gradle 8.5 已就绪！"
fi

export PATH="$GRADLE_DIR/bin:$PATH"

echo "=== 4. 开始编译 PureAir-TV APK ==="
cd "$PROJECT_DIR"

# 确保 local.properties 指向正确的 SDK 路径
echo "sdk.dir=$SDK_DIR" > local.properties

echo "正在执行 gradle assembleDebug..."
gradle assembleDebug --no-daemon --stacktrace

APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    TARGET_APK="$PROJECT_DIR/Apple投屏.apk"
    TARGET_APK_V="$PROJECT_DIR/Apple投屏-v1.2.4.apk"
    cp "$APK_PATH" "$TARGET_APK"
    cp "$APK_PATH" "$TARGET_APK_V"
    echo "=========================================================="
    echo "🎉 Apple投屏 v1.2.4 APK 编译成功！"
    echo "最终安装包已生成并复制到根目录："
    echo "$TARGET_APK_V"
    echo "文件大小: $(du -h "$TARGET_APK_V" | cut -f1)"
    echo "=========================================================="
else
    echo "❌ 编译完成但未在预期路径找到 APK"
    exit 1
fi
