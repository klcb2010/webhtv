# mod — 本地补丁（首页两个开关）

## 内容
- 设置页增加：**首页最近观看**、**默认加载点播**
- 电视端 `HomeActivity` 按开关控制首页行为

## 本地手动应用
在仓库根目录：
```bash
bash mod/apply.sh
```

## CI
`.github/workflows` 里会在 Gradle 打包前自动执行 `bash mod/apply.sh`。
