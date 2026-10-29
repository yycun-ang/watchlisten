"""SVG 是编辑源；生成 Android VectorDrawable，并按需更新网页内联图标。"""
import argparse
import json
from html import escape
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
ET.register_namespace("android", ANDROID)
ET.register_namespace("aapt", AAPT)


def attr(name):
    return "{" + ANDROID + "}" + name


def color(value):
    return "#00000000" if value == "none" else "#F6F8FC" if value == "currentColor" else value


def rect_path(element):
    """圆角矩形转路径，便于用同一条填充/描边逻辑输出。"""
    x = float(element.get("x", "0"))
    y = float(element.get("y", "0"))
    w = float(element.get("width", "0"))
    h = float(element.get("height", "0"))
    r = float(element.get("rx", "0"))
    r = max(0.0, min(r, w / 2, h / 2))
    if r == 0:
        return f"M{x},{y} H{x + w} V{y + h} H{x} Z"
    return (f"M{x + r},{y} H{x + w - r} A{r},{r} 0,0 1 {x + w},{y + r}"
            f" V{y + h - r} A{r},{r} 0,0 1 {x + w - r},{y + h}"
            f" H{x + r} A{r},{r} 0,0 1 {x},{y + h - r}"
            f" V{y + r} A{r},{r} 0,0 1 {x + r},{y} Z")


def path_data(element):
    tag = element.tag.split("}")[-1]
    if tag == "path":
        return element.attrib["d"]
    if tag == "circle":
        x, y, r = [float(element.get(key, "0")) for key in ["cx", "cy", "r"]]
        return f"M{x-r},{y} A{r},{r} 0,1 0,{x+r},{y} A{r},{r} 0,1 0,{x-r},{y} Z"
    if tag == "rect":
        return rect_path(element)
    if tag == "line":
        return f"M{element.get('x1')},{element.get('y1')} L{element.get('x2')},{element.get('y2')}"
    raise ValueError("不支持的 SVG 元素：" + tag)


def paint(path, name, value, gradients):
    if not value.startswith("url(#"):
        path.set(attr(name), color(value))
        return
    source = gradients[value[5:-1]]
    value_node = ET.SubElement(path, "{" + AAPT + "}attr", {"name": "android:" + name})
    gradient = ET.SubElement(value_node, "gradient", {attr("type"): "linear"})
    for svg_key, key in [("x1", "startX"), ("y1", "startY"), ("x2", "endX"), ("y2", "endY")]:
        gradient.set(attr(key), source.get(svg_key, "0"))
    for stop in source:
        ET.SubElement(gradient, "item", {attr("offset"): stop.get("offset", "0"), attr("color"): stop.get("stop-color")})


def convert(source):
    svg = ET.parse(source).getroot()
    _, _, width, height = svg.get("viewBox").split()
    vector = ET.Element("vector", {attr("width"): width + "dp", attr("height"): height + "dp", attr("viewportWidth"): width, attr("viewportHeight"): height})
    gradients = {node.get("id"): node for node in svg.iter() if node.tag.endswith("linearGradient")}
    for element in svg:
        if element.tag.split("}")[-1] in ("title", "defs"):
            continue
        if element.get("transform"):
            raise ValueError(f"请先展开 SVG 变换再生成 Android 资源：{source}")
        path = ET.SubElement(vector, "path", {attr("pathData"): path_data(element)})
        paint(path, "fillColor", element.get("fill", svg.get("fill", "none")), gradients)
        stroke = element.get("stroke", svg.get("stroke"))
        if stroke:
            paint(path, "strokeColor", stroke, gradients)
            path.set(attr("strokeWidth"), element.get("stroke-width", svg.get("stroke-width", "1.8")))
            path.set(attr("strokeLineCap"), element.get("stroke-linecap", svg.get("stroke-linecap", "round")))
            path.set(attr("strokeLineJoin"), element.get("stroke-linejoin", svg.get("stroke-linejoin", "round")))
        if element.get("opacity"):
            path.set(attr("strokeAlpha"), element.get("opacity"))
            path.set(attr("fillAlpha"), element.get("opacity"))
    return ET.tostring(vector, encoding="unicode") + "\n"


def browser_paths(source):
    """保留 SVG 根节点的绘制约定，避免网页外层样式覆盖线宽与填充。"""
    text = source.read_text(encoding="utf-8")
    svg = ET.fromstring(text)
    expected = {
        "viewBox": "0 0 24 24",
        "fill": "none",
        "stroke": "currentColor",
        "stroke-width": "2",
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
    }
    for key, value in expected.items():
        if svg.get(key) != value:
            raise ValueError(f"{source.name} 必须使用统一图标约定：{key}={value}")
    drawing = {key: value for key, value in expected.items() if key != "viewBox"}
    attributes = " ".join(f'{key}="{escape(value, quote=True)}"' for key, value in drawing.items())
    content = text[text.index(">") + 1:text.rindex("</svg>")].strip()
    return f"<g {attributes}>{content}</g>"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-output", type=Path, default=ROOT / "app/src/main/res/drawable",
                        help="Android drawable 输出目录，默认 app/src/main/res/drawable")
    options = parser.parse_args()
    icons = {}
    destination = options.android_output
    destination.mkdir(parents=True, exist_ok=True)
    for source in sorted((ROOT / "assets/icons").glob("*.svg")):
        icons[source.stem] = browser_paths(source)
        (destination / ("ic_" + source.stem + ".xml")).write_text(convert(source), encoding="utf-8")
    (destination / "ic_brand.xml").write_text(convert(ROOT / "assets/brand/watch-listen.svg"), encoding="utf-8")
    if (ROOT / "design").is_dir():
        (ROOT / "design/icons.js").write_text("// 由 tools/build_vectors.py 生成，请编辑 assets/icons/*.svg。\nwindow.WatchIcons = Object.freeze(" + json.dumps(icons, ensure_ascii=False, indent=2) + ");\n", encoding="utf-8")
    print(f"生成 {len(icons)} 个 24×24 Android 图标与品牌矢量。")


if __name__ == "__main__":
    main()
