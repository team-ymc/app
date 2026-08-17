# Paper Teacher Design System

Paper Teacher is a bilingual (Korean/English) academic reading workspace for university students. A student uploads a PDF, reads its parsed content as a clean digital paper, and studies it alongside a teaching agent that explains sections, asks guided questions, and translates selected passages. The product is a responsive website (bookshelf + reading workspace), not a native app.

The intended feel: a quiet classical university library — warm parchment, deep walnut framing, navy-ink interactions, serif reading typography. Restrained, not theatrical: no fake leather, wax seals, page-turn effects, or decorative historical pastiche. The parsed paper is always the hero; the AI tutor behaves like a patient scholar's notebook, never a conventional chat bubble UI.

## Source

This system was authored entirely from a single design brief: `uploads/DESIGN.md` (a structured brand/UI spec — front-matter tokens + prose guidelines). No codebase, Figma file, or slide deck was attached. If any of those exist for Paper Teacher, attach them and this system should be reconciled against that ground truth — right now every visual decision traces back to that one document.

## Fonts — flag for follow-up

`Noto Serif KR` and `Pretendard Variable` are loaded live from Google Fonts and jsdelivr CDNs (see `tokens/fonts.css`) — no local font files were provided. This works, but is a dependency on those CDNs staying up. **Ask:** if you have the licensed font files, upload them and we'll self-host `@font-face` instead.

## Iconography

No icon set was provided. The brief asks for "fine 1.5px line icons with simple geometry and slightly softened corners" — this is served by **Phosphor Icons, Regular weight**, loaded from CDN (`unpkg.com/@phosphor-icons/web`) and used throughout components and UI kit screens. This is a substitution — flag it if the product has its own icon set. No emoji are used anywhere in the UI per the brief ("no slang, emojis, or a childish teaching voice").

No logo file was provided either. The brief specifies a **paper-stack mark** in prose (three offset paper sheets, engraved outline, optional folded corner) — this is built as the `PaperStackMark` component from that description, not copied from an existing asset. There is no separate wordmark logotype; the brand name sets in Noto Serif KR wherever a wordmark is needed.

## Content fundamentals

- **Voice:** warm, composed, quietly scholarly. Serious but not intimidating; traditional in atmosphere but efficient as a modern product. No slang, no exclamation-heavy enthusiasm, no childish tutoring voice.
- **Korean and English are equals.** Never shrink, italicize, or otherwise visually demote English relative to Korean (or vice versa) — both set in the same serif at the same weight for reading contexts.
- **Sentence case throughout.** Avoid all-caps in English except very short archival-style labels (e.g. a caption tag).
- **The one piece of marketing copy that exists** is the landing value statement, in Korean: "논문을 이해하도록 가르치는 AI 리딩 튜터" ("An AI reading tutor that teaches you to understand the paper"). There is no other marketing copy — no feature grid, no testimonials, no pricing language — by design.
- **Tutor voice structure:** responses may use "Key idea," "Example," and "Check your understanding" as section labels — a notebook, not a chatty assistant.
- **No emoji, ever.** No gamification language either (no "streak," "XP," "level up," badges-as-copy).
- **Progress language is understated:** "reading position," "sections understood," continuity — never leaderboard or points language.

## Visual foundations

**Color.** Navy Ink (`--color-primary`, `#1F3552`) is the only interactive/brand color — buttons, links, selection, focus. Deep Walnut (`--color-bg-walnut`) is reserved for the landing page and the authenticated global nav bar; Parchment is the ambient workspace canvas; Reading Paper (near-white, warm) is the surface of the document itself and of the tutor notebook. Antique Brass is a rare decorative accent (fine dividers, small markers) — never body text or large fills. Sage green = success/complete; muted red = errors/destructive only, always paired with text or an icon, never color alone. Night Study Mode swaps canvas/paper/ink for deep navy-charcoal / `night-paper` / warm `night-ink` — never a pure black-and-white invert.

**Type.** Two families: **Noto Serif KR** for anything meant to be *read* — logo wordmark, landing statement, headings, paper titles, document body, tutor headings — at a generous 1.8 line-height for long-form body text. **Pretendard** for UI chrome — nav, controls, inputs, labels, metadata, chat input. Scale runs from `hero-display` (56px/600) down to `label` (12px/600, wide tracking, for archival-style tags). Sentence case; avoid ultra-light serif weights.

