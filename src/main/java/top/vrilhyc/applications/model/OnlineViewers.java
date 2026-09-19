package top.vrilhyc.applications.model;

import java.util.List;

/** Platform-provided public ranking; not a complete census of viewers. */
public record OnlineViewers(long count, List<Viewer> viewers) {
    public OnlineViewers { viewers = List.copyOf(viewers.stream().limit(50).toList()); }
    public record Viewer(String name, int rank, int gloryLevel) {}
}
