#!/usr/bin/env python3
"""End-to-end SOCKS5 UDP ASSOCIATE probe using a DNS A query."""

import argparse
import ipaddress
import socket
import struct


def recv_exact(sock: socket.socket, size: int) -> bytes:
    chunks = []
    remaining = size
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise RuntimeError("unexpected TCP EOF")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def encode_address(host: str) -> bytes:
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        raw = host.encode("ascii")
        if not 1 <= len(raw) <= 255:
            raise ValueError("domain length must be 1..255")
        return b"\x03" + bytes([len(raw)]) + raw
    if address.version == 4:
        return b"\x01" + address.packed
    return b"\x04" + address.packed


def decode_address(data: bytes, offset: int):
    atyp = data[offset]
    offset += 1
    if atyp == 1:
        raw = data[offset:offset + 4]
        offset += 4
        host = str(ipaddress.ip_address(raw))
    elif atyp == 3:
        length = data[offset]
        offset += 1
        host = data[offset:offset + length].decode("ascii")
        offset += length
    elif atyp == 4:
        raw = data[offset:offset + 16]
        offset += 16
        host = str(ipaddress.ip_address(raw))
    else:
        raise RuntimeError(f"unsupported ATYP {atyp}")
    port = struct.unpack("!H", data[offset:offset + 2])[0]
    return host, port, offset + 2


def dns_query(name: str, transaction_id: int) -> bytes:
    labels = name.rstrip(".").split(".")
    qname = b"".join(bytes([len(label)]) + label.encode("ascii") for label in labels) + b"\0"
    return struct.pack("!HHHHHH", transaction_id, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", 1, 1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("proxy_host")
    parser.add_argument("--proxy-port", type=int, default=1080)
    parser.add_argument("--target", default="dns.google")
    parser.add_argument("--target-port", type=int, default=53)
    parser.add_argument("--query", default="example.com")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--fragment-probe", action="store_true")
    parser.add_argument("--associate-empty-domain", action="store_true",
                        help="use Stash/Mihomo's empty-domain UDP ASSOCIATE sentinel")
    args = parser.parse_args()

    transaction_id = 0x5A17
    with socket.create_connection((args.proxy_host, args.proxy_port), args.timeout) as control:
        control.settimeout(args.timeout)
        control.sendall(b"\x05\x01\x00")
        greeting = recv_exact(control, 2)
        if greeting != b"\x05\x00":
            raise RuntimeError(f"SOCKS greeting rejected: {greeting.hex()}")

        if args.associate_empty_domain:
            control.sendall(b"\x05\x03\x00\x03\x00\x00\x00")
        else:
            control.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
        prefix = recv_exact(control, 4)
        if prefix[:3] != b"\x05\x00\x00":
            raise RuntimeError(f"UDP ASSOCIATE rejected: {prefix.hex()}")
        atyp = prefix[3]
        if atyp == 1:
            rest = recv_exact(control, 6)
        elif atyp == 4:
            rest = recv_exact(control, 18)
        elif atyp == 3:
            length = recv_exact(control, 1)[0]
            rest = bytes([length]) + recv_exact(control, length + 2)
        else:
            raise RuntimeError(f"invalid relay ATYP {atyp}")
        relay_host, relay_port, _ = decode_address(bytes([atyp]) + rest, 0)
        if relay_host in ("0.0.0.0", "::"):
            relay_host = args.proxy_host

        family = socket.AF_INET6 if ipaddress.ip_address(relay_host).version == 6 else socket.AF_INET
        with socket.socket(family, socket.SOCK_DGRAM) as udp:
            udp.settimeout(args.timeout)
            query = dns_query(args.query, transaction_id)
            request = b"\x00\x00\x00" + encode_address(args.target)
            request += struct.pack("!H", args.target_port) + query
            if args.fragment_probe:
                fragmented = request[:2] + b"\x01" + request[3:]
                udp.settimeout(1.0)
                udp.sendto(fragmented, (relay_host, relay_port))
                try:
                    udp.recvfrom(65_535)
                except socket.timeout:
                    print("UDP_FRAGMENT_DROP=PASS")
                else:
                    raise RuntimeError("FRAG != 0 unexpectedly produced a reply")
                udp.settimeout(args.timeout)
            udp.sendto(request, (relay_host, relay_port))
            response, _ = udp.recvfrom(65_535)

        if response[:3] != b"\x00\x00\x00":
            raise RuntimeError("invalid SOCKS5 UDP response header")
        remote_host, remote_port, payload_offset = decode_address(response, 3)
        payload = response[payload_offset:]
        if len(payload) < 12:
            raise RuntimeError("short DNS response")
        response_id, flags, questions, answers, authority, additional = struct.unpack(
            "!HHHHHH", payload[:12]
        )
        if response_id != transaction_id or not flags & 0x8000:
            raise RuntimeError("DNS response transaction/QR mismatch")
        rcode = flags & 0x000F
        if rcode != 0 or answers < 1:
            raise RuntimeError(f"DNS response failed: rcode={rcode} answers={answers}")

        print(f"SOCKS_METHOD=NO_AUTH")
        print("UDP_ASSOCIATE_ADDRESS="
              + ("EMPTY_DOMAIN" if args.associate_empty_domain else "IPV4_UNSPECIFIED"))
        print(f"UDP_RELAY={relay_host}:{relay_port}")
        print(f"UDP_REMOTE={remote_host}:{remote_port}")
        print(f"DNS_QUERY={args.query}")
        print(f"DNS_RCODE={rcode}")
        print(f"DNS_ANSWERS={answers}")
        print(f"UDP_ASSOCIATE_EXIT=0")


if __name__ == "__main__":
    main()
