# xsystem4-android 中文适配分析工具

## 背景

kichikuou 上游 xsystem4 官方兼容表只保证日版/英版。Kagura 等官方中文版
会重新编译游戏脚本（ain），调用的 HLL 函数集合与日版不同。
xsystem4 中未实现的 HLL 函数（`HLL_TODO_EXPORT`，函数指针为 NULL）
一旦被调用会触发 `VM_ERROR` 直接终止 VM —— 在 Android 上表现为**黑屏**。

## 分析流程（适用于任何 System4 游戏的中文版）

### 1. 解析 ain，导出 HLL 依赖

`AinDump.cs` 是一个 ain v5~v7 解析器（PowerShell `Add-Type` 直接编译运行，
无需安装任何工具链）：

```powershell
Add-Type -Path tools\AinDump.cs
# 导出完整结构：版本、HLL0 库/函数清单、首批消息等
[AinDump]::Run("游戏目录\xxx.ain", "tools\ dump.txt")
```

### 2. 扫描字节码，找出实际被调用的 HLL 函数

HLL0 声明的函数不一定都被调用。`ScanCalls` 扫描 CODE 段中的
`CALLHLL`（opcode 0x5A）指令，统计每个函数的实际调用次数：

```powershell
[AinDump]::ScanCalls("游戏目录\xxx.ain", "tools\calls.txt")
```

### 3. 与 xsystem4 导出表对比

```powershell
# 提取模拟器所有 HLL_EXPORT
$allExports = @{}
Get-ChildItem xsystem4\src\hll\*.c | ForEach-Object {
  [regex]::Matches((Get-Content $_.FullName -Raw), 'HLL_EXPORT\(\s*(\w+)\s*,') |
    ForEach-Object { $allExports[$_.Groups[1].Value] = $true }
}
# 对比 calls.txt 中被调用的函数是否都有实现
Get-Content tools\calls.txt | ForEach-Object {
  if ($_ -match '^\s*\d+\s+(\w+)\s') {
    if (-not $allExports.ContainsKey($Matches[1])) { "缺失: $($Matches[1])" }
  }
}
```

任何出现在 `calls.txt` 但不在 `$allExports` 中的函数 = 必崩点。

## 已知加密/格式

- ain 加密：MT19937 变体（init 乘数 69069，逐字节 XOR 密钥流低字节），
  种子 `0x5D3E3`，解密后以 `VERS` 开头
- `.alm` 视频：MPEG-1 Program Stream（plmpeg 可解）
- `.ald`：AliceSoft 资源包（libsys4 支持）

## 各作适配记录

| 游戏 | ain 版本 | 缺失函数 | 状态 |
|---|---|---|---|
| 兰斯6 (CN) | ? | 无 | ✅ 正常 |
| 战国兰斯 (Kagura CN) | 5 | SACT2 ×7 | ✅ 已修复 (patch 0001) |
| 兰斯8/クエスト (CN) | ? | 待分析 | ⏳ 需要游戏文件 |
| 兰斯IX (CN) | ? | 待分析 | ⏳ 需要游戏文件 |

## 补丁管理

- `patches/*.patch`：CI 构建时自动应用到 xsystem4 子模块
  （见 `.github/workflows/build.yml` 的 "Apply engine patches" 步骤）
- 本地开发：直接在 `xsystem4/` 子模块里改，`git diff > patches/xxxx.patch` 重新生成
- 生成补丁后务必验证：
  `git apply --check`（在干净的 04333ad 树上，注意文件必须是 LF 行尾）

## 长期目标

将 patches/ 迁移到 bailaiOWO/xsystem4 fork（子模块指向自己的仓库），
摆脱 CI 补丁方式，可直接跟随上游 rebase。
