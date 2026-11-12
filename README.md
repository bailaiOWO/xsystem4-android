# xsystem4-android（中文汉化适配分支）

这是 [kichikuou/xsystem4-android](https://github.com/kichikuou/xsystem4-android) 的分支，
目标是把 [xsystem4](https://github.com/nunuhara/xsystem4)（AliceSoft System4 引擎的开源重实现）
做成能**正常游玩中文汉化版**兰斯系列的 Android 版本。

上游原版能跑日文原版游戏，但中文汉化版有一整套"编码/资源/字体"的问题，本分支专门解决这些。

## 支持环境

- APK 声明的最低系统：Android 5.0（API 21）；**当前 CI 原生库按 API 28 编译**，不能据此保证 Android 5–8 可运行，发布验收先以 Android 9+ 为基线。
- CPU：`armeabi-v7a` / `arm64-v8a` / `x86_64`。各架构均需实际构建验证。

## 当前适配进度

| 游戏 | 状态 | 说明 |
| --- | --- | --- |
| 兰斯7 战国兰斯（汉化版） | 可玩 | 中文文本、字体、存档、触控均已适配 |
| 兰斯8 Rance Quest（汉化版） | 可玩 | 需本分支的资源包名识别 + TGA 解码支持 |
| 兰斯6（汉化版） | 可玩 | |
| 兰斯9 赫尔曼革命（汉化版） | 可玩（此前适配记录） | 已接入 FFmpeg 动画后端；ASF/WMV 能否播放取决于 APK 是否实际链接 FFmpeg |
| 兰斯10 决战（汉化版） | 适配中，非稳定支持 | 持续修复 VM、泛型数组/委托、PartsEngine 界面与输入、存读档；本地回归不等同于 Android 完整游玩验证 |
| 其它 System4 游戏 | 见上游 | 日文原版按上游兼容性表；中文版可提 issue |

兰斯6/7/8/9 的“可玩”为此前适配记录，不代表本次重新验证了完整流程或通关。
兰斯10 的当前限制见[已知问题](#已知问题)，不要把宿主机测试通过当成真机兼容性结论。

### 最新修复（2026-11-12）

- 合入 [PR #1](https://github.com/bailaiOWO/xsystem4-android/pull/1)，保留其委托、存档、Android 客户端和构建链改动。
- **通用 `String.Split` 语义修复**：忽略首尾及连续分隔符产生的空片段；请求保留分隔符时，仍逐个返回分隔字符。
  修复闭合参数被错误拆成三项、导致敌方切换、支援状态去除、DOWN、战斗结果等动作不执行的问题；没有 Boss 或剧情名称特判。
- **委托快照存读档**：保存并恢复原委托的槽号和代数；旧档缺少字段时明确初始化，避免继承缓存页残留。
  同时保留完整无符号 32 位堆代数，防止长时间运行后的高位序号在读档时被截断。
- 验证：24 项核心回归通过；核对游戏的全部 42 个 `String.Split` 调用点，并在平板独立进程中调用新原生库，
  逐项验证 5,209 个游戏语料、存档和边界用例的 token 数及原始字节，其中包含平板已有的 52 份存档注释。
  这不是所有战斗或完整游戏流程验收；已触发的剧情结果不会自动撤销，更新后应读取异常事件发生前的存档。

## 中文汉化版做了什么

中文汉化版和日文原版最大的区别在于**一套游戏里混着三种编码**，而上游代码默认"一种编码打天下"。
本分支把编码语义拆成三类分别处理：

| 用途 | 编码规则 | 原因 |
| --- | --- | --- |
| **显示文本** | 自动判定：整串以"假名乱码字符"占比判断是 SJIS 还是 GBK；GBK 串里再按 U+FF01–FF5E 全角区优先走 SJIS | 汉化版文本是 GBK，但引擎运行时会把全角数字/符号按 SJIS 输出 |
| **结构化资源**（MonsterInfo.txt、.s3de、.pms 等） | 真 SJIS | 原版资源是日文，解析器按日文关键字匹配；用 GBK 读会把 `モンスター` 读成 `儌儞僗僞乕` 导致解析失败 |
| **文件路径/包名** | 纯 GBK + **找不到时换一种解读再扫目录** | 资源是在中文 Windows 上用 GBK 代码页解压的，磁盘上的名字是"原始 SJIS 字节按 GBK 读"的结果；而同一目录里**可能混有保留正确假名的文件**（实测兰斯8 就是如此），所以找不到时必须两种解读都试 |

具体实现要点：

- **资源包按磁盘读法匹配**：`asset_manager` 用两套基名分别匹配——`.ald/.afa/.bgi/.wai/.ex` 用 GBK 读法，
  `.fnl` 位图字体则保持原样（见下条）。
- **字体**：内置 [CEFFontsCJK](https://github.com/Partyb0ssishere/cef-fonts-cjk)（GBK 全覆盖 + 全角区完整）。
  游戏自带的 `.fnl` 位图字体只有日文字形，中文会画成空白，因此中文化版本优先使用 TTF。
- **中文汉化版改了游戏名**：兰斯8 汉化版把 `GameName` 从 `ランス・クエスト` 改成 `兰斯8`，
  而上游靠这个名字字符串识别游戏（`game_rance8`），识别失败会导致 CG 全部设不上（黑屏）。
  本分支增加**按引擎 API 签名识别**（只有兰斯8 这一代用"CG 名字=字符串"标识），
  与上游给兰斯7 的处理方式（按库特征而非游戏名）保持一致。
- **TGA 图像格式**：兰斯8 的 CG 包里混有 208 个 TGA 图片（UI 元素），上游不支持该格式，
  本分支新增解码器（未压缩/RLE × 真彩色/灰度，8/16/24/32bpp，BGRA→RGBA，bottom-up 行翻转）。
- **存档路径与拷贝**：修复汉化版大量"文件拷贝失败"（文件名两种编码混装导致）。

## Android 端功能

- **启动器**
  - 游戏名直接取**文件夹名**（想显示什么名字就把文件夹改成什么名字，中文随意）
  - 图标**从游戏的 exe 里提取**（含 8bpp 调色板图标），无需额外放 `.ico`
  - `System40.ini`/`AliceStart.ini` 按 GBK 优先解码（汉化版 ini 是 GBK，注释可能是 SJIS）
- **触控**
  - 两种模式（返回键呼出菜单切换，设置会保存）：
    - **触摸板模式**：相对移动，带虚拟光标
    - **触控模式**：手指位置即屏幕位置（不显示光标）
  - 通用手势：

    | 操作 | 手势 |
    | --- | --- |
    | 左键 | 单指点一下 |
    | 右键 | 按住一指 + 另一指点一下（以第一指位置为准） |
    | 拖动 | 按住并移动 |
    | 滚轮 | 双指上下滑 |

- **游戏设置菜单**：返回键呼出；包含输入方式、画面增强、游戏速度、数值搜索修改器、退出游戏。
- **画面增强**（实验性）：`关闭 / Anime4K 2x (M) / Anime4K 4x (M+S) / CuNNy2 2x（fast-NVL）`。
  默认 Anime4K 4x；画面异常、发热或掉帧时请关闭。CuNNy2 的离线编译验证不代表真机画质/性能已验收。
- **游戏速度**：`0.5x / 1x / 2x / 3x / 4x / 6x`。建议先在原速下确认游戏行为。
- **修改器**：已有数值搜索/修改界面；使用前备份存档，不保证所有游戏的数据布局都适用。
- **诊断日志**：为便于继续适配其它作品，引擎会输出少量提示日志（`HLLUSE:` 游戏用到的引擎 API 清单、
  `asset:` 资源包匹配详情、`CG:` 图像加载详情）。用 `adb logcat -s libsys4:V` 查看。

## 动画支持

AliceSoft 后期作品（兰斯8 Rance Quest、兰斯9 赫尔曼革命等）的动画是 **ASF 容器**，
里面装的是 **WMV1 视频 + WMA2 音频**；早期作品则是 MPEG-PS。

项目提供 FFmpeg 静态库的交叉编译和自动探测（`libavformat/libavcodec/libavutil/libswscale`）。
**只有实际链接 FFmpeg 的 APK 才支持 ASF/WMV**；标准构建脚本现在会在 FFmpeg 编译或必要解码符号校验失败时终止。
可用 `SKIP_FFMPEG=1` 显式跳过其编译；单独配置 CMake 时仍可能选择 pl_mpeg，须核对实际后端，不能仅凭 APK 构建成功判断动画支持。

- 引擎侧：`xsystem4/src/movie_ffmpeg.c` 作为动画后端（`USE_FFMPEG=ON` 且依赖存在时），pl_mpeg 作为
  没有 ffmpeg 时的回退（那种情况只能放 MPEG-PS）
- ffmpeg 由 `tools/build_ffmpeg_android.sh` 交叉编译，只启用播放游戏动画所需的最小集合
  （常用容器 + 常见视频/音频解码器 + swscale），以控制包体积
- 想要自己构建：先跑一次该脚本，或让 CI 代劳（workflow 里有对应步骤）

### 兰斯10 OP / ED（APEG）

已移植 [TomorrowX6/re-xsystem4 的 APEG 解码实现](https://github.com/TomorrowX6/re-xsystem4/blob/683a119d0ab2b23e0440c6f5e84e2141653e5503/xsystem4/src/movie_plmpeg.c)，
支持 `IPIC/PPIC/BPIC` 画面、关键帧定位及末帧输出，沿用本分支已有的 `SOND` 音轨、时钟和游戏脚本跳过流程。
画面经 YUV 着色器写入电影部件纹理，再由 PartsEngine 合成；无需预先转码，也不会替换兰斯8/9的 FFmpeg 后端。
来源、固定提交和 GPL-2.0-or-later 声明保留在 `apeg_decoder.c`、`apeg_decoder.h`、`apeg_vlc.h` 中。

本机已用真实 `OP1A/OP1B/OP1C/ED2B.apeg` 完成全部 22,678 帧解码顺序、结束及前后定位一致性检查，
并用项目着色器完成宿主机 GPU 抽帧呈现。**这些不等于 Android 设备上的音画同步、跳过和前后台切换验收**；
本次字符串与快照修复未重新执行 OP/ED 的设备播放验收。

## 构建

只想安装使用的话可以跳过本节。

### 依赖

- 建议 Linux；CI 使用 Ubuntu。Windows 本地回归可用，原生交叉编译还需要离线依赖准备。
- JDK 17+（CI 显式使用 JDK 21）、Android SDK（含 Platform 34）、Android NDK r23+（CI 使用 runner 提供的 NDK）
- CMake 3.21+、Ninja、flex、bison，以及 FFmpeg 脚本所需的编译工具

### 推荐：GitHub Actions 构建

向主仓库推送或手动运行 [Build workflow](.github/workflows/build.yml)。它会递归检出子模块、应用补丁、
生成 GBK 表、下载并校验字体，执行核心离线回归门禁，再编译三个 ABI 和 Debug APK。成功后下载 `xsystem4-chinese-debug-apk` artifact。
这不是自动发布到 Releases，也不是正式签名的发行构建。

### 本地构建

**干净 clone 不能直接运行原生构建脚本**：必须先应用两层补丁，并准备生成表与字体。
已应用补丁的开发工作区不要重复应用。

```sh
git clone --recursive https://github.com/bailaiOWO/xsystem4-android.git
cd xsystem4-android
for p in patches/*.patch; do
    git -C xsystem4 apply "../$p"
done
for p in patches/libsys4/*.patch; do
    git -C xsystem4/subprojects/libsys4 apply "../../../$p"
done
```

接着按 [workflow 的 Apply GBK Patch 步骤](.github/workflows/build.yml)生成 `gbk_table.h`、`gbk_rev_table.h`，
下载 CEFFontsCJK 与 Noto 后备字体及许可证；本地执行时将 `$GITHUB_WORKSPACE` 设为仓库绝对路径，
并跳过已经执行过的补丁应用循环。这些准备步骤不包含在 `build-shared-libs.sh` 内。

```sh
export ANDROID_SDK_ROOT=<你的 Android SDK>
export ANDROID_NDK_HOME=<你的 Android NDK>
export ANDROID_API_LEVEL=28    # 与当前 CI 一致；脚本单独调用默认 21
./build-shared-libs.sh
cd project && ./gradlew assembleDebug
```

输出：`project/app/build/outputs/apk/debug/app-debug.apk`。构建日志须出现 `Movie backend: ffmpeg (...)`
才能确认启用了 ASF/WMV 后端；若出现 `Movie backend: pl_mpeg`，仅支持 MPEG-PS。

### 关于补丁

`xsystem4` 与嵌套的 `libsys4` 都指向上游仓库。引擎工作区修改不会随主仓库的 commit/push 自动上传，
必须同步进 `patches/`；不要把子模块指针改到只存在于本机的提交。

当前基线：`xsystem4` 为 `04333ad689d7e517a93d58141bb214c19ba16129`，
`libsys4` 为 `20560d4d6d41a7196bd6b8409bcbb974f9de4af0`。

```
patches/
  0001-xsystem4-android-cn.patch   # 引擎的全部改动（中文版适配、兰斯8/9/10 支持、Anime4K、ffmpeg 动画等）
  libsys4/
    0001-libsys4-cn.patch          # 编码语义（GBK/SJIS）、容错文件名解析、TGA 格式等
```

补丁按子模块工作区相对上游提交整体生成。CI 在应用补丁之后还会：

- 生成 GBK 码表 `libsys4/src/gbk_table.h`、`gbk_rev_table.h`（不放进补丁）；
- 下载 CEFFontsCJK 字体，以及 `xsystem4/fonts/NotoFallback.sources.json` 列出的 Noto 字体和许可证。
  Noto 下载会计算 SHA-256，但当前 workflow 在不匹配时只警告，不会中止；不要把它当作强制完整性检查。

动画后端需要的 ffmpeg 静态库由 `tools/build_ffmpeg_android.sh` 交叉编译到 `<仓库>/ffmpeg/<abi>/`，
引擎的 CMake 会自动探测（探测不到就回退 pl_mpeg，不会导致构建失败）。
`build-shared-libs.sh` 会在编译原生库之前先调用它。

离线构建：给 CMake 传 `-DXSYS4_DEPS_DIR=<目录>`，依赖库就从 `<目录>/src/<库名>-<版本>` 读取源码、
`<目录>/libsndfile` 取 libsndfile，不再联网下载（libffi 需预先编译进 staging 目录）。

### 上传前核对

1. 将引擎修改（包括新增 `.c/.h/.inc` 和着色器）同步到引擎补丁；libsys4 单独生成补丁。
   不要把下载的字体二进制、生成的 GBK 表、构建产物或游戏资源放入补丁。
2. 在固定上游基线的干净目录/临时索引中应用两份补丁，确认能还原准备发布的源码。
   仅提交父仓库中的 `xsystem4` 记录无法保存子模块未提交内容。
3. 提交 README、补丁、修改过的测试与新增测试文件。不要上传本机路径配置、签名密钥、令牌、游戏或存档。
4. 运行[本地回归](tools/README.md#兰斯10本地回归)，再检查对应提交的 CI 与 APK；本地通过不能替代三架构构建。
5. 安装新 APK，检查启动、动画、触控/拖动/滚轮、存读档和关键游戏场景；使用备份存档验收。

当前 workflow 使用公开的固定 **Debug** 签名，仅适合开发测试。正式发行应使用私有 Release 密钥，
不要把现有 Debug 签名当作发行身份保证。

## 安装游戏

把游戏目录放到：

```
Android/data/io.github.kichikuou.xsystem4/files/<你想要的显示名>/
```

例如兰斯7：

```
Android/data/io.github.kichikuou.xsystem4/files/战国兰斯/
    Rance7.exe          <- 启动器从这里提取图标
    rance7.ain
    rance7BA.ald
    System40.ini
    ...
```

- **文件夹名就是启动器里显示的名字**，可以随意改成中文。
- 也可以直接把游戏打包成 ZIP，用启动器右上角菜单的"从 ZIP 安装"。
- 若 MTP 访问不了 `Android/data`，请用 ZIP 安装方式，或用读卡器直接写入 SD 卡。

## 已知问题

### 游戏与兼容性

- **兰斯10 OP/ED 的 Android 实播仍待验收**：APEG 画面解码已加入且完成本机样本验证，设备端音画同步、跳过与切后台行为尚未确认；详见[动画支持](#动画支持)。
- **兰斯10场景回顾仍待完整验收**：最近设备日志确认按钮进入脚本后因场景数为 0 退出，并非这两次点击未送达。
  普通分组存档与完整 VM 快照保存的内容不同；不能据此断言所有存档都不保存场景回顾。
  产生非空记录后的画面恢复、翻页、语音和退出返回尚未验收，剧情回顾末行/行距也需要屏幕实测。
- **任务选择/跳剧情的委托修复需要长时间设备回归**：已覆盖失效委托误释放复用槽的模型，
  但不保证所有任务链和既有存档都已无隐患，也不能恢复此前已经丢失的游戏进度。
- **存档保护是单文件原子替换**：已覆盖写入/同步/关闭/替换失败的离线模型，不等于 Android 断电实测或多文件事务保证。
  更新前仍应备份存档；旧档中已缺失的数组内容不能凭兼容修复还原。
- **接口兼容仍有边界**：例如接口数组部分操作、动态 EX 数据快照和若干新 HLL 仍为受限实现。
  这是源码限制，尚不能据此断言正常游戏流程一定触发；“导出已注册”不等于完整行为已还原。
- **Anime4K/CuNNy2** 可能带来重影、发热或掉帧；旧作品部分引擎功能也仍受上游实现限制。

### 发布与构建

- CI 已接入核心离线回归门禁。本次增量修复构建 arm64-v8a，设备验证范围见[最新修复](#最新修复2026-11-12)；不等于三 ABI 或完整游戏流程的实机验收。
- 最低系统声明与原生 API 级别不一致、公开 Debug 签名仍需注意，详见[构建](#构建)及[支持环境](#支持环境)。
  FFmpeg 编译/符号校验失败、Noto 字体校验不一致现在会终止标准构建，不再只警告后继续。
- `collect-licenses.sh` 的应用内许可列表尚未补齐新增字体、CuNNy2 和 FFmpeg；正式分发前需完善许可证收集，
  并核对组合分发要求。源码/资源中已有的许可文件不能当作应用内许可菜单已完整的证明。
- 公开 [Actions 运行 37279287268](https://github.com/bailaiOWO/xsystem4-android/actions/runs/37279287268)
  显示 2026-10-05 因账户计费锁定未启动。该历史运行不对应本次源码，也不能证明限制至今未解除；上传后须核实新运行能启动并成功。

### 首次上传核对（2026-11-11，`a014a4c`）

- 对照最近 Claude Code 会话及工作区，同步了旧引擎补丁遗漏的 27 个源码文件；libsys4 补丁与当前源码一致，无需改动。
- 本次实际运行 `node tools/test-rance10-all.mjs`，24 项核心回归通过。测试范围与环境要求见 [tools/README.md](tools/README.md#兰斯10本地回归)。
- 历史会话中最新 arm64 APK 有构建及覆盖安装成功记录；这不是本次三架构 CI 或完整真机游戏流程通过的证据。
- 两份补丁均已在固定基线的干净临时索引中成功应用，并与当前发布源码树一致（引擎 119 个变更文件、libsys4 21 个）。
  `.gitattributes` 固定补丁为 LF，避免 Windows checkout 的 CRLF 使补丁无法直接应用。

## 致谢与许可

- [xsystem4](https://github.com/nunuhara/xsystem4) / [libsys4](https://github.com/nunuhara/libsys4)：
  Nunuhara Cabbage 等；引擎源码声明 GPL-2.0-or-later，具体文件以各自声明为准。
- [xsystem4-android](https://github.com/kichikuou/xsystem4-android)：kichikuou。
- [re-xsystem4](https://github.com/TomorrowX6/re-xsystem4)：TomorrowX6；本分支 APEG 图片解码及 VLC 表移植来源，保留原始电影后端作者 kichikuou 的声明及 GPL-2.0-or-later 条款。
- [Anime4K](https://github.com/bloc97/Anime4K)：bloc97 等，MIT。
- [CuNNy](https://github.com/funnyplanter/CuNNy) / [Magpie](https://github.com/Blinue/Magpie)：
  CuNNy2 fast-NVL 移植保留 **GPL-3.0-or-later** 声明、原始 HLSL 与 COPYING，见 `xsystem4/shaders/cunny/`。
- [CEFFontsCJK](https://github.com/Partyb0ssishere/cef-fonts-cjk)、Noto 后备字体：OFL-1.1，来源见字体目录。
- FFmpeg 及其它依赖：遵循所用版本和实际构建配置的许可证。

不能将包含 CuNNy2 的分发包笼统标为“全部 GPL-2.0”；发布时须保留各组件的许可证并满足其分发要求。
游戏本体与汉化补丁的版权归各自权利人所有，本项目不附带任何游戏文件。
