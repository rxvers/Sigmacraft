# JAR bauen lassen (ohne eigene Entwicklungsumgebung)

1. Kostenloses Konto auf https://github.com anlegen.
2. Neues **privates** Repository "SigmaCraftCore" erstellen.
3. "uploading an existing file" wählen und den **Inhalt** des Ordners SigmaCraftCore hochladen
   (inkl. des versteckten Ordners `.github`) und committen.
4. Reiter **Actions**: der Build "Build SigmaCraftCore" startet automatisch (ca. 2-3 Minuten).
5. Grünes Häkchen -> rechts unter **Releases** (oder im Build unter "Artifacts") die Datei
   `SigmaCraftCore-0.3.0.jar` herunterladen.
6. Auf einem Testserver in `plugins/` legen, Server neu starten, Log prüfen.

Wichtig: Paper 26.3 braucht Java 25 und ist laut Paper noch Beta. Prüfe vorher, ob Nitrado
Paper 26.3 / Java 25 anbietet, sonst lädt das Plugin nicht. Vorher Backup erstellen.
