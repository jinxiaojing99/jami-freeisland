/*
 * F-904 带外加好友 —— 节点名片编解码（QML/JS 纯函数）
 *
 * 格式 v1：JAMICARD1:<base64url(canonical_json)>
 *  - canonical_json：字段按 key 字典序、无空格、UTF-8
 *  - fp：sha256(canonical_json_without_fp) 前 4 字节 8 个 hex
 *  - base64url 无 padding；base64 用纯 JS 实现（绝不用 Qt.btoa/atob，见下方说明）
 *
 * 兼容：解码同时对 JSON.stringify（/ 不转义）与 Android JSONObject.quote（/ 转义为 \/）
 *       两种 canonical 规则做指纹校验，任一匹配即通过。
 *
 * 本文件可被 Node 直接 require 做单测（末尾 module.exports 兼容）。
 */
// QML importable JS library (pragma library intentionally omitted so Node.js can require it for unit tests)

var MAGIC = "JAMICARD1";

// ---------- UTF-8 ----------
function utf8Encode(str) {
    // 用 encodeURIComponent 得到 %XX 序列，再转字节
    var enc = encodeURIComponent(str);
    var bytes = [];
    for (var i = 0; i < enc.length; i++) {
        if (enc[i] === '%') {
            bytes.push(parseInt(enc.substr(i + 1, 2), 16));
            i += 2;
        } else {
            bytes.push(enc.charCodeAt(i));
        }
    }
    return bytes;
}

function utf8Decode(bytes) {
    var s = "";
    for (var i = 0; i < bytes.length; i++) {
        var b = bytes[i];
        if (b < 0x80) s += String.fromCharCode(b);
        else if (b < 0xE0) {
            s += String.fromCharCode(((b & 0x1F) << 6) | (bytes[++i] & 0x3F));
        } else if (b < 0xF0) {
            s += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[++i] & 0x3F) << 6) | (bytes[++i] & 0x3F));
        } else {
            var cp = ((b & 0x07) << 18) | ((bytes[++i] & 0x3F) << 12) |
                     ((bytes[++i] & 0x3F) << 6) | (bytes[++i] & 0x3F);
            cp -= 0x10000;
            s += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
        }
    }
    return s; // 已手动完成 UTF-8 解码，勿再 decodeURIComponent（二次解码会破坏中文）
}

