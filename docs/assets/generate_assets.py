#!/usr/bin/env python3
"""
GHOST Protocol — Canonical Asset Generator
=========================================
Generates the complete enterprise visual identity asset family from the single
authoritative vector master geometry.

Authoritative Color System:
- Ghost Primary: #9D4EDD
- Ghost Light:   #C77DFF
- Ghost Deep:    #4C1D95
- Space Void:    #0F0F1A
- Pure White:    #FFFFFF

Usage:
    python3 docs/assets/generate_assets.py
"""

import os
import subprocess
import tempfile
from PIL import Image

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
ASSETS_DIR = os.path.join(REPO_ROOT, "docs/assets")
RES_DIR = os.path.join(REPO_ROOT, "android/app/src/main/res")


def get_master_mark_svg_content(mode="default", simplified=False):
    """
    Returns the SVG content of the master mark.
    Modes:
      - 'default': Universal contrast (white nodes with #4C1D95 rim, works on light & dark)
      - 'dark': Optimized for dark backgrounds
      - 'light': Optimized for light backgrounds
      - 'mono_dark': Pure white mark
      - 'mono_light': Pure dark (#0F0F1A) mark
      - 'purple': Single-color #9D4EDD
    """
    if mode == "default":
        shroud_col = "#9D4EDD"
        mesh_col = "#C77DFF"
        spine_outer_col = "#C77DFF"
        spine_inner_col = "#FFFFFF"
        node_col = "#FFFFFF"
        node_rim = "#4C1D95"
        nexus_col = "#4C1D95"
        nexus_rim = "#FFFFFF"
    elif mode == "dark":
        shroud_col = "#9D4EDD"
        mesh_col = "#C77DFF"
        spine_outer_col = "#C77DFF"
        spine_inner_col = "#FFFFFF"
        node_col = "#FFFFFF"
        node_rim = "#4C1D95"
        nexus_col = "#4C1D95"
        nexus_rim = "#FFFFFF"
    elif mode == "light":
        shroud_col = "#9D4EDD"
        mesh_col = "#7B2CBF"
        spine_outer_col = "#7B2CBF"
        spine_inner_col = "#0F0F1A"
        node_col = "#0F0F1A"
        node_rim = "#7B2CBF"
        nexus_col = "#C77DFF"
        nexus_rim = "#0F0F1A"
    elif mode == "mono_dark":
        shroud_col = "#FFFFFF"
        mesh_col = "#FFFFFF"
        spine_outer_col = "#FFFFFF"
        spine_inner_col = "#0F0F1A"
        node_col = "#FFFFFF"
        node_rim = "#0F0F1A"
        nexus_col = "#0F0F1A"
        nexus_rim = "#FFFFFF"
    elif mode == "mono_light":
        shroud_col = "#0F0F1A"
        mesh_col = "#0F0F1A"
        spine_outer_col = "#0F0F1A"
        spine_inner_col = "#FFFFFF"
        node_col = "#0F0F1A"
        node_rim = "#FFFFFF"
        nexus_col = "#FFFFFF"
        nexus_rim = "#0F0F1A"
    elif mode == "purple":
        shroud_col = "#9D4EDD"
        mesh_col = "#9D4EDD"
        spine_outer_col = "#9D4EDD"
        spine_inner_col = "#FFFFFF"
        node_col = "#9D4EDD"
        node_rim = "#FFFFFF"
        nexus_col = "#9D4EDD"
        nexus_rim = "#FFFFFF"
    else:
        raise ValueError(f"Unknown mode: {mode}")

    # For tiny sizes (favicons <= 32px), stroke weights and nodes are slightly heavier
    line_w = 26 if simplified else 20
    spine_outer_w = 22 if simplified else 16
    spine_inner_w = 12 if simplified else 8
    trench_w = 40 if simplified else 32
    trench_spine_w = 34 if simplified else 28
    node_apex_r = 32 if simplified else 26
    node_base_r = 26 if simplified else 22
    cutout_apex_r = 42 if simplified else 34
    cutout_base_r = 36 if simplified else 30
    nexus_r = 16 if simplified else 13
    cutout_nexus_r = 24 if simplified else 20

    mask_id = f"ghostMask_{mode}_{'simp' if simplified else 'std'}"

    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="100%" height="100%">
  <defs>
    <mask id="{mask_id}">
      <rect width="512" height="512" fill="#FFFFFF" />
      <g fill="none" stroke="#000000" stroke-width="{trench_w}" stroke-linecap="round" stroke-linejoin="round">
        <line x1="256" y1="110" x2="130" y2="335" />
        <line x1="256" y1="110" x2="382" y2="335" />
        <line x1="130" y1="335" x2="382" y2="335" />
        <line x1="256" y1="110" x2="256" y2="335" stroke-width="{trench_spine_w}" />
      </g>
      <circle cx="256" cy="110" r="{cutout_apex_r}" fill="#000000" />
      <circle cx="130" cy="335" r="{cutout_base_r}" fill="#000000" />
      <circle cx="382" cy="335" r="{cutout_base_r}" fill="#000000" />
      <circle cx="256" cy="335" r="{cutout_nexus_r}" fill="#000000" />
    </mask>
  </defs>

  <g id="ghost-mark">
    <!-- Aerodynamic Ghost Shroud (Tapering Trajectory) -->
    <path d="M 256 110
             C 288 165, 306 225, 292 290
             C 280 340, 268 380, 256 425
             C 244 380, 232 340, 220 290
             C 206 225, 224 165, 256 110 Z"
          fill="{shroud_col}"
          mask="url(#{mask_id})" />

    <!-- Peer-to-Peer Mesh Lines -->
    <g fill="none" stroke="{mesh_col}" stroke-width="{line_w}" stroke-linecap="round" stroke-linejoin="round">
      <line x1="256" y1="110" x2="130" y2="335" />
      <line x1="256" y1="110" x2="382" y2="335" />
      <line x1="130" y1="335" x2="382" y2="335" />
    </g>

    <!-- Central Transmission Spine -->
    <line x1="256" y1="110" x2="256" y2="335" stroke="{spine_outer_col}" stroke-width="{spine_outer_w}" stroke-linecap="round" />
    <line x1="256" y1="110" x2="256" y2="335" stroke="{spine_inner_col}" stroke-width="{spine_inner_w}" stroke-linecap="round" />

    <!-- Three Solid Peer Nodes -->
    <circle cx="256" cy="110" r="{node_apex_r}" fill="{node_col}" stroke="{node_rim}" stroke-width="4" />
    <circle cx="130" cy="335" r="{node_base_r}" fill="{node_col}" stroke="{node_rim}" stroke-width="3.5" />
    <circle cx="382" cy="335" r="{node_base_r}" fill="{node_col}" stroke="{node_rim}" stroke-width="3.5" />

    <!-- Central Routing Nexus -->
    <circle cx="256" cy="335" r="{nexus_r}" fill="{nexus_col}" stroke="{nexus_rim}" stroke-width="4" />
  </g>
