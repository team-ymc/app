# Feature demo films

These are **optional offline editing tools**, not frontend dependencies. The
website serves the checked-in H.264 MP4s and JPEG posters; npm/build/CI never run
Python or Swift. No package or lockfile changes are needed.

## Source material

`feature-captures/` contains real reader screenshots, captured through browser
interaction. All examples use *Attention Is All You Need*. The quote, figure,
prerequisite, translation and bookmap chapters come from the supplied live paper.
The table chapter uses the existing local preview reader, captured with a
temporary correction to the atomic-selection/text-toolbar conflict. That reader
correction has been reverted and is not included in the feature-page changes.
The recording shows actual block attachment, not a painted chip. Questions are
drafts only: no model replies are fabricated or sent.

Typing frames capture successive 2–3-character increments. Editing adds only
camera crops, eased zoom/pan, and cursor movement/click rings. There are no added
caption strips or progress bars. The five loops are silent, 24 fps, 1224 × 784
pixels. Only the framed animation is visible; click or use the keyboard to pause
and resume. Reduced-motion preferences disable automatic playback.

## Regenerate (macOS)

Use an existing Python environment with Pillow and the system Swift compiler.
These tools are intentionally separate from the app's package setup.

```sh
swiftc -O -module-cache-path /tmp/feature-swift-cache \
  fe/scripts/encode-feature-video.swift -o /tmp/feature-encoder
python3 fe/scripts/create-feature-demos.py --encoder /tmp/feature-encoder
```

`--only chat` regenerates one film. Contact sheets are written to
`/tmp/paperteacher-storyboards` for visual QA. Outputs are staged in a fresh
temporary directory before replacing their corresponding generated asset.

The system H.264 encoder may be unavailable inside an execution sandbox because
it requires the macOS media service. Run this optional editing command with
access to that service; no encoder package needs to be installed.
