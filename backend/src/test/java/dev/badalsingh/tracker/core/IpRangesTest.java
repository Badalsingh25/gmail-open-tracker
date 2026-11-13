package dev.badalsingh.tracker.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class IpRangesTest {
    @Test
    void ipv4MembershipAtBoundaries() {
        IpRanges r = IpRanges.of(List.of("17.0.0.0/8", "172.224.226.0/27"));
        assertTrue(r.contains("17.58.12.4"));
        assertTrue(r.contains("17.0.0.0"));
        assertTrue(r.contains("17.255.255.255"));
        assertFalse(r.contains("16.255.255.255"));
        assertFalse(r.contains("18.0.0.0"));
        assertTrue(r.contains("172.224.226.31"));
        assertFalse(r.contains("172.224.226.32"));
        assertFalse(r.contains("117.5.5.5"));
    }

    @Test
    void ipv6MembershipAndMappedIpv4() {
        IpRanges r = IpRanges.of(List.of("2a02:26f7::/32", "17.0.0.0/8"));
        assertTrue(r.contains("2a02:26f7:1:2::5"));
        assertFalse(r.contains("2a02:26f8::1"));
        assertTrue(r.contains("::ffff:17.1.2.3"));
    }

    @Test
    void overlappingAndNestedRangesAreMerged() {
        IpRanges r = IpRanges.of(List.of("10.0.0.0/8", "10.1.0.0/16", "10.200.0.0/16", "11.0.0.0/8"));
        assertEquals(1, r.size());
        assertTrue(r.contains("10.200.1.1"));
        assertTrue(r.contains("11.5.5.5"));
        assertFalse(r.contains("12.0.0.1"));
    }

    @Test
    void garbageAndHostnamesAreIgnoredAndNeverResolved() {
        IpRanges r = IpRanges.of(List.of("not-an-ip", "999.1.1.1/8", "1.2.3.4/99", "localhost", "example.com/24", "", "8.8.8.8/32"));
        assertEquals(1, r.size());
        assertTrue(r.contains("8.8.8.8"));
        assertFalse(r.contains("localhost"));
        assertFalse(r.contains("example.com"));
        assertFalse(r.contains(null));
        assertFalse(r.contains("   "));
    }

    @Test
    void emptySetContainsNothing() {
        assertTrue(IpRanges.EMPTY.isEmpty());
        assertFalse(IpRanges.EMPTY.contains("1.2.3.4"));
    }

    @Test
    void parsesAppleCsvAndGoogleJson() {
        var apple = IpListParser.parseAppleEgressCsv(Stream.of(
                "172.224.226.0/27,GB,GB-ENG,London,", "", "# comment", "2a02:26f7:b3c0::/48,DE,DE-BE,Berlin,", "garbage line"));
        assertEquals(2, apple.size());
        String json = "{\"syncToken\":\"1\",\"prefixes\":[{\"ipv4Prefix\":\"34.80.0.0/15\"},{\"ipv6Prefix\":\"2600:1900::/35\"},{\"ipv4Prefix\" : \"66.249.64.0/19\"}]}";
        var google = IpListParser.parseGoogleJson(json);
        assertEquals(3, google.size());
        IpRanges r = IpRanges.of(google);
        assertTrue(r.contains("66.249.84.1"));
        assertFalse(r.contains("66.250.0.1"));
        assertTrue(IpListParser.parseGoogleJson(null).isEmpty());
    }
}
