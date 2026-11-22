# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow"]
# ///
"""Draws the Port Probe item texture from the pixel map below.

Run from the repo root:  uv run tools/make_texture.py
"""
from pathlib import Path

from PIL import Image

OUT = Path(__file__).parent.parent / "src/main/resources/assets/portprobe/textures/item/port_probe.png"

# Light comes from the top left. Each material has its own ramp, and outlines are a dark
# shade of the material they border instead of one shared black.
PALETTE = {
    ".": None,
    # casing (blue-gray plastic)
    "H": "#7C8EA8", "B": "#56647F", "D": "#3A4560", "O": "#1E2438",
    # screen
    "Z": "#101C24", "L": "#C4FFCB", "S": "#5BE07A", "T": "#2C9A55",
    # buttons
    "R": "#FF6B5A", "r": "#B02A2E",
    # probe shaft (steel)
    "m": "#EEF2F3", "n": "#AEB6BB", "o": "#737C84", "p": "#3E454F",
    # probe tip (copper)
    "y": "#FFE08A", "g": "#F0A63A", "h": "#B9621F", "q": "#63300F",
}

PIXELS = [
    ".............qq.",
    "............qygq",
    "............qghq",
    "...........mnqq.",
    "..........mnp...",
    ".........mnp....",
    "..OOOOOOOnp.....",
    ".OHHHHHHBO......",
    ".OHZZZZZDO......",
    ".OHZLSSZDO......",
    ".OHZSSTZDO......",
    ".OHZZZZZDO......",
    ".OHRrBmoDO......",
    ".OBDDDDDDO......",
    "..OOOOOOO.......",
    "................",
]


def main():
    assert len(PIXELS) == 16 and all(len(row) == 16 for row in PIXELS), "texture must be 16x16"
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(PIXELS):
        for x, ch in enumerate(row):
            color = PALETTE[ch]
            if color:
                img.putpixel((x, y), tuple(bytes.fromhex(color[1:])) + (255,))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