</svg>"""
    return svg


def render_svg_to_png(svg_content, out_png_path, width, height):
    """Renders SVG content directly to PNG using rsvg-convert."""
    with tempfile.NamedTemporaryFile(suffix=".svg", mode="w", delete=False) as f:
        f.write(svg_content)
        temp_svg = f.name
    try:
        os.makedirs(os.path.dirname(out_png_path), exist_ok=True)
        subprocess.run(
            ["rsvg-convert", "-w", str(width), "-h", str(height), temp_svg, "-o", out_png_path],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
    finally:
        if os.path.exists(temp_svg):
            os.remove(temp_svg)


def generate_core_assets():
    """Generates logo.png, icon-512.png, and favicon.png."""
    print("Generating core icons...")
    # Canonical universal contrast mark
    svg_default = get_master_mark_svg_content("default")

    # 1. logo.png (512x512)
    logo_path = os.path.join(ASSETS_DIR, "logo.png")
    render_svg_to_png(svg_default, logo_path, 512, 512)
    print(f"  ✓ {logo_path} (512x512)")

    # 2. icon-512.png (512x512)
    icon_path = os.path.join(ASSETS_DIR, "icon-512.png")
    render_svg_to_png(svg_default, icon_path, 512, 512)
    print(f"  ✓ {icon_path} (512x512)")

    # 3. favicon.png (32x32, optimized for pixel clarity)
    fav_svg = get_master_mark_svg_content("default", simplified=True)
    fav_path = os.path.join(ASSETS_DIR, "favicon.png")
    render_svg_to_png(fav_svg, fav_path, 32, 32)
    print(f"  ✓ {fav_path} (32x32)")


def generate_wordmarks():
    """Generates logo-wordmark.png and logo-wordmark-dark.png (800x200 RGBA)."""
    print("Generating wordmarks...")
    mark_dark_inner = get_master_mark_svg_content("dark")
    mark_light_inner = get_master_mark_svg_content("light")

    # Dark mode wordmark (light text for dark backgrounds)
    svg_wm_dark = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 200" width="800" height="200">
  <g transform="translate(35, 30) scale(0.2734)">
    {mark_dark_inner[mark_dark_inner.find('<defs>'):mark_dark_inner.rfind('</svg>')]}
  </g>
  <text x="210" y="108" font-family="Red Hat Display, Montserrat, system-ui, sans-serif" font-size="68" font-weight="800" fill="#FFFFFF" letter-spacing="0.06em">GHOST</text>
  <text x="212" y="146" font-family="Red Hat Display, Montserrat, system-ui, sans-serif" font-size="24" font-weight="600" fill="#C77DFF" letter-spacing="0.32em">PROTOCOL</text>
</svg>"""

    # Light mode wordmark (dark text for light backgrounds)
    svg_wm_light = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 200" width="800" height="200">
  <g transform="translate(35, 30) scale(0.2734)">
    {mark_light_inner[mark_light_inner.find('<defs>'):mark_light_inner.rfind('</svg>')]}
  </g>
  <text x="210" y="108" font-family="Red Hat Display, Montserrat, system-ui, sans-serif" font-size="68" font-weight="800" fill="#0F0F1A" letter-spacing="0.06em">GHOST</text>
  <text x="212" y="146" font-family="Red Hat Display, Montserrat, system-ui, sans-serif" font-size="24" font-weight="600" fill="#7B2CBF" letter-spacing="0.32em">PROTOCOL</text>
