import math
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_mesh_ghost_icon(size=512):
    """
    Renders the GHOST mesh icon:
    Three connected nodes forming a mesh triad, with an ethereal ghost tail flowing downward.
    Supersampled at 4x for ultra-sharp anti-aliased rendering.
    """
    scale = 4
    canvas_size = size * scale
    im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))

    cx = canvas_size / 2.0
    cy = canvas_size / 2.0 - (canvas_size * 0.04)
    radius = canvas_size * 0.35

    # Coordinates of 3 triangle vertices (mesh nodes)
    p_top = (cx, cy - radius * 0.92)
    p_bl = (cx - radius * 0.866, cy + radius * 0.58)
    p_br = (cx + radius * 0.866, cy + radius * 0.58)
    p_center = (cx, cy + radius * 0.08)

    # 1. Subtle central ambient node glow
    glow_im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_im)
    glow_draw.ellipse(
        [cx - radius * 0.9, cy - radius * 0.7, cx + radius * 0.9, cy + radius * 0.9],
        fill=(147, 51, 234, 45)
    )
    glow_im = glow_im.filter(ImageFilter.GaussianBlur(radius=24 * scale))
    im.alpha_composite(glow_im)

    # 2. Ghost Tail flowing down from top through center
    tail_im = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    tail_draw = ImageDraw.Draw(tail_im)

    steps = 120
    y_start = p_top[1] + 20 * scale
    y_end = cy + radius * 1.25

    for i in range(steps):
        t = i / float(steps)
        y = y_start + t * (y_end - y_start)
        
        # Width envelope
        base_w = (math.sin(t * math.pi * 0.9) ** 1.4) * (radius * 0.48)
        # S-curve drift for ghostly movement
        wave_x = math.sin(t * math.pi * 2.0) * (10.0 * scale * (1.0 - t * 0.4))
        cur_cx = cx + wave_x
        
        # Color gradient: #E0AAFF -> #A855F7 -> #6B21A8 -> transparent fade
        if t < 0.35:
            ratio = t / 0.35
            r = int(224 - ratio * 56)
            g = int(170 - ratio * 85)
            b = int(255 - ratio * 8)
            a = int(160 + ratio * 40)
        elif t < 0.75:
            ratio = (t - 0.35) / 0.40
            r = int(168 - ratio * 61)
            g = int(85 - ratio * 52)
            b = int(247 - ratio * 79)
            a = int(200 * (1.0 - ratio * 0.4))
        else:
            ratio = (t - 0.75) / 0.25
            r = int(107 * (1.0 - ratio * 0.5))
            g = int(33 * (1.0 - ratio * 0.5))
            b = int(168 * (1.0 - ratio * 0.5))
            a = int(120 * (1.0 - ratio))

        if base_w > 1 and a > 0:
            tail_draw.ellipse(
                [cur_cx - base_w, y - base_w * 0.35, cur_cx + base_w, y + base_w * 0.35],
                fill=(r, g, b, a)
            )

    tail_im = tail_im.filter(ImageFilter.GaussianBlur(radius=4 * scale))
    im.alpha_composite(tail_im)

    # 3. Mesh Connection Lines
    line_draw = ImageDraw.Draw(im)
    edges = [(p_top, p_bl), (p_top, p_br), (p_bl, p_br)]

    # Soft glow lines
    for p1, p2 in edges:
        line_draw.line([p1, p2], fill=(168, 85, 247, 80), width=int(10 * scale))
    # Crisp core lines
    for p1, p2 in edges:
        line_draw.line([p1, p2], fill=(233, 213, 255, 220), width=int(3.5 * scale))

    # Center hop lines
    line_draw.line([p_top, p_center], fill=(192, 132, 252, 90), width=int(2.5 * scale))
    line_draw.line([p_bl, p_center], fill=(192, 132, 252, 70), width=int(2.0 * scale))
    line_draw.line([p_br, p_center], fill=(192, 132, 252, 70), width=int(2.0 * scale))

    # 4. Mesh Nodes
    nodes = [
        (p_top, radius * 0.18, True),
        (p_bl, radius * 0.15, False),
        (p_br, radius * 0.15, False),
        (p_center, radius * 0.09, False)
    ]

    for (nx, ny), nr, is_top in nodes:
        # Outer pulse glow
        line_draw.ellipse(
            [nx - nr * 1.6, ny - nr * 1.6, nx + nr * 1.6, ny + nr * 1.6],
            fill=(147, 51, 234, 60)
        )
        # Ring
        line_draw.ellipse(
            [nx - nr * 1.2, ny - nr * 1.2, nx + nr * 1.2, ny + nr * 1.2],
            fill=(168, 85, 247, 140)
        )
        # Body
        line_draw.ellipse(
            [nx - nr, ny - nr, nx + nr, ny + nr],
            fill=(126, 34, 206, 255),
            outline=(243, 232, 255, 255),
            width=int(2.2 * scale)
        )
        # Center core light
        core_r = nr * 0.40
        line_draw.ellipse(
            [nx - core_r, ny - core_r, nx + core_r, ny + core_r],
            fill=(255, 255, 255, 255)
        )

    return im.resize((size, size), Image.Resampling.LANCZOS)

