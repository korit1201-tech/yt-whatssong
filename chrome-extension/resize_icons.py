from PIL import Image
import os

source_path = "c:/Users/kory/.gemini/antigravity/playground/metallic-plasma/chrome-extension/icons/SOURCE.png"
output_dir = "c:/Users/kory/.gemini/antigravity/playground/metallic-plasma/chrome-extension/icons"
sizes = [16, 48, 128]

if not os.path.exists(source_path):
    print(f"Error: Source file not found at {source_path}")
    exit(1)

try:
    with Image.open(source_path) as img:
        for size in sizes:
            output_path = os.path.join(output_dir, f"icon{size}.png")
            resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
            resized_img.save(output_path)
            print(f"Generated {output_path}")
except Exception as e:
    print(f"Error processing image: {e}")
    exit(1)
