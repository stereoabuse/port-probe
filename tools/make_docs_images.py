# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow"]
# ///
"""Renders the README pictures into docs/images: item icons, crafting recipes and the banner.

Run from the repo root:  uv run tools/make_docs_images.py

Recipes are read from src/main/resources/data/<mod_id>/recipe/*.json, so a new recipe gets a
picture by re-running this. Vanilla item textures come from the Minecraft client jar that Gradle
caches after the first build (override with --client-jar).

Limits: only shaped and shapeless crafting recipes, only single-item ingredients (no tags), only items
with a flat texture in textures/item (no 3D block items), and no stack counts.
"""
import argparse
import io
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).parent.parent
RESOURCES = ROOT / "src/main/resources"
OUT = ROOT / "docs/images"

TAGLINE = "See what every block face exposes: items, fluids and energy."

PANEL, SLOT, SLOT_DARK, SLOT_LIGHT, PANEL_SHADOW = "#C6C6C6", "#8B8B8B", "#373737", "#FFFFFF", "#555555"
RECIPE_SCALE = 4
ICON_SCALE = 10


def gradle_properties():
    props = {}
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


class Textures:
    def __init__(self, client_jar):
        self.jar = zipfile.ZipFile(client_jar)

    def item(self, item_id):
        namespace, path = item_id.split(":")
        if namespace == "minecraft":
            try:
                data = self.jar.read(f"assets/minecraft/textures/item/{path}.png")
            except KeyError:
                raise SystemExit(f"{item_id} has no flat item texture (3D block items are not supported)")
            img = Image.open(io.BytesIO(data))
        else:
            img = Image.open(RESOURCES / f"assets/{namespace}/textures/item/{path}.png")
        img = img.convert("RGBA")
        return img.crop((0, 0, img.width, img.width))  # first frame of animated textures


def ingredient_item(ingredient):
    if not isinstance(ingredient, str) or ingredient.startswith("#"):
        raise SystemExit(f"unsupported ingredient: {ingredient}")
    return ingredient


