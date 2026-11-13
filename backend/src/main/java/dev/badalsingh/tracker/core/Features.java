package dev.badalsingh.tracker.core;

/**
 * Non-identifying facts about one tracking request. These (and only these) are persisted; the raw
 * User-Agent and IP address are discarded after extraction.
 */
public record Features(Kind kind, String method, String uaClass, String ipClass, long offsetSeconds) {
    public enum Kind { PIXEL, CLICK }

    // uaClass values
    public static final String UA_GMAIL_PROXY = "GMAIL_PROXY";
    public static final String UA_YAHOO_PROXY = "YAHOO_PROXY";
    public static final String UA_OUTLOOK = "OUTLOOK_CLIENT";
    public static final String UA_SCANNER = "SCANNER";
    public static final String UA_OTHER = "OTHER";
    public static final String UA_NONE = "NONE";

    // ipClass values
    public static final String IP_APPLE = "APPLE_RELAY";
    public static final String IP_GOOGLE = "GOOGLE";
    public static final String IP_OTHER = "OTHER";      // lists loaded and the address is in none of them
    public static final String IP_UNCHECKED = "UNCHECKED"; // Google list unavailable, so no judgement possible
    public static final String IP_NONE = "NONE";
}
