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

以上仅是按名字初筛，不能证明 ABI 兼容；还需检查 `HLL_EXPORT_SIG`、重载参数及实际调用路径。
函数已导出也不代表所有行为已实现，更不能代替游戏流程验收。

## 已知加密/格式

- ain 加密：MT19937 变体（init 乘数 69069，逐字节 XOR 密钥流低字节），
  种子 `0x5D3E3`，解密后以 `VERS` 开头
- `.alm` 视频：MPEG-1 Program Stream（plmpeg 可解）
- `.ald`：AliceSoft 资源包（libsys4 支持）

## 各作适配记录

| 游戏 | 当前记录 | 验证边界 |
|---|---|---|
| 兰斯6 / 战国兰斯（中文版） | 已有适配记录，战国兰斯 SACT2 缺失调用已补齐 | 本次未重新跑完整游戏流程 |
| 兰斯8 / 兰斯9（中文版） | 已有资源、字体、渲染、音频等适配 | ASF/WMV 需要实际链接 FFmpeg 的 APK |
| 兰斯10（中文版） | VM、泛型数组/委托、PartsEngine 输入、存读档持续适配 | APEG OP/ED 已加入画面解码；实际设备播放、非空场景回顾和长时间游戏仍待验收 |

## 兰斯10本地回归

从仓库根目录执行：

```sh
node tools/test-rance10-all.mjs
```

依赖 Node.js，以及可输出 `wasm32-unknown-unknown` 的 Clang/wasm-ld。
默认编译器是仓库旁 `android-build/sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe`；
其它环境可设置 `RANCE10_TEST_CLANG` 为自己的 Clang 路径：

```sh
RANCE10_TEST_CLANG=/path/to/clang node tools/test-rance10-all.mjs
```

入口顺序执行 24 项核心回归，覆盖 VM 引用/构造调用、50 种游戏使用的 Array 签名、EX/资源读取、
存读档、消息/输入/布局、字符串处理、错误报告与界面资源等。其中重点覆盖：

- `test-rance10-atomic-save.mjs`：原子写入错误路径及写入中断时保留旧文件。
- `test-rance10-load-input.mjs`：读档后根据恢复的 VM 栈还原嵌套等待输入层数。
- `test-rance10-delegates.mjs`：失效委托项不误释放复用 heap slot 的新对象、闭包所有权与清理；生产 JSON 堆保存/恢复、旧档缓存复用和高位 32 位代数。
- `test-rance10-string.mjs`：通用空片段省略、闭合指令参数、嵌套条件、分隔符保留、空白及 GBK/SJIS 字符边界。已删除把额外空项作为正确行为的旧断言，不依赖游戏文件。

测试产物写入已忽略的 `build/rance10-tests/`。这些是宿主机 C/WASM 回归，不运行 Android 完整引擎；
`test-rance10-review-controls.py` 等独立 Python 脚本不包含在这 24 项中，部分还依赖本机游戏资源/路径。
不要将“24 项通过”描述成所有工具脚本、三 ABI 构建、实机断电或完整游戏流程均通过。
GitHub workflow 已将此入口作为原生构建前的门禁；本地仍可独立执行上面的命令。

## 补丁管理

- `patches/0001-xsystem4-android-cn.patch`：CI 应用到 `xsystem4` 的固定基线 `04333ad689d7e517a93d58141bb214c19ba16129`。
- `patches/libsys4/0001-libsys4-cn.patch`：单独应用到嵌套 libsys4 的 `20560d4d6d41a7196bd6b8409bcbb974f9de4af0`。
- 本地开发直接修改子模块，但外层 commit/push 不会携带这些修改。发布必须同步补丁，且包含新增源码。
- 单独 `git diff` 会遗漏未跟踪文件；建议用独立 `GIT_INDEX_FILE` 临时索引：从 `HEAD` 读入，加入源码，
  再用 `git diff --cached --binary HEAD` 导出。不要为生成补丁而改动用户原有暂存区。
- 引擎快照排除嵌套 libsys4 和下载的 `.ttf/.otf/.license`；libsys4 排除生成的 `src/gbk_table.h`、
  `src/gbk_rev_table.h`。字体来源清单、着色器源码及其许可证仍须纳入。
- Windows 导出需按仓库 `core.autocrlf` 规则规范化到 LF，避免把整棵源码的换行差异写进补丁。
- 在固定基线的干净目录中 `git apply --check` 后应用，或在独立索引中 `git apply --cached` 并比较源码树。
  不要在已经打过补丁的工作区重复应用。构建前还需生成 GBK 表并下载字体，见[主 README](../README.md#本地构建)。
- 根目录 `.gitattributes` 将 `*.patch` 固定为 LF；生成工具也须输出 LF，不要保留旧文件的 CRLF 换行。

## 长期目标

将 patches/ 迁移到 bailaiOWO/xsystem4 fork（子模块指向自己的仓库），
摆脱 CI 补丁方式，可直接跟随上游 rebase。