def generate_social_preview():
    """
    Renders an elegant, premium 1280x640 GitHub Social Preview card:
    - Deep violet-slate background (#0B0814)
    - Realistic, soft radial Gaussian backlight (no solid harsh ovals)
    - Seamlessly composited icon (no square boundary box)
    - Clean typography with proper vertical hierarchy and spacing
    - Sleek tech badges
    """
    W, H = 1280, 640
    im = Image.new("RGBA", (W, H), (11, 8, 20, 255)) # #0B0814

    # 1. Soft ambient backlight (using real Gaussian blur on separate layer)
    glow_im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_im)
    # A single soft ellipse
    glow_draw.ellipse(
        [W // 2 - 260, 60, W // 2 + 260, 360],
        fill=(126, 34, 206, 65) # #7E22CE with soft alpha
    )
    glow_draw.ellipse(
        [W // 2 - 120, 110, W // 2 + 120, 290],
        fill=(168, 85, 247, 45) # brighter center
    )
    glow_im = glow_im.filter(ImageFilter.GaussianBlur(radius=60))
    im.alpha_composite(glow_im)

    # 2. Add mesh icon (centered, transparent, seamlessly blended)
    icon_size = 170
    icon = create_mesh_ghost_icon(icon_size)
    icon_x = (W - icon_size) // 2
    icon_y = 75
    im.alpha_composite(icon, (icon_x, icon_y))

    # 3. Typography
    draw = ImageDraw.Draw(im)
    bold_font_path = "/usr/share/fonts/open-sans/OpenSans-Bold.ttf"
    regular_font_path = "/usr/share/fonts/open-sans/OpenSans-Regular.ttf"
    semibold_font_path = "/usr/share/fonts/open-sans/OpenSans-Semibold.ttf"

    font_title = ImageFont.truetype(bold_font_path, 48)
    font_sub = ImageFont.truetype(semibold_font_path, 22)
    font_badge = ImageFont.truetype(regular_font_path, 14)
    font_tech = ImageFont.truetype(regular_font_path, 15)

    # Title: "GHOST Protocol"
    title_text = "GHOST Protocol"
    t_box = draw.textbbox((0, 0), title_text, font=font_title)
    t_w = t_box[2] - t_box[0]
    draw.text(((W - t_w) // 2, 265), title_text, font=font_title, fill=(255, 255, 255, 255))

    # Tagline: "Messages that find their way."
    sub_text = "Messages that find their way."
    s_box = draw.textbbox((0, 0), sub_text, font=font_sub)
    s_w = s_box[2] - s_box[0]
    draw.text(((W - s_w) // 2, 330), sub_text, font=font_sub, fill=(216, 180, 254, 255)) # #D8B4FE

    # Description line
    desc_text = "Offline encrypted mesh messenger for Android. Zero servers. Battery-aware DTN routing."
    d_box = draw.textbbox((0, 0), desc_text, font=font_tech)
    d_w = d_box[2] - d_box[0]
    draw.text(((W - d_w) // 2, 380), desc_text, font=font_tech, fill=(161, 161, 170, 230)) # #A1A1AA

    # 4. Modern Pill Badges
    badges = [
        "BLE 5.0 Mesh",
        "X25519 + AES-256-GCM",
        "Spray-and-Wait DTN",
        "Kotlin · Go · Rust"
    ]
    
    badge_height = 32
    padding_x = 18
    spacing = 14
    
    # Measure total width of all badges
    badge_widths = []
    for b in badges:
        bbox = draw.textbbox((0, 0), b, font=font_badge)
        badge_widths.append(bbox[2] - bbox[0] + padding_x * 2)
    
    total_badges_w = sum(badge_widths) + spacing * (len(badges) - 1)
    start_x = (W - total_badges_w) // 2
    badge_y = 440

    curr_x = start_x
    for i, b in enumerate(badges):
        bw = badge_widths[i]
        # Pill background
        draw.rounded_rectangle(
            [curr_x, badge_y, curr_x + bw, badge_y + badge_height],
            radius=16,
            fill=(30, 21, 51, 230), # dark purple pill
            outline=(107, 33, 168, 180), # purple border
            width=1
        )
        # Pill text
        tbox = draw.textbbox((0, 0), b, font=font_badge)
        tw = tbox[2] - tbox[0]
        th = tbox[3] - tbox[1]
        draw.text(
            (curr_x + (bw - tw) // 2, badge_y + (badge_height - th) // 2 - 2),
            b,
            font=font_badge,
            fill=(226, 232, 240, 240)
        )
        curr_x += bw + spacing

    # 5. Footer Line
    footer_text = "No Infrastructure  ·  No Accounts  ·  No Surrender"
    f_box = draw.textbbox((0, 0), footer_text, font=font_tech)
    f_w = f_box[2] - f_box[0]
    draw.text(((W - f_w) // 2, 540), footer_text, font=font_tech, fill=(113, 113, 122, 190))

    # Convert to RGB and save
    final_sp = im.convert("RGB")
    final_sp.save("docs/assets/social-preview.png", "PNG", quality=95)
    print("✓ Successfully regenerated docs/assets/social-preview.png with perfect alignment and soft lighting!")

def generate_all():
    print("Regenerating all visual assets...")
    # 1. Icons
    icon_512 = create_mesh_ghost_icon(512)
    icon_512.save("docs/assets/icon-512.png", "PNG")
    icon_512.save("docs/assets/logo.png", "PNG")
    
    favicon = create_mesh_ghost_icon(32)
    favicon.save("docs/assets/favicon.png", "PNG")

    # 2. Wordmark (Light text for dark backgrounds)
    bold_font_path = "/usr/share/fonts/open-sans/OpenSans-Bold.ttf"
    light_font_path = "/usr/share/fonts/open-sans/OpenSans-Light.ttf"
    
    wm_w, wm_h = 800, 200
    scale = 2
    wm_im = Image.new("RGBA", (wm_w * scale, wm_h * scale), (0, 0, 0, 0))
    icon_wm = create_mesh_ghost_icon(150 * scale)
    wm_im.alpha_composite(icon_wm, (25 * scale, 25 * scale))

    wm_draw = ImageDraw.Draw(wm_im)
    font_ghost = ImageFont.truetype(bold_font_path, 76 * scale)
    font_proto = ImageFont.truetype(light_font_path, 34 * scale)

    text_x = 200 * scale
    wm_draw.text((text_x, 40 * scale), "GHOST", font=font_ghost, fill=(255, 255, 255, 255))
    wm_draw.text((text_x + 3 * scale, 122 * scale), "PROTOCOL", font=font_proto, fill=(216, 180, 254, 230))

    wm_im.resize((wm_w, wm_h), Image.Resampling.LANCZOS).save("docs/assets/logo-wordmark.png", "PNG")

    # 3. Wordmark (Dark text for light backgrounds)
    wm_dark_im = Image.new("RGBA", (wm_w * scale, wm_h * scale), (0, 0, 0, 0))
    wm_dark_im.alpha_composite(icon_wm, (25 * scale, 25 * scale))
    wm_dark_draw = ImageDraw.Draw(wm_dark_im)
    wm_dark_draw.text((text_x, 40 * scale), "GHOST", font=font_ghost, fill=(24, 18, 38, 255))
    wm_dark_draw.text((text_x + 3 * scale, 122 * scale), "PROTOCOL", font=font_proto, fill=(107, 33, 168, 240))
    wm_dark_im.resize((wm_w, wm_h), Image.Resampling.LANCZOS).save("docs/assets/logo-wordmark-dark.png", "PNG")

    # 4. Social Preview
    generate_social_preview()

if __name__ == "__main__":
    generate_all()
