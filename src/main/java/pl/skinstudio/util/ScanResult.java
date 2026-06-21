package pl.skinstudio.util;

/** Wynik {@link ResourcePackScanner#scan()}. */
public record ScanResult(int skinsAdded, int packsNormalized) {

    public static final ScanResult ERROR = new ScanResult(-1, 0);

    public boolean isError() {
        return skinsAdded < 0;
    }
}
