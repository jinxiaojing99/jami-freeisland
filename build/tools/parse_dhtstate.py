#!/usr/bin/env python3
"""Stream-parse OpenDHT dhtstate msgpack -> known/good DHT nodes (id + IP:port).

DhtState = map { v:int, id:bin, nodes:array[NodeExport], values:array }
NodeExport = map { id:bin, addr:bin(raw sockaddr), ... }
The values section may contain list-as-key maps that break naive unpackb(),
so we stream-read only the fields we need and skip the rest.
"""
import sys, struct, socket, json, collections
import msgpack


def decode_sockaddr(b):
    """addr = raw sockaddr_in (family LE2, port BE2, ipv4 4) or sockaddr_in6."""
    if not isinstance(b, (bytes, bytearray)):
        return None, None, "?"
    b = bytes(b)
    if len(b) >= 8:
        family = struct.unpack_from("<H", b, 0)[0]  # AF_INET=2, AF_INET6=10 on Linux / 23 on Win
        port = struct.unpack_from(">H", b, 2)[0]
        if family == socket.AF_INET or len(b) < 28:
            try:
                ip = socket.inet_ntop(socket.AF_INET, b[4:8])
                return ip, port, "IPv4"
            except Exception:
                pass
        # IPv6
        if len(b) >= 24:
            try:
                ip = socket.inet_ntop(socket.AF_INET6, b[8:24])
                return ip, port, "IPv6"
            except Exception:
                pass
    return None, None, f"len{len(b)}"


def parse_file(path):
    raw = open(path, "rb").read()
    u = msgpack.Unpacker(raw=False, strict_map_key=False)
    u.feed(raw)
    result = {"file": path, "size": len(raw), "self_id": None, "nodes": [], "value_count": 0}
    try:
        nfields = u.read_map_header()
        for _ in range(nfields):
            k = u.unpack()
            if k == "v":
                u.unpack()
            elif k == "id":
                v = u.unpack()
                result["self_id"] = bytes(v).hex() if isinstance(v, (bytes, bytearray)) else str(v)
            elif k == "nodes":
                cnt = u.read_array_header()
                result["node_count"] = cnt
                for _ in range(cnt):
                    try:
                        n = u.unpack()
                    except TypeError:
                        # node with list-as-key map; consume it as raw bytes via skip
                        try:
                            u.skip()
                        except Exception:
                            pass
                        result["nodes"].append({"raw": "unparseable-node"})
                        continue
                    if isinstance(n, dict):
                        nid = n.get("id")
                        nidh = bytes(nid).hex() if isinstance(nid, (bytes, bytearray)) else str(nid)
                        ip, port, fam = decode_sockaddr(n.get("addr"))
                        result["nodes"].append({"id": nidh, "ip": ip, "port": port, "fam": fam})
                    else:
                        result["nodes"].append({"raw": repr(n)[:120]})
            elif k == "values":
                try:
                    vc = u.read_array_header()
                    result["value_count"] = vc
                    u.skip()
                except Exception:
                    pass
            else:
                u.skip()
    except Exception as e:
        result["stream_error"] = repr(e)
    # stats
    fams = collections.Counter(n.get("fam") for n in result["nodes"])
    result["families"] = dict(fams)
    return result


if __name__ == "__main__":
    for p in sys.argv[1:]:
        r = parse_file(p)
        print("=== FILE:", p, "===")
        print(json.dumps({k: v for k, v in r.items() if k != "nodes"}, ensure_ascii=False))
        for n in r["nodes"]:
            print(f"  {n.get('fam','?'):5} {str(n.get('ip')):45} {str(n.get('port')):6} {n.get('id','')[:24]}")
