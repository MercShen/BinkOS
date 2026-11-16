# BinkOS documentation

Index for contributors and maintainers. Product-facing copy rules live in
[terminology.md](terminology.md) — **read it first if you are translating the app or changing
user-visible strings**, including as an AI agent.

| Document | What it covers |
|---|---|
| [terminology.md](terminology.md) | Canonical English/Simplified-Chinese terms, do-not-use list, style rules, placeholder/XML rules and the translation workflow. Source of truth for all UI copy. |
| [automation-entry.md](automation-entry.md) | `--es dest` automation contract: supported destinations, parameters, result JSON and testing boundaries. |
| [testing-playbook.md](testing-playbook.md) | Manual QA checklists for home, widgets, fonts, paging, notifications and backups. |
| [ui-scroll-vs-paged.md](ui-scroll-vs-paged.md) | Which surfaces page and which intentionally scroll, with the reasons. |
| [ui-search-fields.md](ui-search-fields.md) | Inventory of in-app search fields and their behavior. |

## Translating

1. Read [terminology.md](terminology.md).
2. Edit both `app/src/main/res/values/strings.xml` (English) and
   `app/src/main/res/values-zh/strings.xml` (Simplified Chinese), keeping keys and placeholders.
3. Run `python tools/check-locales.py`.
4. Build and check the affected screen at small and `Extra Large` UI scale.

Only values are translated; resource keys, JSON `type` values, enum names and file names stay
untouched.
