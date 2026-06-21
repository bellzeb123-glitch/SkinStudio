package pl.skinstudio.util;

import java.util.ArrayList;
import java.util.List;

public record NormalizeReport(int changed, List<String> errors, List<String> pendingFiles) {

    public static final NormalizeReport ERROR = new NormalizeReport(-1, List.of(), List.of());

    public boolean isError() {
        return changed < 0;
    }

    public static NormalizeReport empty() {
        return new NormalizeReport(0, List.of(), List.of());
    }

    public static NormalizeReport of(int changed, List<String> errors, List<String> pending) {
        return new NormalizeReport(changed, List.copyOf(errors), List.copyOf(pending));
    }

    public NormalizeReport withAppliedPending(int extra) {
        return new NormalizeReport(changed + extra, errors, pendingFiles);
    }
}
