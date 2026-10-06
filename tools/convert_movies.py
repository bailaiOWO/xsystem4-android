#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把游戏里的 ASF/WMV 动画转成引擎能播放的 MPEG-PS。

背景
----
AliceSoft 后期作品（兰斯8 Rance Quest、兰斯9 赫尔曼革命等）的动画是 ASF 容器，
里面装的是 WMV1/WMA2 编码。xsystem4 的 Android 构建只有 MPEG-PS（MPEG-1）解码器，
解不了这些格式，于是游戏会跳过动画。

本脚本用 ffmpeg 把这类动画转码成 MPEG-1/MPEG-PS，输出到**原文件旁边**
（例如 `Opening.alm` → `Opening.mpg`）。引擎（src/movie_plmpeg.c）在发现原文件不是
MPEG-PS 时会自动查找并播放同名的 `.mpg`/`.mpeg`，因此**不需要改动游戏数据**，
原始动画也保持完好。

用法
----
    # 转换 PC 上的游戏目录（会递归查找）
    python tools/convert_movies.py "/path/to/ランス9/Data/Movie"

    # 直接转换平板/手机上的游戏目录（需要 adb 可用）
    python tools/convert_movies.py --adb "/storage/emulated/0/Android/data/io.github.kichikuou.xsystem4/files/兰斯9 赫尔曼革命/Data/Movie"

    # 只转换指定文件；自定义码率（默认 2500k）
    python tools/convert_movies.py Opening.alm --bitrate 4000k

依赖
----
ffmpeg / ffprobe（需要出现在 PATH 中）。Windows 可用 winget 安装：
    winget install Gyan.FFmpeg
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

VIDEO_EXTS = ('.alm', '.asf', '.wmv')
OUT_EXTS = ('.mpg', '.mpeg')


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def have_ffmpeg():
    for tool in ('ffmpeg', 'ffprobe'):
        if not shutil.which(tool):
            print(f'错误：找不到 {tool}，请先安装 ffmpeg（Windows: winget install Gyan.FFmpeg）')
            return False
    return True


def codec_of(path):
    """返回 (视频编码, 音频编码)；失败返回 (None, None)。"""
    r = run(['ffprobe', '-v', 'error', '-show_entries', 'stream=codec_name',
             '-of', 'default=noprint_wrappers=1:nokey=1', path])
    if r.returncode != 0:
        return None, None
    names = [l.strip() for l in r.stdout.splitlines() if l.strip()]
    video = next((n for n in names if n.startswith(('mpeg1', 'mpeg2'))), None)
    return (names[0] if names else None), video


def convert(src, dst, bitrate):
    cmd = ['ffmpeg', '-y', '-hide_banner', '-loglevel', 'warning',
           '-i', src,
           '-c:v', 'mpeg1video', '-b:v', bitrate,
           '-c:a', 'mp2', '-b:a', '160k', '-ar', '44100', '-ac', '2',
           '-f', 'mpeg', dst]
    print(f'  转码中…（大文件需要几分钟）')
    r = run(cmd)
    if r.returncode != 0:
        print(f'  转码失败：{r.stderr.strip().splitlines()[-1] if r.stderr.strip() else "未知错误"}')
        return False
    return True


def process(name, read_path, write_path, bitrate, force):
    base = os.path.splitext(name)[0]
    if not force:
        # 已经转过就跳过（引擎会优先用转换后的文件）
        for ext in OUT_EXTS:
            cand = os.path.join(os.path.dirname(write_path), base + ext)
            if os.path.exists(cand):
                print(f'跳过 {name}：已存在 {os.path.basename(cand)}')
                return True

    vcodec, mpeg = codec_of(read_path)
    if vcodec is None:
        print(f'跳过 {name}：无法读取')
        return False
    if mpeg:
        print(f'跳过 {name}：已经是 {vcodec}（引擎可直接播放）')
        return False

    print(f'转换 {name}：{vcodec} → mpeg1video')
    out = os.path.join(os.path.dirname(write_path), base + '.mpg')
    return convert(read_path, out, bitrate)


def collect_local(target):
    if os.path.isfile(target):
        return [target]
    found = []
    for root, _dirs, files in os.walk(target):
        for f in files:
            if f.lower().endswith(VIDEO_EXTS):
                found.append(os.path.join(root, f))
    return sorted(found)


def main():
    ap = argparse.ArgumentParser(description='把 ASF/WMV 动画转成 MPEG-PS（引擎可播放）')
    ap.add_argument('target', help='游戏动画文件或目录')
    ap.add_argument('--adb', action='store_true',
                    help='target 是设备上的路径（通过 adb 就地转换）')
    ap.add_argument('--bitrate', default='2500k', help='视频码率（默认 2500k）')
    ap.add_argument('--force', action='store_true', help='即使已转换过也重新转')
    args = ap.parse_args()

    if not have_ffmpeg():
        return 1

    if not args.adb:
        files = collect_local(args.target)
        if not files:
            print('没有找到 .alm/.asf/.wmv 文件')
            return 0
        ok = True
        for f in files:
            ok &= process(os.path.basename(f), f, f, args.bitrate, args.force)
        return 0 if ok else 1

    # --- 设备模式：拉取 → 转换 → 推回 ---
    adb = shutil.which('adb') or shutil.which('adb.exe')
    if not adb:
        print('错误：找不到 adb')
        return 1
    ls = run([adb, 'shell', f'ls "{args.target}"'])
    if ls.returncode != 0:
        print(f'错误：无法列出设备目录 {args.target}')
        return 1
    names = [n.strip() for n in ls.stdout.splitlines()
             if n.strip().lower().endswith(VIDEO_EXTS)]
    if not names:
        print('设备目录里没有 .alm/.asf/.wmv 文件')
        return 0

    with tempfile.TemporaryDirectory() as tmp:
        ok = True
        for name in names:
            remote = f'{args.target.rstrip("/")}/{name}'
            local = os.path.join(tmp, name)
            out_local = os.path.splitext(local)[0] + '.mpg'
            out_remote = f'{args.target.rstrip("/")}/{os.path.splitext(name)[0]}.mpg'
            r = run([adb, 'shell', f'ls "{out_remote}" 2>/dev/null'])
            if r.returncode == 0 and r.stdout.strip() and not args.force:
                print(f'跳过 {name}：设备上已存在同名 .mpg')
                continue
            print(f'拉取 {name} …')
            if run([adb, 'pull', remote, local]).returncode != 0:
                print('  拉取失败')
                ok = False
                continue
            if not process(name, local, local, args.bitrate, True):
                ok = False
                continue
            if not os.path.exists(out_local):
                print('  没有生成输出文件')
                ok = False
                continue
            print(f'  推回 {os.path.basename(out_local)}'
                  f'（{os.path.getsize(out_local)/1048576:.0f} MB）…')
            if run([adb, 'push', out_local, out_remote]).returncode != 0:
                print('  推送失败')
                ok = False
                continue
            os.remove(local)
        return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
