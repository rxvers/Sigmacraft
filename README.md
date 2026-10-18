# SigmaCraftCore 0.3.0 — Paper 26.3

Dieses Projekt enthält die Basisfunktionen für SigmaCraft: gespeicherte Statistiken, Spawn-Schutz und `/spawn`, Teams mit Tags/Farben, Top-10-Textdisplays und den temporären Elytra-Boost.

## Status

**Quellcode.** Die fertige JAR entsteht automatisch über GitHub Actions (Java 25), siehe `INSTALLATION.md`.

## Build auf einem Rechner mit Internet

Voraussetzungen: JDK 25 und Gradle 9.x. Paper dokumentiert die API-Abhängigkeit als `io.papermc.paper:paper-api:26.3.build.+` und Java-Toolchain 25.

1. Gradle installieren und sicherstellen, dass `java -version` Java 25 zeigt.
2. In diesem Ordner ein Terminal öffnen.
3. Ausführen: `gradle clean build`
4. Das Ergebnis liegt in `build/libs/SigmaCraftCore-0.3.0.jar`.
5. Erst auf einem Testserver installieren; vor Änderungen an der Live-Welt ein Backup erstellen.

## Befehle

- `/team create <Tag> <Farbe>` — Team erstellen. Farben: `red`, `blue`, `green`, `yellow`, `aqua`, `purple`, `white`, `gold`, `gray`, `black`.
- `/team invite <Spieler>` — Teammitglied einladen (nur Teamleitung).
- `/team accept`, `/team leave`, `/team info`
- `/sctop create <kills|deaths|diamonds|blocks|playtime>` — Rangliste am eigenen Standort erstellen.
- `/sctop remove` — nächstes Hologramm in der Nähe entfernen.
- `/spawn` — zum konfigurierten Spawn, sofern innerhalb des konfigurierten Radius.
- `/scstats <Spielername>` — maschinenlesbare Statistikzeile für die Bot-Integration (Berechtigung `sigmacraft.stats`).
- `/sigmacraft stat <kills|deaths|blocks|diamonds|playtime|stats> <Spieler>`
- `/sigmacraft leaderboard <kills|deaths|blocks|diamonds|playtime>`

## Konfiguration

`src/main/resources/config.yml` wird beim ersten Start in `plugins/SigmaCraftCore/config.yml` kopiert. Dort Spawn-Welt und Koordinaten, Schutzradius, `/spawn`-Radius und Elytra-Einstellungen setzen.

## Noch vor Live-Einsatz testen

- Build gegen exakt die Paper-26.3-API und Start auf einem Testserver.
- Elytra-Boost in allen Fällen testen (Landen, Tod, Disconnect, volles Inventar/Brustplatten-Slot).
- TextDisplay-Ranglisten nach Neustart und bei entladenen Chunks testen.
- Discord-Bot-RCON-Ausgabe gegen die echte Bot-Implementierung verbinden.
