#!/bin/bash
# macOS 图标转换脚本
# 在 macOS 上将 PNG 转换为 ICNS

# 创建临时目录存放不同尺寸的图标
mkdir -p icon.iconset

# 生成不同尺寸的图标
sips -z 16 16     icon.png --out icon.iconset/icon_16x16.png
sips -z 32 32     icon.png --out icon.iconset/icon_16x16@2x.png
sips -z 32 32     icon.png --out icon.iconset/icon_32x32.png
sips -z 64 64     icon.png --out icon.iconset/icon_32x32@2x.png
sips -z 128 128   icon.png --out icon.iconset/icon_128x128.png
sips -z 256 256   icon.png --out icon.iconset/icon_128x128@2x.png
sips -z 256 256   icon.png --out icon.iconset/icon_256x256.png
sips -z 512 512   icon.png --out icon.iconset/icon_256x256@2x.png
sips -z 512 512   icon.png --out icon.iconset/icon_512x512.png
sips -z 1024 1024 icon.png --out icon.iconset/icon_512x512@2x.png

# 使用 iconutil 生成 icns 文件
iconutil -c icns icon.iconset -o icon.icns

# 清理临时文件
rm -rf icon.iconset

echo "icon.icns created successfully!"