# GHOST Protocol — Brand Identity & Design System Specification

Official engineering brand specification and visual design system for the GHOST Protocol project.

The current GHOST Protocol mark is the canonical production identity.

The SVG master is authoritative.
All raster and platform-specific assets are derivatives.

---

## 1. Brand Concept & Positioning

**Product:** GHOST Protocol
**Positioning:** Offline encrypted mesh messenger for Android. Zero servers. Zero accounts. Battery-aware delay-tolerant routing.

The brand identity conveys:
- **Privacy infrastructure:** Sovereign, low-observability communications operating under adversarial conditions.
- **Resilient systems:** Peer-to-peer store-and-forward mesh routing without centralized choke points.
- **Precision engineering:** Mathematical balance, clean geometry, zero ornamental noise.

The identity explicitly rejects generic Web3/crypto, gaming neon, matrix cyberpunk, and playful cartoon aesthetics.

---

## 2. Master Mark Geometry

The GHOST master mark integrates three fundamental concepts into a single vector geometry:
1. **Three-Node Topology:** Three circular communication nodes (Apex origin node, Port relay node, Starboard relay node) forming an engineered triad.
2. **Ghost Trajectory:** An aerodynamic, stealth mantle emerging from the apex and tapering downward along the central axis, signifying encrypted packets slipping through hostile environments without centralized observability.
3. **Encrypted Routing Spine:** A dual-tone central transmission vector descending through the network nexus, representing deterministic delay-tolerant packet delivery.
4. **Negative-Space Trench:** A 6px perimeter knockout trench around the foreground network lines and nodes, ensuring optical separation and contrast across any background without color dependency.

```text
               (256, 110)
                 [Apex]
                   /\
                  /  \
                 / || \   <-- Aerodynamic Ghost Shroud
                /  ||  \
               /   ||   \
  (130, 335)  o====()====o  (382, 335)
    [Port]     \   ||   /     [Starboard]
                \  ||  /
                 \ || /
                  \|/
               (256, 425)
                 [Tail]
```

### Mathematical Metrics (512 × 512 Canvas)

| Geometric Element | Coordinates / Dimensions | Visual Weight / Radius |
|---|---|---|
| **Canvas ViewBox** | `0 0 512 512` | Centered at (256, 256) |
| **Apex Node** | `cx="256" cy="110"` | `r=26` (stroke: 4px `#4C1D95`, fill: `#FFFFFF`) |
| **Port Relay Node** | `cx="130" cy="335"` | `r=22` (stroke: 3.5px `#4C1D95`, fill: `#FFFFFF`) |
| **Starboard Relay Node** | `cx="382" cy="335"` | `r=22` (stroke: 3.5px `#4C1D95`, fill: `#FFFFFF`) |
| **Central Routing Nexus** | `cx="256" cy="335"` | `r=13` (stroke: 4px `#FFFFFF`, fill: `#4C1D95`) |
| **Mesh Connection Edges** | `(256,110) - (130,335) - (382,335)` | Stroke: 20px `#C77DFF` |
| **Central Transmission Spine**| `(256,110) -> (256,335)` | Dual-core: 16px `#C77DFF` outer, 8px `#FFFFFF` core |
| **Stealth Shroud Contour** | Symmetrical cubic Bézier envelope | Height: 315px, Y: 110 to 425 |
| **Knockout Trenches** | SVG `<mask id="ghostTrenchMask">` | 32px stroke trench, 34px node cutouts |

---

## 3. Color System

The color system maintains compatibility with established GHOST Protocol colors while enforcing strict hierarchy:

| Token | Hex | RGB | Semantic Role |
|---|---|---|---|
| **Ghost Primary** | `#9D4EDD` | `rgb(157, 78, 221)` | Core stealth shroud, primary brand mark accent |
| **Ghost Light** | `#C77DFF` | `rgb(199, 125, 255)` | Mesh connection edges, transmission spine, subtitle typography |
| **Ghost Deep** | `#4C1D95` | `rgb(76, 29, 149)` | Central routing nexus core, node boundary stroke, dark depth |
| **Space Void** | `#0F0F1A` | `rgb(15, 15, 26)` | Dark theme surface, launcher background, card backgrounds |
| **Pure White** | `#FFFFFF` | `rgb(255, 255, 255)` | Primary node interiors, dark mode header typography, signal core |
| **Deep Charcoal** | `#1A1829` | `rgb(26, 24, 41)` | Secondary dark borders, subtle dividers |
| **Muted Slate** | `#9490B8` | `rgb(148, 144, 184)` | Technical descriptor typography, secondary documentation |

---

## 4. Typography

Wordmarks and documentation headings use clean modern geometric grotesk typography:

- **Primary Font Family:** `Red Hat Display` (Open-source geometric sans designed for modern infrastructure)
- **Fallback Font Family:** `Montserrat`, `system-ui`, `-apple-system`, `sans-serif`
- **Technical/Body Font Family:** `Red Hat Text`

### Wordmark Hierarchy

| Element | Font Weight | Relative Size | Letter Spacing | Color (Dark Theme) | Color (Light Theme) |
|---|---|---|---|---|---|
| **"GHOST"** | Black / 800 | 68pt (1.00×) | `0.06em` | `#FFFFFF` | `#0F0F1A` |
| **"PROTOCOL"**| SemiBold / 600| 24pt (0.35×) | `0.32em` | `#C77DFF` | `#7B2CBF` |