**Spacing.** 4px base scale, `--space-xxs` (4px) through `--space-section` (80px). Reading areas get generously more space than controls; the paper and tutor responses should never feel crowded even as list rows stay compact.

**Backgrounds.** No imagery-heavy backgrounds, no gradients, no repeating textures/patterns. The landing screen is a single flat Deep Walnut field, optionally an *extremely* subtle paper grain / soft warm light pool — subtle enough to read as flat, not as a visible texture. No hero photography, no illustrations beyond sparse single-color academic linework for empty states (papers, lamps, notes, desks — never cartoons, 3D, mascots, or "AI sparkle" motifs).

**Motion.** Quiet and functional only. Panel transitions ~200ms; paper/tutor context changes crossfade gently; a selected text passage briefly washes with Navy Ink then settles; buttons **darken**, they never scale/bounce. Respect `prefers-reduced-motion` and drop everything nonessential.

**Hover / press states.** Hover darkens the fill (primary buttons) or strengthens a border (secondary buttons) — never a lighten-on-hover or shadow-pop. No scale-up press effects; state changes are color/border only. Focus-visible is a clear Navy Ink outline with real offset (not a glow).

**Borders & shadows.** Structural panels (nav, rails, columns) rely on borders and surface-color contrast — not shadows. The document/paper sheet gets exactly one soft diffuse shadow (`--shadow-paper`) to lift it off the parchment. Menus/dialogs get a smaller shadow plus a fine 1px border (`--shadow-menu`). No glassmorphism, no glow, no glossy gradients, no floating decorative blobs. No blur effects anywhere in the spec.

**Corner radii.** Documents & structural panels: 0–4px (`--radius-none`/`--radius-structural`). Inputs, menus, student message blocks: 8–10px (`--radius-control`/`--radius-message`). Primary buttons & teaching-action chips: full pill (`--radius-pill`). Icon-only/profile controls: circular. Never wrap every region in one big rounded card.

**Cards.** There is no generic "Card" primitive in this system — the brief defines purpose-specific surfaces instead: `PaperSheet` (the document itself, 2px radius, one soft shadow), `ArchivalFolio` (bookshelf grid item — thumbnail + metadata, warm surface, 4px radius, border only, no shadow), and `TutorNotebook` (open note sections, not enclosing chat bubbles). Build against these, not a generic card.

**Imagery.** Paper thumbnails always use the real first page of the source document mounted in an archival folio frame — never generated cover art. No photography elsewhere in the product.

## Components

Standard set authored to the brief's own named components (no codebase/Figma inventory existed to enumerate against). Grouped by concern under `components/`:

- **core/** — `Button` (primary / secondary / landing / text variants), `IconButton`
- **forms/** — `Input`, `Select`, `Checkbox`, `Switch`
- **feedback/** — `Badge`, `Tooltip`, `Toast`
- **navigation/** — `GlobalNav`, `Tabs`
- **overlays/** — `Dialog`
- **study/** — `PaperSheet`, `ArchivalFolio`, `TutorNotebook`, `StudentMessage`, `TeachingAction`, `SelectionToolbar`
- **brand/** — `PaperStackMark`

### Intentional additions
Not named explicitly in the brief but needed for a working set: `Select`, `Checkbox`, `Switch`, `Tooltip`, `Toast`, `Tabs`, `Dialog` — conventional form/overlay primitives sized down to this brand's quiet, low-chroma treatment (borders + Navy Ink, no shadows-as-default).

## UI kit

`ui_kits/paper-teacher/` — one interactive recreation covering the three screens the brief defines: **Landing** (single-viewport walnut entrance), **Bookshelf** (list/grid of papers, search, upload), **Study** (contents rail + paper viewer + tutor notebook, with a working text-selection toolbar and Night Study Mode toggle). Click through from the landing CTA into the bookshelf into a paper.

## Index

- `styles.css` — import list, link this one file
- `tokens/` — colors, typography, spacing, radius, shadows, fonts
- `components/<group>/<Name>.{jsx,d.ts,prompt.md}` + one `*.card.html` per directory
- `ui_kits/paper-teacher/` — index.html + screen JSX
- `guidelines/` — foundation specimen cards (Colors, Type, Spacing, Radius, Shadow, Brand)
- `assets/` — none beyond the built `PaperStackMark` component (no source logo/icon files were provided)
- `SKILL.md` — portable skill definition for use outside this environment
