package pl.skinstudio.delivery;

/**
 * Abstrakcja dostawy resource packa do graczy. Pozwala SkinStudio działać
 * z ResourcePackManager (gdy jest) lub samodzielnie (self-host + Paper push),
 * ewentualnie eksportować pack do innego mergera.
 */
public interface PackDeliveryProvider {

    /** Nazwa providera (do logów / statusu). */
    String name();

    /** Czy provider jest użyteczny w tym środowisku (np. RPM zainstalowany). */
    boolean available();

    /**
     * Wykonuje dostawę zbudowanego {@code pack.zip}. Boot-grace i hash-guard
     * są obsługiwane wyżej przez {@code PackDelivery} — tu jest surowa dostawa.
     *
     * @param force {@code true} = jawna akcja admina (np. wymuś required).
     */
    void deliver(boolean force);

    /** Start zasobów providera (np. serwer HTTP). Domyślnie nic. */
    default void start() {}

    /** Zwolnienie zasobów przy wyłączaniu pluginu. Domyślnie nic. */
    default void shutdown() {}
}
