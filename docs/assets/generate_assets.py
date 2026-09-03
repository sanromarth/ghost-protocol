import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_mesh_ghost_icon(size=512, with_padding=True):
    """
    Renders the GHOST mesh icon:
    Three connected nodes forming a mesh triad, with a ghostly ethereal tail flowing downward.
    Rendered at 4x supersampling for ultra-crisp edges and smooth anti-aliasing.
    """
    scale = 4
    canvas_size = size * scale
    im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(im)

    cx = canvas_size / 2.0
    cy = canvas_size / 2.0 - (canvas_size * 0.02)
    radius = canvas_size * 0.34

    # Coordinates of 3 triangle vertices (mesh nodes)
    # Top node
    p_top = (cx, cy - radius * 0.95)
    # Bottom left node
    p_bl = (cx - radius * 0.88, cy + radius * 0.65)
    # Bottom right node
    p_br = (cx + radius * 0.88, cy + radius * 0.65)

    # Center of mesh
    p_center = (cx, cy + radius * 0.05)

    # 1. Draw subtle ethereal glow background behind center
    glow_im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_im)
    glow_radius = radius * 1.05
    for r_step in range(int(glow_radius), 0, -int(8 * scale)):
        alpha = int(22 * (1.0 - r_step / glow_radius))
        glow_draw.ellipse(
            [cx - r_step, cy - r_step * 0.8, cx + r_step, cy + r_step * 1.2],
            fill=(123, 44, 191, alpha)
        )
    glow_im = glow_im.filter(ImageFilter.GaussianBlur(radius=8 * scale))
    im.alpha_composite(glow_im)

    # 2. Draw Ghost Tail flowing down from top/center
    # The tail is a stylized, flowing tapered shape that emerges from the top node,
    # widens around the center, and tapers smoothly down with ripples
    tail_im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    tail_draw = ImageDraw.Draw(tail_im)

    steps = 140
    y_start = p_top[1] + 15 * scale
    y_end = cy + radius * 1.25

    # Draw tail layers with vertical gradient
    for i in range(steps):
        t = i / float(steps)
        y = y_start + t * (y_end - y_start)
        
        # Width curve: starts narrow, bulges in upper middle, tapers to point with ghost wave
        base_w = (math.sin(t * math.pi * 0.9) ** 1.3) * (radius * 0.55)
        # S-curve oscillation for ghostly float
        wave_x = math.sin(t * math.pi * 2.2) * (14.0 * scale * (1.0 - t * 0.5))
        
        cur_cx = cx + wave_x
        
        # Vertical gradient color: #C77DFF (light purple) -> #7B2CBF (purple) -> #4C1D95 (deep purple) -> fade out
        if t < 0.4:
            # Upper: #C77DFF to #9D4EDD
            ratio = t / 0.4
            r = int(199 + ratio * (157 - 199))
            g = int(125 + ratio * (78 - 125))
            b = int(255 + ratio * (221 - 255))
            a = int(180 + ratio * (210 - 180))
        elif t < 0.8:
            # Middle: #9D4EDD to #7B2CBF
            ratio = (t - 0.4) / 0.4
            r = int(157 + ratio * (123 - 157))
            g = int(78 + ratio * (44 - 78))
            b = int(221 + ratio * (191 - 221))
            a = int(210 * (1.0 - ratio * 0.4))
        else:
            # Lower fade out: #7B2CBF to #4C1D95 with alpha fade
            ratio = (t - 0.8) / 0.2
            r = int(123 + ratio * (76 - 123))
            g = int(44 + ratio * (29 - 44))
            b = int(191 + ratio * (149 - 191))
            a = int(126 * (1.0 - ratio))

        if base_w > 1 and a > 0:
            tail_draw.ellipse(
                [cur_cx - base_w, y - base_w * 0.4, cur_cx + base_w, y + base_w * 0.4],
                fill=(r, g, b, a)
            )

    tail_im = tail_im.filter(ImageFilter.GaussianBlur(radius=3 * scale))
    im.alpha_composite(tail_im)

    # 3. Draw Mesh Connection Lines (edges between the 3 nodes)
    # Double stroke: outer soft glow, inner crisp line
    line_draw = ImageDraw.Draw(im)
    edges = [(p_top, p_bl), (p_top, p_br), (p_bl, p_br)]

    # Outer line glow
    for p1, p2 in edges:
        line_draw.line([p1, p2], fill=(157, 78, 221, 90), width=int(12 * scale))
    # Core crisp line
    for p1, p2 in edges:
        line_draw.line([p1, p2], fill=(224, 170, 255, 230), width=int(4.5 * scale))

    # Also subtle internal lines to center node to show mesh hop
    line_draw.line([p_top, p_center], fill=(199, 125, 255, 110), width=int(3.0 * scale))
    line_draw.line([p_bl, p_center], fill=(199, 125, 255, 90), width=int(2.5 * scale))
    line_draw.line([p_br, p_center], fill=(199, 125, 255, 90), width=int(2.5 * scale))

    # 4. Draw Mesh Nodes
    # Each node: outer pulsing aura -> outer circle gradient -> inner bright white/violet core
    nodes = [
        (p_top, radius * 0.20, True),    # Top primary node
        (p_bl, radius * 0.17, False),   # Left node
        (p_br, radius * 0.17, False),   # Right node
        (p_center, radius * 0.10, False) # Center relay node
    ]

    for (nx, ny), nr, is_primary in nodes:
        # Outer aura
        line_draw.ellipse(
            [nx - nr * 1.7, ny - nr * 1.7, nx + nr * 1.7, ny + nr * 1.7],
            fill=(123, 44, 191, 70)
        )
        # Outer ring
        line_draw.ellipse(
            [nx - nr * 1.25, ny - nr * 1.25, nx + nr * 1.25, ny + nr * 1.25],
            fill=(157, 78, 221, 160)
        )
        # Main node body (#7B2CBF -> #C77DFF)
        line_draw.ellipse(
            [nx - nr, ny - nr, nx + nr, ny + nr],
            fill=(123, 44, 191, 255),
            outline=(224, 170, 255, 255),
            width=int(2.5 * scale)
        )
        # Core highlight
        core_r = nr * 0.42
        line_draw.ellipse(
            [nx - core_r, ny - core_r, nx + core_r, ny + core_r],
            fill=(255, 255, 255, 245)
        )

    # Downsample with Lanczos for ultra-crisp antialiased result
    final_icon = im.resize((size, size), Image.Resampling.LANCZOS)
    return final_icon

