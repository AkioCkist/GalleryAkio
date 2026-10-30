package com.example.galleryakio.editor

import android.graphics.RectF
import android.opengl.GLES20
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter

private const val MAX_FACES = 4

private const val FRAGMENT = """
precision highp float;
varying vec2 textureCoordinate;
uniform sampler2D inputImageTexture;
uniform float shadows;
uniform float highlights;
uniform vec3 spotAll;
uniform vec3 spot1;
uniform vec3 spot2;
uniform vec3 spot3;
uniform vec3 spot4;
uniform vec3 spot5;
uniform vec3 spot6;
uniform vec3 spot7;
uniform float smoothness;
uniform float skinTone;
uniform vec4 face0;
uniform vec4 face1;
uniform vec4 face2;
uniform vec4 face3;

vec3 rgb2hsl(vec3 c) {
    float maxC = max(max(c.r, c.g), c.b);
    float minC = min(min(c.r, c.g), c.b);
    float l = (maxC + minC) / 2.0;
    float d = maxC - minC;
    if (d < 0.00001) return vec3(0.0, 0.0, l);
    float s = l > 0.5 ? d / (2.0 - maxC - minC) : d / (maxC + minC);
    float h;
    if (maxC == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    else if (maxC == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    return vec3(h / 6.0, s, l);
}

float hue2rgb(float p, float q, float t) {
    t = fract(t);
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

vec3 hsl2rgb(vec3 c) {
    if (c.y <= 0.0) return vec3(c.z);
    float q = c.z < 0.5 ? c.z * (1.0 + c.y) : c.z + c.y - c.z * c.y;
    float p = 2.0 * c.z - q;
    return vec3(hue2rgb(p, q, c.x + 1.0 / 3.0), hue2rgb(p, q, c.x), hue2rgb(p, q, c.x - 1.0 / 3.0));
}

float band(float hue, float centerDegrees) {
    float d = abs(fract(hue - centerDegrees / 360.0 + 0.5) - 0.5) * 360.0;
    return 1.0 - smoothstep(12.0, 36.0, d);
}

float ellipse(vec2 uv, vec4 face) {
    if (face.z <= 0.0) return 0.0;
    return 1.0 - smoothstep(0.8, 1.0, length((uv - face.xy) / face.zw));
}

vec3 smoothed(vec2 uv, vec3 center, vec2 step) {
    vec3 sum = center;
    float total = 1.0;
    for (int ring = 1; ring <= 2; ring++) {
        for (int i = 0; i < 12; i++) {
            float a = 6.2832 * float(i) / 12.0;
            vec3 c = texture2D(inputImageTexture, uv + vec2(cos(a), sin(a)) * step * float(ring)).rgb;
            vec3 diff = c - center;
            // Close colours only: skin blends, eyes and lips keep their edges.
            float w = exp(-dot(diff, diff) * 90.0);
            sum += c * w;
            total += w;
        }
    }
    return sum / total;
}

void main() {
    vec2 uv = textureCoordinate;
    vec4 source = texture2D(inputImageTexture, uv);
    vec3 c = source.rgb;

    if (smoothness > 0.0 || skinTone != 0.0) {
        vec4 face = face0;
        float mask = ellipse(uv, face0);
        float m1 = ellipse(uv, face1);
        if (m1 > mask) { mask = m1; face = face1; }
        float m2 = ellipse(uv, face2);
        if (m2 > mask) { mask = m2; face = face2; }
        float m3 = ellipse(uv, face3);
        if (m3 > mask) { mask = m3; face = face3; }
        if (mask > 0.0) {
            if (smoothness > 0.0) c = mix(c, smoothed(uv, c, face.zw * 0.022), mask * smoothness);
            c = mix(c, c * (1.0 + skinTone * 0.22), mask);
        }
    }

    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c += c * shadows * 0.9 * (1.0 - smoothstep(0.0, 0.5, luma));
    c += highlights * 0.3 * smoothstep(0.45, 1.0, luma);

    vec3 hsl = rgb2hsl(clamp(c, 0.0, 1.0));
    vec3 shift = spotAll;
    // Grays have no real hue, so they stay out of the colour ranges.
    float colored = smoothstep(0.03, 0.2, hsl.y);
    shift += spot1 * band(hsl.x, 0.0) * colored;
    shift += spot2 * band(hsl.x, 30.0) * colored;
    shift += spot3 * band(hsl.x, 60.0) * colored;
    shift += spot4 * band(hsl.x, 120.0) * colored;
    shift += spot5 * band(hsl.x, 200.0) * colored;
    shift += spot6 * band(hsl.x, 240.0) * colored;
    shift += spot7 * band(hsl.x, 285.0) * colored;
    if (dot(shift, shift) > 0.0) {
        hsl.x = fract(hsl.x + shift.x * 0.25);
        hsl.y = clamp(hsl.y * (1.0 + shift.y), 0.0, 1.0);
        hsl.z = clamp(hsl.z + shift.z * 0.25 * hsl.y, 0.0, 1.0);
        c = hsl2rgb(hsl);
    }
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), source.a);
}
"""

/**
 * Shadows, highlights, Spot color and Face effects in one pass, the way ImageToolbox's filters run
 * on GPUImage. [faces] are ellipses' bounding boxes as fractions of the picture.
 */
internal class ToneFilter(
    private val shadows: Float,
    private val highlights: Float,
    private val spot: Map<SpotBand, SpotShift>,
    private val face: FaceEffect,
    private val faces: List<RectF>,
) : GPUImageFilter(NO_FILTER_VERTEX_SHADER, FRAGMENT) {

    override fun onInitialized() {
        super.onInitialized()
        fun location(name: String) = GLES20.glGetUniformLocation(program, name)
        setFloat(location("shadows"), shadows)
        setFloat(location("highlights"), highlights)
        val names = listOf("spotAll", "spot1", "spot2", "spot3", "spot4", "spot5", "spot6", "spot7")
        SpotBand.entries.forEachIndexed { index, band ->
            val shift = spot[band] ?: SpotShift()
            setFloatVec3(location(names[index]), floatArrayOf(shift.hue, shift.saturation, shift.lightness))
        }
        setFloat(location("smoothness"), face.smoothness)
        setFloat(location("skinTone"), face.skinTone)
        for (index in 0 until MAX_FACES) {
            val box = faces.getOrNull(index)
            val value = if (box == null) floatArrayOf(0f, 0f, 0f, 0f)
            else floatArrayOf(box.centerX(), box.centerY(), box.width() / 2f, box.height() / 2f)
            setFloatVec4(location("face$index"), value)
        }
    }
}
