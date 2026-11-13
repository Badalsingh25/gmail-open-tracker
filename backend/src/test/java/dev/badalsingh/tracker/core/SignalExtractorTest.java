package dev.badalsingh.tracker.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalExtractorTest {
    private static final String GMAIL_UA = "Mozilla/5.0 (Windows NT 5.1; rv:11.0) Gecko Firefox/11.0 (via ggpht.com GoogleImageProxy)";
    private final IpRanges apple = IpRanges.of(List.of("17.0.0.0/8"));
    private final IpRanges google = IpRanges.of(List.of("66.249.64.0/19"));

    @Test
    void classifiesUserAgents() {
        assertEquals(Features.UA_GMAIL_PROXY, SignalExtractor.classifyUserAgent(GMAIL_UA));
        assertEquals(Features.UA_YAHOO_PROXY, SignalExtractor.classifyUserAgent("YahooMailProxy; https://help.yahoo.com/kb/yahoo-mail-proxy-SLN28749.html"));
        assertEquals(Features.UA_SCANNER, SignalExtractor.classifyUserAgent("Mozilla/5.0 Barracuda Sentinel"));
        assertEquals(Features.UA_SCANNER, SignalExtractor.classifyUserAgent("python-requests/2.31"));
        assertEquals(Features.UA_NONE, SignalExtractor.classifyUserAgent(null));
        assertEquals(Features.UA_NONE, SignalExtractor.classifyUserAgent("  "));
        assertEquals(Features.UA_OTHER, SignalExtractor.classifyUserAgent("Mozilla/5.0 (X11; Linux) Thunderbird/115"));
    }

    @Test
    void outlookDesktopIsNotTreatedAsAScanner() {
        assertEquals(Features.UA_OUTLOOK, SignalExtractor.classifyUserAgent("Microsoft Office/16.0 (Windows NT 10.0; Microsoft Outlook 16.0.17126; Pro)"));
    }

    @Test
    void classifiesIpsAgainstLists() {
        SignalExtractor x = new SignalExtractor(() -> apple, () -> google);
        assertEquals(Features.IP_APPLE, x.classifyIp("17.58.1.1"));
        assertEquals(Features.IP_GOOGLE, x.classifyIp("66.249.84.1"));
        assertEquals(Features.IP_OTHER, x.classifyIp("203.0.113.9"));
        assertEquals(Features.IP_NONE, x.classifyIp(null));
    }

    @Test
    void withoutAGoogleListIpIsUncheckedNotOther() {
        SignalExtractor x = new SignalExtractor(() -> apple, () -> IpRanges.EMPTY);
        assertEquals(Features.IP_UNCHECKED, x.classifyIp("203.0.113.9"));
        assertEquals(Features.IP_APPLE, x.classifyIp("17.1.1.1"));
    }

    @Test
    void extractNormalisesMethodAndKeepsOnlyDerivedFields() {
        SignalExtractor x = new SignalExtractor(() -> apple, () -> google);
        Features f = x.extract(Features.Kind.PIXEL, "head", GMAIL_UA, "66.249.84.1", 400);
        assertEquals("HEAD", f.method());
        assertEquals(400, f.offsetSeconds());
        assertEquals(Features.UA_GMAIL_PROXY, f.uaClass());
    }
}
