# GHOST Protocol — Branding & Visual Identity

Official brand assets and visual identity guidelines for the GHOST Protocol project.

## Brand Assets

| File | Resolution | Format | Purpose / Usage |
|---|---|---|---|
| [`icon-512.png`](icon-512.png) | 512 × 512 | PNG (32-bit RGBA) | Android launcher icon, app store icon, square avatars |
| [`logo.png`](logo.png) | 512 × 512 | PNG (32-bit RGBA) | Repository header logo (scaled to 120px in `README.md`) |
| [`logo-wordmark.png`](logo-wordmark.png) | 800 × 200 | PNG (32-bit RGBA) | Horizontal header logo (light text) for dark mode web pages, GitHub dark, and decks |
| [`logo-wordmark-dark.png`](logo-wordmark-dark.png) | 800 × 200 | PNG (32-bit RGBA) | Horizontal header logo (dark text) for light mode websites, white documents, and light themes |
| [`social-preview.png`](social-preview.png) | 1280 × 640 | PNG (RGB) | GitHub Social Preview OpenGraph card (Twitter/X, LinkedIn, Discord previews) |
| [`favicon.png`](favicon.png) | 32 × 32 | PNG (32-bit RGBA) | Browser favicon and taskbar icon |
| [`logo.svg`](logo.svg) | Vector | Scalable SVG | Infinite-resolution vector asset for print, web, and custom sizing |

## Brand Concept

The GHOST logo combines two core ideas:
1. **Mesh Network Topology:** Three connected nodes forming an ad-hoc mesh triad, signifying peer-to-peer relay and decentralization.
2. **The Ghost Trajectory:** An ethereal, glowing tail flowing downward from the primary node through the network, representing encrypted messages slipping through infrastructure-denied environments without detection ("*Messages that find their way*").

## Color Palette

| Name | Hex | RGB | Usage |
|---|---|---|---|
| **Ghost Light** | `#C77DFF` | `rgb(199, 125, 255)` | Node glow, highlights, accents |
| **Ghost Primary** | `#9D4EDD` | `rgb(157, 78, 221)` | Core brand purple, link lines, active states |
| **Ghost Purple** | `#7B2CBF` | `rgb(123, 44, 191)` | Node body, gradients |
| **Ghost Deep** | `#4C1D95` | `rgb(76, 29, 149)` | Gradient depth, dark theme secondary |
| **Space Void** | `#0F0F1A` | `rgb(15, 15, 26)` | Social preview & app dark background |
| **Slate Gray** | `#94A3B8` | `rgb(148, 163, 184)` | Secondary copy, technical labels |

## Asset Generation

All raster assets are programmatically reproducible via Pillow at 4× supersampling:
```bash
python3 docs/assets/generate_assets.py
```