// ---------- base64url (bytes <-> string) ----------
// F-904 关键修复：绝不使用 Qt.btoa/atob。
// Qt 6.12 的 Qt.btoa(string)/Qt.atob(string) 已弃用且对高位字节(>=0x80)与 Web API
// 行为不同：btoa 会先把每个 char 当 Unicode 码点再做 UTF-8 编码，atob 则反过来丢字节。
// 中文昵称（UTF-8 高位字节）经 Qt API 后 base64 损坏，导致含中文的名片跨端/甚至本机
// 解码报 BAD_JSON / BAD_FINGERPRINT。改为纯 JS 实现，Node 单测与 QML 运行时行为一致。
var _B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function bytesToBase64Url(bytes) {
    var out = "";
    for (var i = 0; i < bytes.length; i += 3) {
        var b0 = bytes[i] & 0xff;
        var b1 = i + 1 < bytes.length ? bytes[i + 1] & 0xff : -1;
        var b2 = i + 2 < bytes.length ? bytes[i + 2] & 0xff : -1;
        out += _B64.charAt(b0 >> 2);
        out += _B64.charAt(((b0 & 0x03) << 4) | (b1 < 0 ? 0 : b1 >> 4));
        out += b1 < 0 ? "=" : _B64.charAt(((b1 & 0x0f) << 2) | (b2 < 0 ? 0 : b2 >> 6));
        out += b2 < 0 ? "=" : _B64.charAt(b2 & 0x3f);
    }
    return out.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlToBytes(token) {
    // 容忍粘贴时混入的空白字符（微信/短信换行、空格）
    var clean = token.replace(/[\s\r\n]/g, "");
    var b64 = clean.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    var out = [];
    for (var i = 0; i < b64.length; i += 4) {
        var c0 = _B64.indexOf(b64.charAt(i));
        var c1 = _B64.indexOf(b64.charAt(i + 1));
        var c2 = b64.charAt(i + 2) === "=" ? -1 : _B64.indexOf(b64.charAt(i + 2));
        var c3 = b64.charAt(i + 3) === "=" ? -1 : _B64.indexOf(b64.charAt(i + 3));
        if (c0 < 0 || c1 < 0) throw new Error("bad base64 char");
        var n = (c0 << 18) | (c1 << 12) | ((c2 < 0 ? 0 : c2) << 6) | (c3 < 0 ? 0 : c3);
        out.push((n >> 16) & 0xff);
        if (c2 >= 0) out.push((n >> 8) & 0xff);
        if (c3 >= 0) out.push(n & 0xff);
    }
    return out;
}

// ---------- SHA-256 (pure JS) ----------
var _K = [
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
  0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
  0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
  0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
  0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
  0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
  0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
];

function _rr(x, n) { return (x >>> n) | (x << (32 - n)); }

function sha256Bytes(bytes) {
    var H = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
    var ml = bytes.length * 8;
    var msg = bytes.slice();
    msg.push(0x80);
    while (msg.length % 64 !== 56) msg.push(0);
    var bitsHi = Math.floor(ml / 0x100000000);
    var bitsLo = ml >>> 0;
    msg.push((bitsHi >>> 24) & 0xff,(bitsHi >>> 16) & 0xff,(bitsHi >>> 8) & 0xff,bitsHi & 0xff);
    msg.push((bitsLo >>> 24) & 0xff,(bitsLo >>> 16) & 0xff,(bitsLo >>> 8) & 0xff,bitsLo & 0xff);

    for (var off = 0; off < msg.length; off += 64) {
        var w = [];
        for (var i = 0; i < 16; i++) {
            w[i] = ((msg[off+i*4] << 24) | (msg[off+i*4+1] << 16) |
                    (msg[off+i*4+2] << 8) | msg[off+i*4+3]) >>> 0;
        }
        for (i = 16; i < 64; i++) {
            var s0 = _rr(w[i-15],7) ^ _rr(w[i-15],18) ^ (w[i-15] >>> 3);
            var s1 = _rr(w[i-2],17) ^ _rr(w[i-2],19) ^ (w[i-2] >>> 10);
            w[i] = (w[i-16] + s0 + w[i-7] + s1) >>> 0;
        }
        var a=H[0],b=H[1],c=H[2],d=H[3],e=H[4],f=H[5],g=H[6],h=H[7];
        for (i = 0; i < 64; i++) {
            var S1 = _rr(e,6) ^ _rr(e,11) ^ _rr(e,25);
            var ch = (e & f) ^ (~e & g);
            var t1 = (h + S1 + ch + _K[i] + w[i]) >>> 0;
            var S0 = _rr(a,2) ^ _rr(a,13) ^ _rr(a,22);
            var maj = (a & b) ^ (a & c) ^ (b & c);
            var t2 = (S0 + maj) >>> 0;
            h=g; g=f; f=e; e=(d+t1)>>>0; d=c; c=b; b=a; a=(t1+t2)>>>0;
        }
        H[0]=(H[0]+a)>>>0; H[1]=(H[1]+b)>>>0; H[2]=(H[2]+c)>>>0; H[3]=(H[3]+d)>>>0;
        H[4]=(H[4]+e)>>>0; H[5]=(H[5]+f)>>>0; H[6]=(H[6]+g)>>>0; H[7]=(H[7]+h)>>>0;
    }
    var out = [];
    for (i = 0; i < 8; i++) {
        out.push((H[i] >>> 24) & 0xff,(H[i] >>> 16) & 0xff,(H[i] >>> 8) & 0xff,H[i] & 0xff);
    }
    return out;
}

function fingerprint(canonicalStr) {
    var dig = sha256Bytes(utf8Encode(canonicalStr));
    var s = "";
    for (var i = 0; i < 4; i++) {
        var h = dig[i].toString(16);
        if (h.length < 2) h = "0" + h;
        s += h;
    }
    return s;
}

// F-904 跨端兼容：Android JSONObject.quote 会把 '/' 转义成 '\\/'，
// 而本端 JSON.stringify 不转义。若名片值含 '/'，两端 canonical 会不一致导致
// 指纹校验失败。校验时对 canonical 再算一份"斜杠转义"变体，任一匹配即通过。
function _quoteAndroid(s) {
    return JSON.stringify(s).replace(/\//g, "\\/");
}

function canonicalAndroid(obj) {
    var keys = Object.keys(obj).sort();
    var parts = [];
    for (var i = 0; i < keys.length; i++) {
        var k = keys[i], v = obj[k];
        var val = (typeof v === "number") ? String(v) : _quoteAndroid(String(v));
        parts.push(_quoteAndroid(k) + ":" + val);
    }
    return "{" + parts.join(",") + "}";
}

// ---------- 批量文件（contacts.jamicards）----------
// 文件结构（与 Android NodeCardCodec.toFile/parseFile 一致）：
// {"magic":"JAMICARD1","version":1,"exportedAt":<ms>,"cards":[{m,id,n?,d?,v6?,v4?,ver?,ts?,fp}, ...]}

// cards: [{id, n?, d?, v6?, v4?, ver?, ts?}, ...] -> 文件 JSON 字符串
function toFile(cards, exportedAt) {
    var root = { magic: MAGIC, version: 1, exportedAt: exportedAt };
    var arr = [];
    for (var i = 0; i < cards.length; i++) {
        var c = cards[i];
        var m = { m: MAGIC, id: c.id };
        if (c.n) m.n = c.n;
        if (c.d) m.d = c.d;
        if (c.v6) m.v6 = c.v6;
        if (c.v4) m.v4 = c.v4;
        if (c.ver) m.ver = c.ver;
        if (c.ts !== undefined && c.ts !== null) m.ts = c.ts;
        m.fp = fingerprint(canonical(m));
        arr.push(m);
    }
    root.cards = arr;
    return JSON.stringify(root);
}

// 文件 JSON 字符串 -> 名片文本数组（每张重编码为标准串，统一走 decode 校验）
function parseFile(jsonText) {
    var out = [];
    var root;
    try { root = JSON.parse(jsonText); } catch (e) { return out; }
    if (!root || root.magic !== MAGIC || !root.cards || !root.cards.length) return out;
    for (var i = 0; i < root.cards.length; i++) {
        var o = root.cards[i];
        if (!o || !o.id) continue;
        // 先校验文件自带指纹，不一致的卡片直接跳过（fp 必须来自文件本身）
        var givenFp = o.fp || "";
        if (!givenFp) continue;
        var without = {};
        for (var k in o) { if (k !== "fp" && o.hasOwnProperty(k)) without[k] = o[k]; }
        if (fingerprint(canonical(without)) !== givenFp &&
            fingerprint(canonicalAndroid(without)) !== givenFp) continue;
        out.push(encode({
            id: o.id, n: o.n || null, d: o.d || null, v6: o.v6 || null,
            v4: o.v4 || null, ver: o.ver || null, ts: o.ts || null
        }));
    }
    return out;
}

// ---------- canonical JSON ----------
function _quote(s) { return JSON.stringify(s); }

function canonical(obj) {
    var keys = Object.keys(obj).sort();
    var parts = [];
    for (var i = 0; i < keys.length; i++) {
        var k = keys[i], v = obj[k];
        var val = (typeof v === "number") ? String(v) : _quote(String(v));
        parts.push(_quote(k) + ":" + val);
    }
    return "{" + parts.join(",") + "}";
}

// ---------- public API ----------
// card: {id, n?, d?, v6?, v4?, ver?, ts?}  -> text
function encode(card) {
    var m = { m: MAGIC, id: card.id };
    if (card.n) m.n = card.n;
    if (card.d) m.d = card.d;
    if (card.v6) m.v6 = card.v6;
    if (card.v4) m.v4 = card.v4;
    if (card.ver) m.ver = card.ver;
    if (card.ts !== undefined && card.ts !== null) m.ts = card.ts;
    var fp = fingerprint(canonical(m));
    m.fp = fp;
    var json = canonical(m);
    return MAGIC + ":" + bytesToBase64Url(utf8Encode(json));
}

// text -> { ok: true, card: {...} } | { ok: false, reason: "..." }
function decode(text) {
    var t = (text || "").trim();
    if (!t) return { ok: false, reason: "EMPTY" };
    if (t.length > 4096) return { ok: false, reason: "TOO_LONG" };
    var colon = t.indexOf(":");
    if (colon <= 0 || t.substring(0, colon) !== MAGIC) return { ok: false, reason: "BAD_PREFIX" };
    var token = t.substring(colon + 1).trim();
    var bytes;
    try { bytes = base64UrlToBytes(token); } catch (e) { return { ok: false, reason: "BAD_BASE64" }; }
    var json;
    try { json = utf8Decode(bytes); } catch (e) { return { ok: false, reason: "BAD_JSON" }; }
    var obj;
    try { obj = JSON.parse(json); } catch (e) { return { ok: false, reason: "BAD_JSON" }; }
    if (obj.m !== MAGIC) return { ok: false, reason: "BAD_MAGIC" };
    var fpGiven = obj.fp || "";
    if (!fpGiven) return { ok: false, reason: "BAD_FINGERPRINT" };
    var without = {};
    for (var k in obj) { if (k !== "fp" && obj.hasOwnProperty(k)) without[k] = obj[k]; }
    // 两种 canonical 规则都接受：本端 JSON.stringify（/ 不转义）+ Android JSONObject.quote（/ 转义为 \/）
    if (fingerprint(canonical(without)) !== fpGiven &&
        fingerprint(canonicalAndroid(without)) !== fpGiven) {
        return { ok: false, reason: "BAD_FINGERPRINT" };
    }
    if (!obj.id) return { ok: false, reason: "NO_ID" };
    if (!/^[0-9a-fA-F]{40}$/.test(obj.id)) return { ok: false, reason: "BAD_ID" };
    return { ok: true, card: {
        id: obj.id, n: obj.n || null, d: obj.d || null, v6: obj.v6 || null,
        v4: obj.v4 || null, ver: obj.ver || null, ts: obj.ts || null, fp: fpGiven
    } };
}

// Node 单测用（QML 下 typeof module 为 undefined，自动跳过）
if (typeof module !== "undefined" && module.exports) {
    module.exports = { MAGIC: MAGIC, encode: encode, decode: decode, fingerprint: fingerprint, canonical: canonical, canonicalAndroid: canonicalAndroid, sha256Bytes: sha256Bytes, toFile: toFile, parseFile: parseFile };
}