def recipe_grid(recipe):
    """Returns the 3x3 grid of item ids (None for empty), or None for unsupported recipe types."""
    grid = [[None] * 3 for _ in range(3)]
    if recipe["type"] == "minecraft:crafting_shaped":
        pattern = recipe["pattern"]
        width = max(len(row) for row in pattern)
        left, top = (3 - width) // 2, (3 - len(pattern)) // 2
        for y, row in enumerate(pattern):
            for x, ch in enumerate(row):
                if ch != " ":
                    grid[top + y][left + x] = ingredient_item(recipe["key"][ch])
    elif recipe["type"] == "minecraft:crafting_shapeless":
        for i, ingredient in enumerate(recipe["ingredients"]):
            grid[i // 3][i % 3] = ingredient_item(ingredient)
    else:
        return None
    return grid


def draw_slot(draw, x, y, size):
    end = size - 1
    draw.rectangle([x, y, x + end, y + end], fill=SLOT)
    draw.line([x, y, x + end - 1, y], fill=SLOT_DARK)
    draw.line([x, y, x, y + end - 1], fill=SLOT_DARK)
    draw.line([x + 1, y + end, x + end, y + end], fill=SLOT_LIGHT)
    draw.line([x + end, y + 1, x + end, y + end], fill=SLOT_LIGHT)


def draw_panel(img):
    draw = ImageDraw.Draw(img)
    w, h = img.size
    draw.rectangle([0, 0, w - 1, h - 1], fill=PANEL, outline="#000000")
    draw.rectangle([1, 1, w - 3, 2], fill=SLOT_LIGHT)
    draw.rectangle([1, 1, 2, h - 3], fill=SLOT_LIGHT)
    draw.rectangle([3, h - 3, w - 2, h - 2], fill=PANEL_SHADOW)
    draw.rectangle([w - 3, 3, w - 2, h - 2], fill=PANEL_SHADOW)
    for x, y in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        img.putpixel((x, y), (0, 0, 0, 0))
    for x, y in [(1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2)]:
        img.putpixel((x, y), (0, 0, 0, 255))
    return draw


def render_recipe(recipe, textures):
    grid = recipe_grid(recipe)
    if grid is None:
        return None
    pad, cell, big = 10, 18, 26
    arrow_x = pad + 3 * cell + 9
    result_x = arrow_x + 22 + 9
    img = Image.new("RGBA", (result_x + big + pad, 3 * cell + 2 * pad), (0, 0, 0, 0))
    draw = draw_panel(img)
    center_y = img.height // 2

    for row in range(3):
        for col in range(3):
            x, y = pad + col * cell, pad + row * cell
            draw_slot(draw, x, y, cell)
            if grid[row][col]:
                img.alpha_composite(textures.item(grid[row][col]), (x + 1, y + 1))

    draw.rectangle([arrow_x, center_y - 1, arrow_x + 12, center_y + 1], fill=SLOT)
    for i in range(8):
        draw.line([arrow_x + 13 + i, center_y - 7 + i, arrow_x + 13 + i, center_y + 7 - i], fill=SLOT)

    result_y = center_y - big // 2
    draw_slot(draw, result_x, result_y, big)
    img.alpha_composite(textures.item(recipe["result"]["id"]), (result_x + 5, result_y + 5))
    if recipe["result"].get("count", 1) != 1:
        print("  note: result count is not drawn")

    return img.resize((img.width * RECIPE_SCALE, img.height * RECIPE_SCALE), Image.NEAREST)


def load_font(size, bold=False):
    name = "segoeuib.ttf" if bold else "segoeui.ttf"
    try:
        return ImageFont.truetype(name, size)
    except OSError:
        print(f"  warning: {name} not found (Windows font), the banner will use Pillow's default font "
              "and will not match the committed one")
        return ImageFont.load_default(size)


def render_banner(icon, title):
    img = Image.new("RGBA", (1280, 320), "#1E2438")
    draw = ImageDraw.Draw(img)
    for x in range(0, img.width, 40):
        draw.line([x, 0, x, img.height], fill="#252C44")
    for y in range(0, img.height, 40):
        draw.line([0, y, img.width, y], fill="#252C44")
    slot = Image.new("RGBA", (18, 18))
    draw_slot(ImageDraw.Draw(slot), 0, 0, 18)
    slot.alpha_composite(icon, (1, 1))
    img.alpha_composite(slot.resize((270, 270), Image.NEAREST), (60, 25))
    draw.text((380, 70), title, font=load_font(110, bold=True), fill="#EEF2F3")
    draw.text((386, 210), TAGLINE, font=load_font(30), fill="#AEB6BB")
    return img


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    print(f"wrote {path.relative_to(ROOT)}")


def main():
    props = gradle_properties()
    mod_id = props["mod_id"]
    default_jar = (Path.home() / ".gradle/caches/neoformruntime/artifacts"
                   / f"minecraft_{props['minecraft_version']}_client.jar")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--client-jar", type=Path, default=default_jar)
    args = parser.parse_args()
    if not args.client_jar.exists():
        raise SystemExit(f"Minecraft client jar not found at {args.client_jar}. "
                         "Run a Gradle build first, or pass --client-jar.")
    textures = Textures(args.client_jar)

    for texture in sorted((RESOURCES / f"assets/{mod_id}/textures/item").glob("*.png")):
        icon = Image.open(texture).convert("RGBA")
        save(icon.resize((icon.width * ICON_SCALE, icon.height * ICON_SCALE), Image.NEAREST),
             OUT / "items" / texture.name)

    for recipe_file in sorted((RESOURCES / f"data/{mod_id}/recipe").glob("*.json")):
        picture = render_recipe(json.loads(recipe_file.read_text()), textures)
        if picture is None:
            print(f"skipped {recipe_file.name}: unsupported recipe type")
        else:
            save(picture, OUT / "recipes" / f"{recipe_file.stem}.png")

    save(render_banner(textures.item(f"{mod_id}:port_probe"), props["mod_name"]), OUT / "banner.png")


if __name__ == "__main__":
    main()
