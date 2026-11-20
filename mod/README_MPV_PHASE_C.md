# MPV 阶段 C

## 内容
1. 字幕：sub-ass-override=force、embeddedfonts、sub-font、sub-fonts-dir，并尝试对接 MpvSubtitleStylePolicy
2. 暂停+横竖屏撕裂：
   - surface 尺寸变化时设置 android-surface-size、force-render
   - 暂停态额外刷新 force-window
   - Activity onConfigurationChanged 时 requestLayout
   - gpu-api：vulkan → opengl（Android 旋转更稳）

## 依赖
阶段 A + B 包内容保留。
