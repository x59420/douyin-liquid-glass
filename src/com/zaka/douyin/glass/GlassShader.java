package com.zaka.douyin.glass;

/**
 * 液态玻璃着色器（AGSL / RuntimeShader，Android 13+）。
 *
 * 折射模型照 Kyant0/AndroidLiquidGlass 的 backdrop 库实现：
 *  - RoundedRectRefraction：圆角矩形 SDF，边缘 refractionHeight 范围内按 circleMap 曲线
 *    把采样坐标沿法线往外推，形成「玻璃厚边放大」的透镜感
 *  - WithDispersion：把 R/G/B 分成若干采样，做出边缘色散（只在按压/拖动时开）
 *  - Highlight：沿 45° 法线方向的幂次高光，Add 混合
 *
 * 低版本（< API 33）没有 RuntimeShader，GlassRender 会退回「裁切位图 + 渐层 + 描边」。
 */
final class GlassShader {

    private static final String ROUNDED_RECT_SDF =
            "float radiusAt(float2 centeredCoord, float4 radii) {\n" +
            "    if (centeredCoord.x >= 0.0) {\n" +
            "        if (centeredCoord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (centeredCoord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n" +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n";

    /** 折射（无色散）：日常控件用这个，开销小 */
    static final String REFRACTION =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            ROUNDED_RECT_SDF +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)" +
            " + depthEffect * normalize(centeredCoord));\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    return content.eval(refractedCoord);\n" +
            "}\n";

    /** 折射 + 色散：dock 按压/拖动时用 */
    static final String REFRACTION_DISPERSION =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            ROUNDED_RECT_SDF +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)" +
            " + depthEffect * normalize(centeredCoord));\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    float dispersionIntensity = chromaticAberration *" +
            " ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n" +
            "    float2 dispersedCoord = d * grad * dispersionIntensity;\n" +
            "    half4 color = half4(0.0);\n" +
            "    half4 red = content.eval(refractedCoord + dispersedCoord);\n" +
            "    color.r += red.r / 3.5; color.a += red.a / 7.0;\n" +
            "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n" +
            "    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;\n" +
            "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n" +
            "    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;\n" +
            "    half4 green = content.eval(refractedCoord);\n" +
            "    color.g += green.g / 3.5; color.a += green.a / 7.0;\n" +
            "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n" +
            "    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;\n" +
            "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n" +
            "    color.b += blue.b / 3.0; color.a += blue.a / 7.0;\n" +
            "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n" +
            "    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;\n" +
            "    return color;\n" +
            "}\n";

    /** Tieba press glow: smoothstep radial spot centered on the selected tab. */
    static final String GLOW =
            "layout(color) uniform half4 color;\\n" +
            "uniform float radius;\\n" +
            "uniform float2 position;\\n" +
            "half4 main(float2 coord) {\\n" +
            "    float dist = distance(coord, position);\\n" +
            "    float intensity = smoothstep(radius, radius * 0.5, dist);\\n" +
            "    return color * intensity;\\n" +
            "}\\n";

    /** 45° 法线高光（Add 混合） */
    static final String HIGHLIGHT =
            "uniform float2 size;\n" +
            "uniform float4 cornerRadii;\n" +
            "layout(color) uniform half4 color;\n" +
            "uniform float angle;\n" +
            "uniform float falloff;\n" +
            ROUNDED_RECT_SDF +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = coord - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n" +
            "    float2 normal = float2(cos(angle), sin(angle));\n" +
            "    float d = dot(grad, normal);\n" +
            "    float intensity = pow(abs(d), falloff);\n" +
            "    return color * intensity;\n" +
            "}\n";

    /** 整面 45° 环境泛光：原版 Highlight.Ambient（只有朝向光那一侧亮） */
    static final String AMBIENT =
            "uniform float2 size;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float angle;\n" +
            "uniform float falloff;\n" +
            ROUNDED_RECT_SDF +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = coord - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n" +
            "    float2 normal = float2(cos(angle), sin(angle));\n" +
            "    float d = dot(grad, normal);\n" +
            "    float intensity = pow(abs(d), falloff);\n" +
            "    float t = step(0.0, d);\n" +
            "    return half4(t, t, t, 1.0) * intensity;\n" +
            "}\n";

    private GlassShader() {
    }
}
