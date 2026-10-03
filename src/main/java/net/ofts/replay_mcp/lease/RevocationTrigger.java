package net.ofts.replay_mcp.lease;

/** Input metadata only: never captures typed text, screen contents or lease tokens. */
public record RevocationTrigger(String source, String control, Integer key_code,
                                Integer scan_code, Integer modifiers, String screen) {
    public static RevocationTrigger command(String source, String control, String screen) {
        return new RevocationTrigger(source, control, null, null, null, screen);
    }
}
