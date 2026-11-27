# Chato

- Use the terms in [docs/GLOSSARY.md](docs/GLOSSARY.md) for UI text, docs, code comments and commit messages. A new feature, key or screen gets a glossary row in the same change.
- User-visible labels live in `app/src/main/res/values/strings.xml`, not in Java.
- Code goes in the package of its feature, inside the boundaries in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). A new feature gets its own package there.
- Run `make check` before calling a change done. When a check fails, fix the cause; don't suppress it or explain it away in a comment. When you are corrected on something a check could catch, add the check.
