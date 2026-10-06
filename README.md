# xsystem4-android（中文汉化适配分支）

这是 [kichikuou/xsystem4-android](https://github.com/kichikuou/xsystem4-android) 的分支，
目标是把 [xsystem4](https://github.com/nunuhara/xsystem4)（AliceSoft System4 引擎的开源重实现）
做成能**正常游玩中文汉化版**兰斯系列的 Android 版本。

上游原版能跑日文原版游戏，但中文汉化版有一整套"编码/资源/字体"的问题，本分支专门解决这些。

## 支持环境

- 系统：Android 5.0（API 21）或更高
- CPU：32 位 ARM / 64 位 ARM / 64 位 x86

## 当前适配进度

| 游戏 | 状态 | 说明 |
| --- | --- | --- |
| 兰斯7 战国兰斯（汉化版） | ✅ 可玩 | 中文文本、字体、存档、触控均已适配 |
| 兰斯8 Rance Quest（汉化版） | ✅ 可玩 | 需本分支的资源包名识别 + TGA 解码支持 |
| 兰斯6（汉化版） | ✅ 可玩 | |
| 兰斯9 赫尔曼革命（汉化版） | ⚠️ 部分 | 开场动画为 ASF/WMV 格式，本引擎只有 MPEG-PS 解码器，目前**自动跳过**该动画（不会报错退出）；其余仍在适配 |
| 其它 System4 游戏 | 见上游 | 日文原版按上游兼容性表；中文版可提 issue |

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

- **侧边菜单**：返回键呼出，Material Design 1 风格（左侧全高抽屉、白底直角、遮罩层），
  包含：输入方式、Anime4K 档位、修改器（开发中）、退出游戏。
- **Anime4K 超分辨率**（实验性）：把画面经过 Anime4K CNN 着色器链放大后再缩放到屏幕。
  菜单里可选 `关闭 / 2x (M) / 4x (M+S)`。**默认档位为 4x**；若遇到画面异常请切到"关闭"。
- **诊断日志**：为便于继续适配其它作品，引擎会输出少量提示日志（`HLLUSE:` 游戏用到的引擎 API 清单、
  `asset:` 资源包匹配详情、`CG:` 图像加载详情）。用 `adb logcat -s libsys4:V` 查看。

## 构建

只想安装使用的话可以跳过本节。

### 依赖

- Linux / macOS（本分支的日常开发在 Windows 上进行，见下方说明）
- Android SDK（Android Studio 或命令行工具）
- Android NDK r23 或更高
- CMake 3.21+、flex、bison、ninja

### 构建步骤（与上游一致）

```sh
export ANDROID_SDK_ROOT=<你的 Android SDK>
export ANDROID_NDK_HOME=<你的 Android NDK>

git clone --recursive https://github.com/bailaiOWO/xsystem4-android.git
cd xsystem4-android
./build-shared-libs.sh          # 编译原生库
cd project && ./gradlew build   # 生成 APK
```

### 关于补丁

`xsystem4` 与 `libsys4` 是指向 **上游仓库** 的 git 子模块（本分支没有推送权限），
因此本分支的所有引擎改动都以补丁形式存放在 `patches/`，由 CI 与本地脚本自动应用：

```
patches/
  0001-sengoku-rance-cn-hll.patch      # 兰斯7 汉化版 HLL 适配
  0002-cn-engine-fixes.patch           # 编码语义拆分、资源包匹配、兰斯8 识别、动画跳过等
  0003-anime4k.patch                   # Anime4K 着色器链（含 CNN 着色器文件）
  libsys4/
    0001-cn-encoding.patch             # 三种编码语义 + 容错文件名解析
    0002-tga-format.patch              # TGA 图像格式支持
```

本地复刻 CI 的补丁流程（生成 GBK 码表 + 应用补丁 + 字体/输入补丁）：

```sh
python android-build/apply_gbk_patch.py
```

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

- 兰斯9 开场动画（ASF/WMV）无法播放，目前自动跳过；要真正播放需要引入支持 ASF/WMV 的解码后端。
- Anime4K 为实验特性：部分游戏/分辨率下可能有重影或性能下降，可在菜单里关闭。
- 部分老游戏的 3D 部件（PartsEngine）功能上游仍未完全实现。

## 致谢与许可

- [xsystem4](https://github.com/nunuhara/xsystem4) / [libsys4](https://github.com/nunuhara/libsys4)：
  Nunuhara Cabbage 等，GPL-2.0
- [xsystem4-android](https://github.com/kichikuou/xsystem4-android)：kichikuou，GPL-2.0
- [Anime4K](https://github.com/bloc97/Anime4K)：bloc97 等，MIT
- [CEFFontsCJK](https://github.com/Partyb0ssishere/cef-fonts-cjk)：OFL-1.1

本分支同样以 GPL-2.0 发布。游戏本体与汉化补丁的版权归各自权利人所有，本项目不附带任何游戏文件。
