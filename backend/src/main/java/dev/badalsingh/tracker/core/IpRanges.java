package dev.badalsingh.tracker.core;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Immutable set of IPv4/IPv6 CIDR ranges with O(log n) membership tests.
 * Overlapping ranges are merged on construction. Unparseable entries are skipped.
 * Never performs DNS lookups: only literal IP addresses are accepted.
 */
public final class IpRanges {
    public static final IpRanges EMPTY = new IpRanges(new BigInteger[0], new BigInteger[0], new BigInteger[0], new BigInteger[0]);

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final BigInteger[] v4Starts, v4Ends, v6Starts, v6Ends;

    private IpRanges(BigInteger[] v4Starts, BigInteger[] v4Ends, BigInteger[] v6Starts, BigInteger[] v6Ends) {
        this.v4Starts = v4Starts; this.v4Ends = v4Ends; this.v6Starts = v6Starts; this.v6Ends = v6Ends;
    }

    public static IpRanges of(Collection<String> cidrs) {
        ArrayList<BigInteger[]> v4 = new ArrayList<>();
        ArrayList<BigInteger[]> v6 = new ArrayList<>();
        for (String raw : cidrs) {
            if (raw == null) continue;
            String cidr = raw.trim();
            int slash = cidr.indexOf('/');
            String address = slash < 0 ? cidr : cidr.substring(0, slash);
            Parsed parsed = parse(address);
            if (parsed == null) continue;
            int maxBits = parsed.v6 ? 128 : 32;
            int prefix = maxBits;
            if (slash >= 0) {
                try { prefix = Integer.parseInt(cidr.substring(slash + 1).trim()); } catch (NumberFormatException e) { continue; }
                if (prefix < 0 || prefix > maxBits) continue;
            }
            BigInteger hostMask = BigInteger.ONE.shiftLeft(maxBits - prefix).subtract(BigInteger.ONE);
            BigInteger start = parsed.value.andNot(hostMask);
            (parsed.v6 ? v6 : v4).add(new BigInteger[]{start, start.or(hostMask)});
        }
        BigInteger[][] m4 = merge(v4);
        BigInteger[][] m6 = merge(v6);
        return new IpRanges(m4[0], m4[1], m6[0], m6[1]);
    }

    public boolean isEmpty() { return v4Starts.length == 0 && v6Starts.length == 0; }

    public int size() { return v4Starts.length + v6Starts.length; }

    public boolean contains(String ip) {
        Parsed parsed = parse(ip == null ? "" : ip.trim());
        if (parsed == null) return false;
        return parsed.v6 ? within(v6Starts, v6Ends, parsed.value) : within(v4Starts, v4Ends, parsed.value);
    }

    private static boolean within(BigInteger[] starts, BigInteger[] ends, BigInteger value) {
        int lo = 0, hi = starts.length - 1, found = -1;
        while (lo <= hi) { // last range whose start <= value
            int mid = (lo + hi) >>> 1;
            if (starts[mid].compareTo(value) <= 0) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found >= 0 && ends[found].compareTo(value) >= 0;
    }

    private static BigInteger[][] merge(ArrayList<BigInteger[]> ranges) {
        ranges.sort(Comparator.comparing((BigInteger[] r) -> r[0]));
        ArrayList<BigInteger[]> out = new ArrayList<>();
        for (BigInteger[] r : ranges) {
            if (!out.isEmpty()) {
                BigInteger[] last = out.get(out.size() - 1);
                if (r[0].compareTo(last[1].add(BigInteger.ONE)) <= 0) {
                    if (r[1].compareTo(last[1]) > 0) last[1] = r[1];
                    continue;
                }
            }
            out.add(new BigInteger[]{r[0], r[1]});
        }
        BigInteger[] starts = new BigInteger[out.size()];
        BigInteger[] ends = new BigInteger[out.size()];
        for (int i = 0; i < out.size(); i++) { starts[i] = out.get(i)[0]; ends[i] = out.get(i)[1]; }
        return new BigInteger[][]{starts, ends};
    }

    private record Parsed(boolean v6, BigInteger value) {}

    private static Parsed parse(String ip) {
        var m = IPV4.matcher(ip);
        if (m.matches()) {
            long value = 0;
            for (int i = 1; i <= 4; i++) {
                int octet = Integer.parseInt(m.group(i));
                if (octet > 255) return null;
                value = (value << 8) | octet;
            }
            return new Parsed(false, BigInteger.valueOf(value));
        }
        if (ip.indexOf(':') < 0 || !IPV6_CHARS.matcher(ip).matches()) return null;
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress(); // literal only: no DNS
            if (bytes.length == 4) return new Parsed(false, new BigInteger(1, bytes)); // IPv4-mapped
            return new Parsed(true, new BigInteger(1, bytes));
        } catch (Exception e) {
            return null;
        }
    }

    @Override public String toString() { return "IpRanges[v4=" + v4Starts.length + ", v6=" + v6Starts.length + "]"; }
    @Override public boolean equals(Object o) { return o instanceof IpRanges r && Arrays.equals(v4Starts, r.v4Starts) && Arrays.equals(v6Starts, r.v6Starts); }
    @Override public int hashCode() { return Arrays.hashCode(v4Starts) * 31 + Arrays.hashCode(v6Starts); }
}