def generate_logo_svg():
    """Generates pure SVG vector for high-fidelity rendering at any resolution."""
    svg_content = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="100%" height="100%">
  <defs>
    <!-- Gradient for Mesh Lines -->
    <linearGradient id="meshLineGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#C77DFF" stop-opacity="0.9"/>
      <stop offset="50%" stop-color="#9D4EDD" stop-opacity="0.8"/>
      <stop offset="100%" stop-color="#7B2CBF" stop-opacity="0.9"/>
    </linearGradient>

    <!-- Gradient for Ghost Tail -->
    <linearGradient id="ghostTailGrad" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#E0AAFF" stop-opacity="0.85"/>
      <stop offset="35%" stop-color="#9D4EDD" stop-opacity="0.75"/>
      <stop offset="70%" stop-color="#7B2CBF" stop-opacity="0.55"/>
      <stop offset="100%" stop-color="#4C1D95" stop-opacity="0.0"/>
    </linearGradient>

    <!-- Node Glow Filter -->
    <filter id="purpleGlow" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur stdDeviation="8" result="blur" />
      <feComposite in="SourceGraphic" in2="blur" operator="over" />
    </filter>
  </defs>

  <g id="ghost-mesh-icon">
    <!-- Ethereal Ghost Tail Flowing Down -->
    <path d="M 256 125
             C 285 200, 310 260, 275 340
             C 255 390, 240 420, 262 445
             C 238 430, 230 380, 235 340
             C 240 270, 225 200, 256 125 Z"
          fill="url(#ghostTailGrad)" />

    <!-- Center Mesh Linkage -->
    <line x1="256" y1="125" x2="256" y2="280" stroke="#C77DFF" stroke-width="3" stroke-opacity="0.5" stroke-dasharray="4,4"/>
    <line x1="120" y1="360" x2="256" y2="280" stroke="#C77DFF" stroke-width="3" stroke-opacity="0.5" stroke-dasharray="4,4"/>
    <line x1="392" y1="360" x2="256" y2="280" stroke="#C77DFF" stroke-width="3" stroke-opacity="0.5" stroke-dasharray="4,4"/>

    <!-- Outer Mesh Triangle Edges -->
    <polygon points="256,125 120,360 392,360"
             fill="none"
             stroke="url(#meshLineGrad)"
             stroke-width="7"
             stroke-linejoin="round"
             stroke-linecap="round"
             filter="url(#purpleGlow)"/>

    <!-- Center Node -->
    <circle cx="256" cy="280" r="14" fill="#7B2CBF" stroke="#E0AAFF" stroke-width="3"/>
    <circle cx="256" cy="280" r="5" fill="#FFFFFF"/>

    <!-- Bottom Left Node -->
    <circle cx="120" cy="360" r="24" fill="#7B2CBF" stroke="#E0AAFF" stroke-width="4.5"/>
    <circle cx="120" cy="360" r="9" fill="#FFFFFF"/>

    <!-- Bottom Right Node -->
    <circle cx="392" cy="360" r="24" fill="#7B2CBF" stroke="#E0AAFF" stroke-width="4.5"/>
    <circle cx="392" cy="360" r="9" fill="#FFFFFF"/>

    <!-- Top Primary Node -->
    <circle cx="256" cy="125" r="28" fill="#7B2CBF" stroke="#FFFFFF" stroke-width="5"/>
    <circle cx="256" cy="125" r="11" fill="#FFFFFF"/>
  </g>
