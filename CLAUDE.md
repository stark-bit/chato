# Chato

- Use the terms in [docs/GLOSSARY.md](docs/GLOSSARY.md) for UI text, docs and commit messages. A new feature, key or screen gets a glossary row in the same change.
- User-visible labels live in `app/src/main/res/values/strings.xml`, not in Java.
- Code goes in the package of its feature. A new feature gets its own package. `make check` enforces the boundaries (`config/checkstyle/import-control.xml`, `ArchitectureTest`).
- Comments are banned, in code, XML, Gradle and the Makefile. They can't be enforced and go stale. Say it with a name, a test or a check instead. The only exception is a fact about the outside world (Android, a server, a file format, hardware), written as `// External: …` on one line. `make check` enforces this.
- Don't write rules down in docs: put them in a check. A doc that restates the code drifts.
- After changing the keyboard, recording, sending or a screen, also run `make device-check`. It drives a debug copy on the phone against fake servers on this machine. Ask before each run, because it takes over the phone for about 2 minutes. When a check could catch a bug you found on the phone, add it to `tools/devicecheck/check.py`.
- Run `make check` before calling a change done. When a check fails, fix the cause; don't suppress it. When you are corrected on something a check could catch, add the check.