---

## 5. Lockups & Variants

```text
A. Primary Horizontal Lockup
   [ GHOST MARK ]   GHOST
                    P R O T O C O L

B. Stacked Vertical Lockup
         [ MARK ]
          GHOST
      P R O T O C O L

C. Symbol Only
   [ GHOST MARK ]
   Used for: Android launcher, browser favicon, app avatar, BLE UI indicators.
```

### Color Variations

1. **Universal / Default:** Ghost Primary shroud (`#9D4EDD`), Ghost Light mesh (`#C77DFF`), White nodes with Deep Purple rims (`#4C1D95`). Legible across any surface.
2. **Dark Theme:** Optimized for dark backgrounds (`#0F0F1A`), pure white node interiors and header typography.
3. **Light Theme:** Optimized for light surfaces (white documents, light web themes). Near-black typography (`#0F0F1A`) and purple accents.
4. **Monochrome Dark:** Pure `#FFFFFF` mark with negative-space knockout trenches on dark backgrounds.
5. **Monochrome Light:** Pure `#0F0F1A` mark with white knockout trenches on white paper, laser engraving, or single-color print.
6. **Single-Color Purple:** Entire geometry rendered in solid `#9D4EDD`.

---

## 6. Clear Space & Minimum Sizing

### Clear Space
The minimum clear space surrounding the mark is defined as **0.5 × Node Radius (r/2 = 13px on a 512px canvas)**. No foreign graphics, typography, or page borders may encroach into this exclusion zone.

### Minimum Sizes
- **Digital Screen (Vector/Raster):** 16 × 16 px (use simplified high-contrast favicon treatment)
- **Android App Launcher:** 48 × 48 dp (legacy), 108 × 108 dp (adaptive)
- **Print / Physical Media:** 6 mm (0.24 in) width

---

## 7. Anti-Patterns (Incorrect Usage)

To maintain enterprise integrity, avoid:
- **No Neon Blooms or Glows:** Do NOT add Gaussian blurs, glowing halos, or outer glow filters.
- **No Organic / Wavy Tails:** Do NOT distort the geometric stealth shroud into an amorphous snake or worm.
- **No Decorative Concentric Rings:** Do NOT add multiple rings or target graphics inside nodes.
- **No Non-Standard Gradients:** Do NOT apply rainbow, linear metallic, or chrome gradients.
- **No Skew or Rotation:** Do NOT rotate the mark off its vertical axis of symmetry.
- **No Arbitrary Color Shifts:** Do NOT render the brand in unapproved palettes (e.g. green, orange, cyan).

---

## 8. Asset Inventory

All assets are programmatically derived from `docs/assets/logo.svg`:

| File | Dimensions | Format | Color Space | Primary Context |
|---|---|---|---|---|
| [`logo.svg`](logo.svg) | Vector | SVG | Scalable RGBA | Authoritative master vector asset |
| [`logo.png`](logo.png) | 512 × 512 | PNG | 32-bit RGBA | Repository README header & documentation |
| [`icon-512.png`](icon-512.png) | 512 × 512 | PNG | 32-bit RGBA | App distribution, avatars, store listings |
| [`favicon.png`](favicon.png) | 32 × 32 | PNG | 32-bit RGBA | Browser tab favicon, taskbar, bookmarks |
| [`logo-wordmark.png`](logo-wordmark.png) | 800 × 200 | PNG | 32-bit RGBA | Horizontal header (white text) for dark mode |
| [`logo-wordmark-dark.png`](logo-wordmark-dark.png) | 800 × 200 | PNG | 32-bit RGBA | Horizontal header (dark text) for light mode |
| [`social-preview.png`](social-preview.png) | 1280 × 640 | PNG | 24-bit RGB | GitHub OpenGraph card (social embeds) |

### Android Mipmap Suite (`android/app/src/main/res/`)

| Density | Legacy Icon (`ic_launcher.png`) | Round Icon (`ic_launcher_round.png`) | Adaptive Layers (`background` / `foreground`) |
|---|---|---|---|
| **mdpi** | 48 × 48 px | 48 × 48 px | 108 × 108 px |
| **hdpi** | 72 × 72 px | 72 × 72 px | 162 × 162 px |
| **xhdpi** | 96 × 96 px | 96 × 96 px | 216 × 216 px |
| **xxhdpi** | 144 × 144 px | 144 × 144 px | 324 × 324 px |
| **xxxhdpi** | 192 × 192 px | 192 × 192 px | 432 × 432 px |

---

## 9. Android Adaptive Icon Principles

Android adaptive icons (`mipmap-anydpi-v26/ic_launcher.xml`) decouple the foreground mark from the background:
- **Canvas Size:** 108 × 108 dp
- **Inner Safe Zone:** Central 72 × 72 dp circle (18dp safe margins on all sides)
- **Foreground:** `ic_launcher_foreground.png` contains the master mark optically centered with a vertical height of ~60dp.
- **Background:** `ic_launcher_background.png` renders solid Space Void (`#0F0F1A`).
- **OEM Compatibility:** Compatible with circle, squircle, rounded square, and teardrop clipping masks.

---

## 10. Programmatic Asset Regeneration

To regenerate the entire asset family from the vector master, execute:

```bash
python3 docs/assets/generate_assets.py
```

Dependencies: Python 3, `librsvg` (`rsvg-convert`), Pillow (`PIL`).