</svg>"""
    with open("docs/assets/logo.svg", "w") as f:
        f.write(svg_content)
    print("Generated docs/assets/logo.svg")

def generate_assets():
    print("Generating assets...")
    # 1. Generate SVG
    generate_logo_svg()

    # 2. App Icon 512x512
    icon_512 = create_mesh_ghost_icon(512)
    icon_512.save("docs/assets/icon-512.png", "PNG")
    print("Generated docs/assets/icon-512.png")

    # Also save as logo.png for README (<img src="docs/assets/logo.png" width="120">)
    icon_512.save("docs/assets/logo.png", "PNG")
    print("Generated docs/assets/logo.png")

    # 3. Favicon 32x32
    favicon = create_mesh_ghost_icon(32)
    favicon.save("docs/assets/favicon.png", "PNG")
    print("Generated docs/assets/favicon.png")

    # Fonts to use
    bold_font_path = "/usr/share/fonts/open-sans/OpenSans-Bold.ttf"
    regular_font_path = "/usr/share/fonts/open-sans/OpenSans-Regular.ttf"
    light_font_path = "/usr/share/fonts/open-sans/OpenSans-Light.ttf"

    # 4. Logo with Wordmark (800x200 PNG, transparent background)
    # Icon on left (~160x160), "GHOST" bold sans-serif, "Protocol" lighter weight below
    wm_w, wm_h = 800, 200
    scale = 2
    wm_im = Image.new("RGBA", (wm_w * scale, wm_h * scale), (0, 0, 0, 0))
    
    # Left icon: 160x160
    icon_wm = create_mesh_ghost_icon(160 * scale)
    wm_im.paste(icon_wm, (20 * scale, 20 * scale), icon_wm)

    wm_draw = ImageDraw.Draw(wm_im)
    font_ghost = ImageFont.truetype(bold_font_path, 80 * scale)
    font_proto = ImageFont.truetype(light_font_path, 36 * scale)

    text_x = 205 * scale
    ghost_y = 35 * scale
    proto_y = 125 * scale

    # Gradient or solid sleek text
    wm_draw.text((text_x, ghost_y), "GHOST", font=font_ghost, fill=(255, 255, 255, 255))
    wm_draw.text((text_x + 3 * scale, proto_y), "PROTOCOL", font=font_proto, fill=(199, 125, 255, 230))

    final_wordmark = wm_im.resize((wm_w, wm_h), Image.Resampling.LANCZOS)
    final_wordmark.save("docs/assets/logo-wordmark.png", "PNG")
    print("Generated docs/assets/logo-wordmark.png")

    # 5. GitHub Social Preview (1280x640 PNG)
    # Dark background (#0F0F1A), centered icon, "GHOST Protocol", "Messages that find their way.", "Offline · Encrypted · Unstoppable"
    sp_w, sp_h = 1280, 640
    sp_im = Image.new("RGBA", (sp_w, sp_h), (15, 15, 26, 255)) # #0F0F1A
    sp_draw = ImageDraw.Draw(sp_im)

    # Subtle background ambient purple radial glow in center
    glow_center_x, glow_center_y = sp_w // 2, 210
    for r in range(320, 0, -8):
        alpha = int(28 * (1.0 - r / 320.0))
        sp_draw.ellipse(
            [glow_center_x - r, glow_center_y - r * 0.7, glow_center_x + r, glow_center_y + r * 0.7],
            fill=(123, 44, 191, alpha)
        )

    # Centered Icon (~200x200)
    icon_sp = create_mesh_ghost_icon(210)
    icon_x = (sp_w - 210) // 2
    icon_y = 90
    sp_im.paste(icon_sp, (icon_x, icon_y), icon_sp)

    # Typography
    font_title = ImageFont.truetype(bold_font_path, 54)
    font_sub = ImageFont.truetype(regular_font_path, 26)
    font_footer = ImageFont.truetype(light_font_path, 18)

    title_text = "GHOST Protocol"
    sub_text = "Messages that find their way."
    footer_text = "Offline  ·  Encrypted  ·  Unstoppable"

    # Measure and center title
    t_bbox = sp_draw.textbbox((0, 0), title_text, font=font_title)
    t_w = t_bbox[2] - t_bbox[0]
    sp_draw.text(((sp_w - t_w) // 2, 330), title_text, font=font_title, fill=(255, 255, 255, 255))

    # Measure and center subtitle
    s_bbox = sp_draw.textbbox((0, 0), sub_text, font=font_sub)
    s_w = s_bbox[2] - s_bbox[0]
    sp_draw.text(((sp_w - s_w) // 2, 405), sub_text, font=font_sub, fill=(199, 125, 255, 240)) # #C77DFF

    # Subtitle separator line
    line_w = 120
    sp_draw.line([(sp_w - line_w) // 2, 455, (sp_w + line_w) // 2, 455], fill=(123, 44, 191, 140), width=2)

    # Technology badges/highlights
    tech_font = ImageFont.truetype(regular_font_path, 16)
    tech_text = "Zero Servers  ·  Battery-Aware DTN Routing  ·  Kotlin + Go + Rust"
    tech_bbox = sp_draw.textbbox((0, 0), tech_text, font=tech_font)
    tech_w = tech_bbox[2] - tech_bbox[0]
    sp_draw.text(((sp_w - tech_w) // 2, 480), tech_text, font=tech_font, fill=(148, 163, 184, 220))

    # Measure and center footer
    f_bbox = sp_draw.textbbox((0, 0), footer_text, font=font_footer)
    f_w = f_bbox[2] - f_bbox[0]
    sp_draw.text(((sp_w - f_w) // 2, 570), footer_text, font=font_footer, fill=(100, 116, 139, 200)) # subtle gray

    sp_im.save("docs/assets/social-preview.png", "PNG")
    print("Generated docs/assets/social-preview.png")

if __name__ == "__main__":
    generate_assets()
