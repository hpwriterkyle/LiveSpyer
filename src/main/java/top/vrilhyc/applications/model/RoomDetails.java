package top.vrilhyc.applications.model;

import java.time.Instant;

/** Public room metadata, fetched independently of playback and account presence. */
public record RoomDetails(LiveRoom room, String anchorId, Instant startedAt) {}
