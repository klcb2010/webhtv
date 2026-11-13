# MPV 阶段 B（东八区）

## 行为
- 播放控制栏 **共用一个截图按钮**
- **EXO / 非 MPV**：仅 Surface 截图（不再独立解码原画）
- **MPV**：优先 `screenshot-to-file video` 原画；失败回退 Surface
- 个性设置：
  - **播放器EXO截图**（关/PNG/WebP/WebP98）+ **EXO截图亮度**
  - **播放器MPV截图** + **MPV截图亮度**
- 按钮显示：当前内核对应截图模式不为「关」

## 依赖
阶段 A（可选手动选 MPV、默认 EXO、默认 option）
