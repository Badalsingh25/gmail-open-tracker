package dev.badalsingh.tracker.core;

/** Per-recipient verdict labels, from strongest to weakest evidence. */
public final class Status {
    private Status() {}
    public static final String ENGAGED = "ENGAGED";               // a link was clicked by something that looks human
    public static final String OPENED = "OPENED";                 // strong open evidence
    public static final String POSSIBLY_OPENED = "POSSIBLY_OPENED";
    public static final String AUTOMATED_ONLY = "AUTOMATED_ONLY"; // requests seen, all look automated
    public static final String NO_ACTIVITY = "NO_ACTIVITY";

    public static int rank(String status) {
        if (status == null) return 0;
        return switch (status) {
            case ENGAGED -> 4;
            case OPENED -> 3;
            case POSSIBLY_OPENED -> 2;
            case AUTOMATED_ONLY -> 1;
            default -> 0;
        };
    }
}
