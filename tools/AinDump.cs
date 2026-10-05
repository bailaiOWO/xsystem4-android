// AIN v5 parser: decrypt (MT19937 variant) + dump HLL0 libraries/functions + sample messages
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public class AinDump {
    // --- MT19937 variant used by libsys4 (init: 69069*x; xorcode uses low byte per call)
    class MT {
        uint[] st = new uint[624]; int idx = 624;
        public MT(uint seed) { st[0] = seed; for (int i = 1; i < 624; i++) st[i] = 69069u * st[i - 1]; }
        public uint Gen() {
            if (idx >= 624) {
                for (int k = 0; k < 227; k++) { uint y = (st[k] & 0x80000000u) | (st[k + 1] & 0x7fffffffu); st[k] = st[k + 397] ^ (y >> 1) ^ ((y & 1) != 0 ? 0x9908b0dfu : 0u); }
                for (int k = 227; k < 623; k++) { uint y = (st[k] & 0x80000000u) | (st[k + 1] & 0x7fffffffu); st[k] = st[k - 227] ^ (y >> 1) ^ ((y & 1) != 0 ? 0x9908b0dfu : 0u); }
                uint y2 = (st[623] & 0x80000000u) | (st[0] & 0x7fffffffu); st[623] = st[396] ^ (y2 >> 1) ^ ((y2 & 1) != 0 ? 0x9908b0dfu : 0u);
                idx = 0;
            }
            uint r = st[idx++];
            r ^= r >> 11; r ^= (r << 7) & 0x9d2c5680u; r ^= (r << 15) & 0xefc60000u; r ^= r >> 18;
            return r;
        }
    }

    static byte[] buf;
    static int pos;
    static Encoding gbk = Encoding.GetEncoding(936);

    static int I32() { int v = BitConverter.ToInt32(buf, pos); pos += 4; return v; }
    static string Str() { int s = pos; while (buf[pos] != 0) pos++; string r = gbk.GetString(buf, s, pos - s); pos++; return r; }

    public static void ScanCalls(string ainPath, string outPath) {
        buf = File.ReadAllBytes(ainPath);
        if (Encoding.ASCII.GetString(buf, 0, 4) != "VERS") {
            var mt = new MT(0x5D3E3);
            for (int i = 0; i < buf.Length; i++) buf[i] ^= (byte)(mt.Gen() & 0xff);
        }
        // locate CODE and HLL0 sections
        int codePos = -1, codeLen = 0, hllPos = -1;
        for (int i = 0; i + 8 < buf.Length; i++) {
            if (buf[i] == 'C' && buf[i+1] == 'O' && buf[i+2] == 'D' && buf[i+3] == 'E' && codePos < 0) { codePos = i + 4; codeLen = BitConverter.ToInt32(buf, codePos); codePos += 4; }
            if (buf[i] == 'H' && buf[i+1] == 'L' && buf[i+2] == 'L' && buf[i+3] == '0') { hllPos = i + 4; }
        }
        // parse HLL0 function counts
        pos = hllPos;
        int nlibs = I32();
        var libNames = new List<string>(); var libCounts = new List<int>();
        for (int i = 0; i < nlibs; i++) {
            string lib = Str(); int nf = I32();
            libNames.Add(lib); libCounts.Add(nf);
            for (int j = 0; j < nf; j++) { Str(); I32(); int na = I32(); for (int k = 0; k < na; k++) { Str(); I32(); } }
        }
        // scan code for CALLHLL (0x5A 0x00 + lib + fn)
        var counts = new Dictionary<string, int>();
        var fnNames = new Dictionary<string, List<string>>();
        for (int i = codePos; i + 10 <= codePos + codeLen; i++) {
            if (buf[i] != 0x5A || buf[i+1] != 0) continue;
            int lib = BitConverter.ToInt32(buf, i + 2);
            int fn = BitConverter.ToInt32(buf, i + 6);
            if (lib < 0 || lib >= nlibs || fn < 0 || fn >= libCounts[lib]) continue;
            string key = libNames[lib] + "#" + fn;
            if (!counts.ContainsKey(key)) {
                counts[key] = 0;
                // recover function name by re-parsing HLL0 once more later; store index
            }
            counts[key]++;
        }
        // re-parse HLL0 to map index -> name
        var nameMap = new Dictionary<string, string>();
        pos = hllPos; I32();
        for (int i = 0; i < nlibs; i++) {
            string lib = Str(); int nf = I32();
            for (int j = 0; j < nf; j++) { string fn = Str(); nameMap[lib + "#" + j] = fn; I32(); int na = I32(); for (int k = 0; k < na; k++) { Str(); I32(); } }
        }
        var sb = new StringBuilder();
        foreach (var kv in counts) {
            sb.AppendLine($"{kv.Value,6}  {nameMap.GetValueOrDefault(kv.Key, kv.Key)}   [{kv.Key.Replace("#", " #")}]");
        }
        File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
        Console.WriteLine("scan done: " + counts.Count + " distinct HLL calls");
    }

    public static void Run(string ainPath, string outPath) {
        buf = File.ReadAllBytes(ainPath);
        if (Encoding.ASCII.GetString(buf, 0, 4) != "VERS") {
            // decrypt
            var mt = new MT(0x5D3E3);
            for (int i = 0; i < buf.Length; i++) buf[i] ^= (byte)(mt.Gen() & 0xff);
        }
        var sb = new StringBuilder();
        pos = 0;
        int version = -1;
        try {
        while (pos + 4 <= buf.Length) {
            string tag = Encoding.ASCII.GetString(buf, pos, 4);
            sb.AppendLine($"-- {tag} @ 0x{pos:X}");
            pos += 4;
            if (pos + 4 > buf.Length) break;
            switch (tag) {
                case "VERS": version = I32(); sb.AppendLine($"AIN version: {version}"); break;
                case "KEYC": I32(); break;
                case "CODE": { int n = I32(); pos += n; sb.AppendLine($"CODE size: {n}"); break; }
                case "FUNC": {
                    int n = I32();
                    for (int i = 0; i < n; i++) {
                        I32(); // address
                        string name = Str();
                        I32(); // is_label (v>1 && v<7)
                        I32(); I32(); // return type (v<11: data, struc)
                        I32(); // nr_args
                        int nv = I32(); // nr_vars
                        I32(); // crc (v>1)
                        for (int j = 0; j < nv; j++) { Str(); I32(); I32(); I32(); } // v5: no initval
                    }
                    sb.AppendLine($"FUNC count: {n}");
                    break;
                }
                case "GLOB": {
                    int n = I32();
                    for (int i = 0; i < n; i++) { Str(); I32(); I32(); I32(); I32(); } // name, type(data,struc,rank), group_index(v>=5)
                    sb.AppendLine($"GLOB count: {n}");
                    break;
                }
                case "GSET": {
                    int n = I32();
                    for (int i = 0; i < n; i++) { I32(); int dt = I32(); if (dt == 12) Str(); else I32(); } // AIN_STRING=12
                    break;
                }
                case "STRT": {
                    int n = I32();
                    for (int i = 0; i < n; i++) {
                        Str(); I32(); I32(); int nm = I32();
                        for (int j = 0; j < nm; j++) { Str(); I32(); I32(); I32(); } // v5: no initval
                    }
                    sb.AppendLine($"STRT count: {n}");
                    break;
                }
                case "MSG0": {
                    int n = I32();
                    var msgs = new List<string>();
                    for (int i = 0; i < n && i < 30; i++) msgs.Add(Str());
                    for (int i = 30; i < n; i++) Str();
                    sb.AppendLine($"MSG0 count: {n}");
                    sb.AppendLine("--- first messages ---");
                    foreach (var m in msgs) sb.AppendLine(m.Replace("\r", "\\r").Replace("\n", "\\n"));
                    break;
                }
                case "MAIN": I32(); break;
                case "MSGF": I32(); break;
                case "HLL0": {
                    int n = I32();
                    sb.AppendLine($"=== HLL0: {n} libraries ===");
                    for (int i = 0; i < n; i++) {
                        string lib = Str();
                        int nf = I32();
                        sb.AppendLine($"[{lib}] ({nf} functions)");
                        for (int j = 0; j < nf; j++) {
                            string fn = Str();
                            I32(); // return type data
                            int na = I32();
                            sb.Append("  ").Append(fn).Append('(');
                            for (int k = 0; k < na; k++) { string an = Str(); I32(); sb.Append(an); if (k < na - 1) sb.Append(", "); }
                            sb.AppendLine(")");
                        }
                    }
                    break;
                }
                case "SWI0": { int n = I32(); for (int i = 0; i < n; i++) { I32(); I32(); int nc = I32(); for (int j = 0; j < nc; j++) { I32(); I32(); } } break; }
                case "GVER": sb.AppendLine($"GVER: {I32()}"); break;
                case "STR0": { int n = I32(); for (int i = 0; i < n; i++) Str(); sb.AppendLine($"STR0 count: {n}"); break; }
                case "FNAM": { int n = I32(); var fns = new List<string>(); for (int i = 0; i < n; i++) fns.Add(Str()); sb.AppendLine($"FNAM: {string.Join(", ", fns)}"); break; }
                case "OJMP": I32(); break;
                case "FNCT": {
                    I32(); int n = I32();
                    for (int i = 0; i < n; i++) { Str(); I32(); I32(); I32(); int nv = I32(); for (int j = 0; j < nv; j++) { Str(); I32(); I32(); I32(); } }
                    break;
                }
                case "OBJG": { int n = I32(); for (int i = 0; i < n; i++) Str(); break; }
                default:
                    sb.AppendLine($"Unknown tag '{tag}' at 0x{(pos - 4):X}, stopping");
                    pos = buf.Length;
                    break;
            }
        }
        } catch (Exception ex) {
            sb.AppendLine($"EXCEPTION at 0x{pos:X}: {ex.Message}");
        }
        File.WriteAllText(outPath, sb.ToString(), new UTF8Encoding(false));
        Console.WriteLine("done. version=" + version);
    }
}