</svg>"""

    wm_dark_path = os.path.join(ASSETS_DIR, "logo-wordmark.png")
    render_svg_to_png(svg_wm_dark, wm_dark_path, 800, 200)
    print(f"  ✓ {wm_dark_path} (800x200)")

    wm_light_path = os.path.join(ASSETS_DIR, "logo-wordmark-dark.png")
    render_svg_to_png(svg_wm_light, wm_light_path, 800, 200)
    print(f"  ✓ {wm_light_path} (800x200)")


def generate_social_preview():
    """Generates the 1280x640 GitHub OpenGraph social preview card."""
    print("Generating social preview card...")
    mark_svg = get_master_mark_svg_content("default")

    svg_sp = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 640" width="1280" height="640">
  <rect width="1280" height="640" fill="#0F0F1A" />
  <line x1="140" y1="520" x2="1140" y2="520" stroke="#1F1D36" stroke-width="1" />

  <!-- Master Mark -->
  <g transform="translate(542, 70) scale(0.38)">
    {mark_svg[mark_svg.find('<defs>'):mark_svg.rfind('</svg>')]}
  </g>

  <!-- Typography Hierarchy -->
  <text x="640" y="300" font-family="Red Hat Display, Montserrat, system-ui, sans-serif" font-size="46" font-weight="800" fill="#FFFFFF" text-anchor="middle" letter-spacing="0.08em">GHOST PROTOCOL</text>
  <text x="640" y="356" font-family="Red Hat Display, Montserrat, system-ui, sans-serif" font-size="22" font-weight="500" fill="#C77DFF" text-anchor="middle" letter-spacing="0.04em">Messages that find their way.</text>

  <text x="640" y="410" font-family="Red Hat Text, Montserrat, system-ui, sans-serif" font-size="16" font-weight="400" fill="#9490B8" text-anchor="middle" letter-spacing="0.02em">Offline encrypted mesh messenger for Android. Zero servers. Zero accounts.</text>
  <text x="640" y="438" font-family="Red Hat Text, Montserrat, system-ui, sans-serif" font-size="14" font-weight="400" fill="#6B6790" text-anchor="middle" letter-spacing="0.05em">BATTERY-AWARE DELAY-TOLERANT ROUTING  ·  BLE 5.0  ·  END-TO-END ENCRYPTED</text>

  <!-- Technical Stack Badges -->
  <text x="640" y="556" font-family="Red Hat Text, Montserrat, system-ui, monospace" font-size="13" font-weight="500" fill="#5A567E" text-anchor="middle" letter-spacing="0.15em">KOTLIN  |  GO ROUTER ENGINE  |  RUST CIPHERSUITE</text>
</svg>"""

    sp_path = os.path.join(ASSETS_DIR, "social-preview.png")
    render_svg_to_png(svg_sp, sp_path, 1280, 640)
    with Image.open(sp_path) as im:
        rgb_im = im.convert("RGB")
        rgb_im.save(sp_path, "PNG", optimize=True)
    print(f"  ✓ {sp_path} (1280x640)")


def generate_android_icons():
    """Generates complete Android launcher icon suite across all mipmap densities."""
    print("Generating Android launcher icons...")
    densities = {
        "mdpi": {"icon": 48, "adaptive": 108},
        "hdpi": {"icon": 72, "adaptive": 162},
        "xhdpi": {"icon": 96, "adaptive": 216},
        "xxhdpi": {"icon": 144, "adaptive": 324},
        "xxxhdpi": {"icon": 192, "adaptive": 432},
    }

    mark_svg = get_master_mark_svg_content("default")
    inner_defs = mark_svg[mark_svg.find('<defs>'):mark_svg.rfind('</svg>')]

    for density, sizes in densities.items():
        dir_path = os.path.join(RES_DIR, f"mipmap-{density}")
        os.makedirs(dir_path, exist_ok=True)

        icon_sz = sizes["icon"]
        adapt_sz = sizes["adaptive"]

        # 1. ic_launcher_background.png (adaptive background)
        bg_svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {adapt_sz} {adapt_sz}" width="{adapt_sz}" height="{adapt_sz}">
  <rect width="{adapt_sz}" height="{adapt_sz}" fill="#0F0F1A" />
</svg>"""
        bg_path = os.path.join(dir_path, "ic_launcher_background.png")
        render_svg_to_png(bg_svg, bg_path, adapt_sz, adapt_sz)

        # 2. ic_launcher_foreground.png (adaptive foreground with 72dp safe zone centering)
        fg_scale = (adapt_sz * (60.0 / 108.0)) / 341.0
        fg_tx = (adapt_sz / 2.0) - (256.0 * fg_scale)
        fg_ty = (adapt_sz / 2.0) - (254.5 * fg_scale)

        fg_svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {adapt_sz} {adapt_sz}" width="{adapt_sz}" height="{adapt_sz}">
  <g transform="translate({fg_tx:.2f}, {fg_ty:.2f}) scale({fg_scale:.4f})">
    {inner_defs}
  </g>
</svg>"""
        fg_path = os.path.join(dir_path, "ic_launcher_foreground.png")
        render_svg_to_png(fg_svg, fg_path, adapt_sz, adapt_sz)

        # 3. ic_launcher.png (legacy rounded squircle icon)
        sq_scale = (icon_sz * 0.62) / 341.0
        sq_tx = (icon_sz / 2.0) - (256.0 * sq_scale)
        sq_ty = (icon_sz / 2.0) - (254.5 * sq_scale)
        sq_rx = icon_sz * 0.20

        sq_svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {icon_sz} {icon_sz}" width="{icon_sz}" height="{icon_sz}">
  <defs>
    <clipPath id="sqClip_{density}">
      <rect width="{icon_sz}" height="{icon_sz}" rx="{sq_rx}" ry="{sq_rx}" />
    </clipPath>
  </defs>
  <g clip-path="url(#sqClip_{density})">
    <rect width="{icon_sz}" height="{icon_sz}" fill="#0F0F1A" />
    <rect width="{icon_sz}" height="{icon_sz}" rx="{sq_rx}" ry="{sq_rx}" fill="none" stroke="#261E38" stroke-width="2" />
    <g transform="translate({sq_tx:.2f}, {sq_ty:.2f}) scale({sq_scale:.4f})">
      {inner_defs}
    </g>
  </g>
</svg>"""
        sq_path = os.path.join(dir_path, "ic_launcher.png")
        render_svg_to_png(sq_svg, sq_path, icon_sz, icon_sz)

        # 4. ic_launcher_round.png (legacy circular icon)
        r_scale = (icon_sz * 0.62) / 341.0
        r_tx = (icon_sz / 2.0) - (256.0 * r_scale)
        r_ty = (icon_sz / 2.0) - (254.5 * r_scale)
        r_clip = (icon_sz / 2.0) - 1.5

        round_svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {icon_sz} {icon_sz}" width="{icon_sz}" height="{icon_sz}">
  <defs>
    <clipPath id="rndClip_{density}">
      <circle cx="{icon_sz/2.0}" cy="{icon_sz/2.0}" r="{r_clip}" />
    </clipPath>
  </defs>
  <g clip-path="url(#rndClip_{density})">
    <rect width="{icon_sz}" height="{icon_sz}" fill="#0F0F1A" />
    <circle cx="{icon_sz/2.0}" cy="{icon_sz/2.0}" r="{r_clip}" fill="none" stroke="#261E38" stroke-width="2" />
    <g transform="translate({r_tx:.2f}, {r_ty:.2f}) scale({r_scale:.4f})">
      {inner_defs}
    </g>
  </g>
</svg>"""
        round_path = os.path.join(dir_path, "ic_launcher_round.png")
        render_svg_to_png(round_svg, round_path, icon_sz, icon_sz)

        print(f"  ✓ mipmap-{density}: ic_launcher ({icon_sz}x{icon_sz}), round ({icon_sz}x{icon_sz}), adaptive ({adapt_sz}x{adapt_sz})")


def generate_all():
    print("==================================================")
    print("GHOST Protocol — Canonical Asset Family Generation")
    print("==================================================")
    generate_core_assets()
    generate_wordmarks()
    generate_social_preview()
    generate_android_icons()
    print("==================================================")
    print("All assets successfully regenerated from vector master!")
    print("==================================================")


if __name__ == "__main__":
    generate_all()
